package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.lighting.SkyLight;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * This frame's golden hour for the distance fog ({@code sunset_fog.glsl}): the
 * fog fades land into the gold on the sun's side of the sky and into dusk where
 * the gold has drawn back, as the sky behind it does. Opposite the sun it takes
 * the Earth's shadow, and toward the sun the sun's aureole, both as the sky
 * draws them. Published once per frame by the world renderer, in the way
 * {@code WeatherFog} publishes the rain fog.
 */
public final class SunsetFog {

    private static final Vector3f gold = new Vector3f();
    private static final Vector2f sunFlat = new Vector2f(1f, 0f);
    private static float coverage = -1f;
    private static final Vector3f sunDir = new Vector3f(0f, 1f, 0f);
    private static final Vector3f shadow = new Vector3f();
    private static float shadowHeight, band;
    private static final Vector3f aureole = new Vector3f();
    /** The sky's glow round the sun (sky.frag getSunContribution): its tint over sunlight, and strength. */
    private static final Vector3f AUREOLE_TINT = new Vector3f(1.0f, 0.8f, 0.5f);
    private static final float AUREOLE = 0.4f;
    /** sky.frag SUN_SINK: how far below the sea's edge the sun takes to fade out. */
    private static final float SUN_SINK = 0.12f;

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
        sunDir.set(sunDirection);
        float len = (float) Math.hypot(sunDirection.x, sunDirection.z);
        if (len > 1e-4f) {
            sunFlat.set(sunDirection.x / len, sunDirection.z / len);
        }
    }

    /**
     * @param earthShadow   the Earth's shadow colour, as the sky draws it
     * @param edgeHeight    its edge's height opposite the sun (sine of elevation)
     * @param twilightBand  how strongly it shows (0 = not at all)
     * @param sunAureole    the sky's glow round the sun ({@link #sunAureole}); zero for none
     */
    public static synchronized void setTwilight(Vector3f earthShadow, float edgeHeight, float twilightBand,
                                                Vector3f sunAureole) {
        shadow.set(earthShadow);
        shadowHeight = edgeHeight;
        band = twilightBand;
        aureole.set(sunAureole);
    }

    /**
     * The sky's glow round the sun, into {@code dest}: what the land toward the sun
     * fades into. {@code clear} (0..1) dims it under overcast, which hides the sun.
     * {@code seaHorizon} is the sea's edge from the eye ({@link #seaHorizon}).
     */
    public static Vector3f sunAureole(SkyLight sky, float clear, float seaHorizon, Vector3f dest) {
        // sky.frag's glow: full until the sun reaches the sea's edge, gone SUN_SINK below it.
        float t = Math.clamp((sky.sunDir().y - seaHorizon + SUN_SINK) / SUN_SINK, 0f, 1f);
        float lit = t * t * (3f - 2f * t);
        return dest.set(AUREOLE_TINT).mul(sky.sunColor()).mul(AUREOLE * lit * clear);
    }

    /**
     * The sea's edge as seen from {@code eyeY}, as the sine of its elevation (0 or below):
     * the sea is drawn out to {@code reach} blocks, so from above it its edge dips under
     * eye level. The sun sets there, not on the eye-level horizon.
     */
    public static float seaHorizon(float eyeY, float reach) {
        float above = Math.max(eyeY - WorldConfiguration.SEA_LEVEL, 0f);
        return (float) -Math.sin(Math.atan2(above, reach));
    }

    /** Sets the {@code sunset_fog.glsl} uniforms on the bound shader. */
    public static synchronized void applyTo(ShaderProgram shader) {
        shader.setVec3("u_sunsetFog", gold);
        shader.setFloat("u_sunsetCoverage", coverage);
        shader.setVec2("u_sunFlat", sunFlat);
        shader.setVec3("u_sunDir3", sunDir);
        shader.setVec3("u_earthShadow", shadow);
        shader.setFloat("u_earthShadowHeight", shadowHeight);
        shader.setFloat("u_twilightBand", band);
        shader.setVec3("u_sunAureole", aureole);
    }
}
