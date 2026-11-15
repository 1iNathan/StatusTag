package net.statustag.core.net;

public final class RateLimiter {

  static final long DEFAULT_INTERVAL_MS = 2_600L;
  static final long MIN_INTERVAL_MS = 750L;
  static final long MAX_INTERVAL_MS = 15_000L;
  static final long MAX_BACKOFF_MS = 120_000L;
  static final long RESET_MARGIN_MS = 150L;
  static final long RELAX_STEP_MS = 250L;

  private long nextAllowedAt;
  private long intervalMs = DEFAULT_INTERVAL_MS;
  private int failures;

  public synchronized long waitMs(long now) {
    return Math.max(0L, this.nextAllowedAt - now);
  }

  public synchronized void onDispatch(long now) {
    this.nextAllowedAt = Math.max(this.nextAllowedAt, now + this.intervalMs);
  }

  public synchronized void onQuota(long now, int remaining, long resetAt) {
    if (remaining < 0 || resetAt <= now) {
      return;
    }

    long window = Math.min(resetAt - now, MAX_BACKOFF_MS);
    if (remaining == 0) {
      this.nextAllowedAt = Math.max(this.nextAllowedAt, now + window + RESET_MARGIN_MS);
      return;
    }

    long spread = window / (remaining + 1L);
    this.intervalMs = clamp(spread, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
  }

  public synchronized void onSuccess() {
    this.failures = 0;
    if (this.intervalMs > DEFAULT_INTERVAL_MS) {
      this.intervalMs = Math.max(DEFAULT_INTERVAL_MS, this.intervalMs - RELAX_STEP_MS);
    }
  }

  public synchronized void onRateLimited(long now, long retryAfterMs) {
    this.failures++;
    long wait = retryAfterMs > 0L ? retryAfterMs : this.backoff(5_000L);
    this.nextAllowedAt = Math.max(this.nextAllowedAt, now + Math.min(wait, MAX_BACKOFF_MS));
    this.intervalMs = clamp(this.intervalMs + this.intervalMs / 2L, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
  }

  public synchronized void onFailure(long now) {
    this.failures++;
    this.nextAllowedAt = Math.max(this.nextAllowedAt, now + this.backoff(4_000L));
  }

  public synchronized void pause(long now, long durationMs) {
    this.nextAllowedAt = Math.max(this.nextAllowedAt, now + durationMs);
  }

  public synchronized void reset() {
    this.nextAllowedAt = 0L;
    this.intervalMs = DEFAULT_INTERVAL_MS;
    this.failures = 0;
  }

  public synchronized long intervalMs() {
    return this.intervalMs;
  }

  private long backoff(long baseMs) {
    int shift = Math.min(Math.max(this.failures - 1, 0), 5);
    return Math.min(baseMs << shift, MAX_BACKOFF_MS);
  }

  private static long clamp(long value, long min, long max) {
    return value < min ? min : Math.min(value, max);
  }

  public static long parseResetAt(long now, long raw) {
    if (raw <= 0L) {
      return -1L;
    }
    if (raw > 1_000_000_000_000L) {
      return raw;
    }
    if (raw > 1_000_000_000L) {
      return raw * 1_000L;
    }
    return now + raw * 1_000L;
  }
}
