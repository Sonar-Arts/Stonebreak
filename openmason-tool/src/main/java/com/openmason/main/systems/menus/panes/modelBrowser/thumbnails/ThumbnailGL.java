package com.openmason.main.systems.menus.panes.modelBrowser.thumbnails;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

/**
 * Shared helpers for converting PNG bytes into a square OpenGL thumbnail
 * texture used by the Model Browser. Pixel-art textures are upscaled with
 * nearest-neighbour filtering to keep edges crisp; transparent areas land
 * on a checker so empty regions read as empty rather than as solid black.
 */
final class ThumbnailGL {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailGL.class);

    private static final int CHECKER_LIGHT = 0xFFCCCCCC;
    private static final int CHECKER_DARK = 0xFF999999;
    private static final int CHECKER_SIZE = 8;
    private static final int BORDER_COLOR = 0xFF666666;

    private ThumbnailGL() {}

    /** Decode PNG bytes, scale to {@code size}, blend onto a checker, upload as GL texture. */
    static int uploadFromPng(byte[] png, int size) {
        if (png == null || png.length == 0) {
            return 0;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
            if (source == null) {
                logger.warn("Failed to decode PNG for thumbnail (no reader)");
                return 0;
            }
            return uploadFromImage(source, size);
        } catch (Exception e) {
            logger.error("Failed to upload PNG thumbnail", e);
            return 0;
        }
    }

    /** Composite a list of PNG layers onto a single thumbnail. */
    static int uploadComposite(java.util.List<byte[]> layerPngs, int canvasW, int canvasH, int size) {
        try {
            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setComposite(AlphaComposite.SrcOver);
            for (byte[] png : layerPngs) {
                if (png == null) continue;
                BufferedImage layer = ImageIO.read(new ByteArrayInputStream(png));
                if (layer != null) {
                    g.drawImage(layer, 0, 0, canvasW, canvasH, null);
                }
            }
            g.dispose();
            return uploadFromImage(canvas, size);
        } catch (Exception e) {
            logger.error("Failed to composite thumbnail layers", e);
            return 0;
        }
    }

    private static int uploadFromImage(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();

        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = scaled.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int gC = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int a = (argb >> 24) & 0xFF;

                int bg = checker(x, y);
                int br = (bg >> 16) & 0xFF;
                int bgG = (bg >> 8) & 0xFF;
                int bb = bg & 0xFF;

                int outR = (r * a + br * (255 - a)) / 255;
                int outG = (gC * a + bgG * (255 - a)) / 255;
                int outB = (b * a + bb * (255 - a)) / 255;

                pixels.put((byte) outR);
                pixels.put((byte) outG);
                pixels.put((byte) outB);
                pixels.put((byte) 0xFF);
            }
        }
        drawBorder(pixels, size);
        pixels.flip();

        return createTexture(pixels, size);
    }

    private static int checker(int x, int y) {
        boolean light = ((x / CHECKER_SIZE) + (y / CHECKER_SIZE)) % 2 == 0;
        return light ? CHECKER_LIGHT : CHECKER_DARK;
    }

    private static void drawBorder(ByteBuffer pixels, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    pixels.position((y * size + x) * 4);
                    pixels.put((byte) ((BORDER_COLOR >> 16) & 0xFF));
                    pixels.put((byte) ((BORDER_COLOR >> 8) & 0xFF));
                    pixels.put((byte) (BORDER_COLOR & 0xFF));
                    pixels.put((byte) 0xFF);
                }
            }
        }
        pixels.position(0);
    }

    private static int createTexture(ByteBuffer pixels, int size) {
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
