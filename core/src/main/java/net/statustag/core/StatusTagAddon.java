package net.statustag.core;

import java.util.UUID;
import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.entity.player.badge.PositionType;
import net.labymod.api.feature.BuiltinCategories;
import net.labymod.api.models.addon.annotation.AddonMain;
import net.labymod.api.revision.SimpleRevision;
import net.labymod.api.util.version.SemanticVersion;
import net.statustag.core.command.StatusCommand;
import net.statustag.core.config.ConfigMigration;
import net.statustag.core.config.StatusColorsConfig;
import net.statustag.core.listener.ChatDotListener;
import net.statustag.core.listener.FriendNotificationListener;
import net.statustag.core.listener.StatusKeyListener;
import net.statustag.core.listener.StatusListener;
import net.statustag.core.net.LabyNetClient;
import net.statustag.core.render.StatusDotNameTag;
import net.statustag.core.render.StatusDotTabBadge;
import net.statustag.core.render.StatusOverviewWidget;
import net.statustag.core.render.StatusPalette;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusService;
import net.statustag.core.status.StatusStore;
import net.statustag.core.sync.LabyConnectTransport;
import net.statustag.core.sync.StatusSync;
import net.statustag.core.util.Async;
import net.statustag.core.util.DataFiles;
import net.statustag.core.util.DebugLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@AddonMain
public class StatusTagAddon extends LabyAddon<StatusTagConfiguration> {

  public static final String NAMESPACE = "statustag";
  private static final String TAG_ID = "statustag_status_dot";

  private static volatile StatusTagAddon instance;

  private StatusService service;
  private StatusSync sync;

  @Override
  protected void preConfigurationLoad() {
    this.registerListener(new ConfigMigration());
    Laby.references().revisionRegistry().register(
        new SimpleRevision(NAMESPACE, new SemanticVersion(1, 1, 0), "2026-11-15")
    );
  }

  @Override
  protected void enable() {
    StatusTagConfiguration config = this.configuration();

    DebugLog debug = new DebugLog(DataFiles.debugLog(), Async::run);
    debug.setEnabled(config.debugLogging().get());
    config.debugLogging().addChangeListener(debug::setEnabled);

    StatusColorsConfig colors = config.statusColors();
    StatusPalette palette = new StatusPalette(kind -> colors.property(kind).get());
    for (StatusKind kind : StatusKind.values()) {
      colors.property(kind).addChangeListener(palette::rebuild);
    }
    palette.setShowPlayersWithoutLabyMod(config.showPlayersWithoutLabyMod().get());
    config.showPlayersWithoutLabyMod().addChangeListener(palette::setShowPlayersWithoutLabyMod);

    StatusStore store = new StatusStore(DataFiles.cache(), DataFiles.legacyCache());
    this.service = new StatusService(config, store, palette, debug, new LabyNetClient());
    this.sync = new StatusSync(
        config,
        this.service,
        new LabyConnectTransport(),
        debug,
        () -> System.nanoTime() / 1_000_000L
    );
    instance = this;

    Async.run(() -> {
      int loaded = store.load(System.currentTimeMillis());
      if (debug.enabled()) {
        debug.log("cache loaded entries=" + loaded);
      }
    });

    config.enabled().addChangeListener(enabled -> {
      if (enabled) {
        this.service.syncSession();
        this.service.rebuildTabPlayers();
        this.sync.requestResync();
      } else {
        this.service.demoteLive();
      }
    });
    config.useLabyChat().addChangeListener(enabled -> {
      if (enabled) {
        this.service.syncSession();
      } else {
        this.service.demoteLive();
      }
    });
    config.useLabyNetApi().addChangeListener(enabled -> {
      if (!enabled) {
        this.service.scheduler().clear();
      }
    });
    config.useMesh().addChangeListener(enabled -> {
      if (enabled) {
        this.sync.requestResync();
      }
    });

    this.registerSettingCategory();
    this.placeInModsCategory(BuiltinCategories.PLAYER);

    this.registerListener(new StatusListener(this.service, this.sync));
    this.registerListener(new ChatDotListener(config, this.service));
    this.registerListener(new FriendNotificationListener(config, palette, debug));
    this.registerCommand(new StatusCommand(this.service, this.sync));
    this.registerListener(new StatusKeyListener(config, palette));
    this.labyAPI().hudWidgetRegistry().register(new StatusOverviewWidget(this.service));

    this.registerRenderers(config);
    this.service.syncSession();
    this.service.rebuildTabPlayers();

    this.logger().info("StatusTag " + this.addonInfo().getVersion() + " enabled");
  }

  @Override
  protected Class<StatusTagConfiguration> configurationClass() {
    return StatusTagConfiguration.class;
  }

  public static @Nullable StatusTagAddon instance() {
    return instance;
  }

  public boolean isNameTagActive() {
    StatusTagConfiguration config = this.configuration();
    return config.enabled().get() && config.nameTag().enabled().get() && !this.service.isSuppressed();
  }

  public int nameTagArgb(@NotNull UUID uniqueId) {
    return this.service.shownArgb(uniqueId);
  }

  public void clearCache() {
    this.service.clearCache();
    this.sync.requestResync();
  }

  private void registerRenderers(@NotNull StatusTagConfiguration config) {
    StatusDotNameTag nameTag = new StatusDotNameTag(config.nameTag());
    try {
      Laby.references().tagRegistry().registerBefore(
          "labymod_role",
          TAG_ID,
          net.labymod.api.client.entity.player.tag.PositionType.LEFT_TO_NAME,
          nameTag
      );
    } catch (RuntimeException exception) {
      Laby.references().tagRegistry().register(
          TAG_ID,
          net.labymod.api.client.entity.player.tag.PositionType.LEFT_TO_NAME,
          nameTag
      );
    }

    StatusDotTabBadge badge = new StatusDotTabBadge(config, this.service);
    try {
      Laby.references().badgeRegistry().registerBefore("server_badge", TAG_ID, PositionType.LEFT_TO_NAME, badge);
    } catch (RuntimeException exception) {
      Laby.references().badgeRegistry().register(TAG_ID, PositionType.LEFT_TO_NAME, badge);
    }
  }
}
