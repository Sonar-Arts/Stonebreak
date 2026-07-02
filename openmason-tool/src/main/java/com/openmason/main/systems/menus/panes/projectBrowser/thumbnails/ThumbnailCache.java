package com.openmason.main.systems.menus.panes.projectBrowser.thumbnails;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages thumbnail texture cache for the Project Browser.
 */
public class ThumbnailCache {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailCache.class);

    // Thumbnail sizes
    public static final int SIZE_LARGE = 64;  // Grid view
    public static final int SIZE_MEDIUM = 32; // List view
    public static final int SIZE_SMALL = 16;  // Compact view

    // Cache storage: key -> texture ID
    private final Map<String, Integer> textureCache = new HashMap<>();
    private final Map<String, Long> versionCache = new HashMap<>(); // For invalidation

    /**
     * Gets or creates a thumbnail texture with version checking (version 0
     * skips the check).
     */
    public int getOrCreate(String key, long version, ThumbnailGenerator generator) {
        if (textureCache.containsKey(key)) {
            Long cachedVersion = versionCache.get(key);
            if (version == 0 || (cachedVersion != null && cachedVersion == version)) {
                return textureCache.get(key);
            }
            invalidate(key); // version mismatch
        }

        try {
            int textureId = generator.generate();
            if (textureId > 0) {
                textureCache.put(key, textureId);
                versionCache.put(key, version);
                logger.trace("Generated thumbnail: {} (version {})", key, version);
                return textureId;
            }
            logger.warn("Failed to generate thumbnail: {}", key);
            return 0;
        } catch (Exception e) {
            logger.error("Error generating thumbnail: {}", key, e);
            return 0;
        }
    }

    /** Invalidates a cached thumbnail and frees its GL texture. */
    public void invalidate(String key) {
        Integer textureId = textureCache.remove(key);
        versionCache.remove(key);
        if (textureId != null && textureId > 0) {
            GL11.glDeleteTextures(textureId);
            logger.trace("Invalidated thumbnail: {}", key);
        }
    }

    /** Clears all cached thumbnails and frees GPU memory. */
    public void clear() {
        for (Integer textureId : textureCache.values()) {
            if (textureId > 0) {
                GL11.glDeleteTextures(textureId);
            }
        }
        textureCache.clear();
        versionCache.clear();
        logger.debug("Cleared all thumbnails");
    }

    /** Cache key for a .OMO model thumbnail. */
    public static String omoKey(String filePath, int size) {
        return "omo_" + filePath + "_" + size;
    }

    /** Cache key for a .OMT texture thumbnail. */
    public static String omtKey(String filePath, int size) {
        return "omt_" + filePath + "_" + size;
    }

    /** Functional interface for thumbnail generation. */
    @FunctionalInterface
    public interface ThumbnailGenerator {
        int generate();
    }

    /** Cleans up resources when the cache is no longer needed. */
    public void cleanup() {
        clear();
        logger.debug("ThumbnailCache cleaned up");
    }
}
