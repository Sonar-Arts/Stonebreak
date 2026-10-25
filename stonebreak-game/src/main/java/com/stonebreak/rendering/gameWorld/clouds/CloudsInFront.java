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

    /** Texture units of the cloud buffer and its cloud distances; no other pass of these shaders uses them. */
    public static final int TEXTURE_UNIT = 20;
    public static final int DISTANCE_TEXTURE_UNIT = 23;

    private static final int[] VIEWPORT = new int[4];
    private static final Vector4f VIEWPORT_VEC = new Vector4f();

    private CloudsInFront() {}

    /** Points the samplers at their units. Call once per shader: an unset sampler shares unit 0. */
    public static void bindSampler(ShaderProgram shader) {
        shader.setInt("u_cloudBuffer", TEXTURE_UNIT);
        shader.setInt("u_cloudDistanceBuffer", DISTANCE_TEXTURE_UNIT);
    }

    /**
     * Turns the effect on for a BOUND shader and binds {@code clouds} (its
     * buffer and cloud distances; no clouds drawn this frame leaves the effect
     * off). Returns what was bound on the units, for {@link #disable}.
     */
    public static Bindings enable(ShaderProgram shader, CloudRenderer clouds) {
        int buffer = clouds != null ? clouds.transmittanceTexture() : 0;
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        int previous = glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, buffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DISTANCE_TEXTURE_UNIT);
        int previousDistance = glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, buffer != 0 ? clouds.distanceTexture() : 0);
        GL13.glActiveTexture(previousUnit);

        glGetIntegerv(GL_VIEWPORT, VIEWPORT);
        shader.setVec4("u_cloudViewport", VIEWPORT_VEC.set(VIEWPORT[0], VIEWPORT[1],
                Math.max(1, VIEWPORT[2]), Math.max(1, VIEWPORT[3])));
        shader.setFloat("u_cloudSlabBottom", CloudType.slabBottom());
        shader.setFloat("u_cloudSlabTop", CloudType.slabTop());
        shader.setBool("u_cloudsInFront", buffer != 0);
        return new Bindings(previous, previousDistance);
    }

    /** The textures {@link #enable} replaced on its units. */
    public record Bindings(int buffer, int distance) {}

    /** Turns the effect off for a BOUND shader and restores the units' textures. */
    public static void disable(ShaderProgram shader, Bindings previous) {
        shader.setBool("u_cloudsInFront", false);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previous.buffer());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DISTANCE_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previous.distance());
        GL13.glActiveTexture(previousUnit);
    }
}
