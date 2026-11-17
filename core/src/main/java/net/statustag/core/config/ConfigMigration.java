package net.statustag.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.config.ConfigurationVersionUpdateEvent;
import net.statustag.core.StatusTagConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ConfigMigration {

  public static final int CONFIG_VERSION = 2;

  static final int DARK_GRAY = 0xFF555555;
  static final int GRAY = 0xFFAAAAAA;

  @Subscribe
  public void onVersionUpdate(ConfigurationVersionUpdateEvent event) {
    if (event.getUsedVersion() >= CONFIG_VERSION) {
      return;
    }

    JsonObject root = event.getJsonObject();
    if (root == null) {
      return;
    }

    Class<?> type = event.getConfigClass();
    if (type == StatusColorsConfig.class) {
      migrate(root);
    } else if (type == StatusTagConfiguration.class) {
      keepPreviousBehavior(root);
      if (root.get("statusColors") instanceof JsonObject colors) {
        migrate(colors);
      }
    } else {
      return;
    }
    event.setJsonObject(root);
  }

  public static boolean migrate(@NotNull JsonObject colors) {
    boolean hidden = replace(colors, "profileHiddenColor", DARK_GRAY, GRAY);
    boolean unknown = replace(colors, "unknownColor", GRAY, DARK_GRAY);
    return hidden || unknown;
  }

  public static boolean keepPreviousBehavior(@NotNull JsonObject root) {
    if (!root.has("enabled")) {
      return false;
    }

    boolean changed = addIfMissing(root, "showPlayersWithoutLabyMod", true);
    if (root.get("notifications") instanceof JsonObject notifications
        && addIfMissing(notifications, "pinnedOnly", false)) {
      changed = true;
    }
    return changed;
  }

  private static boolean addIfMissing(@NotNull JsonObject object, @NotNull String key, boolean value) {
    if (object.has(key)) {
      return false;
    }
    object.addProperty(key, value);
    return true;
  }

  private static boolean replace(@NotNull JsonObject colors, @NotNull String key, int from, int to) {
    Integer current = intValue(colors.get(key));
    if (current == null || current != from) {
      return false;
    }
    colors.addProperty(key, to);
    return true;
  }

  private static @Nullable Integer intValue(@Nullable JsonElement element) {
    if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
      try {
        return primitive.getAsInt();
      } catch (NumberFormatException invalid) {
        return null;
      }
    }
    return null;
  }
}
