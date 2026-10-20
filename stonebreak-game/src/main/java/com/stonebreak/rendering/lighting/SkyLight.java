package com.stonebreak.rendering.lighting;

import com.stonebreak.world.MoonCycle;
import com.stonebreak.world.TimeOfDay;
import org.joml.Vector3f;

/**
 * The sky's light at one moment: what the sky looks like, and what light it
 * and the sun or moon cast on the world, the clouds and the fog. Everything
 * that is lit takes its light from here, so nothing can glow brighter than the
 * sky it hangs in.
 *
 * <p>The palette follows the real sky:
 * <ul>
 *   <li><b>Twilight is blue.</b> With the sun just below the horizon the zenith
 *       turns deep blue. Rayleigh scattering gives about a third of that blue,
 *       and ozone's Chappuis absorption over the long grazing path gives about
 *       two-thirds. Orange appears only in a band low on the horizon toward the
 *       sun ({@link #sunGlow}). The bands are civil (0° to −6°), nautical (−6° to
 *       −12°) and astronomical (−12° to −18°).</li>
 *   <li><b>Moonlight reads as blue.</b> Physically it is slightly reddened
 *       sunlight, about 4000 K. In dim light rod vision takes over (the Purkinje
 *       shift) and we see it as blue, which is why film grades night blue. A
 *       full-moon sky is indigo; a new-moon sky is close to black.</li>
 * </ul>
 *
 * <p>The evening keeps the warm gold Stonebreak's sunset had before the moon
 * (the README logo's golden hour), but as a region of the sky, not the whole
 * dome. From 4 PM the gold grows in until, as the sun touches the horizon at
 * 6 PM, it covers the sky (a little thinner opposite the sun). Then it draws back:
 * the anti-sun horizon goes to the blue hour first, then the zenith, and last
 * the sun's own side, before the moonlit night. {@link #sunsetWeight} is the
 * shape; the sky shader draws it per pixel, and {@code zenith}/{@code horizon}
 * here already include it for the fog and clouds.
 *
 * <p>Vectors are shared and must not be modified; copy one to change it.
 */
