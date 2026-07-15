package net.statustag.core.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.statustag.core.api.LabyNetRateGovernor;
import net.statustag.core.internal.StatusTagInternals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusResolverStressTest {

  @TempDir
  Path tempDir;

  private StatusResolver newResolver() {
    PersistentStatusStore store = new PersistentStatusStore(this.tempDir.resolve("store.json"));
    StatusDebugLog debugLog = new StatusDebugLog(() -> this.tempDir.resolve("debug.log"));
    debugLog.setEnabledSupplier(() -> false);
    return new StatusResolver(store, debugLog);
  }

  @Test
  void survivesHeavyConcurrentCommitAndRead() throws InterruptedException {
    StatusResolver resolver = this.newResolver();

    int players = 500;
    UUID[] pool = new UUID[players];
    for (int i = 0; i < players; i++) {
      pool[i] = UUID.randomUUID();
    }
    StatusKind[] kinds = StatusKind.values();

    int threads = 16;
    int opsPerThread = 100_000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicLong reads = new AtomicLong();
    CountDownLatch done = new CountDownLatch(threads);

    long start = System.nanoTime();
    for (int t = 0; t < threads; t++) {
      final int seed = t;
      executor.submit(() -> {
        Random rnd = new Random(seed);
        try {
          for (int i = 0; i < opsPerThread; i++) {
            UUID u = pool[rnd.nextInt(players)];
            int op = rnd.nextInt(10);
            if (op < 4) {

              resolver.commit(u, kinds[rnd.nextInt(kinds.length)], StatusSource.MESH,
                  false, 0, System.currentTimeMillis());
            } else if (op == 4) {
              resolver.markLabyModUser(u);
            } else {

              resolver.peekKind(u);
              reads.incrementAndGet();
            }
          }
        } catch (Throwable e) {
          error.set(e);
        } finally {
          done.countDown();
        }
      });
    }

    boolean finished = done.await(60, TimeUnit.SECONDS);
    executor.shutdownNow();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    long totalOps = (long) threads * opsPerThread;

    assertTrue(finished, "stress run did not finish within 60s — possible deadlock");
    assertNull(error.get(), () -> "stress run threw: " + error.get());

    assertTrue(resolver.store().size() <= players,
        "store grew beyond the player pool: " + resolver.store().size());

    double opsPerSec = totalOps * 1000.0 / Math.max(1L, elapsedMs);
    System.out.printf(
        "[STRESS] %,d ops (%,d reads) across %d threads in %d ms = %,.0f ops/sec; store=%d entries%n",
        totalOps, reads.get(), threads, elapsedMs, opsPerSec, resolver.store().size());
  }

  @Test
  void rateGovernorIsThreadSafeUnderContention() throws InterruptedException {
    LabyNetRateGovernor governor = new LabyNetRateGovernor(new StatusTagInternals());
    int threads = 12;
    int opsPerThread = 200_000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      final int seed = t;
      executor.submit(() -> {
        Random rnd = new Random(seed);
        try {
          for (int i = 0; i < opsPerThread; i++) {
            switch (rnd.nextInt(5)) {
              case 0 -> governor.recordDispatch();
              case 1 -> governor.recordSuccess();
              case 2 -> governor.recordRateLimit();
              case 3 -> governor.recordNetworkError();
              default -> governor.waitMs();
            }
          }
        } catch (Throwable e) {
          error.set(e);
        } finally {
          done.countDown();
        }
      });
    }

    boolean finished = done.await(60, TimeUnit.SECONDS);
    executor.shutdownNow();
    assertTrue(finished, "governor stress did not finish — possible deadlock");
    assertNull(error.get(), () -> "governor stress threw: " + error.get());
  }
}
