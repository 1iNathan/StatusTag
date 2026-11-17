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
  void takesErrorCodesFromTheResponse() {
    assertEquals(403, LabyNetClient.errorCode(403, 200));
    assertEquals(404, LabyNetClient.errorCode(-1, 404));
    assertEquals(LookupResult.NETWORK_ERROR, LabyNetClient.errorCode(-1, 200));
    assertEquals(LookupResult.NETWORK_ERROR, LabyNetClient.errorCode(-1, -1));
  }

  @Test
  void readsRetryAfterAsSecondsOrTimestamp() {
    long now = 1_794_500_000_000L;
    assertEquals(30_000L, LabyNetClient.retryAfterMs(30L, now));
    assertEquals(3_600_000L, LabyNetClient.retryAfterMs(3_600L, now));
    assertEquals(5_000L, LabyNetClient.retryAfterMs(now + 5_000L, now));
    assertEquals(0L, LabyNetClient.retryAfterMs(now - 1L, now));
    assertEquals(0L, LabyNetClient.retryAfterMs(-1L, now));
  }
}
