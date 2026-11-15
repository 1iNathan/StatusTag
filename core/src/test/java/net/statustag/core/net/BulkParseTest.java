package net.statustag.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.statustag.core.status.StatusKind;
import org.junit.jupiter.api.Test;

class BulkParseTest {

  private static final UUID A = UUID.fromString("34e57efa-5783-46c7-a9fc-890296aaba1f");
  private static final UUID B = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
  private static final UUID C = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

  private static Map<UUID, LookupResult> parse(String json) {
    return LabyNetClient.parseBulk(JsonParser.parseString(json), List.of(A, B, C));
  }

  @Test
  void readsObjectMapWithHiddenAndMissingPlayers() {
    Map<UUID, LookupResult> entries = parse(
        "{\"" + A + "\":{\"online\":true,\"status\":\"AWAY\"},\"069a79f444e94726a5befca90e38aaf5\":{\"hidden\":true}}"
    );

    assertNotNull(entries);
    assertEquals(StatusKind.AWAY, entries.get(A).interpret(true, false));
    assertEquals(StatusKind.PROFILE_HIDDEN, entries.get(B).interpret(true, false));
    assertEquals(LookupResult.NOT_LISTED, entries.get(C).code());
    assertEquals(StatusKind.NO_LABYMOD, entries.get(C).interpret(true, false));
    assertEquals(StatusKind.PROFILE_HIDDEN, entries.get(C).interpret(true, true));
  }

  @Test
  void readsTheRealLabyNetShape() {
    Map<UUID, LookupResult> entries = parse("{\"" + A + "\":{\"online\":true,\"last-online\":\"2026-11-14T08:53:06+00:00\","
        + "\"lastOnline\":\"2026-11-14T08:53:06+00:00\",\"status\":\"ONLINE\",\"server\":{\"name\":\"x\"}},"
        + "\"" + B + "\":{\"online\":false,\"status\":\"OFFLINE\"}}");

    assertNotNull(entries);
    assertEquals(StatusKind.ONLINE, entries.get(A).interpret(true, false));
    assertEquals(java.time.OffsetDateTime.parse("2026-11-14T08:53:06+00:00").toInstant().toEpochMilli(),
        entries.get(A).lastOnline());
    assertEquals(-1L, entries.get(B).lastOnline());
    assertEquals(StatusKind.INVISIBLE, entries.get(B).interpret(true, true));
    assertEquals(StatusKind.OFFLINE, entries.get(B).interpret(false, true));
    assertEquals(StatusKind.PROFILE_HIDDEN, entries.get(C).interpret(true, true));
  }

  @Test
  void readsArrayOfObjectsAndPlainStrings() {
    Map<UUID, LookupResult> array = parse("[{\"uuid\":\"" + A + "\",\"status\":\"BUSY\"}]");
    assertNotNull(array);
    assertEquals(StatusKind.BUSY, array.get(A).interpret(true, false));

    Map<UUID, LookupResult> strings = parse("{\"" + A + "\":\"ONLINE\",\"" + B + "\":null}");
    assertNotNull(strings);
    assertEquals(StatusKind.ONLINE, strings.get(A).interpret(true, false));
    assertEquals(StatusKind.NO_LABYMOD, strings.get(B).interpret(true, false));
  }

  @Test
  void rejectsUnknownFormats() {
    assertNull(parse("{\"" + A + "\":{\"current\":5}}"));
    assertNull(parse("{\"not-a-uuid\":{\"status\":\"ONLINE\"}}"));
    assertNull(parse("\"ok\""));
    assertNull(LabyNetClient.parseBulk(null, List.of(A)));
  }

  @Test
  void emptyObjectMeansNobodyKnown() {
    Map<UUID, LookupResult> entries = parse("{}");
    assertNotNull(entries);
    for (LookupResult entry : entries.values()) {
      assertEquals(LookupResult.NOT_LISTED, entry.code());
    }
  }

  @Test
  void parsesUndashedUuids() {
    assertEquals(A, LabyNetClient.parseUuid("34e57efa578346c7a9fc890296aaba1f"));
    assertNull(LabyNetClient.parseUuid("nope"));
  }

  @Test
  void unsupportedDetection() {
    assertTrue(BulkLookupResult.unsupported().isUnsupported());
    assertTrue(new BulkLookupResult(405, null, 0L, -1, -1L).isUnsupported());
    assertTrue(new BulkLookupResult(401, null, 0L, -1, -1L).isUnsupported());
    assertTrue(new BulkLookupResult(403, null, 0L, -1, -1L).isUnsupported());
    assertTrue(new BulkLookupResult(400, null, 0L, -1, -1L).isUnsupported());
    assertFalse(new BulkLookupResult(429, null, 0L, -1, -1L).isUnsupported());
    assertFalse(new BulkLookupResult(503, null, 0L, -1, -1L).isUnsupported());
    assertTrue(new BulkLookupResult(200, null, 0L, -1, -1L).isUnsupported());
    assertTrue(new BulkLookupResult(200, Map.of(), 0L, -1, -1L).isSuccess());
  }
}
