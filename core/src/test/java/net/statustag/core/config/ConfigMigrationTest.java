package net.statustag.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.labymod.api.configuration.loader.Config;
import net.labymod.api.event.labymod.config.ConfigurationVersionUpdateEvent;
import net.statustag.core.StatusTagConfiguration;
import net.statustag.core.status.StatusKind;
import org.junit.jupiter.api.Test;

class ConfigMigrationTest {

  private static ConfigurationVersionUpdateEvent update(Class<? extends Config> type, String json, int usedVersion) {
    ConfigurationVersionUpdateEvent event = new ConfigurationVersionUpdateEvent(
        type, JsonParser.parseString(json).getAsJsonObject(), usedVersion, ConfigMigration.CONFIG_VERSION
    );
    new ConfigMigration().onVersionUpdate(event);
    return event;
  }

  @Test
  void customColorsAreStampedUnchanged() {
    ConfigurationVersionUpdateEvent event = update(
        StatusColorsConfig.class, "{\"profileHiddenColor\":-65536,\"unknownColor\":-16580397}", 1
    );

    JsonObject edited = event.getEditedJsonObject();
    assertNotNull(edited);
    assertEquals(-65536, edited.get("profileHiddenColor").getAsInt());
    assertEquals(-16580397, edited.get("unknownColor").getAsInt());
  }

  @Test
  void mainConfigFromOneZeroIsMigrated() {
    ConfigurationVersionUpdateEvent event = update(
        StatusTagConfiguration.class,
        "{\"enabled\":true,\"notifications\":{\"enabled\":true},"
            + "\"statusColors\":{\"profileHiddenColor\":-11184811,\"unknownColor\":-5592406}}",
        1
    );

    JsonObject edited = event.getEditedJsonObject();
    assertNotNull(edited);
    assertTrue(edited.get("showPlayersWithoutLabyMod").getAsBoolean());
    assertFalse(edited.getAsJsonObject("notifications").get("pinnedOnly").getAsBoolean());
    JsonObject colors = edited.getAsJsonObject("statusColors");
    assertEquals(ConfigMigration.GRAY, colors.get("profileHiddenColor").getAsInt());
    assertEquals(ConfigMigration.DARK_GRAY, colors.get("unknownColor").getAsInt());
  }

  @Test
  void otherConfigsAreNotTouched() {
    ConfigurationVersionUpdateEvent event = update(TabListConfig.class, "{\"enabled\":true}", 1);
    assertNull(event.getEditedJsonObject());
  }

  @Test
  void currentVersionIsNotTouched() {
    ConfigurationVersionUpdateEvent event = update(
        StatusColorsConfig.class, "{\"profileHiddenColor\":-11184811}", ConfigMigration.CONFIG_VERSION
    );
    assertNull(event.getEditedJsonObject());
  }

  @Test
  void swapsUntouchedDefaults() {
    JsonObject colors = JsonParser.parseString(
        "{\"profileHiddenColor\":-11184811,\"unknownColor\":-5592406,\"onlineColor\":-11141291}"
    ).getAsJsonObject();

    assertTrue(ConfigMigration.migrate(colors));
    assertEquals(ConfigMigration.GRAY, colors.get("profileHiddenColor").getAsInt());
    assertEquals(ConfigMigration.DARK_GRAY, colors.get("unknownColor").getAsInt());
    assertEquals(-11141291, colors.get("onlineColor").getAsInt());
  }

  @Test
  void keepsCustomColors() {
    JsonObject colors = JsonParser.parseString(
        "{\"profileHiddenColor\":-65536,\"unknownColor\":-16580397,\"noLabyModColor\":-1}"
    ).getAsJsonObject();

    assertFalse(ConfigMigration.migrate(colors));
    assertEquals(-65536, colors.get("profileHiddenColor").getAsInt());
    assertEquals(-16580397, colors.get("unknownColor").getAsInt());
  }

  @Test
  void isIdempotent() {
    JsonObject colors = JsonParser.parseString(
        "{\"profileHiddenColor\":-11184811,\"unknownColor\":-5592406}"
    ).getAsJsonObject();

    ConfigMigration.migrate(colors);
    assertFalse(ConfigMigration.migrate(colors));
    assertEquals(ConfigMigration.GRAY, colors.get("profileHiddenColor").getAsInt());
    assertEquals(ConfigMigration.DARK_GRAY, colors.get("unknownColor").getAsInt());
  }

  @Test
  void ignoresChromaObjectsAndMissingKeys() {
    JsonObject colors = JsonParser.parseString(
        "{\"profileHiddenColor\":{\"value\":-11184811,\"chroma\":true}}"
    ).getAsJsonObject();
    assertFalse(ConfigMigration.migrate(colors));
    assertFalse(ConfigMigration.migrate(new JsonObject()));
  }

  @Test
  void newDefaultsMatchMigrationTargets() {
    assertEquals(ConfigMigration.GRAY, 0xFF000000 | StatusKind.PROFILE_HIDDEN.defaultRgb());
    assertEquals(ConfigMigration.GRAY, 0xFF000000 | StatusKind.INVISIBLE.defaultRgb());
    assertEquals(ConfigMigration.GRAY, 0xFF000000 | StatusKind.OFFLINE.defaultRgb());
    assertEquals(ConfigMigration.DARK_GRAY, 0xFF000000 | StatusKind.UNKNOWN.defaultRgb());
    assertEquals(ConfigMigration.DARK_GRAY, 0xFF000000 | StatusKind.NO_LABYMOD.defaultRgb());
  }

  @Test
  void upgradeFromOneZeroKeepsPreviousBehavior() {
    JsonObject root = JsonParser.parseString(
        "{\"enabled\":true,\"notifications\":{\"enabled\":true,\"friendOnline\":true}}"
    ).getAsJsonObject();

    assertTrue(ConfigMigration.keepPreviousBehavior(root));
    assertTrue(root.get("showPlayersWithoutLabyMod").getAsBoolean());
    assertFalse(root.getAsJsonObject("notifications").get("pinnedOnly").getAsBoolean());
    assertFalse(ConfigMigration.keepPreviousBehavior(root));
  }

  @Test
  void upgradeKeepsChosenValues() {
    JsonObject root = JsonParser.parseString(
        "{\"enabled\":true,\"showPlayersWithoutLabyMod\":false,\"notifications\":{\"pinnedOnly\":true}}"
    ).getAsJsonObject();

    assertFalse(ConfigMigration.keepPreviousBehavior(root));
    assertFalse(root.get("showPlayersWithoutLabyMod").getAsBoolean());
    assertTrue(root.getAsJsonObject("notifications").get("pinnedOnly").getAsBoolean());
  }

  @Test
  void freshConfigGetsNewDefaults() {
    JsonObject root = new JsonObject();
    assertFalse(ConfigMigration.keepPreviousBehavior(root));
    assertFalse(root.has("showPlayersWithoutLabyMod"));
  }
}
