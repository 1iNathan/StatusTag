package net.statustag.core.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.statustag.core.util.AddonDataPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatusDebugLog {

  private static final int MAX_FILE_BYTES = 10_000_000;
  private static final Object WRITE_LOCK = new Object();

  private final Supplier<Path> logFileSupplier;
  private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
  private final AtomicLong sequence = new AtomicLong();
  private BooleanSupplier enabledSupplier = () -> true;
  private @Nullable java.util.function.Consumer<String> errorSink;

  public StatusDebugLog() {
    this(AddonDataPaths::debugLogFile);
  }

  public StatusDebugLog(@NotNull Supplier<Path> logFileSupplier) {
    this.logFileSupplier = logFileSupplier;
  }

  public void setEnabledSupplier(@NotNull BooleanSupplier enabledSupplier) {
    this.enabledSupplier = enabledSupplier;
  }

  public void bindErrorSink(@NotNull java.util.function.Consumer<String> errorSink) {
    this.errorSink = errorSink;
  }

  public @NotNull Path logFile() {
    return this.logFileSupplier.get().toAbsolutePath().normalize();
  }

  public void bootstrap() {
    if (!this.enabledSupplier.getAsBoolean()) {
      return;
    }

    Path path = this.logFile();
    this.writeRaw(path, "[" + Instant.now() + "] BOOTSTRAP session=" + this.sessionId
        + " path=" + path + " ready=true");
  }

  public void sessionStart(@NotNull String addonVersion, @NotNull Map<String, Object> config) {
    this.log("SESSION_START", config(
        "addonVersion", addonVersion,
        "logPath", this.logFile(),
        "dataDir", AddonDataPaths.dataDirectory().toAbsolutePath(),
        "sessionId", this.sessionId
    ));
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      this.log("SESSION_CONFIG", config("key", entry.getKey(), "value", entry.getValue()));
    }
  }

  public void sessionEnd(@NotNull String reason) {
    this.log("SESSION_END", config("reason", reason));
  }

  public void log(@NotNull String event, @NotNull Map<String, Object> fields) {
    if (!this.enabledSupplier.getAsBoolean()) {
      return;
    }

    long nowMs = System.currentTimeMillis();
    long seq = this.sequence.incrementAndGet();

    StringBuilder line = new StringBuilder(512);
    line.append('[').append(Instant.now()).append(']');
    line.append(" seq=").append(seq);
    line.append(" epochMs=").append(nowMs);
    line.append(" session=").append(this.sessionId);
    line.append(" thread=").append(Thread.currentThread().getName());
    line.append(" EVENT=").append(event);

    for (Map.Entry<String, Object> field : fields.entrySet()) {
      line.append(' ').append(field.getKey()).append('=').append(this.escape(String.valueOf(field.getValue())));
    }

    this.writeLine(line.toString());
  }

  public void enqueue(
      @NotNull UUID uuid,
      @NotNull String reason,
      boolean priority,
      int queueSize,
      long enqueuedAtMs
  ) {
    this.log("ENQUEUE", config(
        "uuid", uuid,
        "reason", reason,
        "priority", priority,
        "queueSize", queueSize,
        "enqueuedAtMs", enqueuedAtMs
    ));
  }

  public void skip(@NotNull UUID uuid, @NotNull String reason) {
    this.skip(uuid, reason, config());
  }

  public void skip(@NotNull UUID uuid, @NotNull String reason, @NotNull Map<String, Object> extra) {
    Map<String, Object> fields = config("uuid", uuid, "reason", reason);
    fields.putAll(extra);
    this.log("SKIP", fields);
  }

  public void fetchDenied(@NotNull UUID uuid, @NotNull String reason) {
    this.log("FETCH_DENIED", config("uuid", uuid, "reason", reason));
  }

  public void schedulerState(
      @NotNull String phase,
      int priorityQueue,
      int normalQueue,
      int queuedIds,
      @Nullable UUID inFlight,
      long backoffRemainingMs,
      long intervalRemainingMs,
      @Nullable String note
  ) {
    Map<String, Object> fields = config(
        "phase", phase,
        "priorityQueue", priorityQueue,
        "normalQueue", normalQueue,
        "queuedIds", queuedIds,
        "inFlight", inFlight == null ? "none" : inFlight,
        "backoffRemainingMs", backoffRemainingMs,
        "intervalRemainingMs", intervalRemainingMs
    );
    if (note != null) {
      fields.put("note", note);
    }
    this.log("SCHEDULER", fields);
  }

  public void probeBegin(@NotNull UUID uuid, @NotNull String probeId, long queueWaitMs) {
    this.log("PROBE_BEGIN", config(
        "uuid", uuid,
        "probeId", probeId,
        "queueWaitMs", queueWaitMs,
        "startedAtMs", System.currentTimeMillis()
    ));
  }

  public void probeEnd(
      @NotNull UUID uuid,
      @NotNull String probeId,
      long totalMs,
      boolean rateLimited
  ) {
    this.log("PROBE_END", config(
        "uuid", uuid,
        "probeId", probeId,
        "totalMs", totalMs,
        "rateLimited", rateLimited,
        "finishedAtMs", System.currentTimeMillis()
    ));
  }

  public void probeStep(
      @NotNull UUID uuid,
      @NotNull String probeId,
      @NotNull String step,
      @NotNull String action,
      @NotNull Map<String, Object> extra
  ) {
    Map<String, Object> fields = config(
        "uuid", uuid,
        "probeId", probeId,
        "step", step,
        "action", action
    );
    fields.putAll(extra);
    this.log("PROBE_STEP", fields);
  }

  public void httpStart(
      @NotNull UUID uuid,
      @NotNull String probeId,
      @NotNull String step,
      @NotNull String url,
      long queueWaitMs
  ) {
    this.log("HTTP_START", config(
        "uuid", uuid,
        "probeId", probeId,
        "step", step,
        "url", url,
        "queueWaitMs", queueWaitMs,
        "requestStartedAtMs", System.currentTimeMillis()
    ));
  }

  public void httpEnd(
      @NotNull UUID uuid,
      @NotNull String probeId,
      @NotNull String step,
      int code,
      @Nullable String body,
      long elapsedMs,
      boolean rateLimitBackoff,
      @Nullable String error
  ) {
    this.log("HTTP_END", config(
        "uuid", uuid,
        "probeId", probeId,
        "step", step,
        "code", code,
        "body", body == null ? "null" : body,
        "elapsedMs", elapsedMs,
        "rateLimitBackoff", rateLimitBackoff,
        "error", error == null ? "none" : error,
        "responseFinishedAtMs", System.currentTimeMillis()
    ));
  }

  public void resolve(
      @NotNull UUID uuid,
      @NotNull String path,
      @NotNull StatusKind kind,
      @Nullable StatusKind previous
  ) {
    this.log("RESOLVE", config(
        "uuid", uuid,
        "path", path,
        "kind", kind.name(),
        "previous", previous == null ? "none" : previous.name()
    ));
  }

  public void meshOut(
      @NotNull String type,
      @NotNull StatusKind kind,
      @Nullable UUID target,
      @NotNull String payloadSummary
  ) {
    this.log("MESH_OUT", config(
        "type", type,
        "kind", kind.name(),
        "target", target == null ? "broadcast" : target,
        "payload", payloadSummary,
        "sentAtMs", System.currentTimeMillis()
    ));
  }

  public void meshIn(
      @NotNull UUID sender,
      @NotNull StatusKind kind,
      long timestamp,
      boolean accepted,
      @NotNull String reason,
      @Nullable String payloadSummary
  ) {
    this.log("MESH_IN", config(
        "sender", sender,
        "kind", kind.name(),
        "ts", timestamp,
        "accepted", accepted,
        "reason", reason,
        "payload", payloadSummary == null ? "none" : payloadSummary,
        "receivedAtMs", System.currentTimeMillis()
    ));
  }


  public void persist(
      @NotNull UUID uuid,
      @NotNull StatusKind kind,
      @NotNull StatusSource source,
      @Nullable StatusKind previous,
      int httpCode
  ) {
    this.log("PERSIST", config(
        "uuid", uuid,
        "kind", kind.name(),
        "source", source.name(),
        "previous", previous == null ? "none" : previous.name(),
        "httpCode", httpCode
    ));
  }

  public void commitRejected(
      @NotNull UUID uuid,
      @NotNull StatusSource source,
      @NotNull String reason,
      @NotNull Map<String, Object> extra
  ) {
    Map<String, Object> fields = config(
        "uuid", uuid,
        "source", source.name(),
        "reason", reason
    );
    fields.putAll(extra);
    this.log("COMMIT_REJECTED", fields);
  }

  public void listener(@NotNull String listener, @NotNull String event, @NotNull Map<String, Object> fields) {
    Map<String, Object> payload = config("listener", listener, "event", event);
    payload.putAll(fields);
    this.log("LISTENER", payload);
  }

  public void tabScan(int playerCount, int enqueued, long scanAtMs) {
    this.log("TAB_SCAN", config(
        "playerCount", playerCount,
        "enqueued", enqueued,
        "scanAtMs", scanAtMs
    ));
  }

  private void writeLine(@NotNull String line) {
    Path primary = this.logFileSupplier.get();
    if (this.writeRaw(primary, line)) {
      return;
    }

    Path fallback = primary.getParent().resolve("debug-fallback.log");
    if (this.writeRaw(fallback, line)) {
      this.reportError("Primary debug log failed; wrote to " + fallback.toAbsolutePath());
      return;
    }

    this.reportError("Failed to write debug log to " + primary.toAbsolutePath() + " and " + fallback.toAbsolutePath());
  }

  private boolean writeRaw(@NotNull Path path, @NotNull String line) {
    synchronized (WRITE_LOCK) {
      try {
        Files.createDirectories(path.getParent());
        this.rotateIfNeeded(path);
        Files.writeString(
            path,
            line + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        );
        return true;
      } catch (IOException exception) {
        this.reportError("Write failed for " + path.toAbsolutePath() + ": " + exception.getMessage());
        return false;
      }
    }
  }

  private void rotateIfNeeded(@NotNull Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }

    long size = Files.size(path);
    if (size <= 0L || size <= MAX_FILE_BYTES) {
      return;
    }

    Path rotated = path.resolveSibling("debug.log.old");
    Files.deleteIfExists(rotated);
    Files.move(path, rotated);
  }

  private void reportError(@NotNull String message) {
    if (this.errorSink != null) {
      this.errorSink.accept("[StatusTag Debug] " + message);
    }
  }

  public static @NotNull Map<String, Object> config(Object... keyValues) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int index = 0; index + 1 < keyValues.length; index += 2) {
      fields.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
    }
    return fields;
  }

  private @NotNull String escape(@NotNull String value) {
    if (value.indexOf(' ') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return "\"" + value.replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
