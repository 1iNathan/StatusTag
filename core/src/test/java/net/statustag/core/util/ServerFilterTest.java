package net.statustag.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerFilterTest {

  @Test
  void normalizeStripsSchemePortPathAndCase() {
    assertEquals("mc.hypixel.net", ServerFilter.normalize("  MC.Hypixel.NET:25565 "));
    assertEquals("play.example.org", ServerFilter.normalize("minecraft://play.example.org/lobby"));
    assertEquals("hypixel.net", ServerFilter.normalize("hypixel.net."));
    assertNull(ServerFilter.normalize("   "));
    assertNull(ServerFilter.normalize(null));
  }

  @Test
  void ruleCoversSubdomains() {
    assertTrue(ServerFilter.covers("hypixel.net", "mc.hypixel.net"));
    assertTrue(ServerFilter.covers("hypixel.net", "HYPIXEL.NET:25565"));
    assertFalse(ServerFilter.covers("mc.hypixel.net", "hypixel.net"));
    assertFalse(ServerFilter.covers("hypixel.net", "nothypixel.net"));
    assertFalse(ServerFilter.covers("", "hypixel.net"));
    assertFalse(ServerFilter.covers("hypixel.net", null));
  }

  @Test
  void sameServerWorksBothWays() {
    assertTrue(ServerFilter.sameServer("mc.hypixel.net", "hypixel.net"));
    assertTrue(ServerFilter.sameServer("hypixel.net", "mc.hypixel.net:25565"));
    assertFalse(ServerFilter.sameServer("abc.aternos.me", "xyz.aternos.me"));
    assertFalse(ServerFilter.sameServer(null, "hypixel.net"));
  }
}
