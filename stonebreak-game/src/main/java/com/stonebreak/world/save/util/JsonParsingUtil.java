package com.stonebreak.world.save.util;

import org.joml.Vector3f;
import org.joml.Vector2f;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        String pattern = "\"" + key + "\"\\s*:\\s*\\{[^}]+\"x\"\\s*:\\s*([\\d.\\-]+)[^}]+\"y\"\\s*:\\s*([\\d.\\-]+)[^}]+\"z\"\\s*:\\s*([\\d.\\-]+)";
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
}
