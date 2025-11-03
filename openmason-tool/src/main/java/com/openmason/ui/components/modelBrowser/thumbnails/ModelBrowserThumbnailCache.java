package com.openmason.ui.components.modelBrowser.thumbnails;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages thumbnail texture cache for the Model Browser.
 *
 * <p>Adapted from LayerThumbnailCache, this cache stores OpenGL texture IDs
 * for block, item, and model thumbnails. Uses version-based invalidation and
 * memory-efficient cleanup.</p>
 *
 * <p>Thumbnail sizes:</p>
 * <ul>
 *   <li>Grid view: 64x64 pixels</li>
 *   <li>List view: 32x32 pixels</li>
 *   <li>Compact view: 16x16 pixels</li>
 * </ul>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Only manages thumbnail textures</li>
 *   <li><strong>Open/Closed</strong>: Easy to add new thumbnail types</li>
 * </ul>
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
     *
     * @param key Unique key for the thumbnail (e.g., "block_grass_64")
     * @param generator Function to generate the texture if not cached
     * @return OpenGL texture ID
     */
    public int getOrCreate(String key, ThumbnailGenerator generator) {
        return getOrCreate(key, 0, generator);
    }

    /**
     * Gets or creates a thumbnail texture with version checking.
     *
     * @param key Unique key for the thumbnail
     * @param version Version number for cache invalidation (0 = no versioning)
     * @param generator Function to generate the texture if not cached
     * @return OpenGL texture ID
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
            logger.error("Error generating thumbnail: " + key, e);
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
     *
     * @return Cache size
     */
    public int size() {
        return textureCache.size();
    }

    /**
     * Checks if a thumbnail is cached.
     *
     * @param key The thumbnail key
     * @return true if cached
     */
    public boolean contains(String key) {
        return textureCache.containsKey(key);
    }

    /**
     * Generates a cache key for a block thumbnail.
     *
     * @param blockName Block name
     * @param size Thumbnail size
     * @return Cache key
     */
    public static String blockKey(String blockName, int size) {
        return "block_" + blockName + "_" + size;
    }

    /**
     * Generates a cache key for an item thumbnail.
     *
     * @param itemName Item name
     * @param size Thumbnail size
     * @return Cache key
     */
    public static String itemKey(String itemName, int size) {
        return "item_" + itemName + "_" + size;
    }

    /**
     * Generates a cache key for a model thumbnail.
     *
     * @param modelName Model name
     * @param size Thumbnail size
     * @return Cache key
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
         *
         * @return OpenGL texture ID, or 0 if failed
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
