package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FastLOD's coarse cells keep DaedalusTGM-Exp's rivers whole.
 *
 * <p>The defect this pins: the model's rivers are 3-16 blocks wide and a coarse cell was one
 * point probe, so a river survived only in the cells where the probe happened to land in it —
 * on real v4 tiles 22 rivers drew as 156 pieces at L3, most of them single-cell puddles. A cell
 * whose footprint holds a river now stands on it; the sea is left to the probe.
 *
 * <p>The fake world: flat ground with a 3-wide river running along +X at z 37..39, placed so
 * that the L3 and L4 probes (z 36 and 40) both miss it, and a 2-wide strip of still sea-level
 * water at z 101..102 that no probe lands on.
 */
class FastLodRiverSamplingTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int TILE = TerrainScale.TILE_SIZE_BLOCKS;

    private static final int LAND = SEA_LEVEL + 40;
    private static final int RIVER_BED = LAND - 4;
    private static final int RIVER_SURFACE = LAND - 2;
    private static final int RIVER_Z0 = 37;
    private static final int RIVER_Z1 = 39;
    private static final int SEA_Z0 = 101;
    private static final int SEA_Z1 = 102;

    private static boolean inRiver(int z) {
        return z >= RIVER_Z0 && z <= RIVER_Z1;
    }

    private static boolean inSea(int z) {
        return z >= SEA_Z0 && z <= SEA_Z1;
    }

    /** Flat land, the river and the sea strip, built per 256-block tile like the service's. */
    private static final class RiverTiles implements TerrainTileSource {
        private final Map<Long, TerrainTile> tiles = new ConcurrentHashMap<>();

        @Override
        public TerrainTile getTile(int worldX, int worldZ) {
            int tx = Math.floorDiv(worldX, TILE);
            int tz = Math.floorDiv(worldZ, TILE);
            return tiles.computeIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL), k -> build(tx, tz));
        }

        private static TerrainTile build(int tx, int tz) {
            int n = TILE * TILE;
            short[] heights = new short[n];
            short[] biomes = new short[n];
            short[] water = new short[n];
            short[] floors = new short[n];
            short[] roofs = new short[n];
            short[] flows = new short[n];
            Arrays.fill(floors, TerrainTile.NO_TUNNEL);
            Arrays.fill(roofs, TerrainTile.NO_TUNNEL);
            Arrays.fill(flows, TerrainTile.NO_FLOW);
            for (int row = 0; row < TILE; row++) {
                for (int col = 0; col < TILE; col++) {
                    int z = tz * TILE + col;
                    int i = row * TILE + col;
                    if (inRiver(z)) {
                        heights[i] = RIVER_BED;
                        water[i] = RIVER_SURFACE;
                        flows[i] = 0;
                    } else if (inSea(z)) {
                        heights[i] = (short) (SEA_LEVEL - 3);
                        water[i] = (short) SEA_LEVEL;
                    } else {
                        heights[i] = LAND;
                        water[i] = TerrainTile.NO_WATER;
                    }
                }
            }
            int i1 = tx * TILE;
            int j1 = tz * TILE;
            return new TerrainTile(tx, tz, i1, j1, i1 + TILE, j1 + TILE, TILE, TILE,
                    heights, biomes, water, floors, roofs, flows);
        }
    }

    private final FastLodSampler sampler =
            new FastLodSampler(new DiffusionTerrainGenerator(99L, new RiverTiles()));

    private static boolean wet(FastLodChunkData data, int ix, int iz) {
        return data.waterLevelAt(ix, iz) > data.heightAt(ix, iz);
    }

    @Test
    void everyCoarseCellWhoseFootprintHoldsARiverShowsIt() {
        List<String> problems = new ArrayList<>();
        for (FastLodLevel level : FastLodLevel.values()) {
            int cell = level.cellSize();
            for (int cx = 0; cx < 3; cx++) {
                FastLodChunkData data = sampler.sample(FastLodKey.of(level, cx, 2));
                for (int ix = 0; ix < level.cellsPerAxis(); ix++) {
                    for (int iz = 0; iz < level.cellsPerAxis(); iz++) {
                        int z0 = 2 * CHUNK + iz * cell;
                        boolean river = false;
                        for (int z = z0; z < z0 + cell; z++) {
                            river |= inRiver(z);
                        }
                        if (river != wet(data, ix, iz)) {
                            problems.add(level + " chunk (" + cx + ",2) cell (" + ix + "," + iz + "): footprint "
                                    + (river ? "holds" : "has no") + " river but the cell is "
                                    + (wet(data, ix, iz) ? "wet" : "dry"));
                        } else if (river) {
                            assertEquals(RIVER_SURFACE, data.waterLevelAt(ix, iz), level + " river surface");
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theSeaIsLeftToTheProbe() {
        for (FastLodLevel level : FastLodLevel.values()) {
            if (level == FastLodLevel.L0) {
                continue; // one column per cell: the strip is drawn exactly where it is
            }
            FastLodChunkData data = sampler.sample(FastLodKey.of(level, 0, 6));
            for (int ix = 0; ix < level.cellsPerAxis(); ix++) {
                for (int iz = 0; iz < level.cellsPerAxis(); iz++) {
                    int z = 6 * CHUNK + iz * level.cellSize() + level.cellSize() / 2;
                    assertEquals(inSea(z), wet(data, ix, iz),
                            level + " cell (" + ix + "," + iz + ") must show what its probe at z " + z + " shows");
                }
            }
        }
    }

    /**
     * The mesher builds seam skirts from each node's margin ring, which is the neighbouring
     * node's edge cells probed again — so the river choice must depend on the cell alone, or
     * the two nodes disagree along the seam.
     */
    @Test
    void marginsAgreeWithTheNeighbouringNode() {
        for (FastLodLevel level : FastLodLevel.values()) {
            int last = level.cellsPerAxis() - 1;
            FastLodChunkData west = sampler.sample(FastLodKey.of(level, 0, 2));
            FastLodChunkData east = sampler.sample(FastLodKey.of(level, 1, 2));
            FastLodChunkData north = sampler.sample(FastLodKey.of(level, 1, 1));
            for (int i = 0; i < level.cellsPerAxis(); i++) {
                assertEquals(west.heightAt(last, i), east.heightAt(-1, i), level + " west/east seam, row " + i);
                assertEquals(east.heightAt(i, 0), north.heightAt(i, last + 1), level + " north/south seam, col " + i);
            }
        }
    }
}
