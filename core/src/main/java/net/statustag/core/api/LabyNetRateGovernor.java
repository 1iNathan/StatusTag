package net.statustag.core.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import net.statustag.core.internal.StatusTagInternals;
import org.jetbrains.annotations.NotNull;

public class LabyNetRateGovernor {

  private final StatusTagInternals internals;
  private final Deque<Long> requestTimestamps = new ArrayDeque<>();
  private LongSupplier clock = System::currentTimeMillis;

  private long nextAllowedAt;
  private long cooldownUntil;
  private int consecutiveSuccesses;
  private int consecutiveRateLimits;
  private int consecutiveNetworkErrors;
  private int currentIntervalMs;

  public LabyNetRateGovernor(@NotNull StatusTagInternals internals) {
    this.internals = internals;
    this.currentIntervalMs = internals.apiRequestIntervalMs();
  }

  synchronized void clock(@NotNull LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized long waitMs() {
    long now = this.clock.getAsLong();
    long wait = Math.max(0L, this.nextAllowedAt - now);
    wait = Math.max(wait, Math.max(0L, this.cooldownUntil - now));
    this.pruneOld(now);
    if (this.requestTimestamps.size() >= this.internals.apiMaxRequestsPerMinute()) {
      Long oldest = this.requestTimestamps.peekFirst();
      if (oldest != null) {
        wait = Math.max(wait, 60_000L - (now - oldest));
      }
    }
    return wait;
  }

  public boolean canDispatch() {
    return this.waitMs() <= 0L;
  }

  public synchronized void recordDispatch() {
    long now = this.clock.getAsLong();
    this.pruneOld(now);
    this.requestTimestamps.addLast(now);
    this.nextAllowedAt = now + this.currentIntervalMs;
  }

  public synchronized void recordSuccess() {
    this.consecutiveRateLimits = 0;
    this.consecutiveNetworkErrors = 0;
    this.consecutiveSuccesses++;
    if (!this.internals.apiAdaptiveEnabled()) {
      return;
    }

    if (this.consecutiveSuccesses >= 8 && this.currentIntervalMs > this.internals.apiRequestIntervalMs()) {
      this.currentIntervalMs = Math.max(
          this.internals.apiRequestIntervalMs(),
          this.currentIntervalMs - 250
      );
      this.consecutiveSuccesses = 0;
    }
  }

  public synchronized void recordRateLimit() {
    this.consecutiveSuccesses = 0;
    this.consecutiveRateLimits++;
    long backoff = switch (Math.min(this.consecutiveRateLimits, 4)) {
      case 1 -> 5_000L;
      case 2 -> 12_000L;
      case 3 -> 25_000L;
      default -> 45_000L;
    };
    this.cooldownUntil = this.clock.getAsLong() + backoff;
    if (this.internals.apiAdaptiveEnabled()) {
      this.currentIntervalMs = Math.min(
          this.internals.apiMaxRequestIntervalMs(),
          this.currentIntervalMs + 750
      );
    }
  }

  public synchronized void recordNetworkError() {
    this.consecutiveSuccesses = 0;
    this.consecutiveNetworkErrors++;
    long backoff = switch (Math.min(this.consecutiveNetworkErrors, 4)) {
      case 1 -> 5_000L;
      case 2 -> 15_000L;
      case 3 -> 30_000L;
      default -> 60_000L;
    };
    this.cooldownUntil = Math.max(this.cooldownUntil, this.clock.getAsLong() + backoff);
  }

  public synchronized void reset() {
    this.requestTimestamps.clear();
    this.nextAllowedAt = 0L;
    this.cooldownUntil = 0L;
    this.consecutiveSuccesses = 0;
    this.consecutiveRateLimits = 0;
    this.consecutiveNetworkErrors = 0;
    this.currentIntervalMs = this.internals.apiRequestIntervalMs();
  }

  public synchronized int currentIntervalMs() {
    return this.currentIntervalMs;
  }

  public synchronized long cooldownRemainingMs() {
    return Math.max(0L, this.cooldownUntil - this.clock.getAsLong());
  }

  private void pruneOld(long now) {
    while (!this.requestTimestamps.isEmpty() && now - this.requestTimestamps.peekFirst() > 60_000L) {
      this.requestTimestamps.pollFirst();
    }
  }
}
