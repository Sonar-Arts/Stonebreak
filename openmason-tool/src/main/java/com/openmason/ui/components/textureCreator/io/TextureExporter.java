package com.openmason.ui.components.textureCreator.io;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImageWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Texture exporter - exports pixel canvas to PNG files.
 *
 * Uses STB Image Write for PNG encoding.
 * Follows SOLID principles - Single Responsibility: PNG export only.
 *
 * @author Open Mason Team
 */
public class TextureExporter {

    private static final Logger logger = LoggerFactory.getLogger(TextureExporter.class);

    /**
     * Export canvas to PNG file.
     *
     * @param canvas pixel canvas to export
     * @param filePath output file path
     * @return true if export succeeded
     */
    public boolean exportToPNG(PixelCanvas canvas, String filePath) {
        if (canvas == null) {
            logger.error("Cannot export null canvas");
            return false;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return false;
        }

        // Ensure .png extension
        if (!filePath.toLowerCase().endsWith(".png")) {
            filePath += ".png";
        }

        try {
            // Get pixel data as RGBA byte array
            byte[] pixelBytes = canvas.getPixelsAsRGBABytes();

            // Create ByteBuffer for STB
            ByteBuffer buffer = BufferUtils.createByteBuffer(pixelBytes.length);
            buffer.put(pixelBytes);
            buffer.flip();

            // Write PNG file
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            int stride = width * 4; // 4 bytes per pixel (RGBA)

            boolean success = STBImageWrite.stbi_write_png(filePath, width, height, 4, buffer, stride);

            if (success) {
                logger.info("Exported texture to: {}", filePath);
            } else {
                logger.error("Failed to write PNG file: {}", filePath);
            }

            return success;

        } catch (Exception e) {
            logger.error("Error exporting texture", e);
            return false;
        }
    }

    /**
     * Validate output file path.
     *
     * @param filePath file path to validate
     * @return true if path is valid and writable
     */
    public boolean validateFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        try {
            File file = new File(filePath);
            File parent = file.getParentFile();

            // Check if parent directory exists or can be created
            if (parent != null && !parent.exists()) {
                return parent.mkdirs();
            }

            return true;
        } catch (Exception e) {
            logger.warn("Invalid file path: {}", filePath, e);
            return false;
        }
    }
}
