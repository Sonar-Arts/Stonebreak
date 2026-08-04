package com.openmason.engine.wayfind.voxel;

/**
 * What a single voxel cell means to navigation, as a bit set. Deliberately tiny: navigation cares
 * about four properties, and everything else a block might be is the game's business.
 *
 * <p>An empty flag set ({@link #OPEN}) is plain traversable air.
 */
public final class NavCell {

    /** Traversable empty space. */
    public static final int OPEN = 0;

    /**
     * Not loaded, or outside the world. Treated as impassable everywhere: a mob must never path
     * into terrain that does not exist yet, and a search must never be the thing that generates it.
     */
    public static final int UNKNOWN = 1;

    /**
     * Occupies space. Pair with {@link NavVolume#topSurface} for the height it occupies — a full
     * block reports 1.0, a stair tread or snow layer reports its real height, which is what lets an
     * agent stand on it or step over it.
     */
    public static final int SOLID = 1 << 1;

    /** Water (or any swimmable fluid). Passable; costed separately from walking. */
    public static final int LIQUID = 1 << 2;

    /** Harmful to stand in or pass through (lava, fire). Impassable — never cost-avoided. */
    public static final int HAZARD = 1 << 3;

    private NavCell() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isUnknown(int flags) {
        return (flags & UNKNOWN) != 0;
    }

    public static boolean isSolid(int flags) {
        return (flags & SOLID) != 0;
    }

    public static boolean isLiquid(int flags) {
        return (flags & LIQUID) != 0;
    }

    public static boolean isHazard(int flags) {
        return (flags & HAZARD) != 0;
    }
}
