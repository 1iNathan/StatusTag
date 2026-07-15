package net.statustag.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabyNetProbeMemoryTest {

  private final UUID player = UUID.randomUUID();
  private LabyNetProbeMemory memory;

  @BeforeEach
  void setUp() {
    this.memory = new LabyNetProbeMemory();
  }

  @Test
  void unknownPlayerStartsWithOnlineFirst() {
    assertEquals(LabyNetProbePlan.ONLINE_FIRST, this.memory.planFor(this.player, false));
    assertEquals(LabyNetProbePlan.ONLINE_FIRST, this.memory.planFor(this.player, true));
  }

  @Test
  void noLabyModAccountSkipsAllProbes() {
    this.memory.recordNoLabyModAccount(this.player);
    assertEquals(LabyNetProbePlan.SKIP, this.memory.planFor(this.player, false));
    assertEquals(LabyNetProbePlan.SKIP, this.memory.planFor(this.player, true));
  }

  @Test
  void noLabyModWinsOverOnlineAccessHints() {
    this.memory.recordOnlineAllowed(this.player);
    this.memory.recordNoLabyModAccount(this.player);
    assertEquals(LabyNetProbePlan.SKIP, this.memory.planFor(this.player, true));
  }

  @Test
  void blockedOnlineStatusFallsBackToStats() {
    this.memory.recordOnlineBlocked(this.player);
    assertEquals(LabyNetProbePlan.STATS_ONLY, this.memory.planFor(this.player, false));
    assertEquals(LabyNetProbePlan.STATS_WITH_PROFILE, this.memory.planFor(this.player, true));
  }

  @Test
  void allowedOnlineStatusUsesOnlineOnly() {
    this.memory.recordOnlineAllowed(this.player);
    assertEquals(LabyNetProbePlan.ONLINE_ONLY, this.memory.planFor(this.player, false));
    assertEquals(LabyNetProbePlan.ONLINE_ONLY, this.memory.planFor(this.player, true));
  }

  @Test
  void latestOnlineAccessObservationWins() {
    this.memory.recordOnlineBlocked(this.player);
    this.memory.recordOnlineAllowed(this.player);
    assertEquals(LabyNetProbePlan.ONLINE_ONLY, this.memory.planFor(this.player, false));
  }

  @Test
  void knownLabyModAccountWithUnknownAccessProbesWiderOnTab() {
    this.memory.recordLabyModAccount(this.player);
    assertEquals(LabyNetProbePlan.ONLINE_FIRST, this.memory.planFor(this.player, false));
    assertEquals(LabyNetProbePlan.ONLINE_THEN_PROFILE, this.memory.planFor(this.player, true));
  }

  @Test
  void clearForgetsHints() {
    this.memory.recordNoLabyModAccount(this.player);
    this.memory.clear(this.player);
    assertEquals(LabyNetProbePlan.ONLINE_FIRST, this.memory.planFor(this.player, false));

    this.memory.recordOnlineBlocked(this.player);
    this.memory.clearAll();
    assertEquals(LabyNetProbePlan.ONLINE_FIRST, this.memory.planFor(this.player, true));
  }
}
