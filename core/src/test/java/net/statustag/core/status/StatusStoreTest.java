package net.statustag.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusStoreTest {

  private static final long NOW = 1_794_500_000_000L;

  @TempDir
  Path directory;

  private StatusStore newStore() {
    return new StatusStore(this.directory.resolve("status-cache.json"), this.directory.resolve("status-store.json"));
  }

  private StatusStore loadedStore() {
    StatusStore store = this.newStore();
    store.load(NOW);
    return store;
  }

  @Test
  void neverSavesBeforeLoading() {
    StatusStore store = this.newStore();
    store.put(UUID.randomUUID(), new StatusEntry(StatusKind.ONLINE, StatusSource.API, NOW, NOW));
    assertFalse(store.isSaveDue(System.currentTimeMillis() + StatusStore.SAVE_DELAY_MS * 2L));
    store.save(NOW);
    assertFalse(Files.exists(this.directory.resolve("status-cache.json")));

    store.load(NOW);
    assertTrue(store.isLoaded());
    store.save(NOW);
    assertTrue(Files.exists(this.directory.resolve("status-cache.json")));
  }

  @Test
  void pruningKeepsLiveEntries() {
    StatusStore store = this.loadedStore();
    UUID friend = UUID.randomUUID();
    store.put(friend, new StatusEntry(StatusKind.ONLINE, StatusSource.FRIEND, NOW - StatusPolicy.MAX_CACHE_AGE_MS - 1L, 0L));
    store.save(NOW);
    assertNotNull(store.get(friend));
  }

  @Test
  void roundTripsEntriesAsCache() {
    StatusStore store = this.loadedStore();
    UUID api = UUID.randomUUID();
    UUID friend = UUID.randomUUID();
    store.put(api, new StatusEntry(StatusKind.BUSY, StatusSource.API, NOW - 1_000L, NOW - 1_000L));
    store.put(friend, new StatusEntry(StatusKind.ONLINE, StatusSource.FRIEND, NOW - 1_000L, 0L));
    store.save(NOW);

    StatusStore reloaded = this.newStore();
    assertEquals(2, reloaded.load(NOW));

    StatusEntry apiEntry = reloaded.get(api);
    assertNotNull(apiEntry);
    assertEquals(StatusKind.BUSY, apiEntry.kind());
    assertEquals(StatusSource.CACHE, apiEntry.source());
    assertEquals(NOW - 1_000L, apiEntry.checkedAt());

    StatusEntry friendEntry = reloaded.get(friend);
    assertNotNull(friendEntry);
    assertEquals(StatusSource.CACHE, friendEntry.source());
    assertEquals(0L, friendEntry.checkedAt());
  }

  @Test
  void loadNeverOverridesNewerEntries() {
    StatusStore store = this.loadedStore();
    UUID id = UUID.randomUUID();
    store.put(id, new StatusEntry(StatusKind.AWAY, StatusSource.API, NOW, NOW));
    store.save(NOW);

    StatusStore reloaded = this.newStore();
    reloaded.put(id, new StatusEntry(StatusKind.ONLINE, StatusSource.FRIEND, NOW + 1L, 0L));
    reloaded.load(NOW);
    assertEquals(StatusKind.ONLINE, reloaded.get(id).kind());
  }

  @Test
  void migratesLegacyFile() throws IOException {
    UUID id = UUID.randomUUID();
    String legacy = "{\"" + id + "\":{\"uuid\":\"" + id + "\",\"kind\":\"INVISIBLE\",\"source\":\"MESH\","
        + "\"lastCheckedAt\":" + (NOW - 5_000L) + ",\"lastUpdatedAt\":" + (NOW - 5_000L)
        + ",\"skipApi\":false,\"httpCode\":200,\"meshTimestamp\":0},"
        + "\"not-a-uuid\":{\"kind\":\"ONLINE\"}}";
    Path legacyFile = this.directory.resolve("status-store.json");
    Files.writeString(legacyFile, legacy, StandardCharsets.UTF_8);

    StatusStore store = this.newStore();
    assertEquals(1, store.load(NOW));
    StatusEntry entry = store.get(id);
    assertNotNull(entry);
    assertEquals(StatusKind.INVISIBLE, entry.kind());
    assertEquals(StatusSource.CACHE, entry.source());
    assertTrue(store.isSaveDue(System.currentTimeMillis() + StatusStore.SAVE_DELAY_MS));

    store.save(NOW);
    assertFalse(Files.exists(legacyFile));
    assertTrue(Files.exists(this.directory.resolve("status-cache.json")));
  }

  @Test
  void dropsExpiredEntries() {
    StatusStore store = this.loadedStore();
    UUID fresh = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    store.put(fresh, new StatusEntry(StatusKind.ONLINE, StatusSource.API, NOW, NOW));
    store.put(stale, new StatusEntry(StatusKind.ONLINE, StatusSource.API, NOW - StatusPolicy.MAX_CACHE_AGE_MS - 1L, 0L));
    store.save(NOW);

    assertNull(store.get(stale));
    StatusStore reloaded = this.newStore();
    assertEquals(1, reloaded.load(NOW));
    assertNotNull(reloaded.get(fresh));
  }

  @Test
  void capsEntryCountKeepingNewest() {
    StatusStore store = this.loadedStore();
    int total = StatusStore.MAX_ENTRIES + 250;
    UUID newest = null;
    for (int i = 0; i < total; i++) {
      UUID id = UUID.randomUUID();
      store.put(id, new StatusEntry(StatusKind.OFFLINE, StatusSource.API, NOW - (total - i), NOW));
      newest = id;
    }
    store.save(NOW);

    assertEquals(StatusStore.MAX_ENTRIES, store.size());
    assertNotNull(store.get(newest));
  }

  @Test
  void ignoresCorruptFile() throws IOException {
    Files.writeString(this.directory.resolve("status-cache.json"), "{broken", StandardCharsets.UTF_8);
    StatusStore store = this.newStore();
    assertEquals(0, store.load(NOW));
    assertEquals(0, store.size());
  }

  @Test
  void saveIsOnlyDueAfterDelay() {
    StatusStore store = this.loadedStore();
    long now = System.currentTimeMillis();
    assertFalse(store.isSaveDue(now));
    store.put(UUID.randomUUID(), new StatusEntry(StatusKind.ONLINE, StatusSource.API, now, now));
    assertFalse(store.isSaveDue(now));
    assertTrue(store.isSaveDue(now + StatusStore.SAVE_DELAY_MS + 50L));
  }
}
