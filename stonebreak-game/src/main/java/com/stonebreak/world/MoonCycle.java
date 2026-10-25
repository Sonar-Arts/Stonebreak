package com.stonebreak.world;

import org.joml.Vector3f;

/**
 * The moon's phase and place in the sky, from the world clock alone.
 *
 * <p>Geometry as in life, with the month shortened to {@link #CYCLE_DAYS} days.
 * The phase p (0 = new, 0.5 = full) sets the moon's elongation from the sun,
 * e = 2πp. The moon is the sun turned back by e about the pole the sky wheels
 * around (Z), so it lags the sun by e and rises later each day. Each night is
 * centred on one phase: day d's midnight is phase d / {@link #CYCLE_DAYS}. The
 * full moon rises at sunset and is overhead at midnight, the waxing crescent
 * hangs in the west after dusk, and the waning crescent rises in the east
 * before dawn. Because the sky shader lights the disc from the real
 * sun direction, the lit limb always faces the sun and the phase shapes follow
 * from that.
 *
 * <p>Brightness follows the observed lunar phase curve, not a Lambertian sphere.
 * A Lambertian ball predicts a full-to-quarter ratio of π, but the real moon's
 * is about 10 because its regolith back-scatters (the opposition surge). The
 * curve is the standard magnitude fit m(α) = 0.026|α| + 4·10⁻⁹·α⁴, with α the
 * Sun–Moon–Earth phase angle in degrees.
 */
public final class MoonCycle {

    /** Days from one new moon to the next. Real: 29.53; shortened so players see it turn. */
    public static final int CYCLE_DAYS = 8;

    private static final String[] NAMES = {
            "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
            "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"};

    private MoonCycle() {}

    /** Phase in [0, 1): 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    public static float phase(long day, long ticks) {
        double days = Math.floorMod(day, CYCLE_DAYS)
                + (ticks - TimeOfDay.MIDNIGHT) / (double) TimeOfDay.TICKS_PER_DAY;
        double p = days / CYCLE_DAYS;
        return (float) (p - Math.floor(p));
    }

    public static float phase(TimeOfDay time) {
        return phase(time.getDay(), time.getTicks());
    }

    /** Angle between sun and moon as seen from the ground, radians in [0, 2π). */
    public static float elongation(float phase) {
        return (float) (2.0 * Math.PI * phase);
    }

    /** Share of the disc that is sunlit, 0 (new) to 1 (full). */
    public static float illuminatedFraction(float phase) {
        return (float) (1.0 - Math.cos(elongation(phase))) * 0.5f;
    }

    /** The moon's light relative to a full moon (1), from the observed phase curve. */
    public static float relativeBrightness(float phase) {
        double e = Math.toDegrees(elongation(phase));
        double alpha = Math.abs(180.0 - e); // phase angle: 0 at full, 180 at new
        double magnitude = 0.026 * alpha + 4e-9 * Math.pow(alpha, 4);
        return (float) Math.pow(10.0, -0.4 * magnitude);
    }

    /**
     * Direction to the moon: the sun turned back along the sky's daily wheel by the
     * elongation. A rotation, so the angle to the sun is the elongation itself and
     * crescents really do hang beside the sun.
     */
    public static Vector3f direction(TimeOfDay time) {
        return time.getSunDirection().rotateZ(elongation(phase(time)));
    }

    /** "Waxing Gibbous" and the like: the name of the nearest of the eight phases. */
    public static String phaseName(float phase) {
        int i = Math.floorMod(Math.round(phase * NAMES.length), NAMES.length);
        return NAMES[i];
    }
}
