package net.statustag.core.status;

import org.jetbrains.annotations.NotNull;

public record StatusEntry(
    @NotNull StatusKind kind,
    @NotNull StatusSource source,
    long updatedAt,
    long checkedAt
) {

  public @NotNull StatusEntry asCache() {
    return this.source == StatusSource.CACHE
        ? this
        : new StatusEntry(this.kind, StatusSource.CACHE, this.updatedAt, this.source == StatusSource.API ? this.checkedAt : 0L);
  }

  public @NotNull StatusEntry withCheckedAt(long checkedAt) {
    return new StatusEntry(this.kind, this.source, this.updatedAt, checkedAt);
  }
}
