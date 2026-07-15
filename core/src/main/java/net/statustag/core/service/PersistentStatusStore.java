package net.statustag.core.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.statustag.core.util.AddonDataPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PersistentStatusStore {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Type STORE_TYPE = new TypeToken<Map<String, StatusEntry>>() {}.getType();
  private static final long MAX_ENTRY_AGE_MS = 45L * 86_400_000L;

  private final Map<UUID, StatusEntry> entries = new ConcurrentHashMap<>();
  private final Path storeFile;
  private volatile long dirtySince;
  private volatile long lastSaveAt;

  public PersistentStatusStore() {
    this(AddonDataPaths.statusStoreFile());
  }

  public PersistentStatusStore(@NotNull Path storeFile) {
    this.storeFile = storeFile;
    this.load();
  }

  public void load() {
    this.entries.clear();
    if (!Files.exists(this.storeFile)) {
      return;
    }

    try (Reader reader = Files.newBufferedReader(this.storeFile)) {
      Map<String, StatusEntry> loaded = GSON.fromJson(reader, STORE_TYPE);
      if (loaded == null) {
        return;
      }

      long now = System.currentTimeMillis();
      for (Map.Entry<String, StatusEntry> entry : loaded.entrySet()) {
        StatusEntry value = entry.getValue();
        if (value == null || value.kind() == null) {
          continue;
        }

        if (value.lastUpdatedAt() > 0L && now - value.lastUpdatedAt() > MAX_ENTRY_AGE_MS) {
          continue;
        }

        UUID uuid;
        try {
          uuid = UUID.fromString(entry.getKey());
        } catch (IllegalArgumentException badKey) {
          continue;
        }

        StatusSource source = value.source();
        if (source == null || value.isAuthoritative()) {
          source = StatusSource.DISK;
        }

        this.entries.put(uuid, new StatusEntry(
            uuid,
            value.kind(),
            source,
            value.lastCheckedAt(),
            value.lastUpdatedAt(),
            value.skipApi(),
            value.httpCode(),
            value.meshTimestamp()
        ));
      }
    } catch (Exception ignored) {
    }
  }

  public void put(@NotNull StatusEntry entry) {
    this.entries.put(entry.uuid(), entry.copy());
    this.markDirty();
  }

  public @Nullable StatusEntry get(@NotNull UUID uuid) {
    StatusEntry entry = this.entries.get(uuid);
    return entry == null ? null : entry.copy();
  }

  public boolean contains(@NotNull UUID uuid) {
    return this.entries.containsKey(uuid);
  }

  public void remove(@NotNull UUID uuid) {
    if (this.entries.remove(uuid) != null) {
      this.markDirty();
    }
  }

  public void clear() {
    this.entries.clear();
    this.markDirty();
    this.saveNow();
  }

  public void markDirty() {
    this.dirtySince = System.currentTimeMillis();
  }

  public void saveIfNeeded() {
    if (this.dirtySince == 0L) {
      return;
    }

    long now = System.currentTimeMillis();
    if (now - this.dirtySince < 3_000L && now - this.lastSaveAt < 3_000L) {
      return;
    }

    this.saveNow();
  }

  public void saveNow() {
    try {

      this.dirtySince = 0L;

      Files.createDirectories(this.storeFile.getParent());
      Map<String, StatusEntry> serialized = new ConcurrentHashMap<>();
      for (Map.Entry<UUID, StatusEntry> entry : this.entries.entrySet()) {
        serialized.put(entry.getKey().toString(), entry.getValue());
      }

      Path tmp = this.storeFile.resolveSibling(this.storeFile.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(tmp)) {
        GSON.toJson(serialized, writer);
      }

      try {
        Files.move(tmp, this.storeFile,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
        Files.move(tmp, this.storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }

      this.lastSaveAt = System.currentTimeMillis();
    } catch (IOException ignored) {
      this.markDirty();
    }
  }

  public int size() {
    return this.entries.size();
  }
}
