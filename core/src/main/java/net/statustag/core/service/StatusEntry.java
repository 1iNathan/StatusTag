package net.statustag.core.service;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatusEntry {

  private UUID uuid;
  private StatusKind kind;
  private StatusSource source;
  private long lastCheckedAt;
  private long lastUpdatedAt;
  private boolean skipApi;
  private int httpCode;
  private long meshTimestamp;

  public StatusEntry() {
  }

  public StatusEntry(
      @NotNull UUID uuid,
      @NotNull StatusKind kind,
      @NotNull StatusSource source,
      long lastCheckedAt,
      long lastUpdatedAt,
      boolean skipApi,
      int httpCode,
      long meshTimestamp
  ) {
    this.uuid = uuid;
    this.kind = kind;
    this.source = source;
    this.lastCheckedAt = lastCheckedAt;
    this.lastUpdatedAt = lastUpdatedAt;
    this.skipApi = skipApi;
    this.httpCode = httpCode;
    this.meshTimestamp = meshTimestamp;
  }

  public @NotNull UUID uuid() {
    return this.uuid;
  }

  public @NotNull StatusKind kind() {
    return this.kind;
  }

  public @NotNull StatusSource source() {
    return this.source;
  }

  public long lastCheckedAt() {
    return this.lastCheckedAt;
  }

  public long lastUpdatedAt() {
    return this.lastUpdatedAt;
  }

  public boolean skipApi() {
    return this.skipApi;
  }

  public int httpCode() {
    return this.httpCode;
  }

  public long meshTimestamp() {
    return this.meshTimestamp;
  }

  public void setKind(@NotNull StatusKind kind) {
    this.kind = kind;
  }

  public void setSource(@NotNull StatusSource source) {
    this.source = source;
  }

  public void setLastCheckedAt(long lastCheckedAt) {
    this.lastCheckedAt = lastCheckedAt;
  }

  public void setLastUpdatedAt(long lastUpdatedAt) {
    this.lastUpdatedAt = lastUpdatedAt;
  }

  public void setSkipApi(boolean skipApi) {
    this.skipApi = skipApi;
  }

  public void setHttpCode(int httpCode) {
    this.httpCode = httpCode;
  }

  public void setMeshTimestamp(long meshTimestamp) {
    this.meshTimestamp = meshTimestamp;
  }

  public @NotNull StatusEntry copy() {
    return new StatusEntry(
        this.uuid,
        this.kind,
        this.source,
        this.lastCheckedAt,
        this.lastUpdatedAt,
        this.skipApi,
        this.httpCode,
        this.meshTimestamp
    );
  }

  public boolean isAuthoritative() {
    return this.source == StatusSource.FRIEND
        || this.source == StatusSource.SELF
        || this.source == StatusSource.MESH;
  }
}
