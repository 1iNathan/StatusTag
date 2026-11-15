package net.statustag.core.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

public final class DebugLog {

  private static final long MAX_BYTES = 4_000_000L;

  private final Path file;
  private final Consumer<Runnable> executor;
  private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean flushQueued = new AtomicBoolean();
  private volatile boolean enabled;

  public DebugLog(@NotNull Path file, @NotNull Consumer<Runnable> executor) {
    this.file = file;
    this.executor = executor;
  }

  public boolean enabled() {
    return this.enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      this.pending.clear();
    }
  }

  public void log(@NotNull String message) {
    if (!this.enabled) {
      return;
    }

    this.pending.add(Instant.now() + " [" + Thread.currentThread().getName() + "] " + message);
    if (this.flushQueued.compareAndSet(false, true)) {
      this.executor.accept(this::flush);
    }
  }

  private void flush() {
    this.flushQueued.set(false);
    synchronized (this) {
      if (this.pending.isEmpty()) {
        return;
      }

      try {
        Files.createDirectories(this.file.getParent());
        if (Files.exists(this.file) && Files.size(this.file) > MAX_BYTES) {
          Files.move(this.file, this.file.resolveSibling(this.file.getFileName() + ".old"),
              StandardCopyOption.REPLACE_EXISTING);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
          String line;
          while ((line = this.pending.poll()) != null) {
            writer.write(line);
            writer.newLine();
          }
        }
      } catch (IOException exception) {
        this.pending.clear();
      }
    }
  }
}
