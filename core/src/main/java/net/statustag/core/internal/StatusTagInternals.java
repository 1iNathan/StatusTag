package net.statustag.core.internal;

public final class StatusTagInternals {

  private volatile boolean fetchOnlineStatusFromApi;
  private final int apiRequestIntervalMs = 3_500;
  private final int apiMaxRequestIntervalMs = 8_000;
  private final int apiMaxRequestsPerMinute = 16;
  private final boolean apiAdaptiveEnabled = true;
  private final int softRefreshDays = 30;
  private final int profileHiddenReprobeHours = 6;
  private final int invisibleReprobeMinutes = 30;
  private final int noLabyModReprobeHours = 24;
  private final int tabPresenceReprobeMinutes = 15;

  public boolean fetchOnlineStatusFromApi() {
    return this.fetchOnlineStatusFromApi;
  }

  public void setFetchOnlineStatusFromApi(boolean enabled) {
    this.fetchOnlineStatusFromApi = enabled;
  }

  public int apiRequestIntervalMs() {
    return this.apiRequestIntervalMs;
  }

  public int apiMaxRequestIntervalMs() {
    return this.apiMaxRequestIntervalMs;
  }

  public int apiMaxRequestsPerMinute() {
    return this.apiMaxRequestsPerMinute;
  }

  public boolean apiAdaptiveEnabled() {
    return this.apiAdaptiveEnabled;
  }

  public int softRefreshDays() {
    return this.softRefreshDays;
  }

  public int profileHiddenReprobeHours() {
    return this.profileHiddenReprobeHours;
  }

  public int invisibleReprobeMinutes() {
    return this.invisibleReprobeMinutes;
  }

  public int noLabyModReprobeHours() {
    return this.noLabyModReprobeHours;
  }

  public int tabPresenceReprobeMinutes() {
    return this.tabPresenceReprobeMinutes;
  }
}
