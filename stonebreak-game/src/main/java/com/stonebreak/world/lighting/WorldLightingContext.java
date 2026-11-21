package com.stonebreak.world.lighting;

import com.openmason.engine.voxel.lighting.LightingContext;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The engine's column-era {@link LightingContext} over a cube world, for the point sky samplers still in use
 * (entity / arm / particle light probes: {@code VertexLightSampler.samplePointSky}). The column height is the
 * native surface tracker ({@code cv_surface_y}: first free y above the topmost sky-opaque block - the same
 * quantity the old {@code ChunkHeightMap} held); solidity is {@link BlockOpacity}. Terrain meshes get their
 * sky light natively and do not use this.
 */
public final class WorldLightingContext implements LightingContext {

    private final World world;

    public WorldLightingContext(World world) {
        this.world = world;
    }

    @Override
    public int getColumnHeight(int worldX, int worldZ) {
        if (world == null) return -1;
        long y = world.surfaceY(worldX, worldZ);
        return (int) Math.max(WorldConfiguration.MIN_Y, Math.min(WorldConfiguration.MAX_Y, y));
    }

    @Override
    public boolean isSolidAt(int worldX, int worldY, int worldZ) {
        if (world == null || !WorldConfiguration.inBounds(worldY)) return false;
        return BlockOpacity.isOpaque(world.getBlockAt(worldX, worldY, worldZ));
    }
}
