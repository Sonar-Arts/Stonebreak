package com.stonebreak.world.generation.biomes;

/**
 * Where The Shade may stand (Project-Heracles' accursed forest): the world is cut into large irregular regions, about
 * {@value #REGION} blocks across - a jittered Voronoi lattice under a smooth domain warp, so borders wander instead of
 * running straight - and each region is the Shade's with chance {@value #CHANCE}, rolled once per world seed. Inside a
 * Shade region the land is Shade (Standard: all temperate-to-warm land whatever its moisture; DaedalusTGM-Exp: the
 * model's temperate grassland and woodland), so the Shade stands as one mass ending at the region's border or at land it
 * cannot take (sea and shore, mountains, ice and snow, the hot belt), never sprinkled through other biomes.
 *
 * <p>One rule for every world kind: the native generator ports it bit for bit ({@code native/src/shape.cpp}
 * {@code shadeRegion}; the library is built with {@code -ffp-contract=off}, so the double maths agree), Standard's Java
 * mirror (the terrain mapper) and the DaedalusTGM-Exp overlay ({@code ShadeOverlay}) call it here. A pure function of
 * (seed, x, z).
 */
public final class ShadeRegions {

    /** Region size in blocks: a forest one and a half kilometres or more across. */
    public static final double REGION = 1536.0;
    /** Chance a region is the Shade's: 3 %, a rare landmark, not a common biome. */
    public static final double CHANCE = 0.03;
    /** Domain warp: amplitude (blocks) and the value-noise lattice it is drawn from. */
    static final double WARP = 200.0;
    static final long WARP_CELL = 384;

    static final long SALT_REGION = 0x5348414445524547L;   // "SHADEREG"
    static final long SALT_JITTER_X = 0x534841444A495458L;
    static final long SALT_JITTER_Z = 0x534841444A49545AL;
    static final long SALT_WARP_X = 0x5348414457415258L;
    static final long SALT_WARP_Z = 0x534841445741525AL;

    private ShadeRegions() {
    }

    /** Whether block (x, z) of a world with seed {@code seed} lies in a region rolled Shade. */
    public static boolean isShadeRegion(long seed, long x, long z) {
        double wx = x + WARP * (2.0 * valueNoise(seed, x, z, SALT_WARP_X) - 1.0);
        double wz = z + WARP * (2.0 * valueNoise(seed, x, z, SALT_WARP_Z) - 1.0);
        long cx = (long) Math.floor(wx / REGION);
        long cz = (long) Math.floor(wz / REGION);
        double best = Double.MAX_VALUE;
        long bx = cx, bz = cz;
        for (long gz = cz - 1; gz <= cz + 1; gz++) {
            for (long gx = cx - 1; gx <= cx + 1; gx++) {
                double px = (gx + 0.15 + 0.7 * unit(hash(seed, gx, gz, SALT_JITTER_X))) * REGION;
                double pz = (gz + 0.15 + 0.7 * unit(hash(seed, gx, gz, SALT_JITTER_Z))) * REGION;
                double d = (px - wx) * (px - wx) + (pz - wz) * (pz - wz);
                if (d < best) {
                    best = d;
                    bx = gx;
                    bz = gz;
                }
            }
        }
        return unit(hash(seed, bx, bz, SALT_REGION)) < CHANCE;
    }

    /** Smooth value noise in [0, 1) on a {@value #WARP_CELL}-block lattice. */
    static double valueNoise(long seed, long x, long z, long salt) {
        long ix = Math.floorDiv(x, WARP_CELL), iz = Math.floorDiv(z, WARP_CELL);
        double fx = (double) (x - ix * WARP_CELL) / WARP_CELL, fz = (double) (z - iz * WARP_CELL) / WARP_CELL;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double v00 = unit(hash(seed, ix, iz, salt)), v10 = unit(hash(seed, ix + 1, iz, salt));
        double v01 = unit(hash(seed, ix, iz + 1, salt)), v11 = unit(hash(seed, ix + 1, iz + 1, salt));
        double a = v00 + (v10 - v00) * fx, b = v01 + (v11 - v01) * fx;
        return a + (b - a) * fz;
    }

    static long hash(long seed, long a, long b, long salt) {
        long h = seed ^ salt;
        h = mix(h + a * 0x9E3779B97F4A7C15L);
        h = mix(h + b * 0xC2B2AE3D27D4EB4FL);
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
