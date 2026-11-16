package net.statustag.core.status;

import static net.statustag.core.status.StatusKind.AWAY;
import static net.statustag.core.status.StatusKind.OFFLINE;
import static net.statustag.core.status.StatusKind.ONLINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.statustag.core.status.NoticeGate.Outcome;
import net.statustag.core.status.NoticeGate.Toggles;
import org.junit.jupiter.api.Test;

class NoticeGateTest {

  private static final UUID FRIEND = UUID.fromString("1de7e8da-e0c2-4550-8678-6d3d3a4bd670");
  private static final String NAME = "Kallecrafter";
  private static final Toggles ALL = new Toggles(true, true, true);

  private final NoticeGate gate = new NoticeGate();

  private static PresenceSettler.Settled<String> burst(StatusKind before, StatusKind after, long start, long last) {
    return new PresenceSettler.Settled<>(
        FRIEND, NAME, before, after, PresenceSettler.classify(before, after), start, last, 1
    );
  }

  private Outcome decide(StatusKind before, StatusKind after, long start, long last) {
    return this.gate.decide(burst(before, after, start, last), NAME, ALL);
  }

  @Test
  void realChangesArePushed() {
    assertEquals(Outcome.PUSH, this.decide(OFFLINE, ONLINE, 0, 0));
    assertEquals(Outcome.PUSH, this.decide(ONLINE, AWAY, 0, 0));
    assertEquals(Outcome.PUSH, this.decide(AWAY, OFFLINE, 0, 0));
    assertEquals(Outcome.SETTLED, this.decide(OFFLINE, OFFLINE, 0, 4_000));
  }

  @Test
  void togglesAreRespected() {
    Toggles none = new Toggles(false, false, false);
    assertEquals(Outcome.OFF, this.gate.decide(burst(OFFLINE, ONLINE, 0, 0), NAME, none));
    assertEquals(Outcome.OFF, this.gate.decide(burst(ONLINE, AWAY, 0, 0), NAME, none));
    assertEquals(Outcome.OFF, this.gate.decide(burst(ONLINE, OFFLINE, 0, 0), NAME, none));
  }

  @Test
  void joinNoticeReplacesComingOnline() {
    assertTrue(this.gate.claimJoin(FRIEND, 1_000));
    assertEquals(Outcome.JOINED, this.decide(OFFLINE, AWAY, 0, 500));
  }

  @Test
  void joinShortlyBeforeTheBurstStillCounts() {
    assertTrue(this.gate.claimJoin(FRIEND, 0));
    assertEquals(Outcome.JOINED, this.decide(OFFLINE, ONLINE, NoticeGate.JOIN_COVER_MS, NoticeGate.JOIN_COVER_MS));
    assertEquals(Outcome.PUSH, this.decide(OFFLINE, ONLINE, NoticeGate.JOIN_COVER_MS + 1, NoticeGate.JOIN_COVER_MS + 1));
  }

  @Test
  void goingOfflineForgetsTheJoin() {
    assertTrue(this.gate.claimJoin(FRIEND, 0));
    assertEquals(Outcome.PUSH, this.decide(ONLINE, OFFLINE, 1_000, 1_000));
    assertEquals(Outcome.PUSH, this.decide(OFFLINE, ONLINE, 8_000, 8_000));
    assertTrue(this.gate.claimJoin(FRIEND, 9_000));
  }

  @Test
  void joinRepeatsAreBlockedForAMinute() {
    assertTrue(this.gate.claimJoin(FRIEND, 0));
    assertFalse(this.gate.claimJoin(FRIEND, NoticeGate.JOIN_REPEAT_MS - 1));
    assertTrue(this.gate.claimJoin(FRIEND, NoticeGate.JOIN_REPEAT_MS));
  }

  @Test
  void otherNoticeAtTheFinalChangeWins() {
    this.gate.foreignNotice("kallecrafter", 10_000);
    assertEquals(Outcome.FOREIGN, this.decide(OFFLINE, ONLINE, 9_000, 10_500));
  }

  @Test
  void otherNoticeEarlierInTheBurstDoesNotHideTheEnd() {
    this.gate.foreignNotice(NAME, 1_000);
    assertEquals(Outcome.PUSH, this.decide(ONLINE, OFFLINE, 0, 4_000));
  }

  @Test
  void messageSecondsAfterComingOnlineDoesNotHideIt() {
    this.gate.foreignNotice(NAME, 2_000);
    assertEquals(Outcome.PUSH, this.decide(OFFLINE, ONLINE, 0, 0));
  }

  @Test
  void oldEntriesAreForgotten() {
    assertTrue(this.gate.claimJoin(FRIEND, 0));
    this.gate.foreignNotice("someone", NoticeGate.FORGET_AFTER_MS);
    long later = NoticeGate.FORGET_AFTER_MS + NoticeGate.JOIN_COVER_MS;
    assertEquals(Outcome.PUSH, this.decide(OFFLINE, ONLINE, later, later));
  }
}
