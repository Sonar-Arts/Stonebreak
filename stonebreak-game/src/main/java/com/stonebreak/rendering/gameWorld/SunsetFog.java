package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * This frame's evening gold for the distance fog ({@code sunset_fog.glsl}): the
 * fog fades land into the gold on the sun's side of the sky and into dusk where
 * the gold has drawn back, as the sky behind it does. Published once per frame by
 * the world renderer, in the way {@code WeatherFog} publishes the rain fog.
 */
public final class SunsetFog {

    private static final Vector3f gold = new Vector3f();
    private static final Vector2f sunFlat = new Vector2f(1f, 0f);
    private static float coverage = -1f;

    private SunsetFog() {}

    /**
     * @param horizonGold sunset gold at the horizon × its strength; zero for none
     *                    (underwater, or outside the evening)
     * @param sunsetCoverage how far round from the sun the gold reaches
     * @param sunDirection the sun's direction, whose compass bearing the gold follows
     */
    public static synchronized void set(Vector3f horizonGold, float sunsetCoverage, Vector3f sunDirection) {
        gold.set(horizonGold);
        coverage = sunsetCoverage;
        float len = (float) Math.hypot(sunDirection.x, sunDirection.z);
        if (len > 1e-4f) {
            sunFlat.set(sunDirection.x / len, sunDirection.z / len);
        }
    }

    /** Sets the {@code sunset_fog.glsl} uniforms on the bound shader. */
    public static synchronized void applyTo(ShaderProgram shader) {
        shader.setVec3("u_sunsetFog", gold);
        shader.setFloat("u_sunsetCoverage", coverage);
        shader.setVec2("u_sunFlat", sunFlat);
    }
}
