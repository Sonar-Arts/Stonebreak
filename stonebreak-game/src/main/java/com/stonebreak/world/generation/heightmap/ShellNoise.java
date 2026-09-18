package com.stonebreak.world.generation.heightmap;

/**
 * Smooth 2D value noise in {@code [0, 1)} at a world column, for roughening the rock
 * the water guards leave behind.
 *
 * <p>{@link WaterGuard} and {@link Density3D}'s tunnel band seal fixed shapes — a
 * one-column ring at a fixed depth under the bed, a fixed band around a tunnel
 * shell — and a cave running into either ends on a face as flat as the rule that
 * cut it. This is what lets those faces wander. It is only ever used to seal
 * <em>more</em> than the fixed rule does, never less, so the safety argument each
 * guard makes is untouched.
 *
 * <p>Seedless on purpose. The noise is cosmetic, {@code HeightMapGenerator} exposes
 * no seed, and threading one through five carvers to vary the shape of a cave wall
 * from world to world would buy nothing a player could see. A pure function of the
 * column, so chunks sharing a border agree on it.
 */
final class ShellNoise {

    private ShellNoise() {
    }

    /**
     * Hashed lattice corners every {@code wave} blocks, blended with a smoothstep.
     * {@code salt} separates independent channels at the same column.
     */
    static float at(int x, int z, float wave, long salt) {
        double fx = (x + 0.5) / wave;
        double fz = (z + 0.5) / wave;
        double x0 = Math.floor(fx);
        double z0 = Math.floor(fz);
        long cx = (long) x0;
        long cz = (long) z0;
        float tx = ease((float) (fx - x0));
        float tz = ease((float) (fz - z0));
        float c00 = corner(cx, cz, salt);
        float c10 = corner(cx + 1, cz, salt);
        float c01 = corner(cx, cz + 1, salt);
        float c11 = corner(cx + 1, cz + 1, salt);
        float a = c00 + (c10 - c00) * tx;
        float b = c01 + (c11 - c01) * tx;
        return a + (b - a) * tz;
    }

    /** {@link #at}, rescaled so values from 0.25 to 0.75 span the whole unit
     *  interval: bilinear value noise piles up around one half and almost never
     *  reaches its ends, so an integer picked from the raw value would nearly
     *  always be the middle one. */
    static float stretched(int x, int z, float wave, long salt) {
        return Math.clamp((at(x, z, wave, salt) - 0.25f) * 2.0f, 0.0f, 1.0f);
    }

    private static float ease(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float corner(long cx, long cz, long salt) {
        long h = mix(salt);
        h = mix(h ^ (cx * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ (cz * 0x165667B19E3779F9L));
        return (h >>> 40) / (float) (1 << 24);
    }

    /** SplitMix64's finaliser. */
    private static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
