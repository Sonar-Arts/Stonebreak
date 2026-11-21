package com.stonebreak.blocks;

import com.stonebreak.blocks.palisade.PalisadeState;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stairs.StairState.Facing;

/**
 * The blocks whose model turns with a facing stored in their state string (stairs and the spiked
 * wall), and how far a given string turns it. One rule for the renderer that bakes the turned
 * stamps and the mesher that picks one, so the two can't disagree.
 */
public final class BlockFacing {

    private BlockFacing() {
    }

    /** True when {@code type}'s model is drawn turned to its stored facing. */
    public static boolean isOrientable(BlockType type) {
        return type != null && (type.isStairs() || type == BlockType.PALISADE);
    }

    /** The state string (and stamp-cache key) for {@code facing}, or null when {@code type} doesn't turn. */
    public static String stateStringFor(BlockType type, Facing facing) {
        if (type == null) return null;
        if (type.isStairs()) return StairState.stateStringFor(facing);
        if (type == BlockType.PALISADE) return PalisadeState.stateStringFor(facing);
        return null;
    }

    /** Quarter turns about +Y from the authored model that {@code rawState} asks for; 0 when the block doesn't turn. */
    public static int quarterTurns(BlockType type, String rawState) {
        if (type == null) return 0;
        if (type.isStairs()) return StairState.parse(rawState).facing().quarterTurns();
        if (type == BlockType.PALISADE) return PalisadeState.parse(rawState).facing().quarterTurns();
        return 0;
    }
}
