package net.statustag.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  void spacesRequestsByDefaultInterval() {
    RateLimiter limiter = new RateLimiter();
    assertEquals(0L, limiter.waitMs(1_000L));
    limiter.onDispatch(1_000L);
    assertEquals(RateLimiter.DEFAULT_INTERVAL_MS, limiter.waitMs(1_000L));
    assertEquals(0L, limiter.waitMs(1_000L + RateLimiter.DEFAULT_INTERVAL_MS));
  }

  @Test
  void waitsForResetWhenQuotaIsExhausted() {
    RateLimiter limiter = new RateLimiter();
    limiter.onDispatch(0L);
    limiter.onQuota(100L, 0, 10_000L);
    assertEquals(10_000L + RateLimiter.RESET_MARGIN_MS - 100L, limiter.waitMs(100L));
  }

  @Test
  void spreadsRemainingQuotaAcrossWindow() {
    RateLimiter limiter = new RateLimiter();
    limiter.onQuota(0L, 3, 10_000L);
    assertEquals(2_500L, limiter.intervalMs());
    limiter.onQuota(0L, 99, 60_000L);
    assertEquals(RateLimiter.MIN_INTERVAL_MS, limiter.intervalMs());
  }

  @Test
  void capsWaitWhenClockIsSkewed() {
    RateLimiter limiter = new RateLimiter();
    limiter.onQuota(0L, 0, 3_600_000L);
    assertEquals(RateLimiter.MAX_BACKOFF_MS + RateLimiter.RESET_MARGIN_MS, limiter.waitMs(0L));
  }

  @Test
  void relaxesIntervalAfterRateLimit() {
    RateLimiter limiter = new RateLimiter();
    limiter.onRateLimited(0L, 1_000L);
    long raised = limiter.intervalMs();
    for (int i = 0; i < 20; i++) {
      limiter.onSuccess();
    }
    assertTrue(raised > RateLimiter.DEFAULT_INTERVAL_MS);
    assertEquals(RateLimiter.DEFAULT_INTERVAL_MS, limiter.intervalMs());
  }

  @Test
  void honoursRetryAfter() {
    RateLimiter limiter = new RateLimiter();
    limiter.onRateLimited(0L, 7_000L);
    assertEquals(7_000L, limiter.waitMs(0L));
    assertTrue(limiter.intervalMs() > RateLimiter.DEFAULT_INTERVAL_MS);
  }

  @Test
  void backsOffExponentiallyAndCaps() {
    RateLimiter limiter = new RateLimiter();
    limiter.onFailure(0L);
    assertEquals(4_000L, limiter.waitMs(0L));
    limiter.onFailure(0L);
    assertEquals(8_000L, limiter.waitMs(0L));
    for (int i = 0; i < 20; i++) {
      limiter.onFailure(0L);
    }
    assertEquals(RateLimiter.MAX_BACKOFF_MS, limiter.waitMs(0L));
    limiter.onSuccess();
    limiter.reset();
    assertEquals(0L, limiter.waitMs(0L));
  }

  @Test
  void parsesResetFormats() {
    long now = 1_794_516_662_219L;
    assertEquals(1_794_516_672_408L, RateLimiter.parseResetAt(now, 1_794_516_672_408L));
    assertEquals(1_794_516_672_000L, RateLimiter.parseResetAt(now, 1_794_516_672L));
    assertEquals(now + 10_000L, RateLimiter.parseResetAt(now, 10L));
    assertEquals(-1L, RateLimiter.parseResetAt(now, 0L));
  }
}
