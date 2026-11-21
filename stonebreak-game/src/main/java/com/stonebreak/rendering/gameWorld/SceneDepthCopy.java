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
    private int attachedFormat;
    private int format;
    private int width;
    private int height;
    /** Cleared when the driver rejects a depth blit from the default framebuffer. */
    private boolean defaultBlitSupported = true;
    private boolean defaultBlitProbed;

    /**
     * Copies the current depth buffer into this texture, bound on the ACTIVE
     * texture unit, (re)allocating it when the viewport size or the read
     * framebuffer's depth format changes.
     */
    public void copy() {
        glGetIntegerv(GL_VIEWPORT, viewportBuf);
        int w = Math.max(1, viewportBuf[2]);
        int h = Math.max(1, viewportBuf[3]);
        viewport.set(viewportBuf[0], viewportBuf[1], w, h);
        int readFbo = glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int sourceFormat = readFbo != 0 || defaultBlitSupported ? blitFormat(readFbo) : GL_NONE;
        int wanted = sourceFormat != GL_NONE ? sourceFormat : GL14.GL_DEPTH_COMPONENT24;
        if (texture == 0) {
            texture = glGenTextures();
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        if (w != width || h != height || wanted != format) {
            allocate(wanted, w, h);
        }
        if (sourceFormat == GL_NONE) {
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewportBuf[0], viewportBuf[1], w, h);
        } else if (readFbo == 0 && !defaultBlitProbed) {
            probeDefaultBlit(w, h);
        } else {
            blit(w, h);
        }
    }

    private void allocate(int internalFormat, int w, int h) {
        boolean packed = internalFormat == GL30.GL_DEPTH24_STENCIL8;
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0,
                packed ? GL30.GL_DEPTH_STENCIL : GL_DEPTH_COMPONENT,
                packed ? GL30.GL_UNSIGNED_INT_24_8 : GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        format = internalFormat;
        width = w;
        height = h;
    }

    /**
     * The texture format a depth blit from the read framebuffer needs (a depth
     * blit requires identical depth and stencil formats on both sides): 24-bit
     * depth for the post-fx target, 24/8 depth-stencil for a default window
     * framebuffer. {@code GL_NONE} when the source is neither, which falls
     * back to glCopyTexSubImage2D.
     */
    private static int blitFormat(int readFbo) {
        int depth = readFbo == 0 ? GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
        int stencil = readFbo == 0 ? GL_STENCIL : GL30.GL_STENCIL_ATTACHMENT;
        if (attachmentType(depth) == GL_NONE
                || GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                        depth, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE) != 24) {
            return GL_NONE;
        }
        int stencilBits = attachmentType(stencil) == GL_NONE ? 0
                : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                        stencil, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        return switch (stencilBits) {
            case 0 -> GL14.GL_DEPTH_COMPONENT24;
            case 8 -> GL30.GL_DEPTH24_STENCIL8;
            default -> GL_NONE;
        };
    }

    private static int attachmentType(int attachment) {
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
    }

    /**
     * The first blit from the default framebuffer, checked for errors: whether
     * the window's depth counts as the same format as a depth-stencil texture
     * is up to the driver. On rejection this frame copies instead, and later
     * frames go straight to the copy.
     */
    private void probeDefaultBlit(int w, int h) {
        while (glGetError() != GL_NO_ERROR) {
            // drain, so the check below sees only the blit
        }
        blit(w, h);
        defaultBlitProbed = true;
        if (glGetError() != GL_NO_ERROR) {
            defaultBlitSupported = false;
            allocate(GL14.GL_DEPTH_COMPONENT24, w, h);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewportBuf[0], viewportBuf[1], w, h);
        }
    }

    /**
     * Copies the read framebuffer's depth with a framebuffer blit. Not
     * glCopyTexSubImage2D: the NVIDIA driver (595.x, Blackwell) intermittently
     * faults the GPU channel on a depth-to-depth-texture copy (Xid 32/69,
     * GL_INVALID_OPERATION across the frame, then a SIGSEGV inside
     * libnvidia-glcore) while chunks remesh, from an FBO and from the default
     * framebuffer alike; the blit path never did.
     */
    private void blit(int w, int h) {
        if (blitFbo == 0) {
            blitFbo = GL30.glGenFramebuffers();
        }
        int previousDraw = glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitFbo);
        if (attachedFormat != format) {
            // Re-point the attachment: a depth-stencil texture goes on the
            // combined point, so the stencil formats match too.
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                    format == GL30.GL_DEPTH24_STENCIL8
                            ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D, texture, 0);
            glDrawBuffer(GL_NONE);
            attachedFormat = format;
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
            attachedFormat = 0;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
            format = 0;
            width = 0;
            height = 0;
        }
    }
}
