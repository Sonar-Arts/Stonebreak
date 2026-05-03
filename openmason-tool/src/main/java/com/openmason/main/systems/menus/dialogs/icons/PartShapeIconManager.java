package com.openmason.main.systems.menus.dialogs.icons;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.shapes.PartShape;
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
import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Loads and caches OpenGL textures for {@link PartShapeFactory.Shape} icons.
 *
 * <p>Each shape's SVG (declared by {@link PartShape#iconFilename()}) is loaded
 * from the {@code /icons/shapes/} classpath directory, transcoded to PNG via
 * Apache Batik, and uploaded as an RGBA OpenGL texture. The icon textures are
 * displayed in {@code AddPartDialog} next to each shape's radio button.
 *
 * <p>Singleton lifecycle: created lazily on first {@link #getInstance()} call
 * (must be invoked on a thread holding a current OpenGL context). Call
 * {@link #dispose()} during application shutdown to release GPU textures.
 */
public final class PartShapeIconManager {

    private static final Logger logger = LoggerFactory.getLogger(PartShapeIconManager.class);

    private static final int ICON_SIZE = 32;
    private static final String ICON_BASE_PATH = "/icons/shapes/";

    private static PartShapeIconManager instance;

    private final Map<PartShapeFactory.Shape, Integer> iconTextures = new EnumMap<>(PartShapeFactory.Shape.class);

    private PartShapeIconManager() {
        loadAllIcons();
    }

    public static synchronized PartShapeIconManager getInstance() {
        if (instance == null) {
            instance = new PartShapeIconManager();
        }
        return instance;
    }

    private void loadAllIcons() {
        logger.info("Loading part shape icons...");
        for (PartShapeFactory.Shape shape : PartShapeFactory.Shape.values()) {
            String filename = shape.impl().iconFilename();
            try {
                int textureId = loadSvgIcon(filename);
                iconTextures.put(shape, textureId);
                logger.debug("Loaded icon for shape '{}': {}", shape, filename);
            } catch (Exception e) {
                logger.error("Failed to load icon for shape '{}': {}", shape, filename, e);
            }
        }
        logger.info("Loaded {} part shape icons", iconTextures.size());
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

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngOutputStream.toByteArray()));
        if (image == null) {
            throw new IOException("Failed to decode transcoded PNG for " + svgFilename);
        }
        return createOpenGLTexture(image);
    }

    private int createOpenGLTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                buf.put((byte) ((pixel >> 16) & 0xFF));
                buf.put((byte) ((pixel >> 8) & 0xFF));
                buf.put((byte) (pixel & 0xFF));
                buf.put((byte) ((pixel >> 24) & 0xFF));
            }
        }
        buf.flip();

        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    /**
     * Get the OpenGL texture id for a shape's icon, or {@code -1} if the icon
     * failed to load.
     */
    public int getIconTexture(PartShapeFactory.Shape shape) {
        Integer id = iconTextures.get(shape);
        return id != null ? id : -1;
    }

    /** Release all GPU textures. */
    public void dispose() {
        logger.info("Disposing {} part shape icons", iconTextures.size());
        for (Map.Entry<PartShapeFactory.Shape, Integer> entry : iconTextures.entrySet()) {
            int id = entry.getValue();
            if (id != -1) {
                glDeleteTextures(id);
            }
        }
        iconTextures.clear();
    }
}
