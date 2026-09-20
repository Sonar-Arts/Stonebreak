package com.stonebreak.rendering.gameWorld;

import org.joml.Vector3f;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * How far you can see underwater, in one place.
 *
 * <p>Visibility closes in with depth: light runs out below you, so just under
 * the surface the water is nearly as clear as a fine day above it, and
 * {@link #FULL_DEPTH} blocks below sea level it is the short, murky reach the
 * fog has always had. Depth is measured against {@link
 * WorldConfiguration#SEA_LEVEL} rather than against the surface of whatever
 * body the camera is actually in — one subtraction, no upward scan. A river or
 * lake perched above sea level therefore reads as clear throughout, which is
 * the right look for one anyway.
 *
 * <p>Two shapes of the same fog are served here, because the passes that draw
 * underwater don't agree on one: terrain and water take the linear
 * {@code smoothstep(start, end)} band the world shader applies to everything
 * else, while entities and drops take an {@code exp(-density · dist)} falloff.
 * {@link #density} is derived from {@link #end} so the two stay in step — a
 * mob dissolves at the same range as the seabed behind it.
 */
public final class UnderwaterFog {

    /**
     * The colour the world fades to underwater, and the colour the frame's
     * background is filled with there. Those two have to be the same value or
     * the horizon fails to hide: fog saturates to this by {@link #end}, so
     * anything behind it that is NOT this colour reads as a hole.
     */
    public static final Vector3f COLOR = new Vector3f(0.05f, 0.2f, 0.35f);

    /** Fog band just under the surface, in blocks. */
    private static final float START_SHALLOW = 12.0f;
    private static final float END_SHALLOW = 64.0f;

    /** Fog band at {@link #FULL_DEPTH} and below, in blocks. */
    private static final float START_DEEP = 4.0f;
    private static final float END_DEEP = 20.0f;

    /** Depth below sea level at which the murk is fully closed in. */
    private static final float FULL_DEPTH = 24.0f;

    /**
     * Distance at which an {@code exp(-density · dist)} falloff has swallowed
     * ~95% of a fragment, expressed as a multiple of {@code 1/density}. Ties
     * {@link #density} to {@link #end}: at 20 blocks it gives the 0.15 the
     * entity passes used to hardcode.
     */
    private static final float EXP_SATURATION = 3.0f;

    private UnderwaterFog() {
    }

    /** 0 at the surface, 1 at {@link #FULL_DEPTH} and below. */
    private static float murk(float cameraY) {
        return Math.clamp((WorldConfiguration.SEA_LEVEL - cameraY) / FULL_DEPTH, 0.0f, 1.0f);
    }

    /** Distance at which the fog starts to bite, in blocks. */
    public static float start(float cameraY) {
        return START_SHALLOW + (START_DEEP - START_SHALLOW) * murk(cameraY);
    }

    /** Distance at which the fog has saturated to {@link #COLOR}, in blocks. */
    public static float end(float cameraY) {
        return END_SHALLOW + (END_DEEP - END_SHALLOW) * murk(cameraY);
    }

    /** The same visibility as an {@code exp(-density · dist)} falloff. */
    public static float density(float cameraY) {
        return EXP_SATURATION / end(cameraY);
    }
}
