package net.statustag.core.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StatusStore {

  private static final Gson GSON = new Gson();

  static final int FORMAT_VERSION = 2;
  static final int MAX_ENTRIES = 5_000;
  static final long SAVE_DELAY_MS = 5_000L;

  private final ConcurrentHashMap<UUID, StatusEntry> entries = new ConcurrentHashMap<>(512);
  private final Path file;
  private final Path legacyFile;
  private final Object fileLock = new Object();
  private volatile long dirtySince;
  private volatile boolean loaded;

  public StatusStore(@NotNull Path file, @NotNull Path legacyFile) {
    this.file = file;
    this.legacyFile = legacyFile;
  }

  public @Nullable StatusEntry get(@NotNull UUID uniqueId) {
    return this.entries.get(uniqueId);
  }

  public void put(@NotNull UUID uniqueId, @NotNull StatusEntry entry) {
    this.entries.put(uniqueId, entry);
    this.markDirty();
  }

  public void clear() {
    this.entries.clear();
    this.markDirty();
  }

  public int size() {
    return this.entries.size();
  }

  public @Nullable StatusEntry compute(
      @NotNull UUID uniqueId,
      @NotNull BiFunction<UUID, StatusEntry, StatusEntry> function
  ) {
    boolean[] changed = new boolean[1];
    StatusEntry result = this.entries.compute(uniqueId, (key, current) -> {
      StatusEntry next = function.apply(key, current);
      changed[0] = next != current;
      return next;
    });
    if (changed[0]) {
      this.markDirty();
    }
    return result;
  }

  public void replaceAll(@NotNull BiFunction<UUID, StatusEntry, StatusEntry> function) {
    this.entries.replaceAll(function);
    this.markDirty();
  }

  public boolean isLoaded() {
    return this.loaded;
  }

  public boolean isSaveDue(long now) {
    long since = this.dirtySince;
    return this.loaded && since != 0L && now - since >= SAVE_DELAY_MS;
  }

  private void markDirty() {
    if (this.dirtySince == 0L) {
      this.dirtySince = System.currentTimeMillis();
    }
  }

  public int load(long now) {
    try {
      return this.read(now);
    } finally {
      this.loaded = true;
    }
  }

  private int read(long now) {
    synchronized (this.fileLock) {
      Path source = Files.exists(this.file) ? this.file : this.legacyFile;
      if (!Files.exists(source)) {
        return 0;
      }

      JsonElement root;
      try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
        root = GSON.fromJson(reader, JsonElement.class);
      } catch (Exception exception) {
        return 0;
      }

      if (root == null || !root.isJsonObject()) {
        return 0;
      }

      JsonObject object = root.getAsJsonObject();
      int count = source == this.file && object.has("entries") && object.get("entries").isJsonObject()
          ? this.readCurrent(object.getAsJsonObject("entries"), now)
          : this.readLegacy(object, now);

      if (source == this.legacyFile && count > 0) {
        this.markDirty();
      }
      return count;
    }
  }

  private int readCurrent(@NotNull JsonObject entries, long now) {
    int count = 0;
    for (Map.Entry<String, JsonElement> element : entries.entrySet()) {
      UUID uniqueId = parseUuid(element.getKey());
      if (uniqueId == null || !element.getValue().isJsonArray()) {
        continue;
      }

      JsonArray array = element.getValue().getAsJsonArray();
      if (array.size() < 4) {
        continue;
      }

      try {
        StatusKind kind = StatusKind.parse(array.get(0).getAsString());
        StatusSource source = StatusSource.parse(array.get(1).getAsString());
        if (kind == null || source == null) {
          continue;
        }

        StatusEntry entry = new StatusEntry(kind, source, array.get(2).getAsLong(), array.get(3).getAsLong());
        if (this.accept(uniqueId, entry.asCache(), now)) {
          count++;
        }
      } catch (RuntimeException malformed) {
        continue;
      }
    }
    return count;
  }

  private int readLegacy(@NotNull JsonObject legacy, long now) {
    int count = 0;
    for (Map.Entry<String, JsonElement> element : legacy.entrySet()) {
      UUID uniqueId = parseUuid(element.getKey());
      if (uniqueId == null || !element.getValue().isJsonObject()) {
        continue;
      }

      JsonObject value = element.getValue().getAsJsonObject();
      try {
        StatusKind kind = StatusKind.parse(value.has("kind") ? value.get("kind").getAsString() : null);
        StatusSource source = StatusSource.parse(value.has("source") ? value.get("source").getAsString() : null);
        if (kind == null) {
          continue;
        }

        long updatedAt = value.has("lastUpdatedAt") ? value.get("lastUpdatedAt").getAsLong() : 0L;
        long checkedAt = value.has("lastCheckedAt") ? value.get("lastCheckedAt").getAsLong() : 0L;
        StatusEntry entry = new StatusEntry(kind, source == null ? StatusSource.CACHE : source, updatedAt, checkedAt);
        if (this.accept(uniqueId, entry.asCache(), now)) {
          count++;
        }
      } catch (RuntimeException malformed) {
        continue;
      }
    }
    return count;
  }

  private boolean accept(@NotNull UUID uniqueId, @NotNull StatusEntry entry, long now) {
    if (StatusPolicy.isExpired(entry, now)) {
      return false;
    }
    return this.entries.putIfAbsent(uniqueId, entry) == null;
  }

  public void save(long now) {
    if (!this.loaded) {
      return;
    }

    synchronized (this.fileLock) {
      this.dirtySince = 0L;
      long cutoff = this.retentionCutoff(now);
      try {
        Files.createDirectories(this.file.getParent());
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
             JsonWriter json = new JsonWriter(writer)) {
          json.beginObject();
          json.name("version").value(FORMAT_VERSION);
          json.name("entries").beginObject();
          for (Map.Entry<UUID, StatusEntry> element : this.entries.entrySet()) {
            StatusEntry entry = element.getValue();
            if (entry.updatedAt() < cutoff && !entry.source().isLive()) {
              this.entries.remove(element.getKey(), entry);
              continue;
            }

            json.name(element.getKey().toString()).beginArray()
                .value(entry.kind().name())
                .value(entry.source().name())
                .value(entry.updatedAt())
                .value(entry.checkedAt())
                .endArray();
          }
          json.endObject();
          json.endObject();
        }

        try {
          Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
          Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(this.legacyFile);
      } catch (IOException exception) {
        this.markDirty();
      }
    }
  }

  private long retentionCutoff(long now) {
    long cutoff = now - StatusPolicy.MAX_CACHE_AGE_MS;
    int size = this.entries.size();
    if (size <= MAX_ENTRIES) {
      return cutoff;
    }

    long[] stamps = new long[size];
    int count = 0;
    for (StatusEntry entry : this.entries.values()) {
      if (count == stamps.length) {
        break;
      }
      stamps[count++] = entry.updatedAt();
    }

    if (count <= MAX_ENTRIES) {
      return cutoff;
    }

    Arrays.sort(stamps, 0, count);
    return Math.max(cutoff, stamps[count - MAX_ENTRIES]);
  }

  private static @Nullable UUID parseUuid(@NotNull String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }
}
