package net.statustag.core.api;

import java.util.UUID;
import java.util.function.Supplier;
import net.statustag.core.service.StatusDebugLog;
import net.statustag.core.service.StatusKind;
import net.statustag.core.service.StatusResolver;
import net.statustag.core.service.StatusSource;
import net.statustag.core.util.StatusUiRefresh;
import net.labymod.api.util.io.web.request.Request;
import net.labymod.api.util.io.web.request.Response;
import org.jetbrains.annotations.NotNull;

public class LabyNetStatusProbe {

  @FunctionalInterface
  public interface ProbeFinishedCallback {
    void finished(boolean rateLimited);
  }

  private final StatusResolver resolver;
  private final StatusDebugLog debugLog;
  private final LabyNetProbeMemory memory;
  private final Supplier<LabyNetRateGovernor> rateGovernor;

  public LabyNetStatusProbe(
      @NotNull StatusResolver resolver,
      @NotNull StatusDebugLog debugLog,
      @NotNull LabyNetProbeMemory memory,
      @NotNull Supplier<LabyNetRateGovernor> rateGovernor
  ) {
    this.resolver = resolver;
    this.debugLog = debugLog;
    this.memory = memory;
    this.rateGovernor = rateGovernor;
  }

  public void probe(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      long queueWaitMs,
      @NotNull ProbeFinishedCallback onFinished
  ) {
    boolean onTab = this.resolver.isPlayerOnTab(uniqueId);

    net.statustag.core.service.StatusEntry known = this.resolver.peekEntry(uniqueId);
    if (known != null && known.httpCode() == 403) {
      this.memory.recordOnlineBlocked(uniqueId);
    }

    LabyNetProbePlan plan = this.memory.planFor(uniqueId, onTab);

    this.debugLog.probeStep(uniqueId, probeId, "plan", "SELECTED", StatusDebugLog.config(
        "plan", plan.name(),
        "onTab", onTab,
        "labyModUser", this.resolver.isLabyModUser(uniqueId)
    ));

    if (plan == LabyNetProbePlan.SKIP) {
      this.debugLog.probeStep(uniqueId, probeId, "plan", "SKIP_KNOWN_NO_LABYMOD", StatusDebugLog.config());
      onFinished.finished(false);
      return;
    }

    switch (plan) {
      case ONLINE_ONLY -> this.fetchOnlineStatus(uniqueId, jwt, probeId, queueWaitMs, onFinished, false);
      case STATS_ONLY -> this.fetchGameStats(uniqueId, jwt, probeId, onFinished, false, false, true);
      case STATS_WITH_PROFILE -> this.fetchGameStats(uniqueId, jwt, probeId, onFinished, false, true, true);
      case ONLINE_THEN_PROFILE -> this.fetchOnlineStatus(uniqueId, jwt, probeId, queueWaitMs, onFinished, true);
      case ONLINE_FIRST -> this.fetchOnlineStatus(uniqueId, jwt, probeId, queueWaitMs, onFinished, false);
      default -> onFinished.finished(false);
    }
  }

  private void fetchOnlineStatus(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      long queueWaitMs,
      @NotNull ProbeFinishedCallback onFinished,
      boolean profileOnTab
  ) {
    String url = LabyNetApiUrls.onlineStatus(uniqueId);
    long startedAt = System.currentTimeMillis();
    this.debugLog.httpStart(uniqueId, probeId, "online-status", url, queueWaitMs);

    Request.ofGson(OnlineStatusPayload.class)
        .url(url)
        .authorization("Bearer", jwt)
        .connectTimeout(6_000)
        .readTimeout(6_000)
        .async(true)
        .execute(response -> this.handleOnlineStatus(
            uniqueId,
            jwt,
            probeId,
            response,
            startedAt,
            onFinished,
            profileOnTab
        ));
  }

