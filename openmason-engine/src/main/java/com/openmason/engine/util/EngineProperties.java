package com.openmason.engine.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine system properties live under {@code openmason.<key>}. The legacy {@code stonebreak.<key>}
 * spelling is still honoured (the new name wins when both are set) and logs a one-time
 * deprecation warning per key.
 */
public final class EngineProperties {

    public static final String PREFIX = "openmason.";
    static final String LEGACY_PREFIX = "stonebreak.";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private EngineProperties() {
    }

    /** {@code openmason.<key>}, for messages that tell the user which flag to pass. */
    public static String name(String key) {
        return PREFIX + key;
    }

    /** The property value, or {@code def} when neither spelling is set. */
    public static String get(String key, String def) {
        String value = System.getProperty(PREFIX + key);
        if (value != null) {
            return value;
        }
        value = System.getProperty(LEGACY_PREFIX + key);
        if (value != null) {
            if (WARNED.add(key)) {
                System.err.println("[EngineProperties] -D" + LEGACY_PREFIX + key + " is deprecated; use -D"
                        + PREFIX + key);
            }
            return value;
        }
        return def;
    }

    /** Same contract as {@link Boolean#getBoolean}: true only when the value is "true". */
    public static boolean flag(String key) {
        return Boolean.parseBoolean(get(key, null));
    }

    /** Kill switch: true when the value is "off" or "false" (default on). */
    public static boolean isOff(String key) {
        String value = get(key, "on");
        return "off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }
}
