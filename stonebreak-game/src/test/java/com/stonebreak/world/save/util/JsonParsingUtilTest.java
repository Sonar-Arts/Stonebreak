package com.stonebreak.world.save.util;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonParsingUtilTest {

    /* ---------- extractString ---------- */

    @Test
    void extractString_returnsValue_whenPresent() {
        String json = "{\"name\":\"MyWorld\"}";
        assertEquals("MyWorld", JsonParsingUtil.extractString(json, "name"));
    }

    @Test
    void extractString_throwsWhenAbsent() {
        String json = "{\"other\":\"value\"}";
        assertThrows(IllegalArgumentException.class, () -> JsonParsingUtil.extractString(json, "name"));
    }

    /**
     * Characterization: the regex [^"]+ requires at least one character,
     * so an empty-string value is treated as absent and throws.
     */
    @Test
    void extractString_treatsEmptyStringAsAbsent_andThrows() {
        String json = "{\"name\":\"\"}";
        assertThrows(IllegalArgumentException.class, () -> JsonParsingUtil.extractString(json, "name"));
    }

    /* ---------- extractStringOptional ---------- */

    @Test
    void extractStringOptional_returnsNull_whenAbsent() {
        String json = "{\"other\":\"value\"}";
        assertNull(JsonParsingUtil.extractStringOptional(json, "name"));
    }

    @Test
    void extractStringOptional_returnsValue_whenPresent() {
        String json = "{\"name\":\"MyWorld\"}";
        assertEquals("MyWorld", JsonParsingUtil.extractStringOptional(json, "name"));
    }

    /* ---------- extractInt / extractFloat / extractBoolean / extractLong (3-arg) ---------- */

    @Test
    void extractInt_returnsDefault_whenAbsent() {
        String json = "{}";
        assertEquals(99, JsonParsingUtil.extractInt(json, "level", 99));
    }

    @Test
    void extractInt_parsesValue_whenPresent() {
        String json = "{\"level\": 42}";
        assertEquals(42, JsonParsingUtil.extractInt(json, "level", 0));
    }

    @Test
    void extractFloat_returnsDefault_whenAbsent() {
        String json = "{}";
        assertEquals(1.5f, JsonParsingUtil.extractFloat(json, "speed", 1.5f));
    }

    @Test
    void extractFloat_parsesValue_whenPresent() {
        String json = "{\"speed\": 3.14}";
        assertEquals(3.14f, JsonParsingUtil.extractFloat(json, "speed", 0f), 0.001f);
    }

    @Test
    void extractBoolean_returnsDefault_whenAbsent() {
        String json = "{}";
        assertEquals(true, JsonParsingUtil.extractBoolean(json, "active", true));
    }

    @Test
    void extractBoolean_parsesValue_whenPresent() {
        String json = "{\"active\": false}";
        assertFalse(JsonParsingUtil.extractBoolean(json, "active", true));
    }

    @Test
    void extractLong_3arg_returnsDefault_whenAbsent() {
        String json = "{}";
        assertEquals(100L, JsonParsingUtil.extractLong(json, "seed", 100L));
    }

    @Test
    void extractLong_3arg_parsesValue_whenPresent() {
        String json = "{\"seed\": 12345}";
        assertEquals(12345L, JsonParsingUtil.extractLong(json, "seed", 0L));
    }

    /* ---------- extractLong (2-arg) / extractDouble ---------- */

    @Test
    void extractLong_2arg_throwsWhenAbsent() {
        String json = "{}";
        assertThrows(IllegalArgumentException.class, () -> JsonParsingUtil.extractLong(json, "seed"));
    }

    @Test
    void extractLong_2arg_parsesValue_whenPresent() {
        String json = "{\"seed\": 12345}";
        assertEquals(12345L, JsonParsingUtil.extractLong(json, "seed"));
    }

    @Test
    void extractDouble_throwsWhenAbsent() {
        String json = "{}";
        assertThrows(IllegalArgumentException.class, () -> JsonParsingUtil.extractDouble(json, "value"));
    }

    @Test
    void extractDouble_parsesValue_whenPresent() {
        String json = "{\"value\": 2.5}";
        assertEquals(2.5, JsonParsingUtil.extractDouble(json, "value"), 0.001);
    }

    /* ---------- extractDateTime ---------- */

    @Test
    void extractDateTime_parsesIsoLocalDateTime() {
        String json = "{\"ts\": \"2024-03-15T10:30:45\"}";
        LocalDateTime expected = LocalDateTime.of(2024, 3, 15, 10, 30, 45);
        assertEquals(expected, JsonParsingUtil.extractDateTime(json, "ts"));
    }

    /* ---------- extractVector3f ---------- */

    @Test
    void extractVector3f_returnsParsedVector_whenPresent() {
        String json = "{\"spawnPosition\": {\"x\": 1.0, \"y\": 64.0, \"z\": 3.0}}";
        Vector3f result = JsonParsingUtil.extractVector3f(json, "spawnPosition");
        assertEquals(new Vector3f(1f, 64f, 3f), result);
    }

    /**
     * Characterization: extractVector3f silently returns (0,100,0) when the key
     * is missing — no exception is thrown.
     */
    @Test
    void extractVector3f_returnsDefaultWhenMissing() {
        String json = "{\"other\": \"value\"}";
        Vector3f result = JsonParsingUtil.extractVector3f(json, "spawnPosition");
        assertEquals(new Vector3f(0f, 100f, 0f), result);
    }

    /* ---------- extractVector2f ---------- */

    @Test
    void extractVector2f_returnsDefaultWhenMissing() {
        String json = "{\"other\": \"value\"}";
        Vector2f result = JsonParsingUtil.extractVector2f(json, "rotation");
        assertEquals(new Vector2f(0f, 0f), result);
    }

    /* ---------- extractStringIntMap / extractStringSet ---------- */

    @Test
    void extractStringIntMap_returnsEmptyMap_whenAbsent() {
        String json = "{\"other\": \"value\"}";
        Map<String, Integer> result = JsonParsingUtil.extractStringIntMap(json, "skills");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractStringIntMap_parsesContents_whenPresent() {
        String json = "{\"skills\": {\"mining\": 3, \"logging\": 5}}";
        Map<String, Integer> result = JsonParsingUtil.extractStringIntMap(json, "skills");
        assertEquals(2, result.size());
        assertEquals(3, result.get("mining"));
        assertEquals(5, result.get("logging"));
    }

    @Test
    void extractStringSet_returnsEmptySet_whenAbsent() {
        String json = "{\"other\": \"value\"}";
        Set<String> result = JsonParsingUtil.extractStringSet(json, "feats");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractStringSet_parsesContents_whenPresent() {
        String json = "{\"feats\": [\"a\", \"b\"]}";
        Set<String> result = JsonParsingUtil.extractStringSet(json, "feats");
        assertEquals(2, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("b"));
    }

    /* ---------- Nested Object Extraction ---------- */

    @Test
    void extractStringFromObject_throwsWhenObjectAbsent() {
        String json = "{\"other\": \"value\"}";
        assertThrows(IllegalArgumentException.class,
            () -> JsonParsingUtil.extractStringFromObject(json, "customData", "textureVariant"));
    }

    @Test
    void extractIntFromObject_returnsDefaultWhenObjectAbsent() {
        // Note: the actual method signature is extractIntFromObject(json, key, defaultValue).
        // When the key is not found in the JSON, it returns the provided default.
        String json = "{\"other\": \"value\"}";
        assertEquals(0, JsonParsingUtil.extractIntFromObject(json, "missingKey", 0));
    }

    @Test
    void extractDoubleFromObject_returnsZeroWhenObjectAbsent() {
        String json = "{\"other\": \"value\"}";
        assertEquals(0.0, JsonParsingUtil.extractDoubleFromObject(json, "customData", "scale"), 0.0);
    }

    @Test
    void extractBooleanFromObject_returnsFalseWhenObjectAbsent() {
        String json = "{\"other\": \"value\"}";
        assertFalse(JsonParsingUtil.extractBooleanFromObject(json, "customData", "enabled"));
    }

    /* ---------- escapeJson ---------- */

    @Test
    void escapeJson_escapesBackslash() {
        assertEquals("\\\\", JsonParsingUtil.escapeJson("\\"));
    }

    @Test
    void escapeJson_escapesQuote() {
        assertEquals("\\\"", JsonParsingUtil.escapeJson("\""));
    }

    @Test
    void escapeJson_escapesNewline() {
        assertEquals("\\n", JsonParsingUtil.escapeJson("\n"));
    }

    @Test
    void escapeJson_escapesCarriageReturn() {
        assertEquals("\\r", JsonParsingUtil.escapeJson("\r"));
    }

    /**
     * Characterization: escapeJson does NOT escape tab characters.
     */
    @Test
    void escapeJson_leavesTabUntouched() {
        assertEquals("\t", JsonParsingUtil.escapeJson("\t"));
    }

    @Test
    void escapeJson_throwsOnNull() {
        assertThrows(NullPointerException.class, () -> JsonParsingUtil.escapeJson(null));
    }

    /* ---------- Characterization quirks ---------- */

    /**
     * Characterization: key matching is document-wide regex — a key inside a
     * nested object earlier in the string is matched even though it is not
     * top-level. Here the nested "level": 42 wins over the top-level "level": 7.
     */
    @Test
    void documentWideKeyMatchingIsNotScoped() {
        String json = "{\"nested\": {\"level\": 42}, \"level\": 7}";
        assertEquals(42, JsonParsingUtil.extractInt(json, "level", 0));
    }

    /**
     * Characterization: extractFloat uses the pattern [-\\d.]+ which does not
     * handle scientific notation — "1.0E-5" is parsed as "1.0" (exponent ignored).
     */
    @Test
    void scientificNotationIsMisparsed() {
        assertEquals(1.0f, JsonParsingUtil.extractFloat("{\"h\": 1.0E-5}", "h", 0f), 0.001f);
    }

    /* ---------- extractVector3f with scientific notation (characterization) ---------- */

    /**
     * Characterization: extractVector3f's float pattern does not support scientific
     * notation, so components like 1.0E-5 cause the whole pattern to fail and the
     * (0,100,0) default is returned silently.
     */
    @Test
    void extractVector3f_scientificNotationComponents_returnDefault() {
        String json = "{\"pos\": {\"x\": 1.0E-5, \"y\": 2.0E-3, \"z\": 3.0E-1}}";
        Vector3f result = JsonParsingUtil.extractVector3f(json, "pos");
        assertEquals(new Vector3f(0f, 100f, 0f), result);
    }
}