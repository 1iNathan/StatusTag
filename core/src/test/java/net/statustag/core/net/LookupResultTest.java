package net.statustag.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.statustag.core.status.StatusKind;
import org.junit.jupiter.api.Test;

class LookupResultTest {

  private static LookupResult result(int code, String status, boolean online) {
    return new LookupResult(code, status, online, 0L, -1, -1L);
  }

  @Test
  void mapsPresenceStatuses() {
    assertEquals(StatusKind.ONLINE, result(200, "ONLINE", true).interpret(true, false));
    assertEquals(StatusKind.AWAY, result(200, "away", true).interpret(false, false));
    assertEquals(StatusKind.BUSY, result(200, "BUSY", true).interpret(true, true));
  }

  @Test
  void offlinePlayerOnTabIsInvisible() {
    assertEquals(StatusKind.INVISIBLE, result(200, "OFFLINE", false).interpret(true, false));
    assertEquals(StatusKind.OFFLINE, result(200, "OFFLINE", false).interpret(false, false));
  }

  @Test
  void respectsHiddenProfiles() {
    assertEquals(StatusKind.PROFILE_HIDDEN, result(403, null, false).interpret(true, true));
  }

  @Test
  void mapsMissingAccounts() {
    assertEquals(StatusKind.NO_LABYMOD, result(404, null, false).interpret(true, false));
    assertEquals(StatusKind.NO_LABYMOD, result(204, null, false).interpret(true, false));
    assertEquals(StatusKind.INVISIBLE, result(204, null, false).interpret(true, true));
    assertEquals(StatusKind.OFFLINE, result(204, null, false).interpret(false, true));
  }

  @Test
  void transientFailuresYieldNothing() {
    assertNull(result(429, null, false).interpret(true, true));
    assertNull(result(500, null, false).interpret(true, true));
    assertNull(result(LookupResult.NETWORK_ERROR, null, false).interpret(true, true));
    assertNull(result(200, "INVISIBLE", true).interpret(true, true));
  }

  @Test
  void readsCodesFromErrorMessages() {
    assertEquals(429, LabyNetClient.codeFromMessage("429 Too Many Requests"));
    assertEquals(403, LabyNetClient.codeFromMessage("Forbidden"));
    assertEquals(404, LabyNetClient.codeFromMessage("Not Found"));
    assertEquals(401, LabyNetClient.codeFromMessage("Unauthorized"));
    assertEquals(LookupResult.NETWORK_ERROR, LabyNetClient.codeFromMessage("connect timed out"));
    assertEquals(LookupResult.NETWORK_ERROR, LabyNetClient.codeFromMessage(null));
  }
}
