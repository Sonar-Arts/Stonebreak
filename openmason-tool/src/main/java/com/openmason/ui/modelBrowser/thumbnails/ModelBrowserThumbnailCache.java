package com.openmason.ui.modelBrowser.thumbnails;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages thumbnail texture cache for the Model Browser.
 */
public class ModelBrowserThumbnailCache {

    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserThumbnailCache.class);

    // Thumbnail sizes
    public static final int SIZE_LARGE = 64;  // Grid view
    public static final int SIZE_MEDIUM = 32; // List view
    public static final int SIZE_SMALL = 16;  // Compact view

    // Cache storage: key -> texture ID
    private final Map<String, Integer> textureCache;
    private final Map<String, Long> versionCache; // For invalidation

    /**
     * Creates a new thumbnail cache.
     */
    public ModelBrowserThumbnailCache() {
        this.textureCache = new HashMap<>();
        this.versionCache = new HashMap<>();
        logger.debug("ModelBrowserThumbnailCache initialized");
    }

    /**
     * Gets or creates a thumbnail texture.
     */
    public int getOrCreate(String key, ThumbnailGenerator generator) {
        return getOrCreate(key, 0, generator);
    }

    /**
     * Gets or creates a thumbnail texture with version checking.
     */
    public int getOrCreate(String key, long version, ThumbnailGenerator generator) {
        // Check if cached and version matches
        if (textureCache.containsKey(key)) {
            Long cachedVersion = versionCache.get(key);
            if (version == 0 || (cachedVersion != null && cachedVersion == version)) {
                return textureCache.get(key);
            } else {
                // Version mismatch, invalidate
                invalidate(key);
            }
        }

        // Generate new thumbnail
        try {
            int textureId = generator.generate();
            if (textureId > 0) {
                textureCache.put(key, textureId);
                versionCache.put(key, version);
                logger.trace("Generated thumbnail: {} (version {})", key, version);
                return textureId;
            } else {
                logger.warn("Failed to generate thumbnail: {}", key);
                return 0;
            }
        } catch (Exception e) {
            logger.error("Error generating thumbnail: {}", key, e);
            return 0;
        }
    }

    /**
     * Invalidates a cached thumbnail.
     *
     * @param key The thumbnail key to invalidate
     */
    public void invalidate(String key) {
        Integer textureId = textureCache.remove(key);
        versionCache.remove(key);

        if (textureId != null && textureId > 0) {
            GL11.glDeleteTextures(textureId);
            logger.trace("Invalidated thumbnail: {}", key);
        }
    }

    /**
     * Clears all cached thumbnails and frees GPU memory.
     */
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

    /**
     * Gets the number of cached thumbnails.
     */
    public int size() {
        return textureCache.size();
    }

    /**
     * Checks if a thumbnail is cached.
     */
    public boolean contains(String key) {
        return textureCache.containsKey(key);
    }

    /**
     * Generates a cache key for a block thumbnail.
     */
    public static String blockKey(String blockName, int size) {
        return "block_" + blockName + "_" + size;
    }

    /**
     * Generates a cache key for an item thumbnail.
     */
    public static String itemKey(String itemName, int size) {
        return "item_" + itemName + "_" + size;
    }

    /**
     * Generates a cache key for a model thumbnail.
     */
    public static String modelKey(String modelName, int size) {
        return "model_" + modelName + "_" + size;
    }

    /**
     * Functional interface for thumbnail generation.
     */
    @FunctionalInterface
    public interface ThumbnailGenerator {
        /**
         * Generates a thumbnail texture.
         */
        int generate();
    }

    /**
     * Cleans up resources when the cache is no longer needed.
     */
    public void cleanup() {
        clear();
        logger.debug("ModelBrowserThumbnailCache cleaned up");
    }
}
