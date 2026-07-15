package net.statustag.core.service;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.api.LabyNetProbeMemory;
import net.statustag.core.api.LabyNetRateGovernor;
import net.statustag.core.api.LabyNetStatusProbe;
import net.statustag.core.internal.StatusTagInternals;
import net.labymod.api.Laby;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.labymod.api.labyconnect.TokenStorage;
import net.labymod.api.labyconnect.protocol.model.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatusFetchScheduler {

  private static final long SESSION_BACKOFF_MS = 8_000L;
  private static final long IN_FLIGHT_TIMEOUT_MS = 30_000L;

  private final StatusResolver resolver;
  private final StatusDebugLog debugLog;
  private final LabyNetStatusProbe probe;
  private final LabyNetProbeMemory probeMemory;
  private final LabyNetRateGovernor rateGovernor;
  private final Supplier<StatusTagConfiguration> configuration;
  private final Supplier<StatusTagInternals> internals;

  private final Queue<UUID> priorityQueue = new ConcurrentLinkedQueue<>();
  private final Queue<UUID> normalQueue = new ConcurrentLinkedQueue<>();
  private final Set<UUID> queuedIds = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<UUID, Long> queuedAt = new ConcurrentHashMap<>();

  private volatile UUID inFlight;
  private volatile long inFlightStartedAt;
  private volatile long sessionBackoffUntil;
  private volatile @Nullable String cachedJwt;
  private volatile long cachedJwtExpiresAt;
  private int tickCounter;

  public StatusFetchScheduler(
      @NotNull StatusResolver resolver,
      @NotNull StatusDebugLog debugLog,
      @NotNull Supplier<StatusTagConfiguration> configuration,
      @NotNull Supplier<StatusTagInternals> internals
  ) {
    this.resolver = resolver;
    this.debugLog = debugLog;
    this.configuration = configuration;
    this.internals = internals;
    this.probeMemory = new LabyNetProbeMemory();
    this.rateGovernor = new LabyNetRateGovernor(internals.get());
    this.probe = new LabyNetStatusProbe(resolver, debugLog, this.probeMemory, () -> this.rateGovernor);
  }

  public void onSessionAvailable() {
    this.sessionBackoffUntil = 0L;
    this.refreshJwtCache();
    this.debugLog.schedulerState(
        "SESSION_AVAILABLE",
        this.priorityQueue.size(),
        this.normalQueue.size(),
        this.queuedIds.size(),
        this.inFlight,
        this.rateGovernor.cooldownRemainingMs(),
        this.rateGovernor.waitMs(),
        "jwtCached=" + (this.cachedJwt != null) + " intervalMs=" + this.rateGovernor.currentIntervalMs()
    );
  }

  public void clearSessionState() {
    this.debugLog.schedulerState(
        "CLEAR_SESSION",
        this.priorityQueue.size(),
        this.normalQueue.size(),
        this.queuedIds.size(),
        this.inFlight,
        this.rateGovernor.cooldownRemainingMs(),
        this.rateGovernor.waitMs(),
        "session cleared"
    );
    this.priorityQueue.clear();
    this.normalQueue.clear();
    this.queuedIds.clear();
    this.queuedAt.clear();
    this.inFlight = null;
    this.sessionBackoffUntil = 0L;
    this.cachedJwt = null;
    this.cachedJwtExpiresAt = 0L;

    this.probeMemory.clearAll();
  }

  public void invalidate(@NotNull UUID uniqueId) {
    this.queuedIds.remove(uniqueId);
    this.queuedAt.remove(uniqueId);
    this.probeMemory.clear(uniqueId);
    this.debugLog.skip(uniqueId, "INVALIDATE_QUEUE", StatusDebugLog.config(
        "priorityQueue", this.priorityQueue.size(),
        "normalQueue", this.normalQueue.size()
    ));
  }

  public void enqueue(@NotNull UUID uniqueId, boolean priority) {
    if (!this.isApiEnabled()) {
      this.debugLog.skip(uniqueId, "API_DISABLED");
      return;
    }

    if (!this.resolver.shouldFetchFromApi(uniqueId)) {
      return;
    }

    if (uniqueId.equals(this.inFlight)) {
      this.debugLog.skip(uniqueId, "ALREADY_IN_FLIGHT");
      return;
    }

    if (this.queuedIds.contains(uniqueId)) {

      return;
    }

    if (!this.queuedIds.add(uniqueId)) {
      this.debugLog.skip(uniqueId, "QUEUE_ADD_FAILED");
      return;
    }

    long now = System.currentTimeMillis();
    this.queuedAt.put(uniqueId, now);
    if (priority) {
      this.priorityQueue.offer(uniqueId);
    } else {
      this.normalQueue.offer(uniqueId);
    }

    this.debugLog.enqueue(
        uniqueId,
        priority ? "PRIORITY" : "NORMAL",
        priority,
        this.queuedIds.size(),
        now
    );
  }

  public void enqueueAll(@NotNull Iterable<UUID> uniqueIds) {
    int count = 0;
    for (UUID uniqueId : uniqueIds) {
      this.enqueue(uniqueId, false);
      count++;
    }
    this.debugLog.log("ENQUEUE_BULK", StatusDebugLog.config("count", count));
  }

  public void tick() {
    this.resolver.saveStoreIfNeeded();
    this.tickCounter++;

    if (!this.isApiEnabled() || !Laby.isInitialized()) {
      return;
    }

    long now = System.currentTimeMillis();
    boolean hasWork = !this.queuedIds.isEmpty() || this.inFlight != null;

    if (this.inFlight != null) {

      if (this.inFlightStartedAt > 0L && now - this.inFlightStartedAt > IN_FLIGHT_TIMEOUT_MS) {
        this.debugLog.skip(this.inFlight, "IN_FLIGHT_TIMEOUT");
        this.inFlight = null;
        this.inFlightStartedAt = 0L;
      } else {
        if (hasWork && this.tickCounter % 40 == 0) {
          this.debugLog.schedulerState(
              "WAIT_IN_FLIGHT",
              this.priorityQueue.size(),
              this.normalQueue.size(),
              this.queuedIds.size(),
              this.inFlight,
              this.rateGovernor.cooldownRemainingMs(),
              this.rateGovernor.waitMs(),
              "inFlightForMs=" + (now - this.inFlightStartedAt)
          );
        }
        return;
      }
    }

    if (now < this.sessionBackoffUntil) {
      if (hasWork && this.tickCounter % 40 == 0) {
        this.debugLog.schedulerState(
            "WAIT_SESSION",
            this.priorityQueue.size(),
            this.normalQueue.size(),
            this.queuedIds.size(),
            null,
            Math.max(0L, this.sessionBackoffUntil - now),
            this.rateGovernor.waitMs(),
            null
        );
      }
      return;
    }

    long waitMs = this.rateGovernor.waitMs();
    if (waitMs > 0L) {
      if (hasWork && this.tickCounter % 40 == 0) {
        this.debugLog.schedulerState(
            this.rateGovernor.cooldownRemainingMs() > 0L ? "WAIT_BACKOFF" : "WAIT_INTERVAL",
            this.priorityQueue.size(),
            this.normalQueue.size(),
            this.queuedIds.size(),
            null,
            this.rateGovernor.cooldownRemainingMs(),
            waitMs,
            "intervalMs=" + this.rateGovernor.currentIntervalMs()
        );
      }
      return;
    }

    UUID next = this.pollNext();
    if (next == null) {
      return;
    }

    if (!this.resolver.shouldFetchFromApi(next)) {
      this.queuedAt.remove(next);
      this.debugLog.skip(next, "SKIP_BEFORE_PROBE");
      return;
    }

    this.inFlight = next;
    this.inFlightStartedAt = now;
    long queueWaitMs = now - this.queuedAt.getOrDefault(next, now);
    this.rateGovernor.recordDispatch();

    this.debugLog.schedulerState(
        "PROBE_DISPATCH",
        this.priorityQueue.size(),
        this.normalQueue.size(),
        this.queuedIds.size(),
        next,
        this.rateGovernor.cooldownRemainingMs(),
        0L,
        "queueWaitMs=" + queueWaitMs + " planIntervalMs=" + this.rateGovernor.currentIntervalMs()
    );

    this.executeProbe(next, queueWaitMs);
  }

  private void executeProbe(@NotNull UUID uniqueId, long queueWaitMs) {
    String jwt = this.resolveJwt();
    if (jwt == null) {
      this.debugLog.skip(uniqueId, "LABYCONNECT_SESSION_NULL");
      this.requeue(uniqueId, true);
      this.sessionBackoffUntil = System.currentTimeMillis() + SESSION_BACKOFF_MS;
      this.finishRequest(uniqueId);
      return;
    }

    String probeId = UUID.randomUUID().toString().substring(0, 8);
    long probeStartedAt = System.currentTimeMillis();
    this.debugLog.probeBegin(uniqueId, probeId, queueWaitMs);

    this.probe.probe(uniqueId, jwt, probeId, queueWaitMs, rateLimited -> {
      long totalMs = System.currentTimeMillis() - probeStartedAt;
      this.debugLog.probeEnd(uniqueId, probeId, totalMs, rateLimited);

      if (rateLimited) {
        this.requeue(uniqueId, true);
      }

      this.finishRequest(uniqueId);
    });
  }

  private void finishRequest(@NotNull UUID uniqueId) {
    long finishedAt = System.currentTimeMillis();
    long inFlightMs = this.inFlightStartedAt > 0L ? finishedAt - this.inFlightStartedAt : 0L;

    this.debugLog.log("PROBE_FINISH_REQUEST", StatusDebugLog.config(
        "uuid", uniqueId,
        "inFlightMs", inFlightMs,
        "finishedAtMs", finishedAt,
        "nextWaitMs", this.rateGovernor.waitMs()
    ));

    if (uniqueId.equals(this.inFlight)) {
      this.inFlight = null;
      this.inFlightStartedAt = 0L;
    }
    if (!this.queuedIds.contains(uniqueId)) {
      this.queuedAt.remove(uniqueId);
    }
  }

  private void requeue(@NotNull UUID uniqueId, boolean priority) {
    if (this.queuedIds.contains(uniqueId)) {
      return;
    }

    this.queuedIds.add(uniqueId);
    this.queuedAt.put(uniqueId, System.currentTimeMillis());
    if (priority) {
      this.priorityQueue.offer(uniqueId);
    } else {
      this.normalQueue.offer(uniqueId);
    }
  }

  private void refreshJwtCache() {
    LabyConnectSession session = this.session();
    if (session == null) {
      return;
    }

    User self = session.self();
    if (self == null) {
      return;
    }

    TokenStorage.Token token = session.tokenStorage().getToken(
        TokenStorage.Purpose.JWT,
        self.getUniqueId()
    );

    if (token == null || !token.isValid()) {
      return;
    }

    this.cachedJwt = token.getToken();
    this.cachedJwtExpiresAt = token.getExpiresAt();
  }

  private @Nullable String resolveJwt() {
    LabyConnectSession session = this.session();
    if (session != null) {
      User self = session.self();
      if (self != null) {
        TokenStorage.Token token = session.tokenStorage().getToken(
            TokenStorage.Purpose.JWT,
            self.getUniqueId()
        );

        if (token != null && token.isValid()) {
          this.cachedJwt = token.getToken();
          this.cachedJwtExpiresAt = token.getExpiresAt();
          return this.cachedJwt;
        }
      }
    }

    if (this.cachedJwt != null && System.currentTimeMillis() < this.cachedJwtExpiresAt) {
      return this.cachedJwt;
    }

    return null;
  }

  private @Nullable UUID pollNext() {
    UUID uniqueId = this.priorityQueue.poll();
    if (uniqueId == null) {
      uniqueId = this.normalQueue.poll();
    }

    if (uniqueId != null) {
      this.queuedIds.remove(uniqueId);
      this.debugLog.log("QUEUE_POLL", StatusDebugLog.config(
          "uuid", uniqueId,
          "polledAtMs", System.currentTimeMillis(),
          "queuedAtMs", this.queuedAt.getOrDefault(uniqueId, 0L)
      ));
    }

    return uniqueId;
  }

  private boolean isApiEnabled() {
    StatusTagConfiguration config = this.configuration.get();
    return config != null && config.isApiEnabled();
  }

  private @Nullable LabyConnectSession session() {
    if (!Laby.isInitialized()) {
      return null;
    }

    LabyConnectSession session = Laby.labyAPI().labyConnect().getSession();
    if (session == null || !session.isAuthenticated()) {
      return null;
    }

    return session;
  }
}
