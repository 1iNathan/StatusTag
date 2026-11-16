package net.statustag.core.status;

import static net.statustag.core.status.StatusKind.AWAY;
import static net.statustag.core.status.StatusKind.BUSY;
import static net.statustag.core.status.StatusKind.OFFLINE;
import static net.statustag.core.status.StatusKind.ONLINE;
import static net.statustag.core.status.StatusKind.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.statustag.core.status.PresenceSettler.Change;
import net.statustag.core.status.PresenceSettler.Settled;
import org.junit.jupiter.api.Test;

class PresenceSettlerTest {

  private static final long QUIET = 3_000L;
  private static final UUID FIRST = UUID.fromString("1de7e8da-e0c2-4550-8678-6d3d3a4bd670");
  private static final UUID SECOND = UUID.fromString("bd4f27b2-69cc-41c3-a381-d6ead7483369");

  private final PresenceSettler<String> settler = new PresenceSettler<>(QUIET);

  private void change(UUID id, StatusKind before, StatusKind after, long at) {
    this.settler.record(id, id.toString(), before, after, at);
  }

  @Test
  void reconnectBurstEndingWhereItStartedIsSilent() {
    long[] at = {0, 100, 200, 1_000, 2_000, 2_100, 2_200, 2_500, 3_700, 3_800, 3_900, 4_200};
    StatusKind[] states = {AWAY, ONLINE, AWAY, OFFLINE, AWAY, ONLINE, AWAY, OFFLINE, AWAY, ONLINE, AWAY, OFFLINE};
    StatusKind previous = OFFLINE;
    for (int i = 0; i < at.length; i++) {
      this.change(FIRST, previous, states[i], at[i]);
      previous = states[i];
    }

    assertTrue(this.settler.due(4_200 + QUIET - 1).isEmpty());
    List<Settled<String>> settled = this.settler.due(4_200 + QUIET);
    assertEquals(1, settled.size());
    assertEquals(Change.NONE, settled.get(0).change());
    assertEquals(12, settled.get(0).changes());
    assertEquals(0, this.settler.pending());
  }

  @Test
  void shortOnlineBlipWhileAwayIsSilent() {
    this.change(SECOND, AWAY, ONLINE, 0);
    this.change(SECOND, ONLINE, AWAY, 100);

    List<Settled<String>> settled = this.settler.due(100 + QUIET);
    assertEquals(1, settled.size());
    assertEquals(Change.NONE, settled.get(0).change());
  }

  @Test
  void comingOnlineThenAwayReportsOnlyTheFinalStatus() {
    this.change(FIRST, OFFLINE, ONLINE, 0);
    this.change(FIRST, ONLINE, AWAY, 900);

    Settled<String> settled = this.settler.due(900 + QUIET).get(0);
    assertEquals(Change.CAME_ONLINE, settled.change());
    assertEquals(OFFLINE, settled.before());
    assertEquals(AWAY, settled.after());
    assertEquals(0, settled.startedAt());
    assertEquals(900, settled.lastAt());
  }

  @Test
  void changesFurtherApartAreSeparate() {
    this.change(FIRST, ONLINE, AWAY, 0);
    assertEquals(Change.STATUS, this.settler.due(QUIET).get(0).change());

    this.change(FIRST, AWAY, ONLINE, 300_000);
    Settled<String> back = this.settler.due(300_000 + QUIET).get(0);
    assertEquals(Change.STATUS, back.change());
    assertEquals(AWAY, back.before());
    assertEquals(ONLINE, back.after());
  }

  @Test
  void everyChangeRestartsTheQuietWindow() {
    this.change(FIRST, OFFLINE, ONLINE, 0);
    this.change(FIRST, ONLINE, OFFLINE, 2_900);
    this.change(FIRST, OFFLINE, ONLINE, 5_800);

    assertTrue(this.settler.due(QUIET).isEmpty());
    assertTrue(this.settler.due(5_800 + QUIET - 1).isEmpty());
    assertEquals(1, this.settler.pending());
    Settled<String> settled = this.settler.due(5_800 + QUIET).get(0);
    assertEquals(Change.CAME_ONLINE, settled.change());
    assertEquals(3, settled.changes());
  }

  @Test
  void friendsAreSettledIndependently() {
    this.change(FIRST, OFFLINE, ONLINE, 0);
    this.change(SECOND, ONLINE, OFFLINE, 2_000);

    List<Settled<String>> first = this.settler.due(QUIET);
    assertEquals(1, first.size());
    assertEquals(FIRST, first.get(0).id());
    assertEquals(1, this.settler.pending());

    List<Settled<String>> second = this.settler.due(2_000 + QUIET);
    assertEquals(SECOND, second.get(0).id());
    assertEquals(Change.WENT_OFFLINE, second.get(0).change());
  }

  @Test
  void classifyCoversEveryTransition() {
    assertEquals(Change.CAME_ONLINE, PresenceSettler.classify(OFFLINE, ONLINE));
    assertEquals(Change.CAME_ONLINE, PresenceSettler.classify(OFFLINE, BUSY));
    assertEquals(Change.WENT_OFFLINE, PresenceSettler.classify(AWAY, OFFLINE));
    assertEquals(Change.STATUS, PresenceSettler.classify(ONLINE, AWAY));
    assertEquals(Change.STATUS, PresenceSettler.classify(BUSY, ONLINE));
    assertEquals(Change.NONE, PresenceSettler.classify(ONLINE, ONLINE));
    assertEquals(Change.NONE, PresenceSettler.classify(OFFLINE, OFFLINE));
    assertEquals(Change.NONE, PresenceSettler.classify(UNKNOWN, ONLINE));
    assertEquals(Change.NONE, PresenceSettler.classify(ONLINE, UNKNOWN));
  }
}
