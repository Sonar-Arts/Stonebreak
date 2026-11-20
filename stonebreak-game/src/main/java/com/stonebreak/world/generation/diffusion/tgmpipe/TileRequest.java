package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TerrainTile;

import java.util.concurrent.CompletableFuture;

/**
 * One tile asked of the terrain pipeline: {@code world} = the handshake world ({@code TerrainScale.worldIndex}), lower
 * {@code priority} first. Completing {@code future} from outside withdraws the request before its build starts.
 */
record TileRequest(long seed, int tileX, int tileZ, int lod, int priority, int world,
                   CompletableFuture<TerrainTile> future) {

    @Override
    public String toString() {
        return "tile (" + tileX + "," + tileZ + ") lod " + lod + " seed " + seed + " world " + world;
    }
}
