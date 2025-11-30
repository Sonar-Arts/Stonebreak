package com.openmason.main.systems.rendering.model.miscComponents;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.io.OMTDeserializer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
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
     * Loads a texture from a .OMT file with full layer compositing.
     * Composites all visible layers with proper alpha blending and returns metadata.
     *
     * @param omtFilePath path to the .OMT file
     * @return TextureLoadResult with texture ID and dimensions, or failed result
     */
    public TextureLoadResult loadTextureComposite(Path omtFilePath) {
        if (omtFilePath == null || !Files.exists(omtFilePath)) {
            logger.error("OMT file does not exist: {}", omtFilePath);
            return TextureLoadResult.failed();
        }

        logger.info("Loading multi-layer texture from .OMT file: {}", omtFilePath);

        try {
            // Load OMT file using deserializer (gets all layers)
            OMTDeserializer deserializer = new OMTDeserializer();
            LayerManager layerManager = deserializer.load(omtFilePath.toString());

            if (layerManager == null) {
                logger.error("Failed to load LayerManager from .OMT file");
                return TextureLoadResult.failed();
            }

            // Composite all visible layers with alpha blending
            PixelCanvas compositedCanvas = layerManager.compositeLayersToCanvas();

            if (compositedCanvas == null) {
                logger.error("Layer compositing returned null");
                return TextureLoadResult.failed();
            }

            int width = compositedCanvas.getWidth();
            int height = compositedCanvas.getHeight();
            logger.info("Composited {} layers into {}x{} texture",
                layerManager.getLayerCount(), width, height);

            // Detect transparency before uploading
            boolean hasTransparency = hasTransparency(compositedCanvas);
            logger.debug("Transparency detection result: {}", hasTransparency);

            // Upload composited canvas to GPU
            int textureId = uploadPixelCanvasToGPU(compositedCanvas);

            if (textureId > 0) {
                logger.info("Successfully loaded multi-layer .OMT texture (ID: {}, {}x{}, transparency: {})",
                    textureId, width, height, hasTransparency);
                return new TextureLoadResult(textureId, width, height, hasTransparency);
            } else {
                logger.error("Failed to upload composited texture to GPU");
                return TextureLoadResult.failed();
            }

        } catch (Exception e) {
            logger.error("Error loading multi-layer .OMT texture", e);
            return TextureLoadResult.failed();
        }
    }

    /**
     * Loads a texture from a .OMT file (legacy single-layer method).
     * Only loads the first layer for backward compatibility.
     *
     * @param omtFilePath path to the .OMT file
     * @return OpenGL texture ID, or 0 if loading failed
     * @deprecated Use {@link #loadTextureComposite(Path)} for full multi-layer support
     */
    @Deprecated
    public int loadTexture(Path omtFilePath) {
        if (omtFilePath == null || !Files.exists(omtFilePath)) {
            logger.error("OMT file does not exist: {}", omtFilePath);
            return 0;
        }

        logger.info("Loading texture from .OMT file (single layer): {}", omtFilePath);

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

            // Load texture in standard orientation (matches game's approach)
            // UV coordinates in CubeNetMeshGenerator are designed for this
            STBImage.stbi_set_flip_vertically_on_load(false);

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
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); // Pixel-perfect, no interpolation (matches game)
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
     * Uploads a PixelCanvas to GPU as an OpenGL texture.
     * Converts pixel data to RGBA ByteBuffer and creates mipmap texture.
     *
     * @param canvas the pixel canvas to upload
     * @return OpenGL texture ID, or 0 if upload failed
     */
    private int uploadPixelCanvasToGPU(PixelCanvas canvas) {
        try {
            int width = canvas.getWidth();
            int height = canvas.getHeight();

            // Get RGBA byte data from pixel canvas
            byte[] rgbaBytes = canvas.getPixelsAsRGBABytes();

            if (rgbaBytes == null || rgbaBytes.length == 0) {
                logger.error("PixelCanvas returned empty RGBA byte array");
                return 0;
            }

            // Convert to ByteBuffer for OpenGL
            ByteBuffer imageData = BufferUtils.createByteBuffer(rgbaBytes.length);
            imageData.put(rgbaBytes);
            imageData.flip();

            // Create OpenGL texture
            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);

            // Upload texture data (no vertical flip - PixelCanvas is already in correct orientation)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, imageData);

            // Generate mipmaps
            glGenerateMipmap(GL_TEXTURE_2D);

            // Set texture parameters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); // Pixel-perfect, no interpolation (matches game)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); // Pixelated look for block textures

            glBindTexture(GL_TEXTURE_2D, 0);

            logger.debug("Uploaded PixelCanvas to GPU: ID={}, size={}x{}", textureId, width, height);
            return textureId;

        } catch (Exception e) {
            logger.error("Error uploading PixelCanvas to GPU", e);
            return 0;
        }
    }

    /**
     * Detects if a PixelCanvas requires transparent rendering (depth writes disabled).
     * For multi-layer composites, determines if the texture is "mostly opaque" vs "truly transparent".
     *
     * <p>Strategy:
     * <ul>
     *   <li>Counts pixels that are nearly or fully opaque (alpha >= 250)</li>
     *   <li>If >= 90% of pixels are opaque, treat entire texture as OPAQUE</li>
     *   <li>Otherwise, treat as TRANSPARENT (requires depth write disabled)</li>
     *   <li>This handles multi-layer composites where background layers may leave transparent areas</li>
     * </ul>
     *
     * <p>Rationale:
     * <ul>
     *   <li>PixelCanvas starts with transparent pixels (alpha=0)</li>
     *   <li>Layer compositing preserves transparency in uncovered areas</li>
     *   <li>A texture with opaque base layer should render as opaque even if edges/corners are transparent</li>
     *   <li>Only genuinely transparent textures (glass, etc.) should disable depth writes</li>
     * </ul>
     *
     * @param canvas the pixel canvas to check
     * @return true if texture requires transparent rendering (depth writes disabled)
     */
    private boolean hasTransparency(PixelCanvas canvas) {
        byte[] rgbaBytes = canvas.getPixelsAsRGBABytes();

        if (rgbaBytes == null || rgbaBytes.length == 0) {
            return false;
        }

        // Threshold for considering a pixel "opaque" (handles anti-aliasing and compositing artifacts)
        final int OPAQUE_THRESHOLD = 250;
        // Percentage of opaque pixels required to treat texture as opaque
        final double OPAQUE_PERCENTAGE_THRESHOLD = 90.0;

        // Count opaque pixels
        int totalPixels = rgbaBytes.length / 4;
        int opaquePixels = 0;

        // Scan every 4th byte (alpha channel in RGBA format)
        for (int i = 3; i < rgbaBytes.length; i += 4) {
            // Check if pixel is nearly or fully opaque
            // Using unsigned byte comparison (& 0xFF converts to unsigned)
            int alpha = rgbaBytes[i] & 0xFF;
            if (alpha >= OPAQUE_THRESHOLD) {
                opaquePixels++;
            }
        }

        // Calculate percentage of opaque pixels
        double opaquePercentage = (double) opaquePixels / totalPixels * 100.0;

        // Texture is "mostly opaque" if >= 90% of pixels are opaque
        // These textures should use depth writes (return false = no transparency mode)
        boolean isMostlyOpaque = opaquePercentage >= OPAQUE_PERCENTAGE_THRESHOLD;

        logger.debug("Opacity analysis: {}/{} pixels opaque ({:.2f}%) - threshold: {} - mostly opaque: {} - requires transparent rendering: {}",
            opaquePixels, totalPixels, opaquePercentage, OPAQUE_PERCENTAGE_THRESHOLD, isMostlyOpaque, !isMostlyOpaque);

        // Return true if texture requires transparent rendering (NOT mostly opaque)
        return !isMostlyOpaque;
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