  private void handleOnlineStatus(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      @NotNull Response<OnlineStatusPayload> response,
      long startedAt,
      @NotNull ProbeFinishedCallback onFinished,
      boolean profileOnTab
  ) {
    int statusCode = LabyNetHttp.statusCode(response);
    long elapsedMs = System.currentTimeMillis() - startedAt;

    this.debugLog.httpEnd(
        uniqueId,
        probeId,
        "online-status",
        statusCode,
        this.summarizeOnline(response),
        elapsedMs,
        LabyNetHttp.isRateLimited(response),
        LabyNetHttp.errorMessage(response)
    );

    if (LabyNetHttp.isRateLimited(response)) {
      this.rateGovernor.get().recordRateLimit();
      onFinished.finished(true);
      return;
    }

    if (LabyNetHttp.isForbidden(response)) {
      this.memory.recordOnlineBlocked(uniqueId);
      this.debugLog.probeStep(uniqueId, probeId, "online-status", "ACCESS_DENIED", StatusDebugLog.config(
          "next", profileOnTab ? "game-stats+profile" : "game-stats"
      ));
      this.fetchGameStats(uniqueId, jwt, probeId, onFinished, false, profileOnTab, true);
      return;
    }

    if (response.hasException()) {

      this.rateGovernor.get().recordNetworkError();
      this.debugLog.probeStep(uniqueId, probeId, "online-status", "NETWORK_ERROR", StatusDebugLog.config(
          "done", true
      ));
      onFinished.finished(false);
      return;
    }

    if (statusCode == 200 && response.isPresent() && response.get() != null) {
      OnlineStatusPayload payload = response.get();
      StatusKind kind = LabyNetStatusInterpreter.fromOnlineStatus(
          uniqueId,
          this.resolver,
          payload.status,
          payload.online
      );

      if (kind != null) {
        this.memory.recordOnlineAllowed(uniqueId);
        this.memory.recordLabyModAccount(uniqueId);
        this.resolver.markLabyModUser(uniqueId);
        this.debugLog.probeStep(uniqueId, probeId, "online-status", "SUCCESS", StatusDebugLog.config(
            "kind", kind.name(),
            "online", payload.online,
            "status", payload.status
        ));
        this.commit(uniqueId, kind, statusCode);
        this.rateGovernor.get().recordSuccess();
        onFinished.finished(false);
        return;
      }
    }

    if (statusCode == 204) {
      this.debugLog.probeStep(uniqueId, probeId, "online-status", "OFFLINE_204", StatusDebugLog.config(
          "next", "game-stats"
      ));
      this.fetchGameStats(uniqueId, jwt, probeId, onFinished, false, profileOnTab, false);
      return;
    }

    this.debugLog.probeStep(uniqueId, probeId, "online-status", "FALLBACK", StatusDebugLog.config(
        "code", statusCode,
        "next", "game-stats"
    ));
    this.fetchGameStats(uniqueId, jwt, probeId, onFinished, statusCode >= 500, profileOnTab, false);
  }

  private void fetchGameStats(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      @NotNull ProbeFinishedCallback onFinished,
      boolean networkFallback,
      boolean fetchProfileOnTab,
      boolean onlineBlocked
  ) {
    String url = LabyNetApiUrls.gameStats(uniqueId);
    long startedAt = System.currentTimeMillis();
    this.debugLog.httpStart(uniqueId, probeId, "game-stats", url, 0L);

    Request.ofGson(GameStatsResponse.class)
        .url(url)
        .authorization("Bearer", jwt)
        .connectTimeout(6_000)
        .readTimeout(6_000)
        .async(true)
        .execute(response -> this.handleGameStats(
            uniqueId,
            jwt,
            probeId,
            response,
            startedAt,
            onFinished,
            networkFallback,
            fetchProfileOnTab,
            onlineBlocked
        ));
  }

  private void handleGameStats(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      @NotNull Response<GameStatsResponse> response,
      long startedAt,
      @NotNull ProbeFinishedCallback onFinished,
      boolean networkFallback,
      boolean fetchProfileOnTab,
      boolean onlineBlocked
  ) {
    int statusCode = LabyNetHttp.statusCode(response);
    long elapsedMs = System.currentTimeMillis() - startedAt;
    String body = response.isPresent() && response.get() != null
        ? "lastOnline=" + response.get().getLastOnline()
        : "null";

    this.debugLog.httpEnd(
        uniqueId,
        probeId,
        "game-stats",
        statusCode,
        body,
        elapsedMs,
        LabyNetHttp.isRateLimited(response),
        LabyNetHttp.errorMessage(response)
    );

    if (LabyNetHttp.isRateLimited(response)) {
      this.rateGovernor.get().recordRateLimit();
      onFinished.finished(true);
      return;
    }

    if (response.hasException() && !LabyNetHttp.isForbidden(response)) {
      this.rateGovernor.get().recordNetworkError();
      if (networkFallback) {
        this.debugLog.probeStep(uniqueId, probeId, "game-stats", "NETWORK_ERROR", StatusDebugLog.config("done", true));
      }
      onFinished.finished(false);
      return;
    }

    if (statusCode == 204 || statusCode == 404) {
      this.memory.recordNoLabyModAccount(uniqueId);
      this.debugLog.probeStep(uniqueId, probeId, "game-stats", "NO_LABYMOD", StatusDebugLog.config("code", statusCode));
      this.commit(uniqueId, StatusKind.NO_LABYMOD, statusCode);
      this.rateGovernor.get().recordSuccess();
      onFinished.finished(false);
      return;
    }

    if (statusCode == 403 || LabyNetHttp.isForbidden(response)) {
      this.memory.recordOnlineBlocked(uniqueId);
      this.debugLog.probeStep(uniqueId, probeId, "game-stats", "PROFILE_HIDDEN", StatusDebugLog.config());
      this.commit(uniqueId, StatusKind.PROFILE_HIDDEN, 403);
      this.rateGovernor.get().recordSuccess();
      onFinished.finished(false);
      return;
    }

    if (statusCode == 200) {
      this.memory.recordLabyModAccount(uniqueId);
      this.resolver.markLabyModUser(uniqueId);
      GameStatsResponse stats = response.get();
      StatusKind kind = LabyNetStatusInterpreter.fromGameStats(uniqueId, this.resolver, stats);

      this.debugLog.probeStep(uniqueId, probeId, "game-stats", "SUCCESS", StatusDebugLog.config(
          "kind", kind.name(),
          "lastOnline", stats == null ? "null" : stats.getLastOnline(),
          "onTab", this.resolver.isPlayerOnTab(uniqueId)
      ));

      boolean onTab = this.resolver.isPlayerOnTab(uniqueId);
      if (fetchProfileOnTab && onTab && LabyNetStatusInterpreter.shouldRefineWithProfile(kind)) {
        this.fetchProfileStatus(uniqueId, jwt, probeId, kind, onFinished, onlineBlocked);
        return;
      }

      this.commit(uniqueId, kind, onlineBlocked ? 403 : statusCode);
      this.rateGovernor.get().recordSuccess();
      onFinished.finished(false);
      return;
    }

    onFinished.finished(false);
  }

