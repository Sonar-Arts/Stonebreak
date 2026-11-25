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
 *       shift) and we see it as blue, which is why film grades night blue. That
 *       shift is not painted into the palette here: the moonlight and the night sky
 *       stay close to their physical colours, and the final pass grades dim light
 *       toward rod vision as the eye adapts ({@link #nightAdaptation}). A full-moon
 *       sky is a dim blue; a new-moon sky is close to black.</li>
 * </ul>
 *
 * <p><b>Exposure.</b> The scene is tone mapped with the fitted ACES curve, whose toe
 * would crush a dark scene, so the exposure rises as the sky darkens ({@link #exposure}),
 * as an eye adapts: {@link #DAY_EXPOSURE} by day, more by night, most on a moonless one.
 *
 * <p>The golden hour is a warm band low on the sky, broad toward the sun and thin
 * opposite it; the zenith stays blue. Real twilight keeps a deep-blue zenith because
 * the sunlight reaching it has crossed the ozone layer at a grazing angle, and ozone's
 * Chappuis band absorbs its orange and red. So the gold falls off with height as
 * exp(−y/H), its scale height H low opposite the sun ({@link #SUNSET_H_AWAY}) and higher
 * toward it ({@link #SUNSET_H_SUN}), where forward scattering lifts the glow. Its hue
 * pales with height, deep orange on the horizon ({@link #SUNSET_GOLD_LOW}, where the
 * light crossed the most air) to pale gold at H ({@link #SUNSET_GOLD_HIGH}). It follows
 * the sun's height, not the clock: from 4 PM the band grows in until, as the sun
 * touches the horizon at 6 PM, it reaches about two-thirds of the way round. Then it
 * draws back toward the sun, leaving the anti-sun horizon to the Earth's shadow first.
 * {@link #sunsetWeight} is the shape; the sky shader draws it per pixel, and
 * {@code zenith}/{@code horizon} here already include it for the fog and clouds.
 *
 * <p>The real golden hour, with the sun between about +6° and −4°, is warm directional
 * light: the sun itself reddens toward the horizon ({@link #sunColor}), and so do the
 * faces it lights. Once it has set, the sky keeps a red afterglow over the point where it
 * went down ({@link #sunGlow}). Opposite it, the Earth's own shadow rises from the
 * horizon as a slate band with the pink Belt of Venus above it ({@link #twilightBand}).
 * Then come the blue hour (−4° to −6°) and nautical twilight.
 *
 * <p><b>Dawn is not dusk.</b> By evening the day has lifted dust, smoke and sea salt
 * into the air, and the night lets it settle, so the dusk sky carries more aerosol
 * than the dawn sky ({@link #aerosol}). Aerosol scatters all colours alike and mostly
 * forward, so at dusk the glow round the sun is broader and warmer, the afterglow
 * stronger and the horizon hazier, and the beam reaching the clouds redder; dawn's
 * cleaner air keeps purer pinks and blues.
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
        /* The afterglow: red light low on the horizon toward the sun, premultiplied by its strength. */
        Vector3f sunGlow,
        /* Sunlight's hue (brightest channel 1): white high up, gold low down, the sunset orange on the horizon. */
        Vector3f sunColor,
        /* Sunlight reaching the world: {@link #sunColor} × its strength, black once the sun is down. */
        Vector3f sunLight,
        /* The Earth's shadow on the sky opposite the sun: its colour, and its edge's height there (sine of elevation). */
        Vector3f earthShadowColor,
        float earthShadowHeight,
        /* 0..1: how strongly the Earth's shadow and the Belt of Venus show (only just after sunset or before sunrise). */
        float twilightBand,
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
        /* Its colour × strength: {@link #sunLight} by day (white at noon), a faint cool blue by moonlight. */
        Vector3f keyLight,
        boolean keyIsMoon,
        /*
         * How much aerosol the air holds, relative to a clear day's (1): {@link #AEROSOL_DUSK}
         * with the sun in the west, {@link #AEROSOL_DAWN} with it in the east, 1 between. It
         * widens the sun's aureole, strengthens the afterglow, hazes the horizon and scales
         * the aerosol share of the clouds' optical depth.
         */
        float aerosol,
        /* Multiplier on the scene's linear light before the tone map, keyed to the sun's elevation (and the moon's light at night). */
        float exposure,
        /* 0..1: how dark-adapted the eye is, for the final pass's Purkinje grade. 0 by day, 1 by nautical dusk. */
        float nightAdaptation) {

    // Palette (linear, the game's sky colour space).
    private static final Vector3f DAY_ZENITH = new Vector3f(0.53f, 0.81f, 0.92f);
    private static final Vector3f DAY_HORIZON = new Vector3f(0.60f, 0.86f, 0.95f);
    private static final Vector3f CIVIL_ZENITH = new Vector3f(0.17f, 0.24f, 0.48f);
    private static final Vector3f CIVIL_HORIZON = new Vector3f(0.30f, 0.30f, 0.42f);
    private static final Vector3f NAUTICAL_ZENITH = new Vector3f(0.09f, 0.11f, 0.29f);
    private static final Vector3f NAUTICAL_HORIZON = new Vector3f(0.18f, 0.17f, 0.30f);
    // The night sky at half the blue it used to be painted with: the final pass's Purkinje
    // grade supplies the rest (see the class comment).
    private static final Vector3f FULL_MOON_ZENITH = new Vector3f(0.066f, 0.074f, 0.131f);
    private static final Vector3f FULL_MOON_HORIZON = new Vector3f(0.099f, 0.108f, 0.156f);
    private static final Vector3f NEW_MOON_ZENITH = new Vector3f(0.010f, 0.011f, 0.022f);
    private static final Vector3f NEW_MOON_HORIZON = new Vector3f(0.019f, 0.021f, 0.032f);
    /** The afterglow over the set sun: deeper and redder than the gold. */
    private static final Vector3f AFTERGLOW = new Vector3f(0.95f, 0.36f, 0.16f);
    /** Low sunlight: the gold between the noon white and the horizon's {@link #SUNSET} orange. */
    private static final Vector3f GOLDEN_SUN = new Vector3f(1.0f, 0.78f, 0.52f);
    private static final Vector3f WHITE = new Vector3f(1f);
    /** The low sun's orange: sunlight's hue on the horizon (the README logo's sunset). */
    private static final Vector3f SUNSET = new Vector3f(1.0f, 0.5f, 0.3f);
    /** The evening gold on the horizon and at its scale height (sky.frag SUNSET_GOLD_LOW / HIGH). */
    static final Vector3f SUNSET_GOLD_LOW = new Vector3f(1.0f, 0.42f, 0.18f);
    static final Vector3f SUNSET_GOLD_HIGH = new Vector3f(1.0f, 0.80f, 0.48f);
    /**
     * The gold's scale height (sine of elevation) straight opposite the sun and toward it
     * (sky.frag SUNSET_H_AWAY / SUNSET_H_SUN): the band is ~3.5° thick on the far side
     * and ~20° over the sun.
     */
    static final float SUNSET_H_AWAY = 0.06f, SUNSET_H_SUN = 0.35f;
    /**
     * How far round its daily circle (degrees of the sky's turn, see {@link #dayAngle})
     * the sun is from the horizon when the gold begins or ends: 30°, where the sun stands
     * 22.5° up or down. It grows in from about 3:15 PM to sunset at 6 PM and draws back
     * over the five real minutes after; at dawn the mirror image.
     */
    static final float GOLDEN_SPAN = 30f;
    /**
     * At its height the gold reaches about two-thirds of the way round the horizon from the
     * sun (full to 0.3, gone by 0.9 in {@link #sunsetWeight}'s units), so the horizon opposite
     * stays free for the Earth's shadow.
     */
    static final float PEAK_COVERAGE = 0.6f;
    /**
     * The most the gold tints the fog's and clouds' sky colour ({@link #evening}): the
     * zenith above them stays blue, and the sky shader's own overhead gold is next to none.
     */
    static final float EVENING_CAP = 0.15f;
    /** Width of the gold's soft edge, in {@link #sunsetWeight}'s distance units. */
    static final float SUNSET_SOFTNESS = 0.3f;
    /** Aerosol at dusk and at dawn, relative to a clear day's (see {@link #aerosol}). */
    static final float AEROSOL_DUSK = 1.3f, AEROSOL_DAWN = 0.75f;
    /**
     * Moonlight on the ground: reflected sunlight, a touch warm (about 4000 K, softened). It reads
     * blue only through the final pass's Purkinje grade (see the class comment).
     */
    private static final Vector3f MOONLIGHT = new Vector3f(1.0f, 0.96f, 0.90f);

    /**
     * Exposure (multiplier on linear light before the fitted ACES curve) by day, through the
     * sunset and the blue hour, measured as the best match of the tone-mapped image to the
     * pre-HDR one: the curve's shoulder wants a little under 1.
     */
    public static final float DAY_EXPOSURE = 0.82f;
    /** Exposure at the end of nautical twilight, where the sky has dimmed past the curve's knee. */
    static final float NAUTICAL_EXPOSURE = 1.0f;
    /**
     * Exposure in full night, moonless and under a full moon. In the dark the curve's toe
     * (slope ~0.21 in linear light) would crush everything; the moonless night wants the most.
     */
    static final float NEW_MOON_EXPOSURE = 3.9f, FULL_MOON_EXPOSURE = 1.7f;
    /** Sun elevations (degrees) over which the eye dark-adapts, for {@link #nightAdaptation}. */
    private static final float ADAPT_START = -4f, ADAPT_FULL = -12f;

    /**
     * Sun elevations (degrees) where the palette's stops sit: the real bands, with the
     * civil stop at the blue hour's top (−4°). Near the horizon the sun sinks 0.069° per
     * real second ({@link TimeOfDay#TWILIGHT_DILATION}), so civil twilight lasts about
     * 85 s, nautical 77 s and astronomical 68 s: night falls four minutes after sunset.
     * (Before the latitude path the sun sank 0.24°/s and the stops were stretched to −24°.)
     */
    private static final float ASTRONOMICAL = -18f, NAUTICAL = -12f, CIVIL = -4f, DAY = 10f;
    /**
     * Sunlight's hue stops, as the sun's y: the horizon orange at 0, gold by about 12°
     * up, white by 30°. Wide, so the sun reddens over the whole descent (several real
     * minutes) rather than flipping to orange in its last few degrees.
     */
    private static final float GOLDEN_Y = 0.20f, WHITE_Y = 0.50f;
    /** Sunlight on the world fades from this sun y, just above the horizon, to nothing at {@link #KEY_SWITCH_Y}. */
    private static final float SUN_FULL_Y = 0.02f;
    /** The Earth's shadow edge stands this many degrees up per degree the sun is down, at most MAX. */
    private static final float SHADOW_RISE = 1.5f, SHADOW_MAX = 20f;

    /** Ambient with no moon, and the extra a full moon overhead adds. */
    static final float NIGHT_AMBIENT = 0.09f, MOON_AMBIENT = 0.13f;
    /** Ambient with the sun on the horizon, as {@link TimeOfDay#getAmbientLightLevel()} has it. */
    private static final float HORIZON_AMBIENT = TimeOfDay.HORIZON_AMBIENT;
    /** Moonlight's diffuse strength relative to the sun's: faint, so moon shadows stay soft. */
    static final float MOON_KEY = 0.6f;
    /** Sun elevation (y) below which the moon becomes the key light. The sun's own shadows are gone by then. */
    static final float KEY_SWITCH_Y = -0.05f;

    /**
     * Sun elevations (degrees) where the hand-painted sky takes over from the physical one
     * ({@link #paintedSky}): it grows in from {@link #PAINTED_FROM} with the golden hour, is the
     * whole sky by {@link #PAINTED_FULL} and through the sunset and the blue hour, and hands the
     * sky back to the physical night from {@link #PAINTED_UNTIL} to {@link #PAINTED_GONE}.
     */
    static final float PAINTED_FROM = 22f, PAINTED_FULL = 12f, PAINTED_UNTIL = -6f, PAINTED_GONE = -12f;

    private static SkyLight last;
    private static long lastTicks = Long.MIN_VALUE;

    /**
     * How much of the sky is the hand-painted one for a sun in direction {@code sunDir}, 0..1:
     * the game's own sunset (the gold, the afterglow, the Earth's shadow and the Belt of
     * Venus) from the golden hour through the blue hour; the physical atmosphere by day and
     * by night. The same at dawn.
     */
    public static float paintedSky(Vector3f sunDir) {
        float elevation = (float) Math.toDegrees(Math.asin(Math.clamp(sunDir.y, -1f, 1f)));
        return smoothstep(PAINTED_FROM, PAINTED_FULL, elevation) * (1f - smoothstep(PAINTED_UNTIL, PAINTED_GONE, elevation));
    }

    /** The sky at {@code time}'s moment; the last result is kept, since every pass asks each frame. */
    public static SkyLight of(TimeOfDay time) {
        long ticks = time.getTotalTicks();
        SkyLight cached = last;
        if (cached != null && lastTicks == ticks) {
            return cached;
        }
        SkyLight sky = compute(time.getSunDirection(), MoonCycle.direction(time), MoonCycle.phase(time),
                time.getAmbientLightLevel());
        last = sky;
        lastTicks = ticks;
        return sky;
    }

    /**
     * @param dayAmbient {@link TimeOfDay#getAmbientLightLevel()}: the daytime ambient,
     *                   used while the sun is up
     */
    static SkyLight compute(Vector3f sunDir, Vector3f moonDir, float phase, float dayAmbient) {
        float sunElevation = (float) Math.toDegrees(Math.asin(Math.clamp(sunDir.y, -1f, 1f)));

        // The moon lights the sky only while it is up, and fully only once clear of the horizon.
        float moonLight = MoonCycle.relativeBrightness(phase) * smoothstep(-0.05f, 0.2f, moonDir.y);
        Vector3f nightZenith = lerp(NEW_MOON_ZENITH, FULL_MOON_ZENITH, moonLight);
        Vector3f nightHorizon = lerp(NEW_MOON_HORIZON, FULL_MOON_HORIZON, moonLight);

        Vector3f zenith = ramp(sunElevation, nightZenith, NAUTICAL_ZENITH, CIVIL_ZENITH, DAY_ZENITH);
        Vector3f horizon = ramp(sunElevation, nightHorizon, NAUTICAL_HORIZON, CIVIL_HORIZON, DAY_HORIZON);

        // The golden hour: grown in like the pre-moon sunset as the sun nears the
        // horizon, then drawn back toward it once it is down. At dawn, the same backwards.
        Vector3f clearZenith = zenith, clearHorizon = horizon;
        float sunset = 0f, coverage = -SUNSET_SOFTNESS;
        float dayAngle = dayAngle(sunElevation);
        if (Math.abs(dayAngle) < GOLDEN_SPAN) {
            sunset = dayAngle > 0f ? 1f - dayAngle / GOLDEN_SPAN : 1f;
            float retreat = Math.max(0f, -dayAngle / GOLDEN_SPAN);
            coverage = lerp(PEAK_COVERAGE, -SUNSET_SOFTNESS, retreat);
        }
        Vector3f sunsetColor = new Vector3f(SUNSET_GOLD_LOW).mul(sunset);
        // Overhead sits half-way round from the sun, but the gold hugs the horizon, so
        // the sky's colour over the fog and clouds keeps only a hint of it.
        float evening = Math.min(sunset * sunsetWeight(coverage, 0.5f), EVENING_CAP);
        // The horizon averages its ring.
        float horizonShare = sunset * Math.clamp(coverage, 0f, 1f);
        zenith = lerp(clearZenith, SUNSET_GOLD_HIGH, evening);
        horizon = lerp(clearHorizon, SUNSET_GOLD_LOW, horizonShare);
        Vector3f skyColor = lerp(lerp(zenith, horizon, 0.5f), zenith, evening);
        // The afterglow: red over the set sun, from just before it sets to nautical twilight.
        // It grows in slowly from 10° up, so the red does not bloom all at once.
        float glow = smoothstep(-14f, -5f, sunElevation) * (1f - smoothstep(-2f, 10f, sunElevation));
        // Hazier evening air glows stronger; dawn's clean air, fainter.
        float aerosol = aerosol(sunDir);
        Vector3f sunGlow = new Vector3f(AFTERGLOW).mul(0.7f * glow * aerosol);

        // Sunlight: white high up, gold low down, the sunset orange on the horizon, and
        // gone just after the sun sets, where the moon takes over (from nothing).
        // One easing over the whole span, linear through the gold stop: easing each
        // segment would hold at gold and then rush to orange near the horizon.
        float hue = smoothstep(0f, WHITE_Y, sunDir.y);
        float goldenHue = smoothstep(0f, WHITE_Y, GOLDEN_Y);
        Vector3f sunColor = hue >= goldenHue
                ? lerp(GOLDEN_SUN, WHITE, (hue - goldenHue) / (1f - goldenHue))
                : lerp(SUNSET, GOLDEN_SUN, hue / goldenHue);
        Vector3f sunLight = new Vector3f(sunColor).mul(smoothstep(KEY_SWITCH_Y, SUN_FULL_Y, sunDir.y));

        // The Earth's shadow rises opposite the sun as it sinks (a slate band, pink above),
        // and is lost in the dark sky by nautical twilight.
        float twilightBand = smoothstep(0f, -1.5f, sunElevation) * (1f - smoothstep(-8f, -16f, sunElevation));
        float shadowEdge = Math.min(-sunElevation * SHADOW_RISE, SHADOW_MAX);
        float earthShadowHeight = (float) Math.sin(Math.toRadians(Math.max(shadowEdge, 0f)));

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
            keyLight = new Vector3f(sunLight);
        }
        float nightAdaptation = smoothstep(ADAPT_START, ADAPT_FULL, sunElevation);
        return new SkyLight(new Vector3f(sunDir), new Vector3f(moonDir), phase, moonLight,
                zenith, horizon, clearZenith, clearHorizon, sunsetColor, coverage,
                sunGlow, sunColor, sunLight, new Vector3f(clearHorizon), earthShadowHeight, twilightBand,
                skyColor, daylight, evening, ambient, keyDir, keyLight, keyIsMoon, aerosol,
                exposure(sunElevation, moonLight), nightAdaptation);
    }

    /**
     * The exposure for a sun {@code sunElevation} degrees up: {@link #DAY_EXPOSURE} down to the
     * blue hour's end (−6°), {@link #NAUTICAL_EXPOSURE} at nautical dusk (−12°), and the night's
     * by astronomical dusk (−18°), between {@link #NEW_MOON_EXPOSURE} and
     * {@link #FULL_MOON_EXPOSURE} by the moon's light. Eased in log space, as the eye adapts.
     */
    static float exposure(float sunElevation, float moonLight) {
        float night = (float) Math.exp(lerp((float) Math.log(NEW_MOON_EXPOSURE),
                (float) Math.log(FULL_MOON_EXPOSURE), moonLight));
        float ev;
        if (sunElevation >= -6f) {
            return DAY_EXPOSURE;
        } else if (sunElevation >= NAUTICAL) {
            ev = lerp(log2(DAY_EXPOSURE), log2(NAUTICAL_EXPOSURE), smoothstep(-6f, NAUTICAL, sunElevation));
        } else {
            ev = lerp(log2(NAUTICAL_EXPOSURE), log2(night), smoothstep(NAUTICAL, ASTRONOMICAL, sunElevation));
        }
        return (float) Math.pow(2.0, ev);
    }

    private static float log2(float x) {
        return (float) (Math.log(x) / Math.log(2.0));
    }

    /**
     * The air's aerosol for a sun in direction {@code sunDir}: {@link #AEROSOL_DUSK} in the
     * west (+X, the evening), {@link #AEROSOL_DAWN} in the east, easing to a clear day's 1
     * as the sun nears the meridian (and the anti-meridian at midnight).
     */
    static float aerosol(Vector3f sunDir) {
        float side = smoothstep(0f, 0.5f, Math.abs(sunDir.x));
        return 1f + (sunDir.x > 0f ? AEROSOL_DUSK - 1f : AEROSOL_DAWN - 1f) * side;
    }

    /**
     * How far round its daily circle the sun is from the horizon, in degrees of the sky's
     * turn ({@link TimeOfDay#skyAngle}): positive with the sun up, negative with it down,
     * the same at dawn as at dusk. On the equinox path sin(elevation) = cos φ·sin of it.
     */
    static float dayAngle(float sunElevation) {
        double cosLat = Math.cos(Math.toRadians(TimeOfDay.LATITUDE_DEG));
        double s = Math.sin(Math.toRadians(sunElevation)) / cosLat;
        return (float) Math.toDegrees(Math.asin(Math.clamp(s, -1.0, 1.0)));
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

    /**
     * How golden the sky is in direction {@code dir} (unit), 0..1: {@link #sunsetWeight(float, float)}
     * round the sky, times the fall-off with height exp(−y/H). H runs from {@link #SUNSET_H_AWAY}
     * opposite the sun to {@link #SUNSET_H_SUN} toward it. sky.frag applySunset and
     * sunset_fog.glsl mirror this.
     */
    public static float sunsetWeight(float coverage, Vector3f dir, Vector3f sunDir) {
        float sunFlat = (float) Math.hypot(sunDir.x, sunDir.z);
        float sx = sunFlat > 1e-4f ? sunDir.x / sunFlat : 0f, sz = sunFlat > 1e-4f ? sunDir.z / sunFlat : 0f;
        float fromSun = 0.5f - 0.5f * (dir.x * sx + dir.z * sz);
        float dirFlat = (float) Math.hypot(dir.x, dir.z);
        float toward = dirFlat > 1e-4f ? (dir.x * sx + dir.z * sz) / dirFlat : 0f;
        return sunsetWeight(coverage, fromSun) * (float) Math.exp(-Math.max(dir.y, 0f) / sunsetHeight(toward));
    }

    /** The gold's scale height for a view whose compass bearing has {@code toward} = cos(angle from the sun's). */
    static float sunsetHeight(float toward) {
        return lerp(SUNSET_H_AWAY, SUNSET_H_SUN, 0.5f + 0.5f * toward);
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
