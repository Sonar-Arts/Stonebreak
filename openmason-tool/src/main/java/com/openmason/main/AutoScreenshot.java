package com.openmason.main;

import com.openmason.main.platform.ImGuiBackend;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

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
 * the given time, then optionally exits. Every other Open Mason window open at that moment (a
 * popped-out workspace, the Texture Editor, a floating panel) is written beside it as
 * {@code <file>.<window title>.png}, re-drawn from its own ImGui draw data (never a desktop grab).
 * Called right before the buffer swap, after the platform windows were rendered.
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

    /**
     * @param others captures the secondary windows (called with the main context current)
     * @return true when the app should quit now
     */
    boolean beforeSwap(long window, Supplier<List<ImGuiBackend.ViewportPixels>> others) {
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
        write(file, w[0], h[0], px);
        if (others != null) {
            List<ImGuiBackend.ViewportPixels> shots;
            try {
                shots = others.get();
            } catch (RuntimeException e) {
                LOGGER.error("[autoscreenshot] cannot capture the other windows", e);
                shots = List.of();
            }
            Set<String> used = new HashSet<>();
            for (ImGuiBackend.ViewportPixels shot : shots) {
                write(siblingFor(shot.title(), used), shot.width(), shot.height(), shot.rgba());
            }
            LOGGER.warn("[autoscreenshot] {} other window(s)", shots.size());
        }
        return quit;
    }

    /** {@code <file stem>.<slug of title>.png} beside the main shot, unique within one capture. */
    private Path siblingFor(String title, Set<String> used) {
        String name = file.getFileName().toString();
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        String slug = title.replaceAll("###.*$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "window";
        }
        String unique = slug;
        for (int n = 2; !used.add(unique); n++) {
            unique = slug + "-" + n;
        }
        return file.resolveSibling(stem + "." + unique + ".png");
    }

    private static void write(Path target, int w, int h, ByteBuffer px) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                img.setRGB(x, y, (px.get(i) & 0xFF) << 16 | (px.get(i + 1) & 0xFF) << 8 | (px.get(i + 2) & 0xFF));
            }
        }
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImageIO.write(img, "png", target.toFile());
            LOGGER.warn("[autoscreenshot] wrote {} ({}x{})", target, w, h);
        } catch (IOException e) {
            LOGGER.error("[autoscreenshot] cannot write {}", target, e);
        }
    }
}
