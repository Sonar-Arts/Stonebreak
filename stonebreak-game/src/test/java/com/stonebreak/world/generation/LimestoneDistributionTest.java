package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stalagmite.Stalagmite;
import com.stonebreak.blocks.stalagmite.StalagmiteState;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.generation.features.LimestoneGenerator;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Limestone must replace stone only, turn cavern formations into standing and hanging stalagmites,
 * show up both on cave walls and inside solid rock, and stay a minority of the rock.
 */
public class LimestoneDistributionTest {

    private static final long SEED = 424242L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int H = WorldConfiguration.WORLD_HEIGHT;

    /** Share of the region's rock that is limestone. Wide: beds and karst zones are regional. */
    private static final double MIN_SHARE = 0.005;
    private static final double MAX_SHARE = 0.35;

    private record Setup(TerrainGenerationSystem terrain, LimestoneGenerator limestone,
                         CavernCarver caverns) {}

    private static Setup setup() {
        DryHillsTileSource tiles = new DryHillsTileSource();
        HeightMapGenerator heights = new HeightMapGenerator(tiles);
        CavernCarver caverns = new CavernCarver(SEED, heights);
        return new Setup(new TerrainGenerationSystem(SEED, tiles),
                new LimestoneGenerator(SEED, heights, caverns, new MegaCavernCarver(SEED, heights)),
                caverns);
    }

    /** Stone or limestone with open air on all four horizontal sides: formation-shaped. */
    private static boolean isPillarCell(Chunk chunk, int x, int y, int z) {
        BlockType b = chunk.getBlock(x, y, z);
        return (b == BlockType.STONE || b == BlockType.LIMESTONE)
                && chunk.getBlock(x + 1, y, z) == BlockType.AIR
                && chunk.getBlock(x - 1, y, z) == BlockType.AIR
                && chunk.getBlock(x, y, z + 1) == BlockType.AIR
                && chunk.getBlock(x, y, z - 1) == BlockType.AIR;
    }

    private static Chunk populate(Setup s, int cx, int cz) {
        TerrainGenerationSystem.TerrainResult result = s.terrain().generateTerrainOnly(cx, cz);
        ColumnProfile p = result.profile();
        s.limestone().generate(new ChunkGenerationContext(null, result.chunk(), null,
                p.heights(), p.biomes(), p.waterLevels(), p.dominantBiome()));
        return result.chunk();
    }

