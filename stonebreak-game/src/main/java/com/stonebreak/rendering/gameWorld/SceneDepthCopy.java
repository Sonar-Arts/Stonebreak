package com.stonebreak.rendering.gameWorld;

import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

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
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewportBuf[0], viewportBuf[1], w, h);
    }

    /** The viewport at the last {@link #copy()}: (x, y, width, height). */
    public Vector4f viewport() {
        return viewport;
    }

    public int texture() {
        return texture;
    }

    public void cleanup() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
    }
}