  private void fetchProfileStatus(
      @NotNull UUID uniqueId,
      @NotNull String jwt,
      @NotNull String probeId,
      @NotNull StatusKind fallbackKind,
      @NotNull ProbeFinishedCallback onFinished,
      boolean onlineBlocked
  ) {
    String url = LabyNetApiUrls.profileStatus(uniqueId);
    long startedAt = System.currentTimeMillis();
    this.debugLog.httpStart(uniqueId, probeId, "profile-status", url, 0L);

    Request.ofGson(ProfileStatusResponse.class)
        .url(url)
        .authorization("Bearer", jwt)
        .connectTimeout(6_000)
        .readTimeout(6_000)
        .async(true)
        .execute(response -> this.handleProfileStatus(
            uniqueId,
            probeId,
            response,
            startedAt,
            fallbackKind,
            onFinished,
            onlineBlocked
        ));
  }

  private void handleProfileStatus(
      @NotNull UUID uniqueId,
      @NotNull String probeId,
      @NotNull Response<ProfileStatusResponse> response,
      long startedAt,
      @NotNull StatusKind fallbackKind,
      @NotNull ProbeFinishedCallback onFinished,
      boolean onlineBlocked
  ) {
    int statusCode = LabyNetHttp.statusCode(response);
    long elapsedMs = System.currentTimeMillis() - startedAt;
    String text = response.isPresent() && response.get() != null ? response.get().getStatus() : null;

    this.debugLog.httpEnd(
        uniqueId,
        probeId,
        "profile-status",
        statusCode,
        text,
        elapsedMs,
        LabyNetHttp.isRateLimited(response),
        LabyNetHttp.errorMessage(response)
    );

    if (LabyNetHttp.isRateLimited(response)) {
      this.rateGovernor.get().recordRateLimit();
      onFinished.finished(true);
      return;
    }

    StatusKind kind = fallbackKind;
    if (!response.hasException() && statusCode == 200 && text != null) {
      StatusKind parsed = LabyNetStatusInterpreter.fromProfileText(text);
      if (parsed != null) {
        kind = parsed;
        this.debugLog.probeStep(uniqueId, probeId, "profile-status", "TEXT_PARSED", StatusDebugLog.config(
            "parsedKind", parsed.name(),
            "fallbackKind", fallbackKind.name()
        ));
      }
    }

    this.commit(uniqueId, kind, onlineBlocked ? 403 : (statusCode > 0 ? statusCode : 200));
    this.rateGovernor.get().recordSuccess();
    onFinished.finished(false);
  }

  private void commit(@NotNull UUID uniqueId, @NotNull StatusKind kind, int httpCode) {
    StatusKind previous = this.resolver.peekKind(uniqueId);
    this.resolver.commit(uniqueId, kind, StatusSource.API, false, httpCode, 0L);
    if (previous != kind) {
      StatusUiRefresh.refresh();
    }
  }

  private @NotNull String summarizeOnline(@NotNull Response<OnlineStatusPayload> response) {
    if (response.hasException()) {
      return "error";
    }

    if (!response.isPresent() || response.get() == null) {
      return statusCode(response) == 204 ? "no-content" : "empty";
    }

    OnlineStatusPayload payload = response.get();
    return "{\"online\":" + payload.online + ",\"status\":\"" + payload.status + "\"}";
  }

  private static int statusCode(@NotNull Response<?> response) {
    return LabyNetHttp.statusCode(response);
  }

  private static final class OnlineStatusPayload {
    private boolean online;
    private String status;

    private OnlineStatusPayload() {
    }
  }
}
