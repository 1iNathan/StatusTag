package net.statustag.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MessageTemplateTest {

  @Test
  void emptyTemplatesUseTheDefault() {
    assertFalse(MessageTemplate.isCustom(null));
    assertFalse(MessageTemplate.isCustom(""));
    assertFalse(MessageTemplate.isCustom("   "));
    assertTrue(MessageTemplate.isCustom("is here"));
  }

  @Test
  void placeholdersAreFilled() {
    assertEquals("is now Online &8(was Away)",
        MessageTemplate.fill(" is now {status} &8(was {previous}) ", "Online", "Away"));
    assertEquals("Online Online", MessageTemplate.fill("{status} {status}", "Online", "Away"));
    assertEquals("left", MessageTemplate.fill("left", "Offline", "Online"));
  }
}
