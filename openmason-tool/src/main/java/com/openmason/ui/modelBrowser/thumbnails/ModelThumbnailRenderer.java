package com.openmason.ui.modelBrowser.thumbnails;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Renders thumbnails for 3D models.
 *
 * <p>Currently uses placeholder thumbnails showing a generic 3D cube icon.
 * Future enhancement: Render actual 3D model to texture using mini viewport.</p>
 */
public class ModelThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ModelThumbnailRenderer.class);

    // Placeholder colors
    private static final int BACKGROUND_COLOR = 0xFFEEEEEE;
    private static final int CUBE_FACE_1 = 0xFF8BC34A; // Light green
    private static final int CUBE_FACE_2 = 0xFF689F38; // Medium green
    private static final int CUBE_FACE_3 = 0xFF558B2F; // Dark green
    private static final int BORDER_COLOR = 0xFF666666;

    private final ModelBrowserThumbnailCache cache;

    /**
     * Creates a new model thumbnail renderer.
     *
     * @param cache The thumbnail cache to use
     */
    public ModelThumbnailRenderer(ModelBrowserThumbnailCache cache) {
        this.cache = cache;
    }

    /**
     * Gets or generates a thumbnail for a model.
     *
     * @param modelName The model name
     * @param size The thumbnail size (64, 32, or 16)
     * @return OpenGL texture ID
     */
    public int getThumbnail(String modelName, int size) {
        String key = ModelBrowserThumbnailCache.modelKey(modelName, size);
        return cache.getOrCreate(key, () -> generatePlaceholder(modelName, size));
    }

    /**
     * Generates a placeholder thumbnail showing a 3D cube icon.
     * TODO: Replace with actual 3D model rendering.
     */
    private int generatePlaceholder(String modelName, int size) {
        try {
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);

            // Fill background
            fillSolid(pixels, size, BACKGROUND_COLOR);

            // Draw border
            drawBorder(pixels, size, BORDER_COLOR);

            // Draw isometric cube icon
            drawCubeIcon(pixels, size);

            pixels.flip();

            return createTexture(pixels, size);

        } catch (Exception e) {
            logger.error("Failed to generate thumbnail for model: " + modelName, e);
            return 0;
        }
    }

    /**
     * Fills the buffer with a solid color.
     */
    private void fillSolid(ByteBuffer pixels, int size, int color) {
        for (int i = 0; i < size * size; i++) {
            putPixel(pixels, color);
        }
    }

    /**
     * Draws a border around the thumbnail.
     */
    private void drawBorder(ByteBuffer pixels, int size, int borderColor) {
        pixels.position(0);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    pixels.position((y * size + x) * 4);
                    putPixel(pixels, borderColor);
                }
            }
        }
    }

    /**
     * Draws a simple isometric cube icon in the center.
     */
    private void drawCubeIcon(ByteBuffer pixels, int size) {
        int centerX = size / 2;
        int centerY = size / 2;
        int cubeSize = size / 3;

        // Simple isometric cube representation
        // Top face
        for (int y = centerY - cubeSize / 2; y < centerY; y++) {
            for (int x = centerX - cubeSize / 2; x < centerX + cubeSize / 2; x++) {
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    pixels.position((y * size + x) * 4);
                    putPixel(pixels, CUBE_FACE_1);
                }
            }
        }

        // Left face
        for (int y = centerY; y < centerY + cubeSize; y++) {
            for (int x = centerX - cubeSize / 2; x < centerX; x++) {
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    pixels.position((y * size + x) * 4);
                    putPixel(pixels, CUBE_FACE_2);
                }
            }
        }

        // Right face
        for (int y = centerY; y < centerY + cubeSize; y++) {
            for (int x = centerX; x < centerX + cubeSize / 2; x++) {
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    pixels.position((y * size + x) * 4);
                    putPixel(pixels, CUBE_FACE_3);
                }
            }
        }
    }

    /**
     * Puts a pixel in RGBA format with full opacity.
     */
    private void putPixel(ByteBuffer pixels, int color) {
        pixels.put((byte) ((color >> 16) & 0xFF)); // R
        pixels.put((byte) ((color >> 8) & 0xFF));  // G
        pixels.put((byte) (color & 0xFF));         // B
        pixels.put((byte) 0xFF);                   // A - Always fully opaque
    }

    /**
     * Creates an OpenGL texture from pixel data.
     */
    private int createTexture(ByteBuffer pixels, int size) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }
}
