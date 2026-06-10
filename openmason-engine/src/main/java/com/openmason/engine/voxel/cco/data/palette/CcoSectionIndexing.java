package com.openmason.engine.voxel.cco.data.palette;

/**
 * Coordinate math shared by paletted chunk storage and section codecs.
 * Sections are 16 blocks tall slices of a chunk column, cells ordered
 * Y-major then Z then X (matching the network codec's section layout).
 */
public final class CcoSectionIndexing {

    /** Blocks per section along Y. */
    public static final int SECTION_HEIGHT = 16;

    private CcoSectionIndexing() {
    }

    /** Section index for a local Y coordinate. */
    public static int sectionIndex(int y) {
        return y / SECTION_HEIGHT;
    }

    /** Number of sections needed to cover a world height. */
    public static int sectionCount(int sizeY) {
        return (sizeY + SECTION_HEIGHT - 1) / SECTION_HEIGHT;
    }

    /** Cell index within a section for local coordinates (Y-major, then Z, then X). */
    public static int cellIndex(int x, int y, int z, int sizeX, int sizeZ) {
        return ((y % SECTION_HEIGHT) * sizeZ + z) * sizeX + x;
    }
}
