package com.stonebreak.world.generation.features;

/**
 * Where a chunk's cavern is centred, as {@code {x, y, z}} in world coordinates, or null when
 * the chunk has none. Lets {@link LimestoneGenerator} follow either generator's carvers.
 */
@FunctionalInterface
public interface CavernOrigins {
    float[] computeCavernOrigin(int chunkX, int chunkZ);
}
