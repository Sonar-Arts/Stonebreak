package com.stonebreak.rendering.gameWorld.weather;

import com.stonebreak.rendering.lighting.SceneLight;
import com.stonebreak.world.weather.ClientWeather;
import org.joml.Vector3f;

/**
 * How the weather around the camera changes the look of the scene: under rain
 * and storm cloud the light dims, the sky and fog go grey and the haze closes
 * in. Driven by the same {@link ClientWeather} field the clouds and rain come
 * from, eased over a few seconds so walking under a cloud edge doesn't pop.
 */
public final class WeatherEffects {

    /** Time constant of the ease toward the local storminess. */
    private static final float RESPONSE_SECONDS = 4f;
    /** Spacing of the samples averaged around the camera, in blocks. */
    private static final float SAMPLE_SPREAD = 24f;
    /** Most the ambient light drops under the heaviest storm. */
    private static final float MAX_DIMMING = 0.45f;
    /** Most the sky and fog move toward storm grey. */
    private static final float MAX_GREYING = 0.6f;
    /** Fraction of the clear-weather fog distance left in the heaviest storm. */
    private static final float STORM_FOG_FRACTION = 0.35f;

    private float storminess;
    private float lastTime = Float.NaN;

    /**
     * Updates from the weather around {@code cameraPos} and publishes the
     * light change to {@link SceneLight}. Call once per frame, before the
     * frame's ambient light is read.
     *
     * @param flash lightning light to add this frame (0 = none)
     */
    public void update(ClientWeather weather, Vector3f cameraPos, float totalTime, float flash) {
        float target = weather == null ? 0f : sampleAround(weather, cameraPos);
        if (Float.isNaN(lastTime)) {
            storminess = target; // first frame: start where the weather already is
        } else {
            float dt = Math.max(0f, totalTime - lastTime);
            storminess += (target - storminess) * (1f - (float) Math.exp(-dt / RESPONSE_SECONDS));
        }
        lastTime = totalTime;
        SceneLight.setWeather(1f - MAX_DIMMING * storminess, flash);
    }

    private static float sampleAround(ClientWeather weather, Vector3f p) {
        float sum = weather.precipitationAt(p.x, p.z);
        sum += weather.precipitationAt(p.x + SAMPLE_SPREAD, p.z);
        sum += weather.precipitationAt(p.x - SAMPLE_SPREAD, p.z);
        sum += weather.precipitationAt(p.x, p.z + SAMPLE_SPREAD);
        sum += weather.precipitationAt(p.x, p.z - SAMPLE_SPREAD);
        return sum / 5f;
    }

    /** 0 = clear, 1 = the heaviest storm, around the camera. */
    public float storminess() {
        return storminess;
    }

    /** Greys {@code sky} toward a storm overcast of the same brightness, into {@code dest}. */
    public Vector3f tintSky(Vector3f sky, Vector3f dest) {
        float grey = (0.3f * sky.x + 0.55f * sky.y + 0.15f * sky.z) * 0.6f;
        return dest.set(sky).lerp(new Vector3f(grey, grey * 1.04f, grey * 1.12f), MAX_GREYING * storminess);
    }

    /** Shortens a fog end distance for rain haze; {@code clearFogEnd} is the clear-weather end. */
    public float fogEnd(float clearFogEnd) {
        return clearFogEnd * (1f - (1f - STORM_FOG_FRACTION) * storminess);
    }
}
