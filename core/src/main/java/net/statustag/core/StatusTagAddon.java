package net.statustag.core;

import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.entity.player.badge.PositionType;
import net.labymod.api.models.addon.annotation.AddonMain;
import net.statustag.core.badge.StatusDotTabBadge;
import net.statustag.core.internal.StatusTagInternals;
import net.statustag.core.listener.ChatStatusDotListener;
import net.statustag.core.listener.FriendStatusToastListener;
import net.statustag.core.listener.GameUserSyncListener;
import net.statustag.core.listener.OnlineStatusQueueListener;
import net.statustag.core.listener.StatusSyncListener;
import net.statustag.core.listener.TabListStatusListener;
import net.statustag.core.listener.UserStatusListener;
import net.statustag.core.tag.StatusDotNameTag;
import net.statustag.core.mesh.StatusSync;
import net.statustag.core.service.PersistentStatusStore;
import net.statustag.core.service.StatusDebugLog;
import net.statustag.core.service.StatusFetchScheduler;
import net.statustag.core.service.StatusResolver;
import net.statustag.core.util.AddonDataPaths;

@AddonMain
public class StatusTagAddon extends LabyAddon<StatusTagConfiguration> {

  private static StatusTagAddon instance;

  private final StatusTagInternals internals = new StatusTagInternals();
  private PersistentStatusStore statusStore;
  private StatusDebugLog debugLog;
  private StatusResolver statusResolver;
  private StatusFetchScheduler fetchScheduler;
  private StatusSync statusSync;

  @Override
  protected void enable() {
    instance = this;
    this.syncApiToggle();

    this.statusStore = new PersistentStatusStore();
    this.debugLog = new StatusDebugLog();
    this.debugLog.setEnabledSupplier(() -> {
      StatusTagConfiguration configuration = this.configuration();
      return configuration != null && configuration.debugLogging().get();
    });
    this.debugLog.bindErrorSink(message -> this.logger().warn(message));
    this.debugLog.bootstrap();

    this.statusResolver = new StatusResolver(this.statusStore, this.debugLog);
    this.statusResolver.configure(this::configuration, () -> this.internals);

    this.fetchScheduler = new StatusFetchScheduler(
        this.statusResolver,
        this.debugLog,
        this::configuration,
        () -> this.internals
    );
    this.statusResolver.setFetchScheduler(this.fetchScheduler);

    this.registerSettingCategory();
    this.configuration().useLabyNetApi().addChangeListener(value -> this.syncApiToggle());

    this.debugLog.sessionStart(StatusTagMeta.VERSION, StatusDebugLog.config(
        "enabled", this.configuration().enabled().get(),
        "showInTabList", this.configuration().tabList().enabled().get(),
        "useLabyChat", this.configuration().isLabyChatEnabled(),
        "useLabyNetApi", this.configuration().isApiEnabled()
    ));

    this.statusSync = new StatusSync(this.statusResolver, this.debugLog, this::configuration);

    this.registerListener(new UserStatusListener(
        this.statusResolver,
        this.debugLog,
        this::configuration
    ));
    this.registerListener(new OnlineStatusQueueListener(this.fetchScheduler));
    this.registerListener(new GameUserSyncListener(this.statusResolver, this::configuration));
    this.registerListener(new TabListStatusListener(
        this.statusResolver,
        this.fetchScheduler,
        this.debugLog,
        this::configuration
    ));
    this.registerListener(new StatusSyncListener(this.statusSync));
    this.registerListener(new ChatStatusDotListener(this));
    this.registerListener(new FriendStatusToastListener(this::configuration));

    this.statusResolver.syncFromSession();

    this.registerTags();

    this.logger().info("StatusTag v" + StatusTagMeta.VERSION + " enabled");
    this.logger().info("StatusTag debug log: " + this.debugLog.logFile());
    this.logger().info("StatusTag data dir: " + AddonDataPaths.dataDirectory().toAbsolutePath());
  }

  @Override
  protected Class<StatusTagConfiguration> configurationClass() {
    return StatusTagConfiguration.class;
  }

  public static StatusTagAddon instance() {
    return instance;
  }

  public StatusResolver statusResolver() {
    return this.statusResolver;
  }

  public StatusTagInternals internals() {
    return this.internals;
  }

  public PersistentStatusStore statusStore() {
    return this.statusStore;
  }

  public StatusSync statusSync() {
    return this.statusSync;
  }

  private void registerTags() {
    try {
      Laby.references().tagRegistry().registerBefore(
          "labymod_role",
          "statustag_status_dot",
          net.labymod.api.client.entity.player.tag.PositionType.LEFT_TO_NAME,
          new StatusDotNameTag(this)
      );
    } catch (RuntimeException exception) {
      this.logger().warn("Falling back to plain name-tag registration: " + exception.getMessage());
      Laby.references().tagRegistry().register(
          "statustag_status_dot",
          net.labymod.api.client.entity.player.tag.PositionType.LEFT_TO_NAME,
          new StatusDotNameTag(this)
      );
    }

    try {
      Laby.references().badgeRegistry().registerBefore(
          "server_badge",
          "statustag_status_dot",
          PositionType.LEFT_TO_NAME,
          new StatusDotTabBadge(this)
      );
    } catch (RuntimeException exception) {
      this.logger().warn("Falling back to plain tab-badge registration: " + exception.getMessage());
      Laby.references().badgeRegistry().register(
          "statustag_status_dot",
          PositionType.LEFT_TO_NAME,
          new StatusDotTabBadge(this)
      );
    }
  }

  private void syncApiToggle() {
    this.internals.setFetchOnlineStatusFromApi(this.configuration().isApiEnabled());
  }
}
