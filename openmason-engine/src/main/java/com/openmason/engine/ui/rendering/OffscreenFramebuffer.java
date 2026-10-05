package com.openmason.engine.ui.rendering;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A GL framebuffer for offscreen Masonry frames: an RGBA8 color texture (what an editor shows
 * through ImGui) plus a depth/stencil renderbuffer (Skia clips complex paths with the stencil).
 * Allocation is rounded up in {@link #SIZE_STEP} pixel steps so dragging a dock splitter does
 * not reallocate every frame; the logical size is the target size and
 * {@link #uvMaxX()}/{@link #uvMaxY()} select it inside the texture (rows top-down, see
 * {@link UiRenderTarget.Origin#TOP_LEFT}).
 *
 * <p>Created, resized and deleted on the GL thread. Call the renderer's
 * {@code releaseTargetSurface()} before {@link #ensureSize} reallocates or {@link #close()}
 * deletes, so Skia never draws into a deleted framebuffer.
 */
public final class OffscreenFramebuffer implements AutoCloseable {

    public static final int SIZE_STEP = 32;

    private int framebuffer;
    private int colorTexture;
    private int depthStencil;
    private int allocWidth;
    private int allocHeight;
    private int width;
    private int height;

    /**
     * Ensures room for {@code w x h}.
     *
     * @return true when the GL objects were (re)created: any surface wrapping the old
     *         framebuffer is stale
     */
    public boolean ensureSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + w + "x" + h);
        }
        width = w;
        height = h;
        int aw = roundUp(w);
        int ah = roundUp(h);
        if (framebuffer != 0 && aw == allocWidth && ah == allocHeight) {
            return false;
        }
        allocate(aw, ah);
        return true;
    }

    /** Target for this framebuffer's allocated area (Skia paints the whole allocation). */
    public UiRenderTarget target(float pixelRatio) {
        return UiRenderTarget.offscreen(framebuffer, allocWidth, allocHeight, pixelRatio);
    }

    public int framebufferId() {
        return framebuffer;
    }

    public int textureId() {
        return colorTexture;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int allocatedWidth() {
        return allocWidth;
    }

    public int allocatedHeight() {
        return allocHeight;
    }

    public float uvMaxX() {
        return allocWidth == 0 ? 0f : (float) width / allocWidth;
    }

    public float uvMaxY() {
        return allocHeight == 0 ? 0f : (float) height / allocHeight;
    }

    public boolean isAllocated() {
        return framebuffer != 0;
    }

    @Override
    public void close() {
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (colorTexture != 0) {
            glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthStencil != 0) {
            glDeleteRenderbuffers(depthStencil);
            depthStencil = 0;
        }
        allocWidth = 0;
        allocHeight = 0;
    }

    private void allocate(int w, int h) {
        close();
        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
        int prevFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        try {
            colorTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, colorTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

            depthStencil = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                close();
                throw new IllegalStateException("Offscreen framebuffer incomplete: 0x" + Integer.toHexString(status));
            }
            allocWidth = w;
            allocHeight = h;
        } finally {
            glBindTexture(GL_TEXTURE_2D, prevTexture);
            glBindRenderbuffer(GL_RENDERBUFFER, prevRenderbuffer);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFramebuffer);
        }
    }

    static int roundUp(int v) {
        return (v + SIZE_STEP - 1) / SIZE_STEP * SIZE_STEP;
    }
}
