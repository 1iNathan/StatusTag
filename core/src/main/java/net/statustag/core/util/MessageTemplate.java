package net.statustag.core.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MessageTemplate {

  public static final String STATUS = "{status}";
  public static final String PREVIOUS = "{previous}";

  private MessageTemplate() {
  }

  public static boolean isCustom(@Nullable String template) {
    return template != null && !template.isBlank();
  }

  public static @NotNull String fill(@NotNull String template, @NotNull String status, @NotNull String previous) {
    return template.strip().replace(STATUS, status).replace(PREVIOUS, previous);
  }
}
