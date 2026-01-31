package com.openmason.main.systems.menus.textureCreator.panels;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Layer thumbnail cache with version-based invalidation.
 */
public class LayerThumbnailCache {

    private static final Logger logger = LoggerFactory.getLogger(LayerThumbnailCache.class);

    private static final int THUMBNAIL_SIZE = 64;
    private static final int CHECKERBOARD_SQUARE_SIZE = 8;

    // Cache: layer index -> cached thumbnail
    private final Map<Integer, CachedThumbnail> cache = new HashMap<>();

    /**
         * Cached thumbnail entry with OpenGL texture and version tracking.
         */
        private record CachedThumbnail(int textureId, long layerVersion) {
    }

    /**
     * Get thumbnail texture for a layer.
     */
    public int getThumbnail(Layer layer, int layerIndex) {
        if (layer == null) {
            logger.warn("Null layer passed to getThumbnail at index {}", layerIndex);
            return 0;
        }

        PixelCanvas canvas = layer.getCanvas();
        long currentVersion = canvas.getModificationVersion();

        // Check cache
        CachedThumbnail cached = cache.get(layerIndex);
        if (cached != null && cached.layerVersion == currentVersion) {
            // Cache hit - reuse existing texture
            return cached.textureId;
        }

        // Cache miss or outdated - regenerate thumbnail
        if (cached != null) {
            // Delete old texture
            GL11.glDeleteTextures(cached.textureId);
        }

        int newTextureId = createThumbnailTexture(canvas);
        cache.put(layerIndex, new CachedThumbnail(newTextureId, currentVersion));

        return newTextureId;
    }

    /**
     * Invalidate all cache entries.
     * Useful when layer indices change (reordering, deletion).
     */
    public void invalidateAll() {
        for (CachedThumbnail cached : cache.values()) {
            GL11.glDeleteTextures(cached.textureId);
        }
        cache.clear();
    }

    /**
     * Cleanup all OpenGL resources.
     * Must be called when cache is no longer needed.
     */
    public void cleanup() {
        int count = cache.size();
        for (CachedThumbnail cached : cache.values()) {
            GL11.glDeleteTextures(cached.textureId);
        }
        cache.clear();
        logger.debug("Cleaned up {} cached thumbnails", count);
    }

    /**
     * Create thumbnail texture from canvas data.
     */
    private int createThumbnailTexture(PixelCanvas canvas) {
        int textureId = GL11.glGenTextures();

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();

        // Determine scaling - fit canvas into thumbnail size
        float scale = Math.min((float) THUMBNAIL_SIZE / canvasWidth, (float) THUMBNAIL_SIZE / canvasHeight);
        int scaledWidth = (int) (canvasWidth * scale);
        int scaledHeight = (int) (canvasHeight * scale);

        // Create buffer for thumbnail
        ByteBuffer buffer = MemoryUtil.memAlloc(THUMBNAIL_SIZE * THUMBNAIL_SIZE * 4);

        // Fill with checkerboard and layer content
        for (int y = 0; y < THUMBNAIL_SIZE; y++) {
            for (int x = 0; x < THUMBNAIL_SIZE; x++) {
                int r, g, b, a;

                // Check if this pixel is within the scaled canvas bounds
                if (x < scaledWidth && y < scaledHeight) {
                    // Map to source canvas coordinates
                    int srcX = (int) (x / scale);
                    int srcY = (int) (y / scale);

                    // Clamp to canvas bounds
                    srcX = Math.min(srcX, canvasWidth - 1);
                    srcY = Math.min(srcY, canvasHeight - 1);

                    // Get canvas pixel
                    int canvasPixel = canvas.getPixel(srcX, srcY);
                    int[] canvasRGBA = PixelCanvas.unpackRGBA(canvasPixel);

                    // Determine checkerboard color for this position
                    int squareX = x / CHECKERBOARD_SQUARE_SIZE;
                    int squareY = y / CHECKERBOARD_SQUARE_SIZE;
                    boolean isLight = (squareX + squareY) % 2 == 0;
                    int bgR = isLight ? 204 : 153;
                    int bgG = isLight ? 204 : 153;
                    int bgB = isLight ? 204 : 153;

                    // Alpha blend canvas over checkerboard
                    float alpha = canvasRGBA[3] / 255.0f;
                    float invAlpha = 1.0f - alpha;

                    r = (int) (canvasRGBA[0] * alpha + bgR * invAlpha);
                    g = (int) (canvasRGBA[1] * alpha + bgG * invAlpha);
                    b = (int) (canvasRGBA[2] * alpha + bgB * invAlpha);
                    a = 255; // Fully opaque
                } else {
                    // Outside canvas bounds - just checkerboard
                    int squareX = x / CHECKERBOARD_SQUARE_SIZE;
                    int squareY = y / CHECKERBOARD_SQUARE_SIZE;
                    boolean isLight = (squareX + squareY) % 2 == 0;
                    r = g = b = isLight ? 204 : 153;
                    a = 255;
                }

                buffer.put((byte) r);
                buffer.put((byte) g);
                buffer.put((byte) b);
                buffer.put((byte) a);
            }
        }
        buffer.flip();

        // Upload to OpenGL with LINEAR filtering for better quality
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, THUMBNAIL_SIZE, THUMBNAIL_SIZE,
            0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        MemoryUtil.memFree(buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }

}
