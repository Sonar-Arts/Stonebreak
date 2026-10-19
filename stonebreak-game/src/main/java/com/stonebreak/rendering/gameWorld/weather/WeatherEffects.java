package com.stonebreak.rendering.gameWorld.weather;

import com.stonebreak.rendering.lighting.SceneLight;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Vector3f;

/**
 * How the weather around the camera changes the look of the scene: under rain
 * and storm cloud the light dims and the sky goes grey, and this sets how thick
 * and what colour the rain and snow fog is ({@link PrecipitationFog} places it
 * in the world). Driven by the same {@link ClientWeather} field the clouds and
 * rain come from, eased over a few seconds so walking under a cloud edge
 * doesn't pop.
 *
 * <p>The fog is Beer-Lambert extinction, {@code exp(-b·d)}, the same law the
 * clouds use. Its coefficient comes from a visibility through Koschmieder's
 * relation {@code b = 3.912 / V} (the distance at which contrast falls to 2%).
 * The visibilities follow the meteorological ordering: rain thins the view
 * less than snow does at the same precipitation rate, because flakes are
 * large, slow and bright. Heavy snow is reported below 400 m, and heavy rain
 * keeps roughly a kilometre. Both are compressed here to fit the render
 * distance.
 */
public final class WeatherEffects {

    /** Time constant of the ease toward the local storminess. */
    private static final float RESPONSE_SECONDS = 4f;
    /** Spacing of the samples averaged around the camera, in blocks. */
    private static final float SAMPLE_SPREAD = 24f;
    /** Most the ambient light drops under the heaviest storm. */
    private static final float MAX_DIMMING = 0.45f;
    /** Most the sky moves toward storm grey. */
    private static final float MAX_GREYING = 0.6f;

    /** Visibility in the heaviest rain, in blocks. */
    static final float HEAVY_RAIN_VISIBILITY = 192f;
    /** Visibility in the heaviest snow, in blocks. */
    static final float HEAVY_SNOW_VISIBILITY = 64f;
    /** Koschmieder's constant: ln(1 / 0.02), the 2% contrast threshold. */
    private static final float KOSCHMIEDER = 3.912f;
    /**
     * How visibility scales with precipitation: {@code V ∝ rate^-0.7}, which
     * lies between the power laws fitted for rain and for snow. Light
     * precipitation therefore already hazes the view, rather than fog
     * appearing only at the peak of a storm.
     */
    static final float RATE_EXPONENT = 0.7f;
    /** How much lightning lights up the fog, as a share of the flash. */
    private static final float FLASH_IN_FOG = 0.6f;
    /**
     * Light left in rain fog under the thickest cloud: storm curtains are dark.
     * Snow keeps more, because its bright flakes scatter what light there is.
     */
    private static final float RAIN_SHADE_MIN = 0.4f;
    private static final float SNOW_SHADE_MIN = 0.8f;
    /** Blocks under the fog's top over which it thins out, as precip_fog.glsl thins it. */
    private static final float BASE_BAND = 48f;
    /**
     * Fog brightness as a share of the time of day's ambient light: about what a
     * pale surface in that light reflects. Tied to the light the terrain gets,
     * not to the sky's own glow, so the fog fades the land toward its own
     * light instead of laying a bright sheet over it at dusk.
     */
    private static final float FOG_BRIGHTNESS = 0.72f;
    /** How much of the sky's hue the fog takes: orange at sunset, blue-grey by day. */
    private static final float SKY_HUE = 0.5f;

    private float storminess;
    private float snow;
    private float extinction;
    private float flash;
    private final Vector3f fogColor = new Vector3f(0.5f);
    private float lastTime = Float.NaN;

    /**
     * Updates from the weather around {@code cameraPos} and publishes the
     * light change to {@link SceneLight}. Call once per frame, before the
     * frame's ambient light is read.
     *
     * @param flash lightning light to add this frame (0 = none)
     */
    public void update(ClientWeather weather, World world, Vector3f cameraPos, float totalTime, float flash) {
        float target = weather == null ? 0f : sampleAround(weather, cameraPos);
        float targetSnow = world == null ? snow : snowAround(world, cameraPos);
        if (Float.isNaN(lastTime)) {
            storminess = target; // first frame: start where the weather already is
            snow = targetSnow;
        } else {
            float dt = Math.max(0f, totalTime - lastTime);
            float ease = 1f - (float) Math.exp(-dt / RESPONSE_SECONDS);
            storminess += (target - storminess) * ease;
            snow += (targetSnow - snow) * ease;
        }
        lastTime = totalTime;
        this.flash = flash;
        // Rain and snow fall from the cloud base down; above the clouds the air is clear.
        float under = smoothstep(Math.clamp((PrecipitationFog.TOP - cameraPos.y) / BASE_BAND, 0f, 1f));
        extinction = extinction(storminess, snow) * under;
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

    /** Share of the ground around the camera where it snows instead of rains (as the precipitation renderer decides). */
    private static float snowAround(World world, Vector3f p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z), s = (int) SAMPLE_SPREAD;
        int n = 0;
        if (WeatherSurface.isSnow(world, x, z)) n++;
        if (WeatherSurface.isSnow(world, x + s, z)) n++;
        if (WeatherSurface.isSnow(world, x - s, z)) n++;
        if (WeatherSurface.isSnow(world, x, z + s)) n++;
        if (WeatherSurface.isSnow(world, x, z - s)) n++;
        return n / 5f;
    }

