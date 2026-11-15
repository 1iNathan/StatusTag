package net.statustag.core.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.labymod.api.util.io.web.exception.WebRequestException;
import net.labymod.api.util.io.web.request.Request;
import net.labymod.api.util.io.web.request.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LabyNetClient implements StatusLookup {

  private static final String ONLINE_STATUS_URL = "https://laby.net/api/v3/user/%s/online-status";
  private static final String BULK_STATUS_URL = "https://laby.net/api/v3/bulk/status";
  private static final int TIMEOUT_MS = 6_000;

  private record RateInfo(long retryAfterMs, int remaining, long resetAt) {
  }

  @Override
  public void lookup(@NotNull UUID uniqueId, @NotNull String jwt, @NotNull Consumer<LookupResult> callback) {
    Request.ofGson(Payload.class)
        .url(ONLINE_STATUS_URL, uniqueId)
        .authorization("Bearer", jwt)
        .connectTimeout(TIMEOUT_MS)
        .readTimeout(TIMEOUT_MS)
        .async(true)
        .execute(response -> callback.accept(toResult(response)));
  }

  @Override
  public void lookupBulk(
      @NotNull List<UUID> uniqueIds,
      @NotNull String jwt,
      @NotNull Consumer<BulkLookupResult> callback
  ) {
    JsonArray body = new JsonArray();
    for (UUID uniqueId : uniqueIds) {
      body.add(new JsonPrimitive(uniqueId.toString()));
    }

    Request.ofGson(JsonElement.class)
        .url(BULK_STATUS_URL)
        .method(Request.Method.POST)
        .authorization("Bearer", jwt)
        .json(body)
        .connectTimeout(TIMEOUT_MS)
        .readTimeout(TIMEOUT_MS)
        .async(true)
        .execute(response -> callback.accept(toBulkResult(response, uniqueIds)));
  }

  static @NotNull LookupResult toResult(@NotNull Response<Payload> response) {
    int code = statusCode(response);
    RateInfo rate = rateInfo(response, System.currentTimeMillis());
    Payload payload = !response.hasException() && response.isPresent() ? response.get() : null;
    return new LookupResult(
        code,
        payload == null ? null : payload.status,
        payload != null && payload.online,
        rate.retryAfterMs(),
        rate.remaining(),
        rate.resetAt()
    );
  }

  static @NotNull BulkLookupResult toBulkResult(@NotNull Response<JsonElement> response, @NotNull List<UUID> requested) {
    int code = statusCode(response);
    RateInfo rate = rateInfo(response, System.currentTimeMillis());
    Map<UUID, LookupResult> entries = null;
    if (code == 200) {
      JsonElement body = !response.hasException() && response.isPresent() ? response.get() : null;
      entries = parseBulk(body, requested);
    }
    return new BulkLookupResult(code, entries, rate.retryAfterMs(), rate.remaining(), rate.resetAt());
  }

  static @Nullable Map<UUID, LookupResult> parseBulk(@Nullable JsonElement body, @NotNull List<UUID> requested) {
    Map<UUID, LookupResult> entries = new HashMap<>(Math.max(16, requested.size() * 2));
    if (body instanceof JsonObject object) {
      for (Map.Entry<String, JsonElement> element : object.entrySet()) {
        UUID uniqueId = parseUuid(element.getKey());
        LookupResult entry = uniqueId == null ? null : parseEntry(element.getValue());
        if (entry == null) {
          return null;
        }
        entries.put(uniqueId, entry);
      }
    } else if (body instanceof JsonArray array) {
      for (JsonElement element : array) {
        if (!(element instanceof JsonObject object) || !(object.get("uuid") instanceof JsonPrimitive id)) {
          return null;
        }
        UUID uniqueId = parseUuid(id.getAsString());
        LookupResult entry = uniqueId == null ? null : parseEntry(object);
        if (entry == null) {
          return null;
        }
        entries.put(uniqueId, entry);
      }
    } else {
      return null;
    }

    for (UUID uniqueId : requested) {
      entries.putIfAbsent(uniqueId, new LookupResult(LookupResult.NOT_LISTED, null, false, 0L, -1, -1L));
    }
    return entries;
  }

  private static @Nullable LookupResult parseEntry(@Nullable JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return new LookupResult(404, null, false, 0L, -1, -1L);
    }

    if (value instanceof JsonPrimitive primitive && primitive.isString()) {
      String status = primitive.getAsString();
      return new LookupResult(200, status, !"OFFLINE".equalsIgnoreCase(status), 0L, -1, -1L);
    }

    if (!(value instanceof JsonObject object)) {
      return null;
    }

    if (object.get("hidden") instanceof JsonPrimitive hidden && hidden.isBoolean() && hidden.getAsBoolean()) {
      return new LookupResult(403, null, false, 0L, -1, -1L);
    }

    if (!(object.get("status") instanceof JsonPrimitive statusElement) || !statusElement.isString()) {
      return null;
    }

    String status = statusElement.getAsString();
    boolean online = object.get("online") instanceof JsonPrimitive onlineElement && onlineElement.isBoolean()
        ? onlineElement.getAsBoolean()
        : !"OFFLINE".equalsIgnoreCase(status);
    long lastOnline = parseTime(object.get("lastOnline"));
    if (lastOnline < 0L) {
      lastOnline = parseTime(object.get("last-online"));
    }
    return new LookupResult(200, status, online, 0L, -1, -1L, lastOnline);
  }

  static long parseTime(@Nullable JsonElement element) {
    if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
      return -1L;
    }

    try {
      return OffsetDateTime.parse(primitive.getAsString()).toInstant().toEpochMilli();
    } catch (DateTimeParseException invalid) {
      return -1L;
    }
  }

  private static @NotNull RateInfo rateInfo(@NotNull Response<?> response, long now) {
    long retryAfterMs = 0L;
    WebRequestException exception = response.hasException() ? response.exception() : null;
    if (exception != null && exception.getRetryAfter() > 0L) {
      long retryAfter = exception.getRetryAfter();
      retryAfterMs = retryAfter > 1_000L ? retryAfter : retryAfter * 1_000L;
    }

    int remaining = -1;
    long resetAt = -1L;
    Map<String, String> headers = response.getHeaders();
    if (headers != null && !headers.isEmpty()) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        String name = header.getKey();
        if (name == null) {
          continue;
        }

        switch (name.toLowerCase(Locale.ROOT)) {
          case "x-ratelimit-remaining" -> remaining = (int) parseLong(header.getValue(), -1L);
          case "x-ratelimit-reset" -> resetAt = RateLimiter.parseResetAt(now, parseLong(header.getValue(), -1L));
          case "retry-after" -> {
            if (retryAfterMs <= 0L) {
              retryAfterMs = Math.max(0L, parseLong(header.getValue(), 0L)) * 1_000L;
            }
          }
          default -> {
          }
        }
      }
    }
    return new RateInfo(retryAfterMs, remaining, resetAt);
  }

  static int statusCode(@NotNull Response<?> response) {
    if (!response.hasException()) {
      int code = response.getStatusCode();
      return code > 0 ? code : LookupResult.NETWORK_ERROR;
    }

    WebRequestException exception = response.exception();
    if (exception == null) {
      return LookupResult.NETWORK_ERROR;
    }

    if (exception.getResponseCode() > 0) {
      return exception.getResponseCode();
    }

    return codeFromMessage(exception.getMessage());
  }

  static int codeFromMessage(@Nullable String message) {
    if (message == null) {
      return LookupResult.NETWORK_ERROR;
    }
    if (message.contains("Too Many Requests") || message.contains(" 429")) {
      return 429;
    }
    if (message.contains("Forbidden") || message.contains(" 403")) {
      return 403;
    }
    if (message.contains("Not Found") || message.contains(" 404")) {
      return 404;
    }
    if (message.contains("Method Not Allowed") || message.contains(" 405")) {
      return 405;
    }
    if (message.contains("Unauthorized") || message.contains(" 401")) {
      return 401;
    }
    return LookupResult.NETWORK_ERROR;
  }

  static @Nullable UUID parseUuid(@Nullable String value) {
    if (value == null) {
      return null;
    }

    String text = value.trim();
    if (text.length() == 32) {
      text = text.substring(0, 8) + '-' + text.substring(8, 12) + '-' + text.substring(12, 16) + '-'
          + text.substring(16, 20) + '-' + text.substring(20);
    }

    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static long parseLong(@Nullable String value, long fallback) {
    if (value == null) {
      return fallback;
    }

    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException invalid) {
      try {
        return (long) Double.parseDouble(value.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
  }

  static final class Payload {
    private boolean online;
    private String status;
  }
}
