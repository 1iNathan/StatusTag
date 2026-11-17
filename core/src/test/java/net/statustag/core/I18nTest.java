package net.statustag.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.statustag.core.status.StatusKind;
import net.statustag.core.status.StatusSource;
import org.junit.jupiter.api.Test;

class I18nTest {

  private static final String BASE = "en_us";
  private static final String[] LANGUAGES = {
      "en_us", "ar_sa", "de_de", "es_es", "fr_fr", "pt_br", "ru_ru", "tr_tr"
  };
  private static final Map<String, String> REGIONAL = Map.of(
      "es_ar", "es_es", "es_cl", "es_es", "es_ec", "es_es", "es_mx", "es_es", "es_uy", "es_es", "es_ve", "es_es",
      "de_at", "de_de", "de_ch", "de_de", "fr_ca", "fr_fr"
  );

  private static Map<String, String> load(String language) throws IOException {
    InputStream stream = I18nTest.class.getResourceAsStream("/assets/statustag/i18n/" + language + ".json");
    assertNotNull(stream, "missing language file " + language);
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      Map<String, String> values = new LinkedHashMap<>();
      flatten(language, "", JsonParser.parseReader(reader), values);
      return values;
    }
  }

  private static void flatten(String language, String prefix, JsonElement element, Map<String, String> values) {
    if (element.isJsonObject()) {
      for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
        String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
        flatten(language, key, entry.getValue(), values);
      }
      return;
    }

    if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
      fail(language + ": " + prefix + " is not a string");
      return;
    }
    values.put(prefix, primitive.getAsString());
  }

  private static int placeholders(String value) {
    int count = 0;
    int index = value.indexOf("%s");
    while (index >= 0) {
      count++;
      index = value.indexOf("%s", index + 2);
    }
    return count;
  }

  @Test
  void regionalVariantsMatchTheirLanguage() throws IOException {
    for (Map.Entry<String, String> entry : REGIONAL.entrySet()) {
      assertEquals(load(entry.getValue()), load(entry.getKey()), entry.getKey() + " differs from " + entry.getValue());
    }
  }

  @Test
  void everyLanguageHasTheSameKeys() throws IOException {
    Map<String, String> base = load(BASE);
    for (String language : LANGUAGES) {
      Map<String, String> values = load(language);
      List<String> missing = new ArrayList<>(base.keySet());
      missing.removeAll(values.keySet());
      List<String> extra = new ArrayList<>(values.keySet());
      extra.removeAll(base.keySet());
      assertTrue(missing.isEmpty() && extra.isEmpty(),
          language + ": missing " + missing + ", extra " + extra);
    }
  }

  @Test
  void placeholdersMatchEnglish() throws IOException {
    Map<String, String> base = load(BASE);
    for (String language : LANGUAGES) {
      for (Map.Entry<String, String> entry : load(language).entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        String english = base.get(key);
        if (english != null) {
          assertEquals(placeholders(english), placeholders(value), language + ": " + key);
        }
        assertFalse(value.replace("%s", "").replace("%%", "").contains("%"),
            language + ": stray percent sign in " + key);
      }
    }
  }

  @Test
  void valuesAreNotEmptyOrPadded() throws IOException {
    for (String language : LANGUAGES) {
      for (Map.Entry<String, String> entry : load(language).entrySet()) {
        String value = entry.getValue();
        assertFalse(value.isEmpty(), language + ": empty " + entry.getKey());
        assertEquals(value.strip(), value, language + ": padded " + entry.getKey());
      }
    }
  }

  @Test
  void englishCoversKeysBuiltInCode() throws IOException {
    Map<String, String> base = load(BASE);
    List<String> expected = new ArrayList<>();
    for (StatusKind kind : StatusKind.values()) {
      expected.add("statustag.status." + kind.key());
    }
    for (String notice : new String[] {"online", "away", "busy", "offline", "joinedServer"}) {
      expected.add("statustag.notify." + notice);
    }
    for (StatusSource source : StatusSource.values()) {
      expected.add("statustag.command.source." + source.name().toLowerCase(Locale.ROOT));
    }
    expected.add("statustag.command.source.none");
    for (String part : new String[] {"name", "description", "label"}) {
      expected.add("statustag.hudWidget.status_overview." + part);
    }

    List<String> missing = new ArrayList<>(expected);
    missing.removeAll(base.keySet());
    assertTrue(missing.isEmpty(), "missing " + missing);
  }
}
