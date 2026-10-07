package com.stonebreak.ui.runtime.providers;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER_BINDING;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glRenderbufferStorage;

/**
 * An offscreen RGBA8 colour texture with a depth/stencil renderbuffer, the target a draw provider
 * renders its GL content into during {@code prepare} before the document paints it through
 * {@link com.openmason.engine.ui.rendering.GlTextureImages}. Rows are bottom-up (GL convention).
 *
 * <p>Creation and binding change GL state; callers wrap their whole {@code prepare} in a
 * {@link com.openmason.engine.ui.rendering.GlStateSnapshot}. GL thread only.
 */
final class GlRenderTarget implements AutoCloseable {

    private int framebuffer;
    private int texture;
    private int depth;
    private int width;
    private int height;

    /** Allocates (or reallocates on a size change). Leaves the framebuffer bound. */
    void ensure(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("render target " + w + "x" + h);
        }
        if (framebuffer != 0 && w == width && h == height) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            return;
        }
        close();
        width = w;
        height = h;
        // a bound unpack buffer would make the null upload read from it
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        int previousRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING); // not in GlStateSnapshot
        depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, previousRenderbuffer);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("UI provider framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    /** Binds the framebuffer for drawing (must be allocated). */
    void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    }

    boolean allocated() {
        return framebuffer != 0;
    }

    int texture() {
        return texture;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void close() {
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (depth != 0) {
            glDeleteRenderbuffers(depth);
            depth = 0;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        width = 0;
        height = 0;
    }
}
