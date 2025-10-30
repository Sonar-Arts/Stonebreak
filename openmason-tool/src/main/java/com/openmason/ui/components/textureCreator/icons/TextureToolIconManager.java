package com.openmason.ui.components.textureCreator.icons;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages texture editor tool icons.
 * Loads SVG files from resources, converts them to OpenGL textures using Apache Batik.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Manages tool icon loading and caching only
 * - Open/Closed: Can be extended with new icon types without modification
 * - Interface Segregation: Simple, focused API
 * - Dependency Inversion: Uses resource loading abstraction
 *
 * Follows KISS: Simple SVG-to-texture pipeline without over-engineering
 * Follows DRY: Centralized icon management, no duplication
 * Follows YAGNI: Only implements what's needed - direct SVG loading
 *
 * @author Open Mason Team
 */
public class TextureToolIconManager {

    private static final Logger logger = LoggerFactory.getLogger(TextureToolIconManager.class);

    // Singleton instance
    private static TextureToolIconManager instance;

    // Icon cache: tool name -> OpenGL texture ID
    private final Map<String, Integer> iconTextures;

    // Icon dimensions (square icons)
    private static final int ICON_SIZE = 32;

    // Base path for icon resources
    private static final String ICON_BASE_PATH = "/icons/textureEditor/svg/";

    // Tool name to SVG filename mapping
    private static final Map<String, String> TOOL_ICON_MAP = new HashMap<>();

    static {
        TOOL_ICON_MAP.put("Pencil", "pencil.svg");
        TOOL_ICON_MAP.put("Eraser", "eraser.svg");
        TOOL_ICON_MAP.put("Fill", "fill.svg");
        TOOL_ICON_MAP.put("Color Picker", "eyedropper.svg");
        TOOL_ICON_MAP.put("Line", "line.svg");
        TOOL_ICON_MAP.put("Rectangle Selection", "selection.svg");
        TOOL_ICON_MAP.put("Selection Brush", "selection-brush.svg");
        TOOL_ICON_MAP.put("Move", "move.svg");
    }

    private TextureToolIconManager() {
        this.iconTextures = new HashMap<>();
        loadAllIcons();
    }

    /**
     * Get singleton instance.
     * @return singleton instance
     */
    public static synchronized TextureToolIconManager getInstance() {
        if (instance == null) {
            instance = new TextureToolIconManager();
        }
        return instance;
    }

    /**
     * Load all tool icons from SVG resources.
     */
    private void loadAllIcons() {
        logger.info("Loading texture tool icons...");

        for (Map.Entry<String, String> entry : TOOL_ICON_MAP.entrySet()) {
            String toolName = entry.getKey();
            String svgFilename = entry.getValue();

            try {
                int textureId = loadSvgIcon(svgFilename);
                iconTextures.put(toolName, textureId);
                logger.debug("Loaded icon for tool '{}': {}", toolName, svgFilename);
            } catch (Exception e) {
                logger.error("Failed to load icon for tool '{}': {}", toolName, svgFilename, e);
            }
        }

        logger.info("Loaded {} texture tool icons successfully", iconTextures.size());
    }

    /**
     * Load SVG icon from resources and convert to OpenGL texture.
     *
     * @param svgFilename SVG filename (e.g., "pencil.svg")
     * @return OpenGL texture ID
     * @throws IOException if resource cannot be loaded
     * @throws TranscoderException if SVG cannot be converted to PNG
     */
    private int loadSvgIcon(String svgFilename) throws IOException, TranscoderException {
        // Load SVG from resources
        String resourcePath = ICON_BASE_PATH + svgFilename;
        InputStream svgStream = getClass().getResourceAsStream(resourcePath);

        if (svgStream == null) {
            throw new IOException("SVG resource not found: " + resourcePath);
        }

        // Convert SVG to PNG using Apache Batik
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) ICON_SIZE);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) ICON_SIZE);

        TranscoderInput input = new TranscoderInput(svgStream);
        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(pngOutputStream);

        transcoder.transcode(input, output);
        pngOutputStream.flush();

        // Convert PNG bytes to BufferedImage
        byte[] pngBytes = pngOutputStream.toByteArray();
        ByteArrayInputStream pngInputStream = new ByteArrayInputStream(pngBytes);
        BufferedImage image = ImageIO.read(pngInputStream);

        if (image == null) {
            throw new IOException("Failed to read PNG image from transcoded SVG: " + svgFilename);
        }

        // Convert BufferedImage to OpenGL texture
        return createOpenGLTexture(image);
    }

    /**
     * Convert BufferedImage to OpenGL texture.
     *
     * @param image buffered image
     * @return OpenGL texture ID
     */
    private int createOpenGLTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Convert BufferedImage to ByteBuffer for OpenGL
        ByteBuffer imageBuffer = BufferUtils.createByteBuffer(width * height * 4);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                imageBuffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                imageBuffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                imageBuffer.put((byte) (pixel & 0xFF));         // Blue
                imageBuffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
            }
        }
        imageBuffer.flip();

        // Create OpenGL texture
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Upload texture data
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, imageBuffer);

        // Unbind texture
        glBindTexture(GL_TEXTURE_2D, 0);

        return textureId;
    }

    /**
     * Get OpenGL texture ID for a tool.
     *
     * @param toolName tool name (e.g., "Pencil", "Eraser")
     * @return OpenGL texture ID, or -1 if icon not found
     */
    public int getIconTexture(String toolName) {
        return iconTextures.getOrDefault(toolName, -1);
    }

    /**
     * Check if icon is loaded for a tool.
     *
     * @param toolName tool name
     * @return true if icon is loaded
     */
    public boolean hasIcon(String toolName) {
        return iconTextures.containsKey(toolName) && iconTextures.get(toolName) != -1;
    }

    /**
     * Get icon size.
     *
     * @return icon size in pixels (width and height are the same)
     */
    public int getIconSize() {
        return ICON_SIZE;
    }

    /**
     * Cleanup all OpenGL textures.
     * Call this when shutting down the texture editor.
     */
    public void dispose() {
        logger.info("Cleaning up texture tool icon resources...");

        for (Map.Entry<String, Integer> entry : iconTextures.entrySet()) {
            int textureId = entry.getValue();
            if (textureId != -1) {
                glDeleteTextures(textureId);
                logger.debug("Deleted texture for tool '{}': ID {}", entry.getKey(), textureId);
            }
        }

        iconTextures.clear();
        logger.info("Texture tool icon resources cleaned up");
    }
}
