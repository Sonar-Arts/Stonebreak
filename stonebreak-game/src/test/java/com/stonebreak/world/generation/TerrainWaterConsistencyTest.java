package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.diffusion.FakeTerrainTileSource;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Water placement, held to the per-column rule Phase 8 replaced {@code y < SEA_LEVEL} with.
 *
 * <p>{@link TerrainMeshConsistencyTest} cannot cover any of this: it explicitly excludes
 * water from its strict assertion ({@code continue; // water / non-unit geometry — out of
 * scope}), because water is meshed with variable height and fails the integer-unit-quad
 * test the whole reconstruction is built on. So every regression in the water plane's
 * journey from the bridge to a placed block would pass that test silently.
 *
 * <p>The fake tile source puts its lakes <em>above</em> sea level on purpose. At sea level
 * the new rule and the old one agree by construction, so a test that only saw ocean would
 * pass just as well against the code this phase deleted.
 */
public class TerrainWaterConsistencyTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    /** Chunks swept exhaustively. (0,0) straddles a lake; the far pair guards the bucketing. */
    private static final int[][] CHUNKS = {{0, 0}, {1, 0}, {0, 1}, {100, 100}, {-3, -5}};

    @Test
    public void placesWaterExactlyWhereTheColumnSaysAndNowhereElse() {
        TerrainGenerationSystem terrain =
                new TerrainGenerationSystem(SEED, new FakeTerrainTileSource());
        List<String> problems = new ArrayList<>();
        int inlandWaterBlocks = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    int height = FakeTerrainTileSource.height(wx, wz);
                    int level = FakeTerrainTileSource.waterLevel(wx, wz);

                    for (int y = 0; y < WORLD_HEIGHT; y++) {
                        boolean isWater = chunk.getBlock(lx, y, lz) == BlockType.WATER;
                        // The rule, stated once: water fills a column from its terrain top up
                        // to (not including) its water level. NO_WATER is negative, so a dry
                        // column can never satisfy it at any y.
                        boolean wants = y >= height && y < level;
                        if (isWater != wants) {
                            problems.add(String.format(
                                    "(%d,%d,%d): block=%s but height=%d level=%d",
                                    wx, y, wz, isWater ? "WATER" : "not-water", height, level));
                        }
                        if (isWater && y >= SEA_LEVEL) {
                            inlandWaterBlocks++;
                        }
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(), problems.size() + " mis-placed column(s), e.g. "
                + problems.subList(0, Math.min(5, problems.size())));
        // Without this the test above passes trivially on a world that has no inland water:
        // it would be asserting that `y < SEA_LEVEL` still works.
        assertTrue(inlandWaterBlocks > 0,
                "no water was placed above sea level, so nothing here exercised the per-column rule");
    }

    /**
     * The containment invariant (plan section 4.5), checked on placed blocks rather than on
     * the planes they came from, and across chunk seams.
     *
     * <p>This is the failure that matters: worldgen water is a source block by definition, so
     * a water block with air beside it is not a cosmetic gap — {@code WaterSim} pushes water
     * out of it across every chunk that loads, forever. And a leak sitting exactly on a chunk
     * boundary is not even detected at load, because {@code WaterSim.onChunkLoaded} →
     * {@code isExposed} stops at {@code lx > 0 / lx < 15}, so it waits for an unrelated block
     * change to wake it.
     *
     * <p>Stated strictly — no air beside water at all — which holds here because the fake's
     * lakes are level. Real terrain has waterfalls, where a step-down beside a lower water
     * body is deliberate; that case is the bridge's to police, and
     * {@code terrain-bridge/tests/test_carve.py} does, on both sides of a tile seam.
     */
    @Test
    public void noWaterBlockIsExposedToAirBesideIt() {
        TerrainGenerationSystem terrain =
                new TerrainGenerationSystem(SEED, new FakeTerrainTileSource());
        Map<Long, Chunk> chunks = new HashMap<>();
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                chunks.put(key(cx, cz), terrain.generateTerrainOnly(cx, cz).chunk());
            }
        }

        List<String> leaks = new ArrayList<>();
        // The centre chunk only: its neighbours exist so the seam columns resolve against
        // real generated data rather than against nothing.
        Chunk centre = chunks.get(key(0, 0));
        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    if (centre.getBlock(lx, y, lz) != BlockType.WATER) {
                        continue;
                    }
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (blockAt(chunks, lx + d[0], y, lz + d[1]) == BlockType.AIR) {
                            leaks.add(String.format("water at (%d,%d,%d) has air to (%+d,%+d)",
                                    lx, y, lz, d[0], d[1]));
                        }
                    }
                }
            }
        }
        assertTrue(leaks.isEmpty(), leaks.size() + " leak(s), e.g. "
                + leaks.subList(0, Math.min(5, leaks.size())));
    }

    /**
     * Cave carvers keep out of the ground under standing water (plan section 4.6).
     *
     * <p>Asserted as a property of the mask rather than of a generated chunk, because
     * {@code Density3D} also produces sub-surface air and a test that looked at the finished
     * blocks could not tell the two apart.
     *
     * <p>Deliberately does <em>not</em> assert that the guard ever removes anything here. It
     * does not, and that is a fact about the world's vertical layout rather than about the
     * guard: every carver's Y band is a fraction of {@code SEA_LEVEL} (caverns top out at
     * {@code SEA_LEVEL * 30 / 64}, mega at {@code 50 / 64}), while after the Phase 5 curve all
     * land sits at or above {@code SEA_LEVEL} itself — so a carve never comes within ~170
     * blocks of a riverbed. Below sea level the pre-existing column skip
     * ({@code surface <= SEA_LEVEL + WATER_CLEARANCE}) already stops carving outright. The
     * guard is a backstop that becomes load-bearing if those bands are ever retuned; the
     * suppression rule itself is tested directly in {@code WaterGuardTest}.
     */
    @Test
    public void caveCarversDoNotBreachTheBedOfAWetColumn() {
        HeightMapGenerator heights = new HeightMapGenerator(new FakeTerrainTileSource());
        CavernCarver caverns = new CavernCarver(SEED, heights);
        MegaCavernCarver mega = new MegaCavernCarver(SEED, heights);
        PerlinWormCarver worms = new PerlinWormCarver(SEED, heights);
        worms.setCavernCarver(caverns);
        worms.setMegaCavernCarver(mega);

        int chunksWithCarving = 0;
        List<String> breaches = new ArrayList<>();

        // A sweep rather than one hand-picked chunk: cavern placement is a hash of the chunk
        // coordinate, so which chunks carve at all is not something a test should assume.
        for (int cx = 0; cx < 12; cx++) {
            for (int cz = 0; cz < 12; cz++) {
                int[] h = new int[CHUNK * CHUNK];
                int[] w = new int[CHUNK * CHUNK];
                heights.populateChunkHeights(cx, cz, h, w);

                BitSet unguarded = carveMask(worms, caverns, mega, cx, cz, h, null);
                BitSet guarded = carveMask(worms, caverns, mega, cx, cz, h, w);
                if (unguarded.isEmpty()) {
                    continue;
                }
                chunksWithCarving++;

                BitSet added = (BitSet) guarded.clone();
                added.andNot(unguarded);
                assertTrue(added.isEmpty(),
                        "the water guard added carving at chunk (" + cx + "," + cz + ")");

                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int idx = lx * CHUNK + lz;
                        if (w[idx] == TerrainTile.NO_WATER) {
                            continue;
                        }
                        // One block of bed is the weakest form of the claim; each carver
                        // actually keeps its own radius-derived clearance, which is larger.
                        int bed = h[idx];
                        for (int y = Math.max(1, bed - 1); y < WORLD_HEIGHT; y++) {
                            if (guarded.get(LocalBlockKey.pack(lx, y, lz))) {
                                breaches.add(String.format(
                                        "chunk(%d,%d) column(%d,%d) bed=%d level=%d carved at y=%d",
                                        cx, cz, lx, lz, bed, w[idx], y));
                            }
                        }
                    }
                }
            }
        }

        assertTrue(chunksWithCarving > 0, "the sweep found no carving at all; nothing was tested");
        assertTrue(breaches.isEmpty(), breaches.size() + " breach(es), e.g. "
                + breaches.subList(0, Math.min(5, breaches.size())));
    }

    private static BitSet carveMask(PerlinWormCarver worms, CavernCarver caverns,
                                    MegaCavernCarver mega, int cx, int cz,
                                    int[] heights, int[] waterLevels) {
        BitSet mask = worms.carveMaskForChunk(cx, cz, heights, waterLevels);
        mask.or(caverns.buildForChunk(cx, cz, heights, waterLevels).carveMask);
        mask.or(mega.buildForChunk(cx, cz, heights, waterLevels).carveMask);
        return mask;
    }

    private static BlockType blockAt(Map<Long, Chunk> chunks, int lx, int y, int lz) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return BlockType.AIR;
        }
        Chunk c = chunks.get(key(Math.floorDiv(lx, CHUNK), Math.floorDiv(lz, CHUNK)));
        return c == null ? BlockType.AIR
                : c.getBlock(Math.floorMod(lx, CHUNK), y, Math.floorMod(lz, CHUNK));
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }
}
