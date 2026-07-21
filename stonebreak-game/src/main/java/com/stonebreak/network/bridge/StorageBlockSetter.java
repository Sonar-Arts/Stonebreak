package com.stonebreak.network.bridge;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.voxel.BlockTypeAdapter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * {@link BlockSetter} that decodes into a detached {@link CcoBlockStorage}
 * instead of a live chunk. Lets a network chunk payload be decoded off the
 * chunk's hot path and then installed with one cheap section-level copy —
 * no per-block dirty tracking, state-map removals, or incremental heightmap
 * updates (the caller recomputes the heightmap once afterwards).
 *
 * <p>Paletted targets take the codec's bulk section paths: uniform sections
 * become one section object, paletted sections are constructed directly from
 * the wire palette + index array — no per-cell {@code set} calls, no palette
 * repacking. The per-cell path remains as fallback (AIR writes skipped so
 * above-terrain sections stay in their uniform tier). Unwraps
 * {@link BlockTypeAdapter} so the storage only ever holds {@link BlockType}
 * constants (the chunk's read path casts to it).
 */
public final class StorageBlockSetter implements BlockSetter {

    private static final int CELLS_PER_LAYER =
        WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE;

    private final CcoBlockStorage storage;
    private final CcoPalettedChunkStorage paletted;

    public StorageBlockSetter(CcoBlockStorage storage) {
        this.storage = storage;
        this.paletted = storage instanceof CcoPalettedChunkStorage p ? p : null;
    }

    @Override
    public void setBlock(int x, int y, int z, IBlockType type) {
        BlockType blockType = unwrap(type);
        if (blockType == null || blockType == BlockType.AIR) {
            return;
        }
        storage.set(x, y, z, blockType);
    }

    @Override
    public boolean setSectionUniform(int sectionY, IBlockType type) {
        if (paletted == null) {
            return false;
        }
        BlockType blockType = unwrap(type);
        paletted.replaceSection(sectionY,
            new CcoPaletteSection(CELLS_PER_LAYER, blockType != null ? blockType : BlockType.AIR));
        return true;
    }

    @Override
    public boolean setSectionPaletted(int sectionY, IBlockType[] palette, byte[] cellIndices) {
        if (paletted == null) {
            return false;
        }
        IBlockType[] resolved = new IBlockType[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockType blockType = unwrap(palette[i]);
            resolved[i] = blockType != null ? blockType : BlockType.AIR;
        }
        paletted.replaceSection(sectionY,
            CcoPaletteSection.fromPaletteData(CELLS_PER_LAYER, resolved, cellIndices));
        return true;
    }

    private static BlockType unwrap(IBlockType type) {
        if (type instanceof BlockTypeAdapter adapter) {
            return adapter.unwrap();
        }
        if (type instanceof BlockType blockType) {
            return blockType;
        }
        return type != null ? BlockType.getById(type.getId()) : null;
    }
}