public record SkyLight(
        Vector3f sunDir,
        Vector3f moonDir,
        /* Phase in [0, 1): 0 = new, 0.5 = full. */
        float moonPhase,
        /* Moonlight relative to a full moon overhead: phase curve × altitude, 0 once the moon sets. */
        float moonLight,
        /* Sky colour overhead and at the horizon, sunset gold included (what fog and clouds read). */
        Vector3f zenith,
        Vector3f horizon,
        /* The same without the sunset: the sky shader draws the gold itself, per direction. */
        Vector3f clearZenith,
        Vector3f clearHorizon,
        /* Sunset gold × how far it has grown in (0..1); black outside the evening. */
        Vector3f sunsetColor,
        /* How much of the sky the gold covers, as a {@link #sunsetWeight} threshold. */
        float sunsetCoverage,
        /* Warm light low on the horizon toward the sun, premultiplied by its strength. */
        Vector3f sunGlow,
        /* What fog, haze and distance fog fade toward: between zenith and horizon. */
        Vector3f skyColor,
        /* 0 with the sun below the horizon, 1 once it is 10° up. */
        float daylight,
        /* 0..1: how much the sunset gold holds the sky overhead (the evening). */
        float evening,
        /* Ambient light level for rendering, the same scale as TimeOfDay's (1 = full day). */
        float ambient,
        /* The light that casts shadows and lights faces: the sun, or the moon at night. */
        Vector3f keyDir,
        /* Its colour × strength: white (1,1,1) by day, a faint cool blue by moonlight. */
        Vector3f keyLight,
        boolean keyIsMoon) {

    // Palette (linear, the game's sky colour space).
    private static final Vector3f DAY_ZENITH = new Vector3f(0.53f, 0.81f, 0.92f);
    private static final Vector3f DAY_HORIZON = new Vector3f(0.60f, 0.86f, 0.95f);
    private static final Vector3f CIVIL_ZENITH = new Vector3f(0.17f, 0.24f, 0.48f);
    private static final Vector3f CIVIL_HORIZON = new Vector3f(0.30f, 0.30f, 0.42f);
    private static final Vector3f NAUTICAL_ZENITH = new Vector3f(0.09f, 0.11f, 0.29f);
    private static final Vector3f NAUTICAL_HORIZON = new Vector3f(0.18f, 0.17f, 0.30f);
    private static final Vector3f FULL_MOON_ZENITH = new Vector3f(0.050f, 0.070f, 0.165f);
    private static final Vector3f FULL_MOON_HORIZON = new Vector3f(0.085f, 0.105f, 0.190f);
    private static final Vector3f NEW_MOON_ZENITH = new Vector3f(0.008f, 0.010f, 0.028f);
    private static final Vector3f NEW_MOON_HORIZON = new Vector3f(0.016f, 0.020f, 0.040f);
    private static final Vector3f GLOW = new Vector3f(1.0f, 0.55f, 0.30f);
    /** The pre-moon sunset sky, whole-sky orange; its horizon was 1.2× it. */
    private static final Vector3f SUNSET = new Vector3f(1.0f, 0.5f, 0.3f);
    private static final float SUNSET_HORIZON = 1.2f;
    /**
     * The evening's ticks: the gold grows in from 4 PM to 6 PM (as the pre-moon sky
     * did), and from sunset at 6 PM draws back toward the sun until 8 PM.
     */
    private static final long EVENING_START = 10_000, SUNSET_FULL = 12_000, RETREAT_START = 12_000,
            EVENING_END = 14_000;
    /** At its height the gold covers the sky, about 85% strength on the horizon opposite the sun. */
    static final float PEAK_COVERAGE = 1.15f;
    /** Width of the gold's soft edge, in {@link #sunsetWeight}'s distance units. */
    static final float SUNSET_SOFTNESS = 0.3f;
    /** Moonlight on the ground: desaturated cool blue (see the class comment). */
    private static final Vector3f MOONLIGHT = new Vector3f(0.70f, 0.80f, 1.0f);

    /**
     * Sun elevations (degrees) where the palette's stops sit. Stretched a little past
     * the real bands (−18°, −12°, −6°): the game's sun sinks about 1.4° per real
     * second, which would leave the blue hour barely a minute long.
     */
    private static final float ASTRONOMICAL = -24f, NAUTICAL = -12f, CIVIL = -4f, DAY = 10f;

    /** Ambient with no moon, and the extra a full moon overhead adds. */
    static final float NIGHT_AMBIENT = 0.09f, MOON_AMBIENT = 0.13f;
    /** Ambient with the sun on the horizon, as {@link TimeOfDay#getAmbientLightLevel()} has it. */
    private static final float HORIZON_AMBIENT = 0.3f;
    /** Moonlight's diffuse strength relative to the sun's: faint, so moon shadows stay soft. */
    static final float MOON_KEY = 0.6f;
    /** Sun elevation (y) below which the moon becomes the key light. The sun's own shadows are gone by then. */
    static final float KEY_SWITCH_Y = -0.05f;

    private static SkyLight last;
    private static long lastTicks = Long.MIN_VALUE;

    /** The sky at {@code time}'s moment; the last result is kept, since every pass asks each frame. */
    public static SkyLight of(TimeOfDay time) {
        long ticks = time.getTotalTicks();
        SkyLight cached = last;
        if (cached != null && lastTicks == ticks) {
            return cached;
        }
        SkyLight sky = compute(time.getSunDirection(), MoonCycle.direction(time), MoonCycle.phase(time),
                time.getAmbientLightLevel(), time.getTicks());
        last = sky;
        lastTicks = ticks;
        return sky;
    }

    /**
     * @param dayAmbient {@link TimeOfDay#getAmbientLightLevel()}: the daytime ambient,
     *                   used while the sun is up
     * @param tick       the time of day, 0..23999 (the evening palette is set in ticks)
     */
    static SkyLight compute(Vector3f sunDir, Vector3f moonDir, float phase, float dayAmbient, long tick) {
        float sunElevation = (float) Math.toDegrees(Math.asin(Math.clamp(sunDir.y, -1f, 1f)));

        // The moon lights the sky only while it is up, and fully only once clear of the horizon.
        float moonLight = MoonCycle.relativeBrightness(phase) * smoothstep(-0.05f, 0.2f, moonDir.y);
        Vector3f nightZenith = lerp(NEW_MOON_ZENITH, FULL_MOON_ZENITH, moonLight);
        Vector3f nightHorizon = lerp(NEW_MOON_HORIZON, FULL_MOON_HORIZON, moonLight);

        Vector3f zenith = ramp(sunElevation, nightZenith, NAUTICAL_ZENITH, CIVIL_ZENITH, DAY_ZENITH);
        Vector3f horizon = ramp(sunElevation, nightHorizon, NAUTICAL_HORIZON, CIVIL_HORIZON, DAY_HORIZON);
        float glow = smoothstep(-8f, -1f, sunElevation) * (1f - smoothstep(4f, 14f, sunElevation));
        Vector3f skyColor = lerp(zenith, horizon, 0.5f);

        // The evening's gold: grown in like the pre-moon sunset, then drawn back toward the sun.
        Vector3f clearZenith = zenith, clearHorizon = horizon;
        float sunset = 0f, coverage = -SUNSET_SOFTNESS;
        if (tick >= EVENING_START && tick < EVENING_END) {
            sunset = Math.min(1f, (tick - EVENING_START) / (float) (SUNSET_FULL - EVENING_START));
            float retreat = Math.clamp((tick - RETREAT_START) / (float) (EVENING_END - RETREAT_START), 0f, 1f);
            coverage = lerp(PEAK_COVERAGE, -SUNSET_SOFTNESS, retreat);
        }
        Vector3f sunsetColor = new Vector3f(SUNSET).mul(sunset);
        // Overhead sits half-way round from the sun; the horizon averages its ring.
        float evening = sunset * sunsetWeight(coverage, 0.5f);
        float horizonShare = sunset * Math.clamp(coverage, 0f, 1f);
        zenith = lerp(clearZenith, SUNSET, evening);
        horizon = lerp(clearHorizon, new Vector3f(SUNSET).mul(SUNSET_HORIZON), horizonShare);
        skyColor = lerp(lerp(zenith, horizon, 0.5f), zenith, evening);
        // While the gold holds the sky it is the glow; the band returns as it goes.
        Vector3f sunGlow = new Vector3f(GLOW).mul(0.8f * glow * (1f - sunset));

        float daylight = smoothstep(0f, DAY, sunElevation);
        float nightAmbient = NIGHT_AMBIENT + MOON_AMBIENT * moonLight;
        float ambient = sunElevation >= 0f
                ? Math.max(dayAmbient, nightAmbient)
                : lerp(nightAmbient, HORIZON_AMBIENT, smoothstep(ASTRONOMICAL, 0f, sunElevation));

        boolean keyIsMoon = sunDir.y <= KEY_SWITCH_Y;
        Vector3f keyDir;
        Vector3f keyLight;
        if (keyIsMoon) {
            // Fades in only once the sun is 6° down, after the sun's light has gone.
            float strength = MOON_KEY * moonLight * smoothstep(KEY_SWITCH_Y, -0.105f, sunDir.y);
            keyDir = new Vector3f(moonDir);
            keyLight = new Vector3f(MOONLIGHT).mul(strength);
        } else {
            keyDir = new Vector3f(sunDir);
            keyLight = new Vector3f(1f);
        }
        return new SkyLight(new Vector3f(sunDir), new Vector3f(moonDir), phase, moonLight,
                zenith, horizon, clearZenith, clearHorizon, sunsetColor, coverage,
                sunGlow, skyColor, daylight, evening, ambient, keyDir, keyLight, keyIsMoon);
    }

    /**
     * How golden the sky is in a direction, 0..1. {@code fromSun} runs 0 at the
     * horizon under the sun, through 0.5 overhead (and at the horizon to either
     * side), to 1 at the horizon opposite it: 0.5 − 0.5·dot(view, sun's compass
     * direction). As {@code coverage} falls, the gold leaves the high values first.
     * sky.frag mirrors this.
     */
    public static float sunsetWeight(float coverage, float fromSun) {
        return 1f - smoothstep(coverage - SUNSET_SOFTNESS, coverage + SUNSET_SOFTNESS, fromSun);
    }


    /** Night → nautical → civil → day across the sun's elevation in degrees. */
    private static Vector3f ramp(float elevation, Vector3f night, Vector3f nautical, Vector3f civil, Vector3f day) {
        if (elevation <= NAUTICAL) {
            return lerp(night, nautical, smoothstep(ASTRONOMICAL, NAUTICAL, elevation));
        }
        if (elevation <= CIVIL) {
            return lerp(nautical, civil, smoothstep(NAUTICAL, CIVIL, elevation));
        }
        return lerp(civil, day, smoothstep(CIVIL, DAY, elevation));
    }

    /** Rec. 601-ish weights, as the weather code measures brightness. */
    public static float luminance(Vector3f c) {
        return 0.3f * c.x + 0.55f * c.y + 0.15f * c.z;
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a).lerp(b, t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
