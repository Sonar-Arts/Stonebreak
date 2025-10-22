package com.openmason.ui.components.textureCreator.io;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Texture importer - imports PNG files to pixel canvas.
 *
 * Uses STB Image for PNG decoding.
 * Follows SOLID principles - Single Responsibility: PNG import only.
 *
 * @author Open Mason Team
 */
public class TextureImporter {

    private static final Logger logger = LoggerFactory.getLogger(TextureImporter.class);

    /**
     * Import PNG file to canvas.
     *
     * @param filePath input file path
     * @return loaded pixel canvas, or null if failed
     */
    public PixelCanvas importFromPNG(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return null;
        }

        try {
            // Prepare buffers for image info
            IntBuffer width = BufferUtils.createIntBuffer(1);
            IntBuffer height = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);

            // Load image data (request RGBA format)
            ByteBuffer imageData = STBImage.stbi_load(filePath, width, height, channels, 4);

            if (imageData == null) {
                logger.error("Failed to load image: {} - {}", filePath, STBImage.stbi_failure_reason());
                return null;
            }

            int imageWidth = width.get(0);
            int imageHeight = height.get(0);

            logger.info("Loaded image: {}x{} from {}", imageWidth, imageHeight, filePath);

            // Create canvas
            PixelCanvas canvas = new PixelCanvas(imageWidth, imageHeight);

            // Copy pixel data
            for (int y = 0; y < imageHeight; y++) {
                for (int x = 0; x < imageWidth; x++) {
                    int index = (y * imageWidth + x) * 4; // 4 bytes per pixel (RGBA)

                    // Read RGBA values (unsigned bytes)
                    int r = imageData.get(index) & 0xFF;
                    int g = imageData.get(index + 1) & 0xFF;
                    int b = imageData.get(index + 2) & 0xFF;
                    int a = imageData.get(index + 3) & 0xFF;

                    // Pack into color int
                    int color = PixelCanvas.packRGBA(r, g, b, a);
                    canvas.setPixel(x, y, color);
                }
            }

            // Free image data
            STBImage.stbi_image_free(imageData);

            logger.info("Successfully imported texture from: {}", filePath);
            return canvas;

        } catch (Exception e) {
            logger.error("Error importing texture", e);
            return null;
        }
    }

    /**
     * Import PNG file and resize to target dimensions.
     *
     * @param filePath input file path
     * @param targetWidth target width
     * @param targetHeight target height
     * @return loaded and resized pixel canvas, or null if failed
     */
    public PixelCanvas importFromPNGResized(String filePath, int targetWidth, int targetHeight) {
        PixelCanvas loaded = importFromPNG(filePath);

        if (loaded == null) {
            return null;
        }

        // If already correct size, return as-is
        if (loaded.getWidth() == targetWidth && loaded.getHeight() == targetHeight) {
            return loaded;
        }

        // Create resized canvas
        PixelCanvas resized = new PixelCanvas(targetWidth, targetHeight);

        // Simple nearest-neighbor scaling
        float xRatio = (float) loaded.getWidth() / targetWidth;
        float yRatio = (float) loaded.getHeight() / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int srcX = (int) (x * xRatio);
                int srcY = (int) (y * yRatio);

                int color = loaded.getPixel(srcX, srcY);
                resized.setPixel(x, y, color);
            }
        }

        logger.info("Resized texture from {}x{} to {}x{}",
                   loaded.getWidth(), loaded.getHeight(), targetWidth, targetHeight);

        return resized;
    }

    /**
     * Validate input file path.
     *
     * @param filePath file path to validate
     * @return true if path exists and is a PNG file
     */
    public boolean validateFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        java.io.File file = new java.io.File(filePath);
        return file.exists() && file.isFile() && filePath.toLowerCase().endsWith(".png");
    }
}