    @Test
    public void onlyStoneIsReplacedAndOutputIsDeterministic() {
        Setup s = setup();
        int cx = 3, cz = -2;
        Chunk before = s.terrain().generateTerrainOnly(cx, cz).chunk();
        Chunk after = populate(s, cx, cz);
        Chunk again = populate(setup(), cx, cz);

        for (int x = 0; x < CHUNK; x++) {
            for (int z = 0; z < CHUNK; z++) {
                for (int y = 0; y < H; y++) {
                    BlockType was = before.getBlock(x, y, z);
                    BlockType now = after.getBlock(x, y, z);
                    if (was != now) {
                        String at = " at " + x + "," + y + "," + z;
                        if (now == BlockType.LIMESTONE_STALAGMITE) {
                            // a stalagmite takes its pillar's stone and the cave air above it
                            assertTrue(was == BlockType.STONE || was == BlockType.AIR, "stalagmite over " + was + at);
                        } else {
                            assertEquals(BlockType.STONE, was, "replaced a non-stone block" + at);
                            assertTrue(now == BlockType.LIMESTONE || now == BlockType.AIR, "stone became " + now + at);
                        }
                    }
                    assertEquals(now, again.getBlock(x, y, z), "non-deterministic at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    public void cavernFormationsBecomeStandingAndHangingStalagmites() {
        Setup s = setup();
        int[] host = null;
        for (int r = 0; r < 64 && host == null; r++) {
            for (int cx = -r; cx <= r && host == null; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (s.caverns().hasCavern(cx, cz)) {
                        host = new int[] { cx, cz };
                        break;
                    }
                }
            }
        }
        assertNotNull(host, "no cavern near the origin for this seed");

        int limestonePillars = 0;
        int stonePillars = 0;
        int stalagmites = 0;
        int hangingCount = 0;
        java.util.Set<Integer> sizes = new java.util.HashSet<>();
        float[] origin = s.caverns().computeCavernOrigin(host[0], host[1]);
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                int cx = host[0] + dcx, cz = host[1] + dcz;
                Chunk chunk = populate(s, cx, cz);
                for (int x = 1; x < CHUNK - 1; x++) {
                    for (int z = 1; z < CHUNK - 1; z++) {
                        for (int y = 2; y < H - 1; y++) {
                            BlockType b = chunk.getBlock(x, y, z);
                            if (b == BlockType.LIMESTONE_STALAGMITE) {
                                Stalagmite.Cells cells = chunkCells(chunk);
                                assertEquals(true, Stalagmite.anchorY(cells, x, y, z) != Integer.MIN_VALUE,
                                        "stalagmite cell without an anchor at " + x + "," + y + "," + z);
                                if (!Stalagmite.isPartState(chunk.getBlockState(x, y, z))) {
                                    StalagmiteState st = StalagmiteState.parse(chunk.getBlockState(x, y, z));
                                    stalagmites++;
                                    sizes.add(st.size());
                                    if (st.hanging()) hangingCount++;
                                    assertTrue(chunk.getBlock(x, y - st.direction(), z) != BlockType.AIR,
                                            "stalagmite not attached at " + x + "," + y + "," + z);
                                }
                                continue;
                            }
                            if (b != BlockType.STONE && b != BlockType.LIMESTONE) continue;
                            if (chunk.getBlock(x + 1, y, z) != BlockType.AIR
                                    || chunk.getBlock(x - 1, y, z) != BlockType.AIR
                                    || chunk.getBlock(x, y, z + 1) != BlockType.AIR
                                    || chunk.getBlock(x, y, z - 1) != BlockType.AIR) continue;
                            float dx = (cx * CHUNK + x - origin[0]) / 20f;
                            float dy = (y - origin[1]) / 12f;
                            float dz = (cz * CHUNK + z - origin[2]) / 20f;
                            if (dx * dx + dy * dy + dz * dz > 1f) continue;
                            if (b == BlockType.LIMESTONE) limestonePillars++; else stonePillars++;
                            // A pillar standing on the floor or hanging from the ceiling is a stalagmite now;
                            // only one bridging floor to ceiling, or floating free of both, may stay rock.
                            // Judge the whole vertical run, not the cell: the carvers can leave a
                            // multi-block fragment floating in a cavern, whose end cells each touch air
                            // on one side only.
                            int lo = y, hi = y;
                            while (lo - 1 >= 1 && isPillarCell(chunk, x, lo - 1, z)) lo--;
                            while (hi + 1 < H - 1 && isPillarCell(chunk, x, hi + 1, z)) hi++;
                            boolean airBelow = chunk.getBlock(x, lo - 1, z) == BlockType.AIR;
                            boolean airAbove = chunk.getBlock(x, hi + 1, z) == BlockType.AIR;
                            assertTrue(airBelow == airAbove, "attached pillar left as " + b + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
        assertTrue(stalagmites > 0, "cavern produced no stalagmites");
        assertTrue(sizes.size() >= 2, "stalagmite sizes never vary: " + sizes);
        assertTrue(hangingCount > 0, "no stalagmites hang from the cavern ceiling");
        assertTrue(hangingCount < stalagmites, "no stalagmites stand on the cavern floor");
        assertEquals(0, stonePillars, "stone formations left inside the cavern");
    }

    private static Stalagmite.Cells chunkCells(Chunk chunk) {
        return new Stalagmite.Cells() {
            @Override public BlockType block(int x, int y, int z) { return chunk.getBlock(x, y, z); }
            @Override public String state(int x, int y, int z) { return chunk.getBlockState(x, y, z); }
            @Override public void set(int x, int y, int z, BlockType block, String state) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    public void limestoneCoatsCavesAndFillsSolidRockInModeration() {
        Setup s = setup();
        long limestone = 0, stone = 0, exposed = 0, buried = 0;
        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                Chunk chunk = populate(s, cx, cz);
                for (int x = 1; x < CHUNK - 1; x++) {
                    for (int z = 1; z < CHUNK - 1; z++) {
                        for (int y = 1; y < H - 1; y++) {
                            BlockType b = chunk.getBlock(x, y, z);
                            if (b == BlockType.STONE) {
                                stone++;
                            } else if (b == BlockType.LIMESTONE) {
                                limestone++;
                                boolean open = chunk.getBlock(x + 1, y, z) == BlockType.AIR
                                        || chunk.getBlock(x - 1, y, z) == BlockType.AIR
                                        || chunk.getBlock(x, y + 1, z) == BlockType.AIR
                                        || chunk.getBlock(x, y - 1, z) == BlockType.AIR
                                        || chunk.getBlock(x, y, z + 1) == BlockType.AIR
                                        || chunk.getBlock(x, y, z - 1) == BlockType.AIR;
                                if (open) exposed++; else buried++;
                            }
                        }
                    }
                }
            }
        }
        double share = (double) limestone / (limestone + stone);
        assertTrue(share >= MIN_SHARE && share <= MAX_SHARE, "limestone share out of band: " + share);
        assertTrue(exposed > 0, "no limestone on cave surfaces");
        assertTrue(buried > 0, "no limestone pockets or beds in solid rock");
    }
}
