package com.stonebreak.world.generation.diffusion;

/**
 * Resolves the tile covering a world column. Production code always wires
 * {@link DiffusionTileCache} (hard dependency on the terrain bridge, no
 * fallback — see plan.md Phase 2). Tests inject a deterministic fake so
 * terrain-shape logic can be exercised offline without a live bridge.
 */
public interface TerrainTileSource {
    TerrainTile getTile(int worldX, int worldZ);
}
