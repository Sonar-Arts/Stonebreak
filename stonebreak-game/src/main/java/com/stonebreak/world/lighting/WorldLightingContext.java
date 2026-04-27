package com.stonebreak.world.lighting;

import com.openmason.engine.voxel.lighting.ChunkHeightMap;
import com.openmason.engine.voxel.lighting.ColumnOpacityProbe;
import com.openmason.engine.voxel.lighting.LightingContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Game-side adapter bridging the engine's {@link LightingContext} contract to
 * Stonebreak's chunk map. Keeps the engine blind to {@code World} /
 * {@code BlockType} concrete types — the engine only sees integer world coords
 * and booleans.
 *
 * <p>Both query methods refuse to trigger chunk generation. Unloaded chunks
 * are reported as "no data" (column height -1, solid false) so the mesh-thread
 * sampler can't cascade into runaway loads.
 */
public final class WorldLightingContext implements LightingContext {

    private final World world;

    public WorldLightingContext(World world) {
        this.world = world;
    }

    @Override
    public int getColumnHeight(int worldX, int worldZ) {
        int cx = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);
        if (world == null || !world.hasChunkAt(cx, cz)) return -1;
        Chunk chunk = world.getChunkAt(cx, cz);
        if (chunk == null) return -1;
        ChunkHeightMap hm = chunk.getHeightMap();
        if (!hm.isPopulated()) {
            // Defensive lazy init — generation hook should have already done this,
            // but if a mesh rebuild races ahead of it we catch up here.
            hm.recomputeAll(probeFor(chunk));
        }
        int lx = Math.floorMod(worldX, WorldConfiguration.CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, WorldConfiguration.CHUNK_SIZE);
        return hm.getHeight(lx, lz);
    }

    @Override
    public boolean isSolidAt(int worldX, int worldY, int worldZ) {
        if (worldY < 0 || worldY >= WorldConfiguration.WORLD_HEIGHT) return false;
        int cx = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);
        if (world == null || !world.hasChunkAt(cx, cz)) return false;
        Chunk chunk = world.getChunkAt(cx, cz);
        if (chunk == null) return false;
        int lx = Math.floorMod(worldX, WorldConfiguration.CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, WorldConfiguration.CHUNK_SIZE);
        return BlockOpacity.isOpaque(chunk.getBlock(lx, worldY, lz));
    }

    /** Returns a {@link ColumnOpacityProbe} that queries the given chunk's blocks. */
    public static ColumnOpacityProbe probeFor(Chunk chunk) {
        return (lx, ly, lz) -> BlockOpacity.isOpaque(chunk.getBlock(lx, ly, lz));
    }
}
