package net.statustag.core.util;

import java.util.function.Supplier;
import net.labymod.api.util.concurrent.task.Task;
import org.jetbrains.annotations.NotNull;

public final class Async {

  private static final Supplier<String> LABEL = () -> "StatusTag";

  private Async() {
  }

  public static void run(@NotNull Runnable runnable) {
    Task.builder(runnable).debugLabel(LABEL).build().execute();
  }
}
