package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.VoxelWorldConfig;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;

import java.util.Objects;

/**
 * Paletted per-section block storage for a chunk column.
 *
 * <p>The column is split into 16-block-tall {@link CcoPaletteSection}s.
 * Air-dominated sections (everything above the terrain surface) stay in
 * their uniform tier and cost ~32 bytes instead of 16 KB of references,
 * bringing a typical 16x256x16 chunk from ~256-512 KB of block references
 * down to ~30-40 KB.
 *
 * <p>Thread safety follows {@link CcoPaletteSection}: lock-free reads,
 * internally synchronized writes.
 */
public final class CcoPalettedChunkStorage implements CcoBlockStorage {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final CcoPaletteSection[] sections;

    /**
     * Replaces one section wholesale (bulk decode path). Only safe on a
     * detached storage that no reader is concurrently observing — network
     * decode builds into a fresh storage, then installs via {@code copyFrom}.
     */
    public void replaceSection(int sectionIndex, CcoPaletteSection section) {
        sections[sectionIndex] = section;
    }

    private CcoPalettedChunkStorage(int sizeX, int sizeY, int sizeZ, CcoPaletteSection[] sections) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sections = sections;
    }

    /** Creates storage for a standard chunk, every section uniform-filled with air. */
    public static CcoPalettedChunkStorage createEmpty(VoxelWorldConfig config, IBlockType airBlock) {
        return createEmpty(config.chunkSize(), config.worldHeight(), config.chunkSize(), airBlock);
    }

    /** Creates storage with custom dimensions, every section uniform-filled with air. */
    public static CcoPalettedChunkStorage createEmpty(int sizeX, int sizeY, int sizeZ, IBlockType airBlock) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("Storage dimensions must be positive");
        }
        int cellsPerLayer = sizeX * sizeZ;
        CcoPaletteSection[] sections = new CcoPaletteSection[CcoSectionIndexing.sectionCount(sizeY)];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new CcoPaletteSection(cellsPerLayer, airBlock);
        }
        return new CcoPalettedChunkStorage(sizeX, sizeY, sizeZ, sections);
    }

    @Override
    public IBlockType get(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return null;
        }
        return sections[CcoSectionIndexing.sectionIndex(y)]
                .get(CcoSectionIndexing.cellIndex(x, y, z, sizeX, sizeZ));
    }

    @Override
    public boolean set(int x, int y, int z, IBlockType block) {
        if (!isInBounds(x, y, z)) {
            return false;
        }
        return sections[CcoSectionIndexing.sectionIndex(y)]
                .set(CcoSectionIndexing.cellIndex(x, y, z, sizeX, sizeZ), block);
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX &&
               y >= 0 && y < sizeY &&
               z >= 0 && z < sizeZ;
    }

    @Override
    public int getSizeX() {
        return sizeX;
    }

    @Override
    public int getSizeY() {
        return sizeY;
    }

    @Override
    public int getSizeZ() {
        return sizeZ;
    }

    @Override
    public int countNonAirBlocks() {
        int count = 0;
        for (CcoPaletteSection section : sections) {
            count += section.nonAirCount();
        }
        return count;
    }

    @Override
    public int getHighestNonAirY() {
        for (int sy = sections.length - 1; sy >= 0; sy--) {
            if (sections[sy].nonAirCount() == 0) {
                continue;
            }
            int localY = sections[sy].highestNonAirLocalY();
            if (localY >= 0) {
                return Math.min(sy * CcoSectionIndexing.SECTION_HEIGHT + localY, sizeY - 1);
            }
        }
        return -1;
    }

    @Override
    public CcoPalettedChunkStorage copy() {
        CcoPaletteSection[] copies = new CcoPaletteSection[sections.length];
        for (int i = 0; i < sections.length; i++) {
            copies[i] = sections[i].copy();
        }
        return new CcoPalettedChunkStorage(sizeX, sizeY, sizeZ, copies);
    }

    @Override
    public void copyFrom(CcoBlockStorage other) {
        Objects.requireNonNull(other, "other cannot be null");
        if (other.getSizeX() != sizeX || other.getSizeY() != sizeY || other.getSizeZ() != sizeZ) {
            throw new IllegalArgumentException(String.format(
                    "Storage dimensions differ: %dx%dx%d vs %dx%dx%d",
                    sizeX, sizeY, sizeZ, other.getSizeX(), other.getSizeY(), other.getSizeZ()));
        }
        if (other instanceof CcoPalettedChunkStorage paletted) {
            for (int i = 0; i < sections.length; i++) {
                sections[i].copyFrom(paletted.sections[i]);
            }
            return;
        }
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    set(x, y, z, other.get(x, y, z));
                }
            }
        }
    }

    /** Number of sections in this column. */
    public int getSectionCount() {
        return sections.length;
    }

    /**
     * Direct section access for codecs and serializers that want to take
     * the uniform-section fast path instead of per-cell reads.
     */
    public CcoPaletteSection getSection(int sectionIndex) {
        return sections[sectionIndex];
    }

    @Override
    public String toString() {
        return String.format("CcoPalettedChunkStorage{size=%dx%dx%d, sections=%d, nonAir=%d}",
                sizeX, sizeY, sizeZ, sections.length, countNonAirBlocks());
    }
}
