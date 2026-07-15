package net.statustag.core.util;

import java.nio.file.Files;
import java.nio.file.Path;
import net.labymod.api.Laby;
import org.jetbrains.annotations.NotNull;

public final class AddonDataPaths {

  private static final String DATA_FOLDER = "statustag";

  private AddonDataPaths() {
  }

  public static @NotNull Path dataDirectory() {
    Path base = Laby.labyAPI().labyModLoader().getGameDirectory().resolve(DATA_FOLDER);
    try {
      Files.createDirectories(base);
    } catch (Exception ignored) {
    }
    return base;
  }

  public static @NotNull Path statusStoreFile() {
    return dataDirectory().resolve("status-store.json");
  }

  public static @NotNull Path debugLogFile() {
    return dataDirectory().resolve("debug.log");
  }
}
