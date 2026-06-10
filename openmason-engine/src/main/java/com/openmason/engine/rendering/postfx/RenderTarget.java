package com.openmason.engine.rendering.postfx;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;

/**
 * Offscreen framebuffer with an RGBA8 color texture and an optional depth texture.
 *
 * <p>The depth attachment is a texture (not a renderbuffer) so post-processing passes can
 * sample scene depth, e.g. for occlusion masks. All methods must be called on the main
 * (OpenGL) thread.</p>
 */
public class RenderTarget {

    private final boolean withDepthTexture;

    private int fboId;
    private int colorTexture;
    private int depthTexture;
    private int width;
    private int height;

    public RenderTarget(int width, int height, boolean withDepthTexture) {
        this.withDepthTexture = withDepthTexture;
        create(Math.max(1, width), Math.max(1, height));
    }

    private void create(int w, int h) {
        this.width = w;
        this.height = h;

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);

        if (withDepthTexture) {
            depthTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, depthTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
        }

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            int failedStatus = status;
            destroy();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            throw new IllegalStateException("Framebuffer incomplete (status 0x"
                    + Integer.toHexString(failedStatus) + ", " + w + "x" + h + ")");
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Binds this framebuffer and sets the viewport to its dimensions.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, width, height);
    }

    /**
     * Recreates the attachments at a new size. No-op if the size is unchanged or invalid.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            return;
        }
        if (newWidth == width && newHeight == height) {
            return;
        }
        destroy();
        create(newWidth, newHeight);
    }

    public int getFboId() {
        return fboId;
    }

    public int getColorTexture() {
        return colorTexture;
    }

    /**
     * @return the depth texture id, or 0 if this target was created without one
     */
    public int getDepthTexture() {
        return depthTexture;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void cleanup() {
        destroy();
    }

    private void destroy() {
        if (fboId != 0) {
            glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (colorTexture != 0) {
            glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthTexture != 0) {
            glDeleteTextures(depthTexture);
            depthTexture = 0;
        }
    }
}
