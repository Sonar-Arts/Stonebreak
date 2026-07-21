package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.openmason.engine.voxel.IVoxelChunkSections;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.world.chunk.Chunk;

/**
 * Adapts a game {@link Chunk} to the engine's {@link IVoxelChunkData} interface.
 * Also exposes {@link IVoxelChunkSections} so the network chunk codec can
 * bulk-read whole sections from the paletted storage instead of making 65k
 * per-cell {@code getBlock} calls (each of which allocated an adapter).
 */
public record ChunkDataAdapter(Chunk chunk) implements IVoxelChunkData, IVoxelChunkSections {

    @Override
    public IBlockType getBlock(int x, int y, int z) {
        return new BlockTypeAdapter(chunk.getBlock(x, y, z));
    }

    @Override
    public boolean copySectionBlockIds(int sectionY, short[] dst) {
        if (chunk.getBlockStorageView() instanceof CcoPalettedChunkStorage paletted) {
            paletted.getSection(sectionY).writeBlockIdsInto(dst, 0);
            return true;
        }
        return false;
    }

    @Override
    public int getChunkX() {
        return chunk.getChunkX();
    }

    @Override
    public int getChunkZ() {
        return chunk.getChunkZ();
    }
}
