package com.openmason.main.systems.menus.icons;

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

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages SVG icons used in the menu bar.
 * Loads SVGs from resources via Batik, rasterizes to OpenGL textures.
 */
public class MenuBarIconManager {

    private static final Logger logger = LoggerFactory.getLogger(MenuBarIconManager.class);

    private static MenuBarIconManager instance;

    private static final int ICON_SIZE = 32;
    private static final String ICON_BASE_PATH = "/icons/navigation/";

    private int homeIconTexture = -1;

    private MenuBarIconManager() {
        loadIcons();
    }

    public static synchronized MenuBarIconManager getInstance() {
        if (instance == null) {
            instance = new MenuBarIconManager();
        }
        return instance;
    }

    private void loadIcons() {
        logger.info("Loading menu bar icons...");
        try {
            homeIconTexture = loadSvgIcon("home-screen.svg");
            logger.info("Menu bar icons loaded successfully");
        } catch (Exception e) {
            logger.error("Failed to load menu bar icons", e);
        }
    }

    /**
     * Get the Home Screen icon texture ID.
     * @return OpenGL texture ID, or -1 if not loaded
     */
    public int getHomeIconTexture() {
        return homeIconTexture;
    }

    private int loadSvgIcon(String svgFilename) throws IOException, TranscoderException {
        String resourcePath = ICON_BASE_PATH + svgFilename;
        InputStream svgStream = getClass().getResourceAsStream(resourcePath);

        if (svgStream == null) {
            throw new IOException("SVG resource not found: " + resourcePath);
        }

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) ICON_SIZE);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) ICON_SIZE);

        TranscoderInput input = new TranscoderInput(svgStream);
        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(pngOutputStream);

        transcoder.transcode(input, output);
        pngOutputStream.flush();

        byte[] pngBytes = pngOutputStream.toByteArray();
        ByteArrayInputStream pngInputStream = new ByteArrayInputStream(pngBytes);
        BufferedImage image = ImageIO.read(pngInputStream);

        if (image == null) {
            throw new IOException("Failed to read PNG image from transcoded SVG: " + svgFilename);
        }

        return createOpenGLTexture(image);
    }

    private int createOpenGLTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

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

        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, imageBuffer);

        glBindTexture(GL_TEXTURE_2D, 0);

        return textureId;
    }

    /**
     * Cleanup all OpenGL textures.
     */
    public void dispose() {
        if (homeIconTexture != -1) {
            glDeleteTextures(homeIconTexture);
            homeIconTexture = -1;
        }
        logger.info("Menu bar icon resources cleaned up");
    }
}
