package com.stonebreak.mobs.wisp;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Which columns belong to the Shade, remembered: a Wisp asks about the same few hundred columns
 * over and over (where it is, where it might hide), and a biome never changes under it.
 *
 * <p>A world with no terrain generator (tests) is all Shade, so a Wisp there never dissolves for
 * want of an answer.
 */
final class ShadeColumns {

    /** Columns remembered before the memory is cleared and starts over. */
    private static final int CAPACITY = 8192;

    private final World world;
    private final Map<Long, Boolean> cache = new HashMap<>();

    ShadeColumns(World world) {
        this.world = world;
    }

    boolean isShade(int x, int z) {
        TerrainGenerator terrain = world != null ? world.terrain() : null;
        if (terrain == null) {
            return true;
        }
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        Boolean known = cache.get(key);
        if (known != null) {
            return known;
        }
        if (cache.size() >= CAPACITY) {
            cache.clear();
        }
        boolean shade = terrain.getBiomeAt(x, z) == BiomeType.SHADE;
        cache.put(key, shade);
        return shade;
    }

    boolean isShade(Vector3f position) {
        return isShade((int) Math.floor(position.x), (int) Math.floor(position.z));
    }

    /** Whether the column and the ones {@code margin} blocks around it are all Shade. */
    boolean isShadeAround(float x, float z, float margin) {
        return isShade((int) Math.floor(x), (int) Math.floor(z))
                && isShade((int) Math.floor(x + margin), (int) Math.floor(z))
                && isShade((int) Math.floor(x - margin), (int) Math.floor(z))
                && isShade((int) Math.floor(x), (int) Math.floor(z + margin))
                && isShade((int) Math.floor(x), (int) Math.floor(z - margin));
    }
}
