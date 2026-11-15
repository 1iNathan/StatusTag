package net.statustag.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatSenderDetectorTest {

  private static final UUID STEVE = UUID.randomUUID();
  private static final UUID ALEX = UUID.randomUUID();
  private static final Map<String, UUID> NAMES = Map.of("steve", STEVE, "alex_99", ALEX);

  @Test
  void findsCommonChatFormats() {
    assertEquals(STEVE, ChatSenderDetector.detect("<Steve> hello", NAMES));
    assertEquals(STEVE, ChatSenderDetector.detect("Steve: hello", NAMES));
    assertEquals(STEVE, ChatSenderDetector.detect("[VIP] Steve \u00BB hello", NAMES));
    assertEquals(ALEX, ChatSenderDetector.detect("[12:30] Alex_99: hi", NAMES));
    assertEquals(ALEX, ChatSenderDetector.detect("Guild > Alex_99 [MVP]: gg", NAMES));
    assertEquals(STEVE, ChatSenderDetector.detect("(Team) Steve | ready", NAMES));
  }

  @Test
  void ignoresMentionsAndSystemMessages() {
    assertNull(ChatSenderDetector.detect("Steve joined the game", NAMES));
    assertNull(ChatSenderDetector.detect("Welcome back! Have fun", NAMES));
    assertNull(ChatSenderDetector.detect("Unknown: Steve said hi", NAMES));
    assertNull(ChatSenderDetector.detect("", NAMES));
    assertNull(ChatSenderDetector.detect(null, NAMES));
    assertNull(ChatSenderDetector.detect("Steve: hi", Map.of()));
  }

  @Test
  void onlyScansTheStartOfLongMessages() {
    String padding = "x".repeat(ChatSenderDetector.SCAN_LIMIT);
    assertNull(ChatSenderDetector.detect(padding + " Steve: hi", NAMES));
  }

  @Test
  void prefersTheNameRightBeforeTheSeparator() {
    assertEquals(ALEX, ChatSenderDetector.detect("[Steve's Island] Alex_99: hey", NAMES));
  }
}