    /**
     * Fog extinction coefficient (per block) for a precipitation rate in
     * [0, 1], where {@code snow} is the share of it falling as snow.
     */
    static float extinction(float precipitation, float snow) {
        if (precipitation <= 0f) return 0f;
        float heaviest = KOSCHMIEDER / HEAVY_RAIN_VISIBILITY
                + (KOSCHMIEDER / HEAVY_SNOW_VISIBILITY - KOSCHMIEDER / HEAVY_RAIN_VISIBILITY) * snow;
        return heaviest * (float) Math.pow(Math.min(precipitation, 1f), RATE_EXPONENT);
    }

    /** 0 = clear, 1 = the heaviest storm, around the camera. */
    public float storminess() {
        return storminess;
    }

    /**
     * Fog extinction right around the camera, per block: for the passes that
     * cannot march the precipitation map (entities). 0 above the clouds.
     */
    public float extinction() {
        return extinction;
    }

    /** Extinction at the heaviest rate, per block, for the snow share around the camera. */
    public float heavyExtinction() {
        return extinction(1f, snow);
    }

    /** Light left in the fog under the thickest cloud, for the snow share around the camera. */
    public float shadeMin() {
        return RAIN_SHADE_MIN + (SNOW_SHADE_MIN - RAIN_SHADE_MIN) * snow;
    }

    /** Fog colour under a thin cloud, set by the last {@link #updateFogColor}. */
    public Vector3f fogColor() {
        return fogColor;
    }

    /**
     * {@link #fogColor()} as it is right around the camera, darkened by the
     * storm overhead the way the map shades it, into {@code dest}.
     */
    public Vector3f localFogColor(Vector3f dest) {
        return dest.set(fogColor).mul(1f + (shadeMin() - 1f) * storminess);
    }

    /** Greys {@code sky} toward a storm overcast of the same brightness, into {@code dest}. */
    public Vector3f tintSky(Vector3f sky, Vector3f dest) {
        float grey = luminance(sky) * 0.6f;
        return dest.set(sky).lerp(new Vector3f(grey, grey * 1.04f, grey * 1.12f), MAX_GREYING * storminess);
    }

    /**
     * Sets {@link #fogColor()}: the light the fog scatters toward the eye.
     * {@code sky} and {@code ambient} are the time of day's own, before any
     * storm: the map shades the fog by the cloud over each place, so the storm
     * over the camera must not darken a distant shower too.
     */
    public void updateFogColor(Vector3f sky, float ambient) {
        float lum = Math.max(luminance(sky), 1e-3f);
        float brightness = FOG_BRIGHTNESS * ambient;
        // The sky's hue at unit brightness, partly greyed: rain is grey, but it
        // is lit by the sunset like everything else.
        float hr = 1f + (sky.x / lum - 1f) * SKY_HUE;
        float hg = 1f + (sky.y / lum - 1f) * SKY_HUE;
        float hb = 1f + (sky.z / lum - 1f) * SKY_HUE;
        // Rain fog is a cool grey. Snow scatters far more, and its bright
        // flakes push the air toward white.
        float rainR = brightness * hr * 0.94f, rainG = brightness * hg, rainB = brightness * hb * 1.07f;
        float bright = Math.min(brightness * 1.3f, 0.95f);
        float snowR = bright * hr * 0.97f, snowG = bright * hg * 0.99f, snowB = bright * hb * 1.04f;
        float glow = FLASH_IN_FOG * flash;
        fogColor.set(rainR + (snowR - rainR) * snow + glow * 0.85f,
                rainG + (snowG - rainG) * snow + glow * 0.88f,
                rainB + (snowB - rainB) * snow + glow);
    }

    private static float luminance(Vector3f c) {
        return 0.3f * c.x + 0.55f * c.y + 0.15f * c.z;
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }
}
