package com.stonebreak.world.generation.trees;

import java.util.HashMap;
import java.util.Map;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Batched per-chunk block placer used by every tree shape.
 *
 * Trees write into multiple chunks; the placer accumulates affected chunks during placement
 * and triggers a single mesh rebuild per chunk on {@link #complete()}. Caller guarantees the
 * required chunks are loaded before invoking {@link #placeBlock} (see TreeGenerator scheduling).
 */
public final class TreeBlockPlacer {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final World world;
    private final Map<Chunk, Integer> affectedChunks = new HashMap<>();

    public TreeBlockPlacer(World world) {
        this.world = world;
    }

    public void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType) {
        if (worldY < 0 || worldY >= WorldConfiguration.WORLD_HEIGHT) return;

        int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (chunk == null) return; // Defensive: scheduling should guarantee this

        int localX = Math.floorMod(worldX, CHUNK_SIZE);
        int localZ = Math.floorMod(worldZ, CHUNK_SIZE);
        chunk.setBlock(localX, worldY, localZ, blockType);
        affectedChunks.merge(chunk, 1, Integer::sum);
    }

    public void complete() {
        // chunk.setBlock only flips the dirty flag — it does NOT schedule the mesh rebuild
        // (compare World.setBlockAt, which explicitly calls scheduleConditionalMeshBuild). For
        // trees placed during a chunk's first feature pass this didn't matter (the chunk hadn't
        // been meshed yet), but for deferred placements that fire AFTER a neighbouring chunk is
        // already meshed, the new blocks would sit in the chunk data with a stale GPU mesh until
        // reload. Explicitly trigger a rebuild for every chunk we wrote into.
        for (Chunk chunk : affectedChunks.keySet()) {
            int worldX = chunk.getChunkX() * CHUNK_SIZE;
            int worldZ = chunk.getChunkZ() * CHUNK_SIZE;
            world.triggerChunkRebuild(worldX, 0, worldZ);
        }
        affectedChunks.clear();
    }
}
