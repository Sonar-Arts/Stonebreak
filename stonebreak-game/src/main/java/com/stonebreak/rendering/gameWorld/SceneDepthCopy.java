package com.stonebreak.rendering.gameWorld;

import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * A snapshot of the bound framebuffer's depth in a samplable texture, for
 * passes that need to know how far the scene behind a pixel is (water
 * thickness, the cloud raymarch's stopping distance). Works for the default
 * framebuffer and the post-fx scene target alike.
 */
public final class SceneDepthCopy {

    private final int[] viewportBuf = new int[4];
    private final Vector4f viewport = new Vector4f();
    private int texture;
    private int blitFbo;
    private int attachedTexture;
    private int width;
    private int height;

    /**
     * Copies the current depth buffer into this texture, bound on the ACTIVE
     * texture unit, (re)allocating it when the viewport size changes.
     */
    public void copy() {
        glGetIntegerv(GL_VIEWPORT, viewportBuf);
        int w = Math.max(1, viewportBuf[2]);
        int h = Math.max(1, viewportBuf[3]);
        viewport.set(viewportBuf[0], viewportBuf[1], w, h);
        if (texture == 0) {
            texture = glGenTextures();
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        if (w != width || h != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            width = w;
            height = h;
        }
        int readFbo = glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        if (readFbo != 0 && hasMatchingDepth()) {
            blit(w, h);
        } else {
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewportBuf[0], viewportBuf[1], w, h);
        }
    }

    /**
     * Whether the read framebuffer's depth is a plain 24-bit depth buffer, the
     * one format {@link #blit} may copy into this texture (a depth blit needs
     * identical formats on both sides).
     */
    private static boolean hasMatchingDepth() {
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        return type != GL_NONE
                && GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE) == 24
                && GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE) == 0;
    }

    /**
     * Copies the read framebuffer's depth with a framebuffer blit. Not
     * glCopyTexSubImage2D: the NVIDIA driver (595.x, Blackwell) intermittently
     * faults the GPU channel on a depth-to-depth-texture copy from an FBO
     * (Xid 32/69, GL_INVALID_OPERATION across the frame, then a SIGSEGV inside
     * libnvidia-glcore) while chunks remesh; the blit path never did.
     */
    private void blit(int w, int h) {
        if (blitFbo == 0) {
            blitFbo = GL30.glGenFramebuffers();
        }
        int previousDraw = glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitFbo);
        if (attachedTexture != texture) {
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D, texture, 0);
            glDrawBuffer(GL_NONE);
            attachedTexture = texture;
        }
        int x = viewportBuf[0], y = viewportBuf[1];
        GL30.glBlitFramebuffer(x, y, x + w, y + h, 0, 0, w, h, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    /** The viewport at the last {@link #copy()}: (x, y, width, height). */
    public Vector4f viewport() {
        return viewport;
    }

    public int texture() {
        return texture;
    }

    public void cleanup() {
        if (blitFbo != 0) {
            GL30.glDeleteFramebuffers(blitFbo);
            blitFbo = 0;
            attachedTexture = 0;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
    }
}
