package com.stonebreak.rendering.gameWorld.weather;

import org.joml.Vector3f;

/**
 * This frame's rain and snow fog right around the camera, for the entity
 * passes: they take it as their exponential fog instead of marching the
 * precipitation map ({@link PrecipitationFog}), since an entity is never far
 * enough away for the fog to change between it and the eye. Published once per
 * frame by the world renderer, in the way {@code SceneLight} publishes the
 * ambient light.
 */
public final class WeatherFog {

    private static volatile float density;
    private static final Vector3f color = new Vector3f(0.5f);

    private WeatherFog() {}

    /** @param extinction fog extinction per block (0 = off) */
    public static synchronized void set(float extinction, Vector3f fogColor) {
        density = extinction;
        color.set(fogColor);
    }

    /** Extinction per block; 0 when there is no weather fog (or the camera is underwater). */
    public static float density() {
        return density;
    }

    /** Copies the fog colour into {@code dest}. */
    public static synchronized Vector3f color(Vector3f dest) {
        return dest.set(color);
    }
}
