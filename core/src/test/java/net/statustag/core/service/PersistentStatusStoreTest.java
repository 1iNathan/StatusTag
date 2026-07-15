package net.statustag.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentStatusStoreTest {

  @TempDir
  Path tempDir;

  private Path storeFile;

  @BeforeEach
  void setUp() {
    this.storeFile = this.tempDir.resolve("nested").resolve("status-store.json");
  }

  private static StatusEntry entry(UUID uuid, StatusKind kind, StatusSource source) {
    long now = System.currentTimeMillis();
    return new StatusEntry(uuid, kind, source, now, now, true, 204, now);
  }

  @Test
  void getReturnsDefensiveCopies() {
    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    UUID uuid = UUID.randomUUID();
    StatusEntry original = entry(uuid, StatusKind.ONLINE, StatusSource.API);
    store.put(original);

    original.setKind(StatusKind.BUSY);
    StatusEntry fetched = store.get(uuid);
    assertNotNull(fetched);
    assertEquals(StatusKind.ONLINE, fetched.kind());

    fetched.setKind(StatusKind.OFFLINE);
    assertEquals(StatusKind.ONLINE, store.get(uuid).kind());
  }

  @Test
  void saveAndReloadRoundTripsApiEntries() {
    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    UUID uuid = UUID.randomUUID();
    StatusEntry original = entry(uuid, StatusKind.AWAY, StatusSource.API);
    store.put(original);
    store.saveNow();

    PersistentStatusStore reloaded = new PersistentStatusStore(this.storeFile);
    StatusEntry loaded = reloaded.get(uuid);
    assertNotNull(loaded);
    assertEquals(StatusKind.AWAY, loaded.kind());
    assertEquals(StatusSource.API, loaded.source());
    assertEquals(204, loaded.httpCode());
  }

  @Test
  void authoritativeSourcesAreDemotedToDiskOnLoad() {
    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    UUID uuid = UUID.randomUUID();
    store.put(entry(uuid, StatusKind.ONLINE, StatusSource.FRIEND));
    store.saveNow();

    StatusEntry loaded = new PersistentStatusStore(this.storeFile).get(uuid);
    assertNotNull(loaded);
    assertEquals(StatusSource.DISK, loaded.source());
    assertFalse(loaded.isAuthoritative());
  }

  @Test
  void staleEntriesAreDroppedOnLoad() throws IOException {
    Files.createDirectories(this.storeFile.getParent());
    long ancient = System.currentTimeMillis() - 60L * 86_400_000L;
    Files.writeString(this.storeFile, "{\n"
        + "  \"11111111-1111-1111-1111-111111111111\": {\"kind\": \"ONLINE\", \"source\": \"API\","
        + " \"lastUpdatedAt\": " + ancient + "}\n}");
    assertEquals(0, new PersistentStatusStore(this.storeFile).size());
  }

  @Test
  void corruptFileYieldsEmptyStore() throws IOException {
    Files.createDirectories(this.storeFile.getParent());
    Files.writeString(this.storeFile, "{{{ not json");
    assertEquals(0, new PersistentStatusStore(this.storeFile).size());
  }

  @Test
  void invalidKeysAndUnknownKindsAreSkipped() throws IOException {
    Files.createDirectories(this.storeFile.getParent());
    long now = System.currentTimeMillis();
    Files.writeString(this.storeFile, "{\n"
        + "  \"not-a-uuid\": {\"kind\": \"ONLINE\", \"source\": \"API\", \"lastUpdatedAt\": " + now + "},\n"
        + "  \"11111111-1111-1111-1111-111111111111\": {\"kind\": \"NOT_A_KIND\", \"source\": \"API\", \"lastUpdatedAt\": " + now + "},\n"
        + "  \"22222222-2222-2222-2222-222222222222\": {\"kind\": \"AWAY\", \"source\": \"API\", \"lastCheckedAt\": " + now + ", \"lastUpdatedAt\": " + now + "}\n"
        + "}");

    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    assertEquals(1, store.size());
    StatusEntry survivor = store.get(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    assertNotNull(survivor);
    assertEquals(StatusKind.AWAY, survivor.kind());
  }

  @Test
  void removeContainsAndClearBehave() {
    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    UUID uuid = UUID.randomUUID();
    store.put(entry(uuid, StatusKind.ONLINE, StatusSource.API));
    assertTrue(store.contains(uuid));

    store.remove(uuid);
    assertFalse(store.contains(uuid));
    assertNull(store.get(uuid));

    store.put(entry(UUID.randomUUID(), StatusKind.BUSY, StatusSource.API));
    store.clear();
    assertEquals(0, store.size());
    assertEquals(0, new PersistentStatusStore(this.storeFile).size());
  }

  @Test
  void saveIsAtomicViaTempFile() {
    PersistentStatusStore store = new PersistentStatusStore(this.storeFile);
    store.put(entry(UUID.randomUUID(), StatusKind.ONLINE, StatusSource.API));
    store.saveNow();
    assertTrue(Files.exists(this.storeFile));

    assertFalse(Files.exists(this.storeFile.resolveSibling(this.storeFile.getFileName() + ".tmp")));
  }
}
