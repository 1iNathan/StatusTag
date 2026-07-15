package net.statustag.core.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

public class LabyNetProbeMemory {

  public enum OnlineAccess {
    UNKNOWN,
    ALLOWED,
    BLOCKED
  }

  public enum AccountKind {
    UNKNOWN,
    LABYMOD,
    NO_LABYMOD
  }

  private static final class Hint {
    private OnlineAccess onlineAccess = OnlineAccess.UNKNOWN;
    private AccountKind accountKind = AccountKind.UNKNOWN;
    private long updatedAt;
  }

  private final Map<UUID, Hint> hints = new ConcurrentHashMap<>();

  public @NotNull LabyNetProbePlan planFor(@NotNull UUID uniqueId, boolean onTab) {
    Hint hint = this.hints.get(uniqueId);
    if (hint == null) {
      return LabyNetProbePlan.ONLINE_FIRST;
    }

    if (hint.accountKind == AccountKind.NO_LABYMOD) {
      return LabyNetProbePlan.SKIP;
    }

    if (hint.onlineAccess == OnlineAccess.BLOCKED) {
      return onTab ? LabyNetProbePlan.STATS_WITH_PROFILE : LabyNetProbePlan.STATS_ONLY;
    }

    if (hint.onlineAccess == OnlineAccess.ALLOWED) {
      return LabyNetProbePlan.ONLINE_ONLY;
    }

    return onTab ? LabyNetProbePlan.ONLINE_THEN_PROFILE : LabyNetProbePlan.ONLINE_FIRST;
  }

  public void recordOnlineAllowed(@NotNull UUID uniqueId) {
    this.hint(uniqueId).onlineAccess = OnlineAccess.ALLOWED;
    this.touch(uniqueId);
  }

  public void recordOnlineBlocked(@NotNull UUID uniqueId) {
    this.hint(uniqueId).onlineAccess = OnlineAccess.BLOCKED;
    this.touch(uniqueId);
  }

  public void recordLabyModAccount(@NotNull UUID uniqueId) {
    this.hint(uniqueId).accountKind = AccountKind.LABYMOD;
    this.touch(uniqueId);
  }

  public void recordNoLabyModAccount(@NotNull UUID uniqueId) {
    this.hint(uniqueId).accountKind = AccountKind.NO_LABYMOD;
    this.touch(uniqueId);
  }

  public void clear(@NotNull UUID uniqueId) {
    this.hints.remove(uniqueId);
  }

  public void clearAll() {
    this.hints.clear();
  }

  private @NotNull Hint hint(@NotNull UUID uniqueId) {
    return this.hints.computeIfAbsent(uniqueId, ignored -> new Hint());
  }

  private void touch(@NotNull UUID uniqueId) {
    this.hint(uniqueId).updatedAt = System.currentTimeMillis();
  }
}
