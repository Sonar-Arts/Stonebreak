package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TunnelledRiverTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Density3D}'s overhang band must not open a surface river's bed or the bank
 * wall beside it.
 *
 * <p>The water kernel raises the ground beside a river to a guard rail above the water,
 * so the flowing layer {@code WaterSim} spreads at every step of the surface stays in the
 * channel. The overhang band carves the top sixteen blocks of every column on noise and
 * biome alone, so without {@link WaterGuard#surfaceGuardPlane} it punched holes through
 * exactly that wall.
 *
 * <p>Self-proving: the same band, in the same chunks and biome, has to carve the dry
 * ground away from the water. Otherwise a clean wall would prove nothing.
 */
public class Density3DBankGuardTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    /** Far from the fixture's own river (z 5..11), so every wet column is ours. */
    private static final int CHUNK_Z = 4;
    private static final int SURFACE = 400;
    private static final int BED = SURFACE - 3;
    private static final int RIVER_Z = 8;

    @Test
    public void theOverhangBandLeavesTheRiverAndItsBanksSolid() {
        Density3D density = new Density3D(9001L, new HeightMapGenerator(new TunnelledRiverTileSource()));
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, SURFACE);
        Arrays.fill(water, TerrainTile.NO_WATER);
        for (int x = 0; x < CHUNK; x++) {
            heights[x * CHUNK + RIVER_Z] = BED;
            water[x * CHUNK + RIVER_Z] = SURFACE;
        }

        List<String> holes = new ArrayList<>();
        int carvedAway = 0;
        for (int chunkX = 0; chunkX < 64; chunkX++) {
            Density3D.Field field = density.prepareChunk(chunkX, CHUNK_Z, heights, water);
            Assumptions.assumeTrue(field != null, "native noise backend unavailable");
            for (int lx = 0; lx < CHUNK; lx++) {
                // The bed and both bank walls, from under the bed to the top of the wall.
                for (int lz = RIVER_Z - 1; lz <= RIVER_Z + 1; lz++) {
                    int top = heights[lx * CHUNK + lz];
                    for (int y = BED - 2; y < top; y++) {
                        if (!field.isSolid(lx, y, lz, top, BiomeType.STONY_PEAKS)) {
                            holes.add("(" + (chunkX * CHUNK + lx) + "," + y + "," + lz + ")");
                        }
                    }
                }
                // Dry ground well clear of the water: the band is free to carve here.
                for (int lz = 0; lz <= 1; lz++) {
                    for (int y = BED - 2; y < SURFACE; y++) {
                        if (!field.isSolid(lx, y, lz, SURFACE, BiomeType.STONY_PEAKS)) {
                            carvedAway++;
                        }
                    }
                }
            }
        }
        assertTrue(carvedAway > 0,
                "the overhang band must carve the dry control ground, or the guard is untested");
        assertTrue(holes.isEmpty(), holes.size() + " open cells in a riverbed or its bank: "
                + holes.subList(0, Math.min(8, holes.size())));
    }
}
