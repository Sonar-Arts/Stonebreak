package com.stonebreak.blocks.cactus;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * Cactus contact rules shared by the player's contact handler
 * ({@code player/combat/CactusContactDamage}) and the mob pulse system
 * ({@code CactusContactSystem}): the eighth-heart damage step, the 0.5 s pulse
 * interval, and the AABB-overlap probe that detects a body touching spiky cells.
 *
 * <p>The probe exists because cacti are solid: collision keeps a body flush against
 * the cell boundary rather than inside it, so "walked against" is an adjacent-cell
 * contact — there is no block-touch callback to hook (mirrors how the ICE-vs-SNOW
 * renderer rule re-probes neighbours).
 */
public final class CactusContactRules {

    /** One contact pulse every 0.5 s while a body stays in contact. */
    public static final float PULSE_INTERVAL = 0.5f;
    /** Eighth heart = 0.25 hearts; hearts are 2.0 HP each ({@code PlayerConstants.HEALTH_PER_HEART}). */
    public static final float DAMAGE_PER_PULSE = HEALTH_PER_HEART * 0.25f;
    /**
     * World-space expansion of a body's AABB for the contact probe: a body flush
     * against the solid spiky cell shares a face boundary with it, and only an
     * expanded probe counts that as touching. Sized well under a block so mere
     * diagonal adjacency never reads as contact.
     */
    public static final float TOUCH_MARGIN = 0.1f;

    private CactusContactRules() {}

    /**
     * True when any cactus cell overlaps the world-space AABB, expanded by
     * {@code TOUCH_MARGIN} so a body flush against the spiky cell counts as touching.
     * Bounded by construction: the cell range is the body's footprint plus one.
     */
    public static boolean touchesCactus(World world,
                                        float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ) {
        int ix0 = (int) Math.floor(minX - TOUCH_MARGIN);
        int ix1 = (int) Math.ceil(maxX + TOUCH_MARGIN);
        int iy0 = (int) Math.floor(minY - TOUCH_MARGIN);
        int iy1 = (int) Math.ceil(maxY + TOUCH_MARGIN);
        int iz0 = (int) Math.floor(minZ - TOUCH_MARGIN);
        int iz1 = (int) Math.ceil(maxZ + TOUCH_MARGIN);
        for (int x = ix0; x < ix1; x++) {
            for (int y = iy0; y < iy1; y++) {
                for (int z = iz0; z < iz1; z++) {
                    if (world.getBlockAt(x, y, z) == BlockType.CACTUS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
