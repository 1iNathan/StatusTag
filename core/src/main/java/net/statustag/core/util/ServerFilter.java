package net.statustag.core.util;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class ServerFilter {

  private ServerFilter() {
  }

  public static @Nullable String normalize(@Nullable String address) {
    if (address == null) {
      return null;
    }

    String host = address.trim().toLowerCase(Locale.ROOT);
    int scheme = host.indexOf("://");
    if (scheme >= 0) {
      host = host.substring(scheme + 3);
    }

    int slash = host.indexOf('/');
    if (slash >= 0) {
      host = host.substring(0, slash);
    }

    int colon = host.lastIndexOf(':');
    if (colon > 0 && host.indexOf(':') == colon) {
      host = host.substring(0, colon);
    }

    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return host.isEmpty() ? null : host;
  }

  public static boolean covers(@Nullable String entry, @Nullable String host) {
    String rule = normalize(entry);
    String target = normalize(host);
    if (rule == null || target == null) {
      return false;
    }
    return target.equals(rule) || target.endsWith("." + rule);
  }

  public static boolean sameServer(@Nullable String first, @Nullable String second) {
    return covers(first, second) || covers(second, first);
  }
}
