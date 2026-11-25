package com.stonebreak.rendering.gameWorld.clouds;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_RGB16F;

/**
 * The clouds' key light by height, {@link CloudSunlight#TEXELS} texels of RGB16F on a 1D
 * texture, re-uploaded once per frame. Linear filtering blends between heights.
 */
final class CloudSunlightTexture {

    /** The unit the march reads it on while the clouds draw. */
    static final int TEXTURE_UNIT = 30;

    private final float[] rgb = new float[3 * CloudSunlight.TEXELS];
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(rgb.length);
    private int texture;

    /** The table to fill this frame ({@link CloudSunlight#bake} or {@link CloudSunlight#fill}). */
    float[] table() {
        return rgb;
    }

    /** Uploads {@link #table()} (creating the texture on first use), leaving the active unit as it was. */
    void upload() {
        int previousUnit = glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + TEXTURE_UNIT);
        int previous = glGetInteger(GL_TEXTURE_BINDING_1D);
        upload.clear();
        upload.put(rgb).flip();
        if (texture == 0) {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_1D, texture);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexImage1D(GL_TEXTURE_1D, 0, GL_RGB16F, CloudSunlight.TEXELS, 0, GL_RGB, GL_FLOAT, upload);
        } else {
            glBindTexture(GL_TEXTURE_1D, texture);
            glTexSubImage1D(GL_TEXTURE_1D, 0, 0, CloudSunlight.TEXELS, GL_RGB, GL_FLOAT, upload);
        }
        glBindTexture(GL_TEXTURE_1D, previous);
        glActiveTexture(previousUnit);
    }

    /** Binds it on {@link #TEXTURE_UNIT}; returns the 1D texture that was there, for {@link #unbind}. */
    int bind() {
        int previousUnit = glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + TEXTURE_UNIT);
        int previous = glGetInteger(GL_TEXTURE_BINDING_1D);
        glBindTexture(GL_TEXTURE_1D, texture);
        glActiveTexture(previousUnit);
        return previous;
    }

    /** Puts back the 1D texture {@link #bind} replaced. */
    void unbind(int previous) {
        int previousUnit = glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_1D, previous);
        glActiveTexture(previousUnit);
    }

    void cleanup() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
    }
}
