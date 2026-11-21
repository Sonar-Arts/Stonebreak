package com.stonebreak.world;

/**
 * How thick the Shade's night fog is, as a number gameplay can ask for. The ground fog is drawn by
 * the client ({@code GroundFog}), but whether it has rolled in is a matter of the sun alone, so the
 * server's mobs and spawner read the same curve from the same clock and never disagree with what
 * the player sees.
 */
public final class ShadeNight {

    /** Sun height (sin of elevation) at which the fog starts to form... */
    public static final float FORM_SUN_Y = 0.03f;
    /** ...and at which it is fully formed. */
    public static final float FULL_SUN_Y = -0.15f;

    private ShadeNight() {}

    /**
     * How formed the fog is with the sun at height {@code sunY} (sin of its elevation):
     * 0 by day, rising to 1 as the sun sinks below the horizon, and back at dawn.
     */
    public static float fogStrength(float sunY) {
        float t = Math.max(0f, Math.min(1f, (FORM_SUN_Y - sunY) / (FORM_SUN_Y - FULL_SUN_Y)));
        return t * t * (3f - 2f * t);
    }

    /** {@link #fogStrength(float)} for the sun of {@code time}. */
    public static float fogStrength(TimeOfDay time) {
        return time == null ? 0f : fogStrength(time.getSunDirection().y);
    }
}
