package com.stonebreak.blocks.palisade;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairState.Facing;
import com.stonebreak.world.World;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;

/**
 * Spiked wall contact, shared by the server's player health tick and the mob system
 * ({@link SpikeContactSystem}): walking into a wall's points hurts and throws you back; coming up
 * behind it, on the side of its supporting log, or alongside it, does nothing. Hurts everyone the
 * same — players, animals, rival goblins and the goblins who built it.
 *
 * <p>Like a cactus, a spiked wall is solid, so a body pressed against it sits flush with its cell
 * rather than in it: contact is an expanded-AABB probe of the neighbouring cells
 * ({@link com.stonebreak.blocks.cactus.CactusContactRules}). Which side the body is on is read
 * from the wall's lean ({@link PalisadeState}): its points lean toward its facing.
 */
public final class SpikeContactRules {

    /** One heart a prick. */
    public static final float DAMAGE = HEALTH_PER_HEART;
    /** At most one prick this often while a body stays on the points. */
    public static final float COOLDOWN_SECONDS = 0.5f;
    /** How hard the points throw a body back, blocks per second, and how much they lift it. */
    public static final float KNOCKBACK = 4.0f;
    public static final float KNOCKBACK_LIFT = 1.5f;
    /** A body flush against the wall's face shares its boundary: only an expanded probe counts that. */
    static final float TOUCH_MARGIN = 0.1f;
    /**
     * How far in front of the wall's centre, along its lean, a body's centre must be to be on the
     * points: past the middle of the cell, so a body beside the wall (level with it) or behind it
     * is never pricked.
     */
    static final float POINTS_SIDE = 0.45f;

    private SpikeContactRules() {
    }

    /**
     * The way the points throw a body whose box is {@code min..max} — the facing of the first
     * spiked wall whose points it presses into, as {@code {dx, dz}} — or {@code null} when it is on
     * no wall's points.
     */
    public static float[] pointsTouching(World world, double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        long ix0 = (long) Math.floor(minX - TOUCH_MARGIN);
        long ix1 = (long) Math.ceil(maxX + TOUCH_MARGIN);
        long iy0 = (long) Math.floor(minY);
        long iy1 = (long) Math.ceil(maxY);
        long iz0 = (long) Math.floor(minZ - TOUCH_MARGIN);
        long iz1 = (long) Math.ceil(maxZ + TOUCH_MARGIN);
        double bodyX = (minX + maxX) * 0.5;
        double bodyZ = (minZ + maxZ) * 0.5;
        for (long x = ix0; x < ix1; x++) {
            for (long y = iy0; y < iy1; y++) {
                for (long z = iz0; z < iz1; z++) {
                    if (world.getBlockAt(x, y, z) != BlockType.PALISADE) {
                        continue;
                    }
                    float[] lean = direction(PalisadeState.parse(world.getBlockStateAt(x, y, z)).facing());
                    if (onPoints(lean, (float) (bodyX - (x + 0.5)), (float) (bodyZ - (z + 0.5)))) {
                        return lean;
                    }
                }
            }
        }
        return null;
    }

    /** Whether a body {@code (offX, offZ)} from a wall's centre is in front of points leaning along {@code lean}. */
    static boolean onPoints(float[] lean, float offX, float offZ) {
        return offX * lean[0] + offZ * lean[1] > POINTS_SIDE;
    }

    /** The horizontal unit vector a facing points along: east +X, south +Z. */
    static float[] direction(Facing facing) {
        return switch (facing) {
            case EAST -> new float[] {1, 0};
            case WEST -> new float[] {-1, 0};
            case SOUTH -> new float[] {0, 1};
            case NORTH -> new float[] {0, -1};
        };
    }
}
