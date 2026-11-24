package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;

/**
 * Whatever lives at a campfire beyond its cooking — a goblin camp. The fire carries that camp's
 * saved data in its state string; this is how the fire tells the camp side about its lifecycle
 * without the blocks package knowing goblins exist.
 */
public interface CampAnchor {

    /** A fire carrying camp data came into the world (its chunk loaded). */
    void loaded(BlockPos fire, String campData);

    /**
     * The fire's chunk is about to unload — still resident, not yet saved: the camp freezes, and
     * what it returns is saved in the fire (null to save no camp).
     */
    String unloading(BlockPos fire);

    /** The fire is gone — broken, or replaced by something else. */
    void extinguished(BlockPos fire);

    /**
     * A save point while the fire stays loaded: what to save in it now, given what it carries
     * ({@code campData}). Lets the camp stamp its data with the time of the save, so a crash
     * between save points cannot leave it an old stamp.
     */
    default String saving(BlockPos fire, String campData) {
        return campData;
    }

    CampAnchor NONE = new CampAnchor() {
        @Override public void loaded(BlockPos fire, String campData) { }
        @Override public String unloading(BlockPos fire) { return null; }
        @Override public void extinguished(BlockPos fire) { }
    };
}
