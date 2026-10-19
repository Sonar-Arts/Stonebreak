package com.stonebreak.world.generation.biomes;

/**
 * Gravel patches in a {@link BiomeType#DESERT}'s sand, shared by both generators and by FastLOD:
 * a per-column decision from the world seed and the column alone, like {@link OceanFloor}.
 *
 * <p>It used to be a decoration: about one patch every 2.6 chunks (12% of all desert sand), each
 * column of it a separate random roll, so a patch was a speckle, and stamped through world
 * writes, so a patch reaching into a chunk not generated yet was cut off at the chunk border
 * and one reaching into a meshed chunk forced it to re-mesh. LOD, which samples surfaces from
 * the terrain pass, never saw any of it and z-fought the gravel wherever the two overlapped.
 *
 * <p>Now each {@value #CELL}-block cell of the world holds at most one patch
 * ({@value #PATCH_CHANCE} of them do; about 1% of the sand), a solid blob with a smooth wavy
 * outline, placed so it never leaves its cell: a column only ever looks at its own cell.
 */
public final class DesertGravel {

    /** Cell edge in blocks; one patch at most per cell. */
    static final int CELL = 64;
    static final double PATCH_CHANCE = 0.55;
    static final double MIN_RADIUS = 3.5;
    static final double MAX_RADIUS = 7.0;
    /** Outline wobble: the radius varies by up to this share with two low harmonics. */
    private static final double WOBBLE = 0.28;
    /** Largest radius any outline reaches, which the centre keeps from the cell's edge. */
    private static final int REACH = (int) Math.ceil(MAX_RADIUS * (1 + WOBBLE)) + 1;

    private DesertGravel() {}

    /** Whether a dry desert column's sand is gravel. */
    public static boolean at(int worldX, int worldZ, long seed) {
        int cx = Math.floorDiv(worldX, CELL);
        int cz = Math.floorDiv(worldZ, CELL);
        long h = hash(seed, cx, cz);
        if (unit(h, 0) >= PATCH_CHANCE) {
            return false;
        }
        int span = CELL - 2 * REACH;
        double centreX = cx * CELL + REACH + unit(h, 1) * span;
        double centreZ = cz * CELL + REACH + unit(h, 2) * span;
        double dx = worldX + 0.5 - centreX;
        double dz = worldZ + 0.5 - centreZ;
        double distSq = dx * dx + dz * dz;
        double radius = MIN_RADIUS + unit(h, 3) * (MAX_RADIUS - MIN_RADIUS);
        double outer = radius * (1 + WOBBLE);
        if (distSq > outer * outer) {
            return false;
        }
        double angle = Math.atan2(dz, dx);
        double wobble = 0.6 * Math.sin(2 * angle + unit(h, 4) * 2 * Math.PI)
                      + 0.4 * Math.sin(3 * angle + unit(h, 5) * 2 * Math.PI);
        double edge = radius * (1 + WOBBLE * wobble);
        return distSq <= edge * edge;
    }

    private static long hash(long seed, int cx, int cz) {
        long h = seed ^ 0x6772_6176_656C_7061L;          // "gravelpa"
        h = (h ^ (cx * 0x9E37_79B9_7F4A_7C15L)) * 0xBF58_476D_1CE4_E5B9L;
        h = (h ^ (cz * 0xC2B2_AE3D_27D4_EB4FL)) * 0x94D0_49BB_1331_11EBL;
        return h ^ (h >>> 31);
    }

    /** The {@code k}-th uniform in [0, 1) drawn from a cell's hash. */
    private static double unit(long h, int k) {
        long x = h + k * 0x9E37_79B9_7F4A_7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D0_49BB_1331_11EBL;
        x ^= x >>> 31;
        return (x >>> 11) * 0x1.0p-53;
    }
}
