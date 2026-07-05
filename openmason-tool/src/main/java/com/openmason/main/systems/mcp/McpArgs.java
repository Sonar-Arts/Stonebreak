package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared MCP tool-argument parsing helpers.
 *
 * <p>Superset of the parsers formerly duplicated across the per-domain
 * {@code *ToolDefinitions} classes. All throw {@link IllegalArgumentException}
 * with messages the request router surfaces to the caller as
 * {@code isError:true} tool results.
 */
public final class McpArgs {

    private McpArgs() {
    }

    public static String reqString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + key);
        }
        return n.asText();
    }

    /** Optional string; null when absent or blank. */
    public static String optString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    public static float reqFloat(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required numeric argument: " + key);
        }
        return n.floatValue();
    }

    /** Optional boxed float; null when absent. */
    public static Float optFloatBoxed(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? null : n.floatValue();
    }

    public static int reqInt(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required integer argument: " + key);
        }
        return n.intValue();
    }

    public static int optInt(JsonNode args, String key, int fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.intValue();
    }

    public static double optDouble(JsonNode args, String key, double fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.doubleValue();
    }

    public static boolean reqBool(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isBoolean()) {
            throw new IllegalArgumentException("Missing required boolean argument: " + key);
        }
        return n.asBoolean();
    }

    public static boolean optBool(JsonNode args, String key, boolean fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isBoolean()) ? fallback : n.asBoolean();
    }

    /** Optional flat float array; null when absent. */
    public static float[] optFloatArray(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isArray()) return null;
        float[] out = new float[n.size()];
        for (int i = 0; i < n.size(); i++) {
            JsonNode item = n.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(key + "[" + i + "] is not a number");
            }
            out[i] = item.floatValue();
        }
        return out;
    }

    public static int[] reqIntArray(JsonNode args, String key) {
        int[] out = optIntArray(args, key);
        if (out == null || out.length == 0) {
            throw new IllegalArgumentException("Missing required non-empty array argument: " + key);
        }
        return out;
    }

    /** Optional flat int array; null when absent. */
    public static int[] optIntArray(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isArray()) return null;
        int[] out = new int[n.size()];
        for (int i = 0; i < n.size(); i++) {
            JsonNode item = n.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(key + "[" + i + "] is not a number");
            }
            out[i] = item.intValue();
        }
        return out;
    }

    /** Parse an optional [x,y,z] array argument; null when absent. */
    public static Vector3f optVec3(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isArray() || n.size() != 3
                || !n.get(0).isNumber() || !n.get(1).isNumber() || !n.get(2).isNumber()) {
            throw new IllegalArgumentException(key + " must be a [x,y,z] number array");
        }
        return new Vector3f(n.get(0).floatValue(), n.get(1).floatValue(), n.get(2).floatValue());
    }

    public static Vector3f reqVec3(JsonNode args, String key) {
        Vector3f v = optVec3(args, key);
        if (v == null) throw new IllegalArgumentException("Missing required [x,y,z] argument: " + key);
        return v;
    }

    /** Optional string-array argument; empty list when absent (blank entries dropped). */
    public static List<String> optStringList(JsonNode args, String key) {
        List<String> out = new ArrayList<>();
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return out;
        if (!n.isArray()) {
            throw new IllegalArgumentException(key + " must be a string array");
        }
        for (JsonNode item : n) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    /** Parse a required [r,g,b,a] array argument. */
    public static int[] reqRgba(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isArray() || n.size() != 4) {
            throw new IllegalArgumentException("Missing required [r,g,b,a] argument: " + key);
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonNode c = n.get(i);
            if (c == null || !c.isNumber()) {
                throw new IllegalArgumentException(key + "[" + i + "] is not a number");
            }
            out[i] = c.intValue();
        }
        return out;
    }

    /** Element of a flat number array, with the array's name used in error messages. */
    public static int intAt(JsonNode arr, int index, String arrayName) {
        JsonNode n = arr.get(index);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException(arrayName + "[" + index + "] is not a number");
        }
        return n.intValue();
    }
}
