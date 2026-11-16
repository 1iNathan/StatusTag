package net.statustag.core.sync;

import java.util.ArrayDeque;
import java.util.Deque;

final class SendBudget {

  private final int limit;
  private final long windowMs;
  private final long minGapMs;
  private final Deque<Long> sentAt = new ArrayDeque<>();
  private long lastAt = Long.MIN_VALUE / 2L;

  SendBudget(int limit, long windowMs, long minGapMs) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.minGapMs = minGapMs;
  }

  synchronized boolean allows(long now) {
    while (!this.sentAt.isEmpty() && now - this.sentAt.peekFirst() >= this.windowMs) {
      this.sentAt.pollFirst();
    }
    return this.sentAt.size() < this.limit && now - this.lastAt >= this.minGapMs;
  }

  synchronized void record(long now) {
    this.sentAt.addLast(now);
    this.lastAt = now;
  }
}
