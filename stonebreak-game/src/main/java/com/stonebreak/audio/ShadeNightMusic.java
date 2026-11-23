package com.stonebreak.audio;

import com.stonebreak.world.ShadeNight;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3dc;

/**
 * When "The Shade" theme plays: once a night, the first moment the player stands on the Shade's
 * surface while its night fog is forming or formed — so either as night starts to fall over a player
 * already there, or as a player walks in after dark. It never loops and never repeats within the
 * same night; it re-arms only once the fog has fully cleared again (day).
 *
 * <p>"Night" is the Shade's own night ({@link ShadeNight#fogStrength}), the same curve its ground fog
 * and its Vestiges follow, so the music arrives with the first wisps of fog rather than at a fixed
 * clock hour. "Surface" = feet no more than {@link #SURFACE_TOLERANCE} blocks below the column's sky
 * surface ({@link World#surfaceY}; leaves pass the sky, so standing under the dry oaks counts, a cave
 * does not).
 *
 * <p>GL- and audio-free; the decision is {@link #shouldStart}.
 */
public final class ShadeNightMusic {

    /** Blocks below the column's sky surface that still count as "on the surface" (dips, water). */
    static final double SURFACE_TOLERANCE = 2.0;

    /** Seconds between place checks (biome + surface queries) while a theme is due. */
    static final float CHECK_INTERVAL = 0.5f;

    private boolean armed = true;
    private float sinceCheck = CHECK_INTERVAL;

    /** Forget the night so far: a new world (or no world) arms the theme again. */
    public void reset() {
        armed = true;
        sinceCheck = CHECK_INTERVAL;
    }

    /**
     * Advances the rule for one frame of a player in {@code world}; true exactly when the theme
     * should start now. {@code feet} is the player's feet position.
     */
    public boolean update(World world, TimeOfDay time, Vector3dc feet, float deltaTime) {
        float fog = ShadeNight.fogStrength(time);
        return shouldStart(fog, deltaTime, () -> onShadeSurface(world, feet));
    }

    /**
     * The rule over plain inputs: {@code fog} is the Shade's night fog now (0 = day), {@code place}
     * answers whether the player is on the Shade's surface (asked at most every
     * {@link #CHECK_INTERVAL} seconds, and only while the theme is still due tonight).
     */
    boolean shouldStart(float fog, float deltaTime, java.util.function.BooleanSupplier place) {
        if (fog <= 0f) {
            armed = true; // daylight: the next night may play it again
            sinceCheck = CHECK_INTERVAL;
            return false;
        }
        if (!armed) return false;
        sinceCheck += deltaTime;
        if (sinceCheck < CHECK_INTERVAL) return false;
        sinceCheck = 0f;
        if (!place.getAsBoolean()) return false;
        armed = false;
        return true;
    }

    /** Whether {@code feet} stands in the Shade biome, at (or within a step of) the sky surface. */
    static boolean onShadeSurface(World world, Vector3dc feet) {
        if (world == null || feet == null) return false;
        TerrainGenerator terrain = world.terrain();
        if (terrain == null) return false;
        long x = (long) Math.floor(feet.x());
        long z = (long) Math.floor(feet.z());
        if (terrain.getBiomeAt((int) x, (int) z) != BiomeType.SHADE) return false;
        return feet.y() >= world.surfaceY(x, z) - SURFACE_TOLERANCE;
    }
}
