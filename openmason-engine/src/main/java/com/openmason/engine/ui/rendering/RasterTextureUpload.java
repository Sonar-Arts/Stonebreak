package com.openmason.engine.ui.rendering;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;

/**
 * Presents a {@link RasterMasonryBackend} frame as a GL texture by {@code glTexSubImage2D}: the
 * editor preview path that does not flicker when sampled from a popped-out ImGui window's
 * shared context (Mesa/XWayland flickers FBO-rendered textures there, uploaded ones never).
 * Rows stay top-down, so the texture is shown with UVs (0,0)-({@link #uvMaxX()},
 * {@link #uvMaxY()}) exactly like an {@link OffscreenFramebuffer}. The texture is allocated in
 * {@link OffscreenFramebuffer#SIZE_STEP} steps and the binding, unpack state and pixel-unpack
 * buffer the caller had are restored after each upload.
 */
public final class RasterTextureUpload implements AutoCloseable {

    private int texture;
    private int allocWidth;
    private int allocHeight;
    private int width;
    private int height;

    /** Uploads {@code frame}'s pixels (its whole allocation). GL thread only. */
    public void upload(RasterMasonryBackend frame) {
        int w = frame.width();
        int h = frame.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        int prevRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        try {
            ensure(w, h);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, frame.rowBytes() / 4);
            ByteBuffer pixels = memByteBuffer(frame.pixelAddress(), frame.rowBytes() * h);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, frame.isBgra() ? GL_BGRA : GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            width = w;
            height = h;
        } finally {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, prevRowLength);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevUnpackBuffer);
            glBindTexture(GL_TEXTURE_2D, prevTexture);
        }
    }

    public int textureId() {
        return texture;
    }

    public float uvMaxX() {
        return allocWidth == 0 ? 0f : (float) width / allocWidth;
    }

    public float uvMaxY() {
        return allocHeight == 0 ? 0f : (float) height / allocHeight;
    }

    @Override
    public void close() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        allocWidth = 0;
        allocHeight = 0;
    }

    private void ensure(int w, int h) {
        int aw = OffscreenFramebuffer.roundUp(w);
        int ah = OffscreenFramebuffer.roundUp(h);
        if (texture != 0 && aw == allocWidth && ah == allocHeight) {
            return;
        }
        close();
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, aw, ah, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        allocWidth = aw;
        allocHeight = ah;
    }
}
