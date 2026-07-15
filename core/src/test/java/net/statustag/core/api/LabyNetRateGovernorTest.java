package net.statustag.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import net.statustag.core.internal.StatusTagInternals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabyNetRateGovernorTest {

  private static final long T0 = 1_000_000L;
  private static final int BASE_INTERVAL = 3_500;

  private final AtomicLong now = new AtomicLong(T0);
  private LabyNetRateGovernor governor;

  @BeforeEach
  void setUp() {
    this.governor = new LabyNetRateGovernor(new StatusTagInternals());
    this.governor.clock(this.now::get);
  }

  @Test
  void freshGovernorDispatchesImmediately() {
    assertEquals(0L, this.governor.waitMs());
    assertTrue(this.governor.canDispatch());
    assertEquals(BASE_INTERVAL, this.governor.currentIntervalMs());
  }

  @Test
  void dispatchEnforcesBaseInterval() {
    this.governor.recordDispatch();
    assertEquals(BASE_INTERVAL, this.governor.waitMs());
    assertFalse(this.governor.canDispatch());
    this.now.addAndGet(BASE_INTERVAL - 1);
    assertEquals(1L, this.governor.waitMs());
    this.now.addAndGet(1);
    assertTrue(this.governor.canDispatch());
  }

  @Test
  void rateLimitBackoffEscalatesAndCaps() {
    this.governor.recordRateLimit();
    assertEquals(5_000L, this.governor.cooldownRemainingMs());
    this.governor.recordRateLimit();
    assertEquals(12_000L, this.governor.cooldownRemainingMs());
    this.governor.recordRateLimit();
    assertEquals(25_000L, this.governor.cooldownRemainingMs());
    this.governor.recordRateLimit();
    assertEquals(45_000L, this.governor.cooldownRemainingMs());
    this.governor.recordRateLimit();
    assertEquals(45_000L, this.governor.cooldownRemainingMs());
    assertEquals(45_000L, this.governor.waitMs());
  }

  @Test
  void networkErrorBackoffEscalatesAndCaps() {
    this.governor.recordNetworkError();
    assertEquals(5_000L, this.governor.cooldownRemainingMs());
    this.governor.recordNetworkError();
    assertEquals(15_000L, this.governor.cooldownRemainingMs());
    this.governor.recordNetworkError();
    assertEquals(30_000L, this.governor.cooldownRemainingMs());
    this.governor.recordNetworkError();
    assertEquals(60_000L, this.governor.cooldownRemainingMs());
    this.governor.recordNetworkError();
    assertEquals(60_000L, this.governor.cooldownRemainingMs());
  }

  @Test
  void successClearsNetworkErrorStreak() {
    this.governor.recordNetworkError();
    this.governor.recordNetworkError();
    this.governor.recordSuccess();
    this.now.addAndGet(60_000L);
    this.governor.recordNetworkError();
    assertEquals(5_000L, this.governor.cooldownRemainingMs());
  }

  @Test
  void rateLimitsWidenIntervalUpToMax() {
    for (int i = 0; i < 7; i++) {
      this.governor.recordRateLimit();
    }
    assertEquals(8_000, this.governor.currentIntervalMs());
  }

  @Test
  void successStreaksShrinkIntervalBackToBaseButNotBelow() {
    this.governor.recordRateLimit();
    for (int batch = 0; batch < 4; batch++) {
      for (int i = 0; i < 8; i++) {
        this.governor.recordSuccess();
      }
    }
    assertEquals(BASE_INTERVAL, this.governor.currentIntervalMs());
  }

  @Test
  void perMinuteWindowCapsBursts() {
    for (int i = 0; i < 16; i++) {
      this.governor.recordDispatch();
      this.now.addAndGet(BASE_INTERVAL);
    }
    assertEquals(4_000L, this.governor.waitMs());
    this.now.addAndGet(4_000L);
    assertTrue(this.governor.canDispatch());
  }

  @Test
  void oldTimestampsArePrunedAfterOneMinute() {
    this.governor.recordDispatch();
    this.now.addAndGet(61_000L);
    assertEquals(0L, this.governor.waitMs());
  }

  @Test
  void resetRestoresBaseline() {
    this.governor.recordRateLimit();
    this.governor.recordDispatch();
    this.governor.reset();
    assertEquals(0L, this.governor.waitMs());
    assertEquals(0L, this.governor.cooldownRemainingMs());
    assertEquals(BASE_INTERVAL, this.governor.currentIntervalMs());
  }
}
