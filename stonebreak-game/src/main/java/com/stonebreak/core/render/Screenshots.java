package com.stonebreak.core.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stonebreak.core.Game;
import com.stonebreak.ui.chat.ChatSystem;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_READ_BUFFER;
import static org.lwjgl.opengl.GL11.GL_RGB;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * F2 screenshots of the game window. {@link #request()} arms a capture; {@link #captureIfRequested}
 * reads the finished back buffer on the main thread (after the frame is drawn, before the swap) and
 * a background thread encodes it to {@code screenshots/<timestamp>.png}, so the frame never waits
 * on PNG compression.
 *
 * <p>Portable by construction: the size is the framebuffer's (Retina/HiDPI/Wayland scaling captures
 * at full resolution), alpha is dropped (the clear colour's alpha is 0, which would otherwise save
 * a see-through PNG), the name has no {@code :} (illegal on Windows), and the PNG goes through LWJGL's
 * STB rather than AWT/ImageIO, which must not be loaded next to GLFW on macOS's main thread.</p>
 */
public final class Screenshots {

    private static final Logger logger = LoggerFactory.getLogger(Screenshots.class);

    private static final Path DIRECTORY = Paths.get("screenshots");
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    private static final int CHANNELS = 3;
    private static final float[] CHAT_GREEN = {0.0f, 1.0f, 0.0f, 1.0f};
    private static final float[] CHAT_RED = {1.0f, 0.0f, 0.0f, 1.0f};

    // One thread: captures encode in order, so the unique-name check below cannot race itself.
    private static final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "screenshot-encoder");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean requested;

    private Screenshots() {}

    /** Captures the next finished frame. */
    public static void request() {
        requested = true;
    }

    /** Main thread, after the frame is drawn and before the buffer swap. Size in framebuffer pixels. */
    public static void captureIfRequested(int width, int height) {
        if (!requested) {
            return;
        }
        requested = false;
        if (width <= 0 || height <= 0) {
            return; // minimized
        }

        // Off-heap: a 4K frame is ~25 MB, too big to leave to the direct-buffer GC limit.
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * CHANNELS);
        int previousFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousReadBuffer = glGetInteger(GL_READ_BUFFER);
        int previousAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        try {
            // GL_BACK is only a valid read buffer on the default framebuffer; a pass that left an FBO
            // bound would make the read fail (strict on Mesa and macOS).
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glReadBuffer(GL_BACK);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, pixels);
        } catch (RuntimeException e) {
            MemoryUtil.memFree(pixels);
            throw e;
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, previousAlignment);
            glReadBuffer(previousReadBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousFramebuffer);
        }

        LocalDateTime takenAt = LocalDateTime.now();
        encoder.execute(() -> {
            try {
                write(pixels, width, height, takenAt);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        });
    }

    private static void write(ByteBuffer pixels, int width, int height, LocalDateTime takenAt) {
        // GL rows run bottom-up, PNG rows top-down: flip in place (no stbi global flip flag,
        // which other STB users in the process would also see).
        int stride = width * CHANNELS;
        byte[] top = new byte[stride];
        byte[] bottom = new byte[stride];
        for (int y = 0; y < height / 2; y++) {
            int topAt = y * stride;
            int bottomAt = (height - 1 - y) * stride;
            pixels.get(topAt, top);
            pixels.get(bottomAt, bottom);
            pixels.put(topAt, bottom);
            pixels.put(bottomAt, top);
        }

        try {
            Files.createDirectories(DIRECTORY);
            Path file = uniqueFile(FILE_NAME.format(takenAt));
            // Keep this path relative and ASCII: stb's fopen takes it as the ANSI codepage on Windows,
            // so an absolute path through a non-ASCII user folder would fail to open there.
            if (!STBImageWrite.stbi_write_png(file.toString(), width, height, CHANNELS, pixels, stride)) {
                throw new IOException("could not write " + file.toAbsolutePath());
            }
            logger.info("Saved screenshot {} ({}x{})", file.toAbsolutePath(), width, height);
            report("Saved screenshot as " + file.getFileName(), CHAT_GREEN);
        } catch (IOException e) {
            logger.error("Screenshot failed", e);
            report("Screenshot failed: " + e.getMessage(), CHAT_RED);
        }
    }

    /** {@code <base>.png}, or {@code <base>_2.png}, {@code _3}... when several land in one second. */
    private static Path uniqueFile(String base) {
        Path file = DIRECTORY.resolve(base + ".png");
        for (int n = 2; Files.exists(file); n++) {
            file = DIRECTORY.resolve(base + "_" + n + ".png");
        }
        return file;
    }

    private static void report(String message, float[] color) {
        Game game = Game.getInstance();
        if (game == null) {
            return;
        }
        game.runOnMainThread(() -> {
            ChatSystem chat = game.getChatSystem();
            if (chat != null) {
                chat.addMessage(message, color);
            }
        });
    }
}
