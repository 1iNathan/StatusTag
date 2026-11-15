package net.statustag.core.net;

import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record BulkLookupResult(
    int code,
    @Nullable Map<UUID, LookupResult> entries,
    long retryAfterMs,
    int remaining,
    long resetAt
) {

  public static @NotNull BulkLookupResult unsupported() {
    return new BulkLookupResult(404, null, 0L, -1, -1L);
  }

  public boolean isRateLimited() {
    return this.code == 429;
  }

  public boolean isUnsupported() {
    return this.code >= 400 && this.code < 500 && this.code != 429
        || this.code == 501
        || this.code == 200 && this.entries == null;
  }

  public boolean isSuccess() {
    return this.code == 200 && this.entries != null;
  }
}
