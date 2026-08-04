package com.openmason.engine.wayfind.voxel;

/**
 * A small hand-built world for navigation tests: a finite box of cells that default to open air,
 * with everything outside reporting {@link NavCell#UNKNOWN} exactly as an unloaded chunk does.
 *
 * <p>Deliberately not a mock. Navigation rules are about the interaction between neighbouring cells,
 * so tests read far better when they build the actual terrain — a ledge, a puddle, a doorway — than
 * when they stub individual probe calls.
 */
final class BoxNavVolume implements NavVolume {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[][][] flags;
    private final float[][][] tops;

    BoxNavVolume(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.flags = new int[sizeX][sizeY][sizeZ];
        this.tops = new float[sizeX][sizeY][sizeZ];
    }

    /** A box filled with solid ground up to and including {@code groundTopY}; open air above. */
    static BoxNavVolume ground(int sizeX, int sizeY, int sizeZ, int groundTopY) {
        BoxNavVolume volume = new BoxNavVolume(sizeX, sizeY, sizeZ);
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y <= groundTopY; y++) {
                    volume.solid(x, y, z);
                }
            }
        }
        return volume;
    }

    BoxNavVolume solid(int x, int y, int z) {
        return set(x, y, z, NavCell.SOLID, 1.0f);
    }

    /** A shaped block — a stair tread, a snow layer — filling {@code top} of its cell. */
    BoxNavVolume partial(int x, int y, int z, float top) {
        return set(x, y, z, NavCell.SOLID, top);
    }

    /** Water fills most of its cell — a source block's surface sits a little below the top. */
    static final float WATER_SURFACE_HEIGHT = 0.875f;

    BoxNavVolume water(int x, int y, int z) {
        return set(x, y, z, NavCell.LIQUID, WATER_SURFACE_HEIGHT);
    }

    /**
     * Digs a column {@code depth} blocks into the ground and fills it with water, so the waterline
     * sits just below the surrounding shore — the shape an actual pond has. Stacking water on top
     * of the ground instead makes the "shore" lower than the water, which no terrain does and which
     * makes wading in read as climbing up.
     */
    BoxNavVolume pond(int x, int z, int groundTopY, int depth) {
        for (int y = groundTopY - depth + 1; y <= groundTopY; y++) {
            water(x, y, z);
        }
        return this;
    }

    BoxNavVolume hazard(int x, int y, int z) {
        return set(x, y, z, NavCell.HAZARD, 0.0f);
    }

    BoxNavVolume air(int x, int y, int z) {
        return set(x, y, z, NavCell.OPEN, 0.0f);
    }

    /** Fills a solid column from {@code fromY} to {@code toY} inclusive — a wall or a pillar. */
    BoxNavVolume column(int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            solid(x, y, z);
        }
        return this;
    }

    private BoxNavVolume set(int x, int y, int z, int cellFlags, float top) {
        flags[x][y][z] = cellFlags;
        tops[x][y][z] = top;
        return this;
    }

    @Override
    public int flags(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return NavCell.UNKNOWN;
        }
        return flags[x][y][z];
    }

    @Override
    public float topSurface(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return 0.0f;
        }
        return tops[x][y][z];
    }
}
