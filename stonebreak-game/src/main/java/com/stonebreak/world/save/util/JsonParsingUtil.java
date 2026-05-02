package com.stonebreak.world.save.util;

import org.joml.Vector3f;
import org.joml.Vector2f;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Shared utility for parsing JSON data manually (without Jackson).
 * Consolidates duplicate parsing logic from JsonWorldSerializer and JsonPlayerSerializer.
 */
public class JsonParsingUtil {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Escapes special characters in a string for JSON encoding.
     */
    public static String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    /**
     * Extracts a string value from JSON by key.
     */
    public static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalArgumentException("Missing or invalid key: " + key);
    }

    /**
     * Extracts an optional string value from JSON by key (returns null if not found).
     */
    public static String extractStringOptional(String json, String key) {
        try {
            return extractString(json, key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Extracts an int value from JSON by key.
     */
    public static int extractInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return defaultValue;
    }

    /**
     * Extracts an int value from a JSON object string.
     */
    public static int extractIntFromObject(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return defaultValue;
    }

    /**
     * Extracts a long value from JSON by key.
     */
    public static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalArgumentException("Missing or invalid key: " + key);
    }

    /**
     * Extracts a long value from JSON by key with default value.
     */
    public static long extractLong(String json, String key, long defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return defaultValue;
    }

    /**
     * Extracts a float value from JSON by key.
     */
    public static float extractFloat(String json, String key, float defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*([-\\d.]+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Float.parseFloat(m.group(1));
        }
        return defaultValue;
    }

    /**
     * Extracts a boolean value from JSON by key.
     */
    public static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return defaultValue;
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
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        throw new IllegalArgumentException("Missing or invalid key: " + key);
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
        // Pattern to match floating point numbers (including negative and decimals)
        String floatPattern = "(-?\\d+(?:\\.\\d+)?)";
        String pattern = "\"" + key + "\"\\s*:\\s*\\{\\s*\"x\"\\s*:\\s*" + floatPattern +
                        "\\s*,\\s*\"y\"\\s*:\\s*" + floatPattern +
                        "\\s*,\\s*\"z\"\\s*:\\s*" + floatPattern;
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
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
        String pattern = "\"" + key + "\"\\s*:\\s*\\{[^}]+\"x\"\\s*:\\s*([-\\d.]+)[^}]+\"y\"\\s*:\\s*([-\\d.]+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
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
        // Match the object block for this key
        String pattern = "\"" + key + "\"\\s*:\\s*\\{([^}]*)\\}";
        Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return result;
        }
        String content = m.group(1);
        // Match individual "key": value pairs
        Pattern entryPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");
        Matcher em = entryPattern.matcher(content);
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
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return result;
        }
        String content = m.group(1);
        Pattern itemPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher im = itemPattern.matcher(content);
        while (im.find()) {
            result.add(im.group(1));
        }
        return result;
    }

    // ===== Nested Object Extraction Methods =====

    /**
     * Extracts a string value from a nested JSON object.
     * Example: extractStringFromObject(json, "customData", "textureVariant")
     */
    public static String extractStringFromObject(String json, String objectKey, String valueKey) {
        String objectPattern = "\"" + objectKey + "\"\\s*:\\s*\\{([^}]+)\\}";
        Pattern p = Pattern.compile(objectPattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String objectContent = m.group(1);
            return extractString(objectContent, valueKey);
        }
        throw new IllegalArgumentException("Missing or invalid object key: " + objectKey);
    }

    /**
     * Extracts an int value from a nested JSON object.
     */
    public static int extractIntFromObject(String json, String objectKey, String valueKey) {
        String objectPattern = "\"" + objectKey + "\"\\s*:\\s*\\{([^}]+)\\}";
        Pattern p = Pattern.compile(objectPattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String objectContent = m.group(1);
            return extractInt(objectContent, valueKey, 0);
        }
        return 0;
    }

    /**
     * Extracts a double value from a nested JSON object.
     */
    public static double extractDoubleFromObject(String json, String objectKey, String valueKey) {
        String objectPattern = "\"" + objectKey + "\"\\s*:\\s*\\{([^}]+)\\}";
        Pattern p = Pattern.compile(objectPattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String objectContent = m.group(1);
            String pattern = "\"" + valueKey + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)";
            Pattern valuePattern = Pattern.compile(pattern);
            Matcher valueMatcher = valuePattern.matcher(objectContent);
            if (valueMatcher.find()) {
                return Double.parseDouble(valueMatcher.group(1));
            }
        }
        return 0.0;
    }

    /**
     * Extracts a boolean value from a nested JSON object.
     */
    public static boolean extractBooleanFromObject(String json, String objectKey, String valueKey) {
        String objectPattern = "\"" + objectKey + "\"\\s*:\\s*\\{([^}]+)\\}";
        Pattern p = Pattern.compile(objectPattern, Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String objectContent = m.group(1);
            return extractBoolean(objectContent, valueKey, false);
        }
        return false;
    }
}
