package com.openmason.rendering.blockmodel;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * Loads textures from .OMT files (Open Mason Texture format).
 *
 * <p>Extracts PNG images from .OMT ZIP archives and creates OpenGL textures.
 * For simplicity, only loads the first layer's texture.
 *
 * <p>.OMT File Structure:
 * <pre>
 * texture.omt (ZIP)
 * ├── manifest.json
 * └── layer_0.png  (first layer - we load this)
 * </pre>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>KISS: Load only first layer (multi-layer compositing is YAGNI for now)</li>
 *   <li>SOLID: Single responsibility - only loads .OMT textures</li>
 *   <li>DRY: Reuses STB image loading from existing codebase</li>
 * </ul>
 *
 * @since 1.0
 */
public class OMTTextureLoader {

    private static final Logger logger = LoggerFactory.getLogger(OMTTextureLoader.class);

    private static final String FIRST_LAYER_FILENAME = "layer_0.png";

    /**
     * Creates a new OMT texture loader.
     */
    public OMTTextureLoader() {
        logger.debug("OMTTextureLoader created");
    }

    /**
     * Loads a texture from a .OMT file and creates an OpenGL texture.
     *
     * @param omtFilePath path to the .OMT file
     * @return OpenGL texture ID, or 0 if loading failed
     */
    public int loadTexture(Path omtFilePath) {
        if (omtFilePath == null || !Files.exists(omtFilePath)) {
            logger.error("OMT file does not exist: {}", omtFilePath);
            return 0;
        }

        logger.info("Loading texture from .OMT file: {}", omtFilePath);

        try {
            // Extract first layer PNG from ZIP
            byte[] pngData = extractFirstLayer(omtFilePath);

            if (pngData == null || pngData.length == 0) {
                logger.error("Failed to extract layer PNG from .OMT file");
                return 0;
            }

            // Load PNG as OpenGL texture
            int textureId = loadPNGAsTexture(pngData);

            if (textureId > 0) {
                logger.info("Successfully loaded .OMT texture (ID: {})", textureId);
            } else {
                logger.error("Failed to create OpenGL texture from PNG data");
            }

            return textureId;

        } catch (IOException e) {
            logger.error("Error loading .OMT texture", e);
            return 0;
        }
    }

    /**
     * Extracts the first layer PNG from the .OMT ZIP file.
     *
     * @param omtFilePath path to .OMT file
     * @return PNG data as byte array, or null if not found
     * @throws IOException if reading fails
     */
    private byte[] extractFirstLayer(Path omtFilePath) throws IOException {
        try (InputStream fis = Files.newInputStream(omtFilePath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (FIRST_LAYER_FILENAME.equals(entry.getName())) {
                    // Found the first layer PNG
                    logger.debug("Found {} in .OMT file", FIRST_LAYER_FILENAME);

                    // Read PNG data to byte array
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }

                    byte[] pngData = baos.toByteArray();
                    logger.debug("Extracted {} bytes of PNG data", pngData.length);
                    return pngData;
                }
                zis.closeEntry();
            }
        }

        logger.error("Could not find {} in .OMT file", FIRST_LAYER_FILENAME);
        return null;
    }

    /**
     * Loads PNG data as an OpenGL texture using STB Image.
     *
     * @param pngData PNG file data
     * @return OpenGL texture ID, or 0 if loading failed
     */
    private int loadPNGAsTexture(byte[] pngData) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Prepare data for STB
            ByteBuffer imageBuffer = BufferUtils.createByteBuffer(pngData.length);
            imageBuffer.put(pngData);
            imageBuffer.flip();

            // Load image with STB
            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);

            // Flip vertically for OpenGL (bottom-left origin)
            STBImage.stbi_set_flip_vertically_on_load(true);

            ByteBuffer imageData = STBImage.stbi_load_from_memory(
                imageBuffer, widthBuffer, heightBuffer, channelsBuffer, 4 // Force RGBA
            );

            if (imageData == null) {
                logger.error("STB Image loading failed: {}", STBImage.stbi_failure_reason());
                return 0;
            }

            int width = widthBuffer.get(0);
            int height = heightBuffer.get(0);
            logger.debug("Loaded image: {}x{}", width, height);

            // Create OpenGL texture
            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);

            // Upload texture data
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, imageData);

            // Generate mipmaps
            glGenerateMipmap(GL_TEXTURE_2D);

            // Set texture parameters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); // Pixelated look for block textures

            // Free STB image data
            STBImage.stbi_image_free(imageData);

            glBindTexture(GL_TEXTURE_2D, 0);

            logger.debug("Created OpenGL texture: ID={}, size={}x{}", textureId, width, height);
            return textureId;

        } catch (Exception e) {
            logger.error("Error loading PNG as texture", e);
            return 0;
        }
    }

    /**
     * Deletes an OpenGL texture.
     *
     * @param textureId the texture ID to delete
     */
    public void deleteTexture(int textureId) {
        if (textureId > 0) {
            glDeleteTextures(textureId);
            logger.debug("Deleted texture: {}", textureId);
        }
    }
}
