package com.openmason.main.systems.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * A small Skia raster surface composited into ImGui as a GL texture.
 *
 * <p><b>Why raster (CPU) and not a GL FBO:</b> when a panel is popped out into its
 * own OS window (ImGui multi-viewport), that window has a second GL context that
 * shares objects with the main context. On Mesa/XWayland, sampling a texture that
 * was rendered into via a GL <em>FBO</em> on the main context flickers in that
 * second context — but a texture whose pixels are uploaded with
 * {@code glTexSubImage2D} does not (the editor's main canvas proves this: it
 * uploads every frame and never flickers). So Skia paints into a CPU buffer here
 * (via {@link Surface#makeRasterDirect}), and each frame that buffer is uploaded
 * into {@link #presentTextureId} with {@code glTexSubImage2D} — the exact,
 * flicker-free path the canvas uses.
 *
 * <p>Allocation is rounded up in {@link #SIZE_STEP}px steps so dock-resize drags
 * don't rebuild the surface every frame; the logical size is tracked separately
 * and painting clips to it. Must be created, painted, and disposed on the GL thread.
 *
 * <pre>
 *   surface.ensureSize(w, h);
 *   Canvas c = surface.beginPaint();   // clears to transparent, clipped to w x h
 *   ... skia draws ...
 *   surface.endPaint();                // uploads the raster pixels to the GL texture
 *   ImGui.image(surface.getTextureId(), w, h);
 * </pre>
 */
public final class SkijaOffscreenSurface implements AutoCloseable {

    private static final int SIZE_STEP = 32;

    /** GL texture ImGui samples; filled from {@link #pixelBuffer} via glTexSubImage2D. */
    private int presentTextureId = -1;

    /**
     * Native buffer Skia renders directly into (top-down, tightly packed RGBA8,
     * premultiplied). {@link #surface} wraps this memory, so it must outlive the
     * surface and be freed only after the surface is closed.
     */
    private ByteBuffer pixelBuffer;

    /** Skia raster surface backed by {@link #pixelBuffer}. */
    private Surface surface;

    /** Allocated (rounded-up) dimensions. */
    private int allocWidth;
    private int allocHeight;

    /** Logical dimensions requested by the caller. */
    private int width;
    private int height;

    private boolean painting = false;

    public SkijaOffscreenSurface(SkijaContext context) {
        // Raster rendering does not use the GPU DirectContext; the context is
        // required only as a signal that Skija is initialized/available.
        if (context == null || !context.isAlive()) {
            throw new IllegalArgumentException("SkijaContext must be initialized");
        }
    }

    /**
     * Ensure the surface can hold {@code w x h} pixels, reallocating only when
     * the rounded allocation size changes.
     */
    public void ensureSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        this.width = w;
        this.height = h;

        int neededW = roundUp(w);
        int neededH = roundUp(h);
        if (surface != null && neededW == allocWidth && neededH == allocHeight) {
            return;
        }
        rebuild(neededW, neededH);
    }

    private static int roundUp(int v) {
        return ((v + SIZE_STEP - 1) / SIZE_STEP) * SIZE_STEP;
    }

    private void rebuild(int w, int h) {
        disposeGpuResources();

        // Native buffer Skia renders straight into (makeRasterDirect), then the
        // same buffer is uploaded to the GL texture — no FBO, no glReadPixels.
        pixelBuffer = MemoryUtil.memAlloc(w * h * 4);
        ImageInfo info = new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB());
        surface = Surface.makeRasterDirect(info, MemoryUtil.memAddress(pixelBuffer), (long) w * 4);

        presentTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, presentTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);

        allocWidth = w;
        allocHeight = h;
    }

    /**
     * Clear to transparent and return the canvas clipped to the logical size.
     */
    public Canvas beginPaint() {
        if (surface == null) {
            throw new IllegalStateException("ensureSize() must be called before beginPaint()");
        }
        if (painting) {
            throw new IllegalStateException("beginPaint() called twice without endPaint()");
        }
        painting = true;
        Canvas canvas = surface.getCanvas();
        canvas.save();
        canvas.clipRect(io.github.humbleui.types.Rect.makeWH(width, height));
        canvas.clear(0x00000000);
        return canvas;
    }

    /**
     * Upload the freshly painted raster pixels into the presentation texture.
     * Raster draws land directly in {@link #pixelBuffer} (makeRasterDirect), so no
     * GPU flush/readback is needed — just a glTexSubImage2D, exactly like the canvas.
     */
    public void endPaint() {
        if (!painting) {
            return;
        }
        painting = false;
        surface.getCanvas().restore();

        if (presentTextureId != -1 && pixelBuffer != null) {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); // ensure no PBO hijacks the upload
            glBindTexture(GL_TEXTURE_2D, presentTextureId);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            pixelBuffer.clear();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, allocWidth, allocHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    /** The GL texture ImGui should sample. */
    public int getTextureId() {
        return presentTextureId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Allocated texture width — needed to compute UV extent for ImGui.image. */
    public int getAllocatedWidth() {
        return allocWidth;
    }

    /** Allocated texture height — needed to compute UV extent for ImGui.image. */
    public int getAllocatedHeight() {
        return allocHeight;
    }

    @Override
    public void close() {
        disposeGpuResources();
    }

    private void disposeGpuResources() {
        // Close the surface before freeing the buffer it wraps.
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (presentTextureId != -1) {
            glDeleteTextures(presentTextureId);
            presentTextureId = -1;
        }
        if (pixelBuffer != null) {
            MemoryUtil.memFree(pixelBuffer);
            pixelBuffer = null;
        }
        allocWidth = 0;
        allocHeight = 0;
    }
}
