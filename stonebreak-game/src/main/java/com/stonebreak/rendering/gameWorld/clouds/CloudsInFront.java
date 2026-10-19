package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.world.weather.CloudType;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

/**
 * Feeds {@code clouds_in_front.glsl}: translucent passes drawn after the clouds
 * composite themselves behind the cloud in front of them instead of over it.
 */
public final class CloudsInFront {

    /** Texture unit of the cloud buffer; no other pass of these shaders uses it. */
    public static final int TEXTURE_UNIT = 20;

    private static final int[] VIEWPORT = new int[4];
    private static final Vector4f VIEWPORT_VEC = new Vector4f();

    private CloudsInFront() {}

    /** Points the sampler at its unit. Call once per shader: an unset sampler shares unit 0. */
    public static void bindSampler(ShaderProgram shader) {
        shader.setInt("u_cloudBuffer", TEXTURE_UNIT);
    }

    /**
     * Turns the effect on for a BOUND shader and binds {@code cloudBuffer} (0 = no
     * clouds this frame: the effect stays off). Returns the texture that was bound
     * on the unit, for {@link #disable}.
     */
    public static int enable(ShaderProgram shader, int cloudBuffer) {
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        int previous = glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, cloudBuffer);
        GL13.glActiveTexture(previousUnit);

        glGetIntegerv(GL_VIEWPORT, VIEWPORT);
        shader.setVec4("u_cloudViewport", VIEWPORT_VEC.set(VIEWPORT[0], VIEWPORT[1],
                Math.max(1, VIEWPORT[2]), Math.max(1, VIEWPORT[3])));
        shader.setFloat("u_cloudSlabBottom", CloudType.slabBottom());
        shader.setFloat("u_cloudSlabTop", CloudType.slabTop());
        shader.setBool("u_cloudsInFront", cloudBuffer != 0);
        return previous;
    }

    /** Turns the effect off for a BOUND shader and restores the unit's texture. */
    public static void disable(ShaderProgram shader, int previousTexture) {
        shader.setBool("u_cloudsInFront", false);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        GL13.glActiveTexture(previousUnit);
    }
}
