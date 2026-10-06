package com.openmason.main;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * Dev hook for live runs: {@code -Dopenmason.autoscreenshot=<seconds>:<file.png>[:quit]} writes the
 * main window's own back buffer (the whole Open Mason UI, nothing else on the desktop) once, after
 * the given time, then optionally exits. Called right before the buffer swap.
 */
final class AutoScreenshot {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScreenshot.class);

    private final double at;
    private final Path file;
    private final boolean quit;
    private final long start = System.nanoTime();
    private boolean done;

    private AutoScreenshot(double at, Path file, boolean quit) {
        this.at = at;
        this.file = file;
        this.quit = quit;
    }

    /** The configured hook, or null. */
    static AutoScreenshot fromProperty() {
        String spec = System.getProperty("openmason.autoscreenshot");
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.split(":");
        return new AutoScreenshot(Double.parseDouble(parts[0]),
            Path.of(parts.length > 1 ? parts[1] : "openmason-shot.png"),
            parts.length > 2 && "quit".equalsIgnoreCase(parts[2]));
    }

    /** @return true when the app should quit now */
    boolean beforeSwap(long window) {
        if (done || (System.nanoTime() - start) / 1e9 < at) {
            return false;
        }
        done = true;
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        ByteBuffer px = BufferUtils.createByteBuffer(w[0] * h[0] * 4);
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window); // multi-viewport rendering may have switched it
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
        glReadBuffer(GL_BACK);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w[0], h[0], GL_RGBA, GL_UNSIGNED_BYTE, px);
        BufferedImage img = new BufferedImage(w[0], h[0], BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h[0]; y++) {
            for (int x = 0; x < w[0]; x++) {
                int i = ((h[0] - 1 - y) * w[0] + x) * 4;
                img.setRGB(x, y, (px.get(i) & 0xFF) << 16 | (px.get(i + 1) & 0xFF) << 8 | (px.get(i + 2) & 0xFF));
            }
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImageIO.write(img, "png", file.toFile());
            LOGGER.warn("[autoscreenshot] wrote {} ({}x{})", file, w[0], h[0]);
        } catch (IOException e) {
            LOGGER.error("[autoscreenshot] cannot write {}", file, e);
        }
        return quit;
    }
}
