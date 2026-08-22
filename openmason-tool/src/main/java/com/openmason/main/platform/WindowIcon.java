package com.openmason.main.platform;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;

/**
 * Loads the Open Mason logo PNG and installs it as the GLFW window/taskbar icon in several
 * downscaled sizes. Failure is non-fatal (GLFW's default icon is used instead).
 */
public final class WindowIcon {

    private static final Logger logger = LoggerFactory.getLogger(WindowIcon.class);

    private static final String LOGO_RESOURCE_PATH = "/icons/Logo/Open Mason Logo.png";

    private WindowIcon() {
    }

    /**
     * Set the window/taskbar icon from the Open Mason logo PNG.
     *
     * <p>GLFW does not use {@code .ico} files: {@link GLFW#glfwSetWindowIcon} takes raw
     * RGBA pixels via {@link GLFWImage}. We supply several downscaled candidates so the OS
     * (and the Windows taskbar) can pick the crispest size for each context. Failure here is
     * non-fatal — the app simply falls back to the default GLFW icon.</p>
     */
    public static void apply(long window) {
        try (InputStream logoStream = WindowIcon.class.getResourceAsStream(LOGO_RESOURCE_PATH)) {
            if (logoStream == null) {
                logger.warn("Window icon resource not found: {}", LOGO_RESOURCE_PATH);
                return;
            }

            BufferedImage source = ImageIO.read(logoStream);
            if (source == null) {
                logger.warn("Failed to decode window icon image: {}", LOGO_RESOURCE_PATH);
                return;
            }

            // Trim the transparent margin so the logo fills the fixed taskbar slot edge-to-edge
            // (it otherwise renders smaller than the slot due to the PNG's empty border).
            source = trimTransparentBorder(source);

            int[] sizes = {16, 24, 32, 48, 64, 128, 256};
            List<ByteBuffer> pixelBuffers = new ArrayList<>(sizes.length);
            try (GLFWImage.Buffer icons = GLFWImage.malloc(sizes.length)) {
                for (int i = 0; i < sizes.length; i++) {
                    int s = sizes[i];
                    ByteBuffer pixels = toRgbaBuffer(scale(source, s, s));
                    pixelBuffers.add(pixels);
                    icons.position(i).width(s).height(s).pixels(pixels);
                }
                icons.position(0);
                glfwSetWindowIcon(window, icons);
            } finally {
                pixelBuffers.forEach(MemoryUtil::memFree);
            }
            logger.info("Window icon set from {}", LOGO_RESOURCE_PATH);
        } catch (IOException e) {
            logger.warn("Failed to load window icon", e);
        } catch (Exception e) {
            logger.warn("Unexpected error setting window icon", e);
        }
    }

    /**
     * Crop fully-transparent rows/columns from the image border so the visible artwork fills the
     * frame. Returns the original image if it has no alpha or is already tight. A small uniform
     * margin is preserved so antialiased/rounded edges aren't clipped.
     */
    private static BufferedImage trimTransparentBorder(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        final int alphaThreshold = 8; // treat near-transparent pixels as empty

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (source.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return source; // fully transparent — nothing to trim
        }

        // Keep a 2% margin so rounded corners / antialiasing aren't shaved off.
        int margin = Math.round(Math.max(w, h) * 0.02f);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(w - 1, maxX + margin);
        maxY = Math.min(h - 1, maxY + margin);

        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        if (cropW >= w && cropH >= h) {
            return source; // already edge-to-edge
        }
        return source.getSubimage(minX, minY, cropW, cropH);
    }

    /** Scale a source image to the given dimensions with smooth interpolation. */
    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /** Convert an image to a tightly-packed RGBA byte buffer (native-freed by the caller). */
    private static ByteBuffer toRgbaBuffer(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) ((argb >> 16) & 0xFF)); // R
                buffer.put((byte) ((argb >> 8) & 0xFF));  // G
                buffer.put((byte) (argb & 0xFF));         // B
                buffer.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();
        return buffer;
    }
}
