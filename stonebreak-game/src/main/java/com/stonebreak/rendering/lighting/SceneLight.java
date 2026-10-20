package com.stonebreak.rendering.lighting;

import com.stonebreak.core.Game;
import com.stonebreak.world.TimeOfDay;
import org.joml.Vector3f;

/**
 * The scene's ambient light level for this frame: the sky's (see {@link SkyLight}), darkened
 * under storm cloud and brightened by lightning. Every shader that takes
 * {@code u_ambientLight} reads it here, so weather reaches the world, water and
 * entities alike. The weather terms are set once per frame by the renderer.
 */
public final class SceneLight {

    private static volatile float weatherScale = 1f;
    private static volatile float flash = 0f;
    private static final Vector3f DEFAULT_KEY = new Vector3f(0.5f, 1.0f, 0.3f).normalize();
    private static final Vector3f WHITE = new Vector3f(1f);

    private SceneLight() {}

    /** Ambient light in [0, ~1.3]: the sky's (sun, twilight or moon) × weather, plus any lightning flash. */
    public static float ambient() {
        TimeOfDay time = Game.getTimeOfDay();
        float base = time != null ? SkyLight.of(time).ambient() : 1f;
        return base * weatherScale + flash;
    }

    /**
     * Direction of the light that lights faces and casts shadows: the sun by
     * day, the moon by night ({@link SkyLight#keyDir()}). Every shader's
     * {@code u_sunDirection} takes this, so terrain, entities and water agree.
     */
    public static Vector3f keyDirection() {
        TimeOfDay time = Game.getTimeOfDay();
        return time != null ? SkyLight.of(time).keyDir() : DEFAULT_KEY;
    }

    /** That light's colour × strength: (1, 1, 1) by day, a faint blue by moonlight. */
    public static Vector3f keyLight() {
        TimeOfDay time = Game.getTimeOfDay();
        return time != null ? SkyLight.of(time).keyLight() : WHITE;
    }

    /**
     * {@link #ambient()} without the lightning flash: for the clouds, which take
     * a bolt's light only around the bolt, not across the whole deck.
     */
    public static float steadyAmbient() {
        return ambient() - flash;
    }

    /**
     * @param scale multiplier on the time-of-day ambient (1 = clear sky)
     * @param flashLight additive lightning light (0 = none)
     */
    public static void setWeather(float scale, float flashLight) {
        weatherScale = scale;
        flash = flashLight;
    }
}
