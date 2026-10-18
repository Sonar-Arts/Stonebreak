package com.stonebreak.rendering.lighting;

import com.stonebreak.core.Game;
import com.stonebreak.world.TimeOfDay;

/**
 * The scene's ambient light level for this frame: the time of day's, darkened
 * under storm cloud and brightened by lightning. Every shader that takes
 * {@code u_ambientLight} reads it here, so weather reaches the world, water and
 * entities alike. The weather terms are set once per frame by the renderer.
 */
public final class SceneLight {

    private static volatile float weatherScale = 1f;
    private static volatile float flash = 0f;

    private SceneLight() {}

    /** Ambient light in [0, ~1.3]: time of day × weather, plus any lightning flash. */
    public static float ambient() {
        TimeOfDay time = Game.getTimeOfDay();
        float base = time != null ? time.getAmbientLightLevel() : 1f;
        return base * weatherScale + flash;
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
