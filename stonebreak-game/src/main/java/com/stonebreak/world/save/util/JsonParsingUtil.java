package com.stonebreak.world.save.util;

import org.joml.Vector3f;
import org.joml.Vector2f;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Shared utility for parsing JSON data manually (without Jackson).
 * Consolidates duplicate parsing logic from JsonWorldSerializer and JsonPlayerSerializer.
 *
 * <p>Compiled patterns are cached per key, since the same small set of keys is
 * queried for every entity/player/world record that is saved or loaded.
 */
public class JsonParsingUtil {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // Key-independent patterns, compiled once.
    private static final Pattern QUOTED_ITEM = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern STRING_INT_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");

    // Per-key pattern caches. Keys are a small, fixed set of literals, so these stay tiny.
    private static final PatternCache STRING = new PatternCache(k -> k + "\\s*:\\s*\"([^\"]+)\"", 0);
    private static final PatternCache INTEGER = new PatternCache(k -> k + "\\s*:\\s*(-?\\d+)", 0);
    private static final PatternCache FLOAT = new PatternCache(k -> k + "\\s*:\\s*([-\\d.]+)", 0);
    private static final PatternCache BOOLEAN = new PatternCache(k -> k + "\\s*:\\s*(true|false)", 0);
    private static final PatternCache DECIMAL = new PatternCache(k -> k + "\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", 0);
    private static final PatternCache VECTOR3 = new PatternCache(k -> {
        String f = "(-?\\d+(?:\\.\\d+)?)";
        return k + "\\s*:\\s*\\{\\s*\"x\"\\s*:\\s*" + f
                + "\\s*,\\s*\"y\"\\s*:\\s*" + f
                + "\\s*,\\s*\"z\"\\s*:\\s*" + f;
    }, 0);
    private static final PatternCache VECTOR2 = new PatternCache(
            k -> k + "\\s*:\\s*\\{[^}]+\"x\"\\s*:\\s*([-\\d.]+)[^}]+\"y\"\\s*:\\s*([-\\d.]+)", 0);
    private static final PatternCache OBJECT_BODY = new PatternCache(k -> k + "\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final PatternCache NON_EMPTY_OBJECT_BODY = new PatternCache(k -> k + "\\s*:\\s*\\{([^}]+)\\}", Pattern.DOTALL);
    private static final PatternCache ARRAY_BODY = new PatternCache(k -> k + "\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);

    /**
     * Escapes special characters in a string for JSON encoding.
     */
    public static String escapeJson(String str) {
        int len = str.length();
        int i = 0;
        // Fast path: most strings need no escaping, so return them without copying.
        while (i < len && !needsEscape(str.charAt(i))) {
            i++;
        }
        if (i == len) {
            return str;
        }
        StringBuilder sb = new StringBuilder(len + 16);
        sb.append(str, 0, i);
        for (; i < len; i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean needsEscape(char c) {
        return c == '\\' || c == '"' || c == '\n' || c == '\r';
    }

    /**
     * Extracts a string value from JSON by key.
     */
    public static String extractString(String json, String key) {
        String value = findGroup(STRING, json, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing or invalid key: " + key);
        }
        return value;
    }

    /**
     * Extracts an optional string value from JSON by key (returns null if not found).
     */
    public static String extractStringOptional(String json, String key) {
        return findGroup(STRING, json, key);
    }

    /**
     * Extracts an int value from JSON by key.
     */
    public static int extractInt(String json, String key, int defaultValue) {
        String value = findGroup(INTEGER, json, key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    /**
     * Extracts an int value from a JSON object string.
     */
    public static int extractIntFromObject(String json, String key, int defaultValue) {
        return extractInt(json, key, defaultValue);
    }

    /**
     * Extracts a long value from JSON by key.
     */
    public static long extractLong(String json, String key) {
        String value = findGroup(INTEGER, json, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing or invalid key: " + key);
        }
        return Long.parseLong(value);
    }

    /**
     * Extracts a long value from JSON by key with default value.
     */
    public static long extractLong(String json, String key, long defaultValue) {
        String value = findGroup(INTEGER, json, key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    /**
     * Extracts a float value from JSON by key.
     */
    public static float extractFloat(String json, String key, float defaultValue) {
        String value = findGroup(FLOAT, json, key);
        return value != null ? Float.parseFloat(value) : defaultValue;
    }

    /**
     * Extracts a boolean value from JSON by key.
     */
    public static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String value = findGroup(BOOLEAN, json, key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    /**
     * Extracts a boolean value from JSON by key (no default).
     */
    public static boolean extractBoolean(String json, String key) {
        return extractBoolean(json, key, false);
    }

    /**
     * Extracts a double value from JSON by key.
     */
    public static double extractDouble(String json, String key) {
        String value = findGroup(DECIMAL, json, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing or invalid key: " + key);
        }
        return Double.parseDouble(value);
    }

    /**
     * Extracts a LocalDateTime value from JSON by key.
     */
    public static LocalDateTime extractDateTime(String json, String key) {
        String value = extractString(json, key);
        return LocalDateTime.parse(value, FORMATTER);
    }

    /**
     * Extracts a Vector3f value from JSON by key.
     */
    public static Vector3f extractVector3f(String json, String key) {
        Matcher m = VECTOR3.get(key).matcher(json);
        if (m.find()) {
            float x = Float.parseFloat(m.group(1));
            float y = Float.parseFloat(m.group(2));
            float z = Float.parseFloat(m.group(3));
            return new Vector3f(x, y, z);
        }
        return new Vector3f(0, 100, 0); // Default position
    }

    /**
     * Extracts a Vector2f value from JSON by key.
     */
    public static Vector2f extractVector2f(String json, String key) {
        Matcher m = VECTOR2.get(key).matcher(json);
        if (m.find()) {
            float x = Float.parseFloat(m.group(1));
            float y = Float.parseFloat(m.group(2));
            return new Vector2f(x, y);
        }
        return new Vector2f(0, 0); // Default rotation
    }

    // ===== Collection Extraction Methods =====

    /**
     * Extracts a JSON object as a Map&lt;String, Integer&gt;.
     * Handles: "key": { "k1": 1, "k2": 2 }
     * Returns empty map if key not found.
     */
    public static Map<String, Integer> extractStringIntMap(String json, String key) {
        Map<String, Integer> result = new HashMap<>();
        String content = findGroup(OBJECT_BODY, json, key);
        if (content == null) {
            return result;
        }
        Matcher em = STRING_INT_ENTRY.matcher(content);
        while (em.find()) {
            result.put(em.group(1), Integer.parseInt(em.group(2)));
        }
        return result;
    }

    /**
     * Extracts a JSON array as a Set&lt;String&gt;.
     * Handles: "key": ["a", "b", "c"]
     * Returns empty set if key not found.
     */
    public static Set<String> extractStringSet(String json, String key) {
        Set<String> result = new HashSet<>();
        String content = findGroup(ARRAY_BODY, json, key);
        if (content != null) {
            collectQuotedItems(content, result);
        }
        return result;
    }

    /**
     * Extracts a JSON array of strings, keeping its order.
     * Returns null if key not found (so callers can tell "absent" from "empty").
     */
    public static List<String> extractStringListOrNull(String json, String key) {
        String content = findGroup(ARRAY_BODY, json, key);
        if (content == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        collectQuotedItems(content, result);
        return result;
    }

    // ===== Nested Object Extraction Methods =====

    /**
     * Extracts a string value from a nested JSON object.
     * Example: extractStringFromObject(json, "customData", "textureVariant")
     */
    public static String extractStringFromObject(String json, String objectKey, String valueKey) {
        String objectContent = findGroup(NON_EMPTY_OBJECT_BODY, json, objectKey);
        if (objectContent == null) {
            throw new IllegalArgumentException("Missing or invalid object key: " + objectKey);
        }
        return extractString(objectContent, valueKey);
    }

    /**
     * Extracts an int value from a nested JSON object.
     */
    public static int extractIntFromObject(String json, String objectKey, String valueKey) {
        String objectContent = findGroup(NON_EMPTY_OBJECT_BODY, json, objectKey);
        return objectContent != null ? extractInt(objectContent, valueKey, 0) : 0;
    }

    /**
     * Extracts a double value from a nested JSON object.
     */
    public static double extractDoubleFromObject(String json, String objectKey, String valueKey) {
        String objectContent = findGroup(NON_EMPTY_OBJECT_BODY, json, objectKey);
        if (objectContent == null) {
            return 0.0;
        }
        String value = findGroup(DECIMAL, objectContent, valueKey);
        return value != null ? Double.parseDouble(value) : 0.0;
    }

    /**
     * Extracts a boolean value from a nested JSON object.
     */
    public static boolean extractBooleanFromObject(String json, String objectKey, String valueKey) {
        String objectContent = findGroup(NON_EMPTY_OBJECT_BODY, json, objectKey);
        return objectContent != null && extractBoolean(objectContent, valueKey, false);
    }

    // ===== Internals =====

    /** Returns capture group 1 of the first match of the key's pattern, or null if absent. */
    private static String findGroup(PatternCache cache, String json, String key) {
        Matcher m = cache.get(key).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void collectQuotedItems(String content, Collection<String> out) {
        Matcher im = QUOTED_ITEM.matcher(content);
        while (im.find()) {
            out.add(im.group(1));
        }
    }

    /**
     * Lazily compiles and caches one regex per JSON key. The key is matched literally
     * (quoted), and the template receives the already-quoted {@code "key"} prefix.
     */
    private static final class PatternCache {
        private final Function<String, String> template;
        private final int flags;
        private final Map<String, Pattern> cache = new ConcurrentHashMap<>();

        PatternCache(Function<String, String> template, int flags) {
            this.template = template;
            this.flags = flags;
        }

        Pattern get(String key) {
            Pattern p = cache.get(key);
            if (p == null) {
                p = cache.computeIfAbsent(key, k ->
                        Pattern.compile(template.apply(Pattern.quote("\"" + k + "\"")), flags));
            }
            return p;
        }
    }
}
