package com.stonebreak.rpg.classes;

import io.github.humbleui.skija.Image;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Loads and caches small ability icons referenced by {@link ClassAbility#iconPath()}. */
public final class AbilityIconCache {

    private static final Map<String, Image> CACHE = new HashMap<>();

    private AbilityIconCache() {}

    /** Returns the cached icon for {@code resourcePath}, or {@code null} if missing/unloadable. */
    public static Image get(String resourcePath) {
        if (resourcePath == null) return null;
        return CACHE.computeIfAbsent(resourcePath, path -> {
            try (InputStream in = AbilityIconCache.class.getResourceAsStream(path)) {
                if (in == null) {
                    System.err.println("[AbilityIconCache] Missing resource: " + path);
                    return null;
                }
                return Image.makeFromEncoded(in.readAllBytes());
            } catch (IOException e) {
                System.err.println("[AbilityIconCache] Failed to load " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
}
