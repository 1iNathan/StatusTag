package net.statustag.core.util;

import java.nio.file.Path;
import net.labymod.api.Laby;
import org.jetbrains.annotations.NotNull;

public final class DataFiles {

  private DataFiles() {
  }

  public static @NotNull Path directory() {
    return Laby.labyAPI().labyModLoader().getGameDirectory().resolve("statustag");
  }

  public static @NotNull Path cache() {
    return directory().resolve("status-cache.json");
  }

  public static @NotNull Path legacyCache() {
    return directory().resolve("status-store.json");
  }

  public static @NotNull Path debugLog() {
    return directory().resolve("debug.log");
  }
}
