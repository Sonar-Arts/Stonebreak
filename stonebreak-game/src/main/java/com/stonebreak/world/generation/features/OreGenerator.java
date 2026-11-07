package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;
import java.util.Random;

/**
 * Places ores within already-generated stone columns.
 *
 * <p>Every ore generates as <b>veins</b>: chains of overlapping spheres, not
 * independent per-block rolls. The previous implementation tested every stone block in
 * every column against a fixed probability, which cannot produce a clump by
 * construction — independent trials scatter single blocks — and allocated a fresh
 * {@link Random} per block through {@link DeterministicRandom}, well over 100k
 * allocations per chunk in a pass that runs on a per-frame time budget.
 *
 * <p>Veins are spawned <em>per source chunk</em> and this chunk scans its neighbours
 * within {@link #SCAN_RADIUS}, the same model {@code CavernCarver} uses for its blob
 * clusters. That is what lets a vein straddle a chunk border while every write still
 * lands inside the chunk being populated: no {@code world.setBlockAt}, no dependence on
 * whether the neighbour is loaded, no {@link FeatureQueue} bookkeeping.
 *
 * <p>The corollary is that vein geometry must be a pure function of world coordinates.
 * A growth algorithm that walked to adjacent stone would see different neighbours
 * depending on which chunk was being generated and tear the vein at the border, so the
 * shape is strictly geometric and the block state is consulted only at the final write.
 */
public class OreGenerator {
    /**
     * Iron abundance is a V in absolute Y: full rate down at bedrock, full rate up in
     * the highlands, scarce in the middle around sea level.
     *
     * <p>The old code gated iron on {@code y < 50} while {@link WorldConfiguration#SEA_LEVEL}
     * is 320 and land surfaces sit at 340-500 — the same absolute-Y-band mistake
     * {@code CavernCarver} records fixing for caverns, and with the same effect: iron sat
     * in a near-bedrock slab hundreds of blocks below anywhere a player actually digs.
     *
     * <p>The peaks are absolute Y for the 256-tall world (sea level 64, 1:4 terrain scale,
     * land reaching ~y 180): the same values {@code main} uses at this height.
     */
    private static final int IRON_DEEP_PEAK_Y = 12;
    private static final int IRON_TROUGH_Y = WorldConfiguration.SEA_LEVEL;
    private static final int IRON_HIGH_PEAK_Y = 130;
    private static final float IRON_TROUGH_WEIGHT = 0.15f;

    /** Highest Y silver can spawn at; it is full-rate at and below {@link #SILVER_FULL_Y}. */
    private static final int SILVER_MAX_Y = 52;
    private static final int SILVER_FULL_Y = 32;
    /** Gold sits below silver: full-rate at and below {@link #GOLD_FULL_Y}, none above {@link #GOLD_MAX_Y}. */
    private static final int GOLD_MAX_Y = 36;
    private static final int GOLD_FULL_Y = 20;
    /**
     * Tin is measured in blocks below the column's top stone, not in absolute Y, so it
     * hugs the surface under lowlands and mountains alike: full-rate within
     * {@link #TIN_FULL_DEPTH}, none deeper than {@link #TIN_MAX_DEPTH}.
     */
    private static final int TIN_MAX_DEPTH = 40;
    private static final int TIN_FULL_DEPTH = 16;

    /**
     * Every ore this generator places, in write order. Only stone is overwritten, so the
     * first writer wins an overlap. The metals go first, rarest first: dense coal veins
     * written ahead of them cut a fifth of copper veins into lone-block fragments. Coal
     * then iron after them preserves the old {@code pickOre} precedence between those two,
     * which give up only the few blocks a metal vein already took.
     *
     * <p>Candidates are per source chunk; realised blocks per chunk come out near
     * {@code candidates × mean keep weight × mean size}, less whatever lands in cave air.
     * Each ore has its own salt so its stream is independent of the others. Copper and tin
     * take coal's vein sizes and silver and gold take iron's, with fewer candidates so
     * the bigger veins hold about the same ore per chunk as before.
     *
     * <p>The metal ores are {@code grouped} — at least two blocks per vein, bar a
     * {@link #LONE_BLOCK_CHANCE} of one. Coal and iron are not, which keeps their draw
     * stream, and so their vein geometry, unchanged.
     */
    private static final OreSpec[] ORES = {
            new OreSpec(BlockType.GOLD_ORE, 0x510E527FADE682D1L, 2, 4, 14, true,
                    (top, out) -> band(1, Math.min(top, GOLD_MAX_Y), out),
                    (y, top) -> fade(y, GOLD_FULL_Y, GOLD_MAX_Y)),
            new OreSpec(BlockType.SILVER_ORE, 0xA54FF53A5F1D36F1L, 4, 4, 14, true,
                    (top, out) -> band(1, Math.min(top, SILVER_MAX_Y), out),
                    (y, top) -> fade(y, SILVER_FULL_Y, SILVER_MAX_Y)),
            new OreSpec(BlockType.TIN_ORE, 0x3C6EF372FE94F82BL, 6, 8, 24, true,
                    (top, out) -> band(Math.max(1, top - TIN_MAX_DEPTH), top, out),
                    (y, top) -> fade(top - y, TIN_FULL_DEPTH, TIN_MAX_DEPTH)),
            new OreSpec(BlockType.COPPER_ORE, 0x6A09E667F3BCC909L, 6, 8, 24, true,
                    OreGenerator::wholeColumn, (y, top) -> 1.0f),
            new OreSpec(BlockType.COAL_ORE, 0x517CC1B727220A95L, 30, 8, 24, false,
                    OreGenerator::wholeColumn, (y, top) -> 1.0f),
            new OreSpec(BlockType.IRON_ORE, 0x2545F4914F6CDD1DL, 28, 4, 14, false,
                    OreGenerator::wholeColumn, (y, top) -> ironDepthWeight(y)),
    };

    /**
     * For a {@code grouped} ore, the chance a vein is deliberately a single block. Every
     * other grouped vein is at least two blocks: its geometry is connected, and
     * {@link #pairIsolatedBlocks} repairs the pieces cave air, soil and bedrock cut off.
     * Only pieces in a chunk's edge columns escape that, adding 1-2% (measured by
     * {@code OreVeinDistributionTest}).
     */
    private static final float LONE_BLOCK_CHANCE = 0.05f;

    /** Sphere radius varies over [0.80, 1.20] of nominal so the union reads as irregular. */
    private static final float RADIUS_JITTER_MIN = 0.80f;
    private static final float RADIUS_JITTER_MAX = 1.20f;
    /** Per-step positional wobble, in blocks, applied off the vein's axis. */
    private static final float STEP_JITTER = 0.9f;
    /**
     * Vertical damping on the vein axis. Veins lie flatter than they are tall, which
     * reads better against the horizontal floors and walls the carvers leave.
     */
    private static final float AXIS_Y_DAMP = 0.6f;

    /**
     * Blocks a vein can reach from its origin: half its elongation, plus the largest
     * sphere radius, plus per-step jitter. Sized off the largest vein in {@link #ORES} so
     * raising any size widens the scan with it.
     */
    private static final int MAX_VEIN_REACH = maxVeinReach();
    /** Source chunks to scan in each direction so border-straddling veins are not clipped. */
    private static final int SCAN_RADIUS =
            (int) Math.ceil((double) MAX_VEIN_REACH / ChunkGenerationContext.SIZE);

    /** Stone stops this far below the surface — mirrors {@code determineBlockType}. */
    private static final int SUBSURFACE_DEPTH = 4;

    private static final int CRYSTAL_MIN_Y = 20;
    private static final float CRYSTAL_CHANCE = 0.02f;

    private final DeterministicRandom rng;
    private final ColumnHeights heightMapGenerator;
    private final long seed;

    public OreGenerator(DeterministicRandom rng, ColumnHeights heightMapGenerator, long seed) {
        this.rng = rng;
        this.heightMapGenerator = heightMapGenerator;
        this.seed = seed;
    }

    public void generate(ChunkGenerationContext ctx) {
        GroupedWrites grouped = new GroupedWrites();
        for (OreSpec ore : ORES) {
            generateVeins(ctx, ore, grouped);
        }
        pairIsolatedBlocks(ctx, grouped);
        // After the pairing pass, so the deliberate lone blocks stay lone.
        for (int i = 0; i < grouped.loneCount; i++) {
            int cell = grouped.lone[i];
            setIfStone(ctx.chunk, cellX(cell), cellY(cell), cellZ(cell), grouped.loneOre[i], null);
        }
        generateCrystals(ctx);
    }

    /**
     * Scans every source chunk whose veins could reach this one and places whatever
     * lands inside it.
     */
    private void generateVeins(ChunkGenerationContext ctx, OreSpec ore, GroupedWrites grouped) {
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                spawnVeins(ctx, ore, ctx.chunkX + dcx, ctx.chunkZ + dcz, grouped);
            }
        }
    }

    /**
     * Places every vein of {@code ore} originating in source chunk {@code (srcCx, srcCz)}
     * into {@code ctx}'s chunk.
     *
     * <p>The draw order here is contractual: the same source chunk is replayed once for
     * every target chunk within {@link #SCAN_RADIUS}, and all of those replays must agree
     * on the vein they are describing. So every candidate consumes exactly the same
     * number of draws whether it is kept or rejected, and no draw may be skipped by an
     * early return — {@code placeVein} is what declines to write, never the sampler.
     */
    private void spawnVeins(ChunkGenerationContext ctx, OreSpec ore, int srcCx, int srcCz,
                            GroupedWrites grouped) {
        Random veinRng = new Random(veinRngSeed(srcCx, srcCz, ore.salt()));
        int[] band = new int[2];

        int baseX = srcCx * ChunkGenerationContext.SIZE;
        int baseZ = srcCz * ChunkGenerationContext.SIZE;

        for (int i = 0; i < ore.candidates(); i++) {
            int ox = baseX + veinRng.nextInt(ChunkGenerationContext.SIZE);
            int oz = baseZ + veinRng.nextInt(ChunkGenerationContext.SIZE);
            // Highest stone in the origin column, and the Y band the ore may start in. The
            // empty-band branch skips a draw, but the band depends only on (ox, oz) — never
            // on which chunk is being populated — so every replay of this source chunk takes
            // the same branch and stays aligned.
            int stoneTop = heightMapGenerator.generateHeight(ox, oz) - SUBSURFACE_DEPTH;
            ore.band().of(stoneTop, band);
            boolean bandOpen = band[1] > band[0];
            int oy = bandOpen ? band[0] + veinRng.nextInt(band[1] - band[0]) : band[0];
            int size = ore.sizeMin() + veinRng.nextInt(ore.sizeMax() - ore.sizeMin() + 1);

            // Rejection sampling against the depth curve. Drawn unconditionally so every
            // ore consumes identical draw counts per candidate.
            float keepRoll = veinRng.nextFloat();
            boolean keep = bandOpen && keepRoll < ore.weight().at(oy, stoneTop);
            // Grouped ores draw one extra value per candidate. Whether it is drawn depends
            // only on the ore, so every replay of the source chunk still agrees.
            VeinShape shape = !ore.grouped() ? VeinShape.CHAIN
                    : veinRng.nextFloat() < LONE_BLOCK_CHANCE ? VeinShape.LONE : VeinShape.GROUPED;

            placeVein(ctx, ore.block(), ox, oy, oz, size, veinRng, keep, shape,
                    ore.grouped() ? grouped : null);
        }
    }

    /**
     * Steps a chain of spheres along a random axis through {@code (ox, oy, oz)}, writing
     * ore into {@code ctx}'s chunk wherever a sphere covers stone.
     *
     * <p>Always consumes the same draws regardless of {@code keep}, {@code shape} or
     * whether the vein overlaps this chunk at all; they suppress or reshape only the writes.
     *
     * <p>{@link VeinShape#GROUPED} keeps the vein one face-connected piece. Most spheres
     * are under a block in radius, and {@link #fillSphere} then covers only the rounded
     * centre; jitter scatters those centres diagonally, which left a third of copper veins
     * as lone blocks touching at corners. So consecutive centres are joined by a
     * face-connected path, and a chain whose spheres all round to one block gets a second
     * block along its axis. Both are pure geometry, so they come out the same whichever
     * chunk is being populated. {@link VeinShape#LONE} only queues its block in
     * {@code grouped}, to be written after {@link #pairIsolatedBlocks}.
     *
     * @param grouped where a grouped ore's writes are recorded, or null for coal and iron
     */
    private void placeVein(ChunkGenerationContext ctx, BlockType ore,
                           int ox, int oy, int oz, int size, Random veinRng, boolean keep,
                           VeinShape shape, GroupedWrites grouped) {
        // Random axis on the unit sphere, then flattened.
        double theta = veinRng.nextDouble() * Math.PI * 2;
        double pitch = (veinRng.nextDouble() - 0.5) * Math.PI;
        float axisX = (float) (Math.cos(pitch) * Math.cos(theta));
        float axisY = (float) Math.sin(pitch) * AXIS_Y_DAMP;
        float axisZ = (float) (Math.cos(pitch) * Math.sin(theta));

        float span = veinSpan(size);
        float radius = veinRadius(size);
        int steps = 2 + size / 8;

        boolean singleBlock = true;
        int firstX = 0, firstY = 0, firstZ = 0;
        int prevX = 0, prevY = 0, prevZ = 0;
        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps - 0.5f;
            float jx = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER;
            float jy = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER * AXIS_Y_DAMP;
            float jz = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER;
            float r = radius * (RADIUS_JITTER_MIN
                    + veinRng.nextFloat() * (RADIUS_JITTER_MAX - RADIUS_JITTER_MIN));
            float wx = ox + axisX * span * t + jx;
            float wy = oy + axisY * span * t + jy;
            float wz = oz + axisZ * span * t + jz;
            if (!keep || shape == VeinShape.LONE) {
                continue;
            }
            fillSphere(ctx, ore, wx, wy, wz, r, grouped);

            int cx = Math.round(wx), cy = Math.round(wy), cz = Math.round(wz);
            if (s == 0) {
                firstX = cx;
                firstY = cy;
                firstZ = cz;
            } else if (shape == VeinShape.GROUPED) {
                joinCentres(ctx, ore, prevX, prevY, prevZ, cx, cy, cz, grouped);
            }
            prevX = cx;
            prevY = cy;
            prevZ = cz;
            singleBlock &= r < 1f && cx == firstX && cy == firstY && cz == firstZ;
        }

        if (!keep) {
            return;
        }
        if (shape == VeinShape.LONE) {
            int lx = ox - ctx.chunkX * ChunkGenerationContext.SIZE;
            int lz = oz - ctx.chunkZ * ChunkGenerationContext.SIZE;
            if (inChunk(lx, oy, lz)) {
                grouped.addLone(cell(lx, oy, lz), ore);
            }
        } else if (shape == VeinShape.GROUPED && singleBlock) {
            // Extend one block along the vein axis's dominant component.
            float ax = Math.abs(axisX), ay = Math.abs(axisY), az = Math.abs(axisZ);
            int dx = ax >= ay && ax >= az ? (axisX < 0 ? -1 : 1) : 0;
            int dz = dx == 0 && az >= ay ? (axisZ < 0 ? -1 : 1) : 0;
            int dy = dx == 0 && dz == 0 ? (axisY < 0 ? -1 : 1) : 0;
            fillSphere(ctx, ore, firstX + dx, firstY + dy, firstZ + dz, 0f, grouped);
        }
    }

    /**
     * Writes a face-connected path of ore from one sphere centre to the next, stepping X,
     * then Y, then Z. Both ends are already written by their spheres; this fills between.
     */
    private void joinCentres(ChunkGenerationContext ctx, BlockType ore,
                             int x, int y, int z, int toX, int toY, int toZ, GroupedWrites grouped) {
        while (x != toX) {
            x += Integer.signum(toX - x);
            fillSphere(ctx, ore, x, y, z, 0f, grouped);
        }
        while (y != toY) {
            y += Integer.signum(toY - y);
            fillSphere(ctx, ore, x, y, z, 0f, grouped);
        }
        while (z != toZ) {
            z += Integer.signum(toZ - z);
            fillSphere(ctx, ore, x, y, z, 0f, grouped);
        }
    }

    /**
     * Writes ore over the stone inside one sphere, clipped to this chunk. Rejects on the
     * bounding box first so the vast majority of spheres — those belonging to neighbouring
     * chunks' veins — cost nothing.
     *
     * @param grouped records each write when non-null
     */
    private void fillSphere(ChunkGenerationContext ctx, BlockType ore,
                            float wx, float wy, float wz, float radius, GroupedWrites grouped) {
        int size = ChunkGenerationContext.SIZE;
        int baseX = ctx.chunkX * size;
        int baseZ = ctx.chunkZ * size;
        int r = (int) Math.ceil(radius);
        int cx = Math.round(wx);
        int cy = Math.round(wy);
        int cz = Math.round(wz);

        if (cx + r < baseX || cx - r >= baseX + size) return;
        if (cz + r < baseZ || cz - r >= baseZ + size) return;

        Chunk chunk = ctx.chunk;
        float r2 = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            int lx = cx + dx - baseX;
            if (lx < 0 || lx >= size) continue;
            for (int dz = -r; dz <= r; dz++) {
                int lz = cz + dz - baseZ;
                if (lz < 0 || lz >= size) continue;
                for (int dy = -r; dy <= r; dy++) {
                    int y = cy + dy;
                    if (y < 1 || y >= WorldConfiguration.WORLD_HEIGHT) continue;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    setIfStone(chunk, lx, y, lz, ore, grouped);
                }
            }
        }
    }

    /**
     * The one state check. Stone only, so cave voids, air, bedrock, surface layers and
     * already-placed ore are all left alone.
     */
    private static void setIfStone(Chunk chunk, int lx, int y, int lz, BlockType ore,
                                   GroupedWrites grouped) {
        if (chunk.getBlock(lx, y, lz) == BlockType.STONE) {
            chunk.setBlock(lx, y, lz, ore);
            if (grouped != null) {
                grouped.add(cell(lx, y, lz));
            }
        }
    }

    /**
     * Gives every grouped-ore block that ended up alone a second block, turning its first
     * stone face-neighbour into the same ore.
     *
     * <p>A vein's geometry is always at least two connected blocks, but only stone takes
     * ore, so cave air, soil and bedrock can cut a piece off a vein — a single block left
     * showing in a cave wall. Those are found here, after every vein is written, by their
     * neighbours in this chunk. A block in an edge column is skipped: its neighbour across
     * the border belongs to a chunk this one cannot see, and may well be the rest of its
     * vein. The neighbour order is fixed, so the pairing is deterministic.
     */
    private static void pairIsolatedBlocks(ChunkGenerationContext ctx, GroupedWrites grouped) {
        Chunk chunk = ctx.chunk;
        int last = ChunkGenerationContext.SIZE - 1;
        for (int i = 0; i < grouped.count; i++) {
            int cell = grouped.cells[i];
            int lx = cellX(cell), y = cellY(cell), lz = cellZ(cell);
            if (lx == 0 || lx == last || lz == 0 || lz == last) {
                continue;
            }
            BlockType ore = chunk.getBlock(lx, y, lz);
            int stone = -1;
            boolean paired = false;
            for (int[] d : FACE_NEIGHBOURS) {
                int nx = lx + d[0], ny = y + d[1], nz = lz + d[2];
                if (!inChunk(nx, ny, nz)) {
                    continue;
                }
                BlockType neighbour = chunk.getBlock(nx, ny, nz);
                if (neighbour == ore) {
                    paired = true;
                    break;
                }
                if (stone < 0 && neighbour == BlockType.STONE) {
                    stone = cell(nx, ny, nz);
                }
            }
            if (!paired && stone >= 0) {
                chunk.setBlock(cellX(stone), cellY(stone), cellZ(stone), ore);
            }
        }
    }

    private static final int[][] FACE_NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}
    };

    private static boolean inChunk(int lx, int y, int lz) {
        return lx >= 0 && lx < ChunkGenerationContext.SIZE
                && lz >= 0 && lz < ChunkGenerationContext.SIZE
                && y >= 1 && y < WorldConfiguration.WORLD_HEIGHT;
    }

    /** Packs a chunk-local block position; the chunk is 16 wide, so X and Z take 4 bits. */
    private static int cell(int lx, int y, int lz) {
        return lx | (lz << 4) | (y << 8);
    }

    private static int cellX(int cell) {
        return cell & 0xF;
    }

    private static int cellZ(int cell) {
        return (cell >> 4) & 0xF;
    }

    private static int cellY(int cell) {
        return cell >>> 8;
    }

    /**
     * Iron abundance at absolute {@code y}, in [{@link #IRON_TROUGH_WEIGHT}, 1]: full rate
     * at or below {@link #IRON_DEEP_PEAK_Y} and at or above {@link #IRON_HIGH_PEAK_Y},
     * falling linearly to the trough at sea level in between.
     */
    private static float ironDepthWeight(int y) {
        if (y <= IRON_DEEP_PEAK_Y || y >= IRON_HIGH_PEAK_Y) {
            return 1.0f;
        }
        if (y < IRON_TROUGH_Y) {
            float t = (float) (y - IRON_DEEP_PEAK_Y) / (IRON_TROUGH_Y - IRON_DEEP_PEAK_Y);
            return 1.0f + t * (IRON_TROUGH_WEIGHT - 1.0f);
        }
        float t = (float) (y - IRON_TROUGH_Y) / (IRON_HIGH_PEAK_Y - IRON_TROUGH_Y);
        return IRON_TROUGH_WEIGHT + t * (1.0f - IRON_TROUGH_WEIGHT);
    }

    /** Linear ramp: 1 at or below {@code full}, 0 at or above {@code zero}. */
    private static float fade(int value, int full, int zero) {
        if (value <= full) return 1.0f;
        if (value >= zero) return 0.0f;
        return 1.0f - (float) (value - full) / (zero - full);
    }

    /** The whole stone column, {@code [1, stoneTop)}: the band coal and iron always used. */
    private static void wholeColumn(int stoneTop, int[] out) {
        band(1, stoneTop, out);
    }

    private static void band(int lo, int hi, int[] out) {
        out[0] = lo;
        out[1] = hi;
    }

    private static int maxVeinReach() {
        int maxSize = 0;
        for (OreSpec ore : ORES) {
            maxSize = Math.max(maxSize, ore.sizeMax());
        }
        return (int) Math.ceil(veinSpan(maxSize) * 0.5f
                + veinRadius(maxSize) * RADIUS_JITTER_MAX
                + STEP_JITTER);
    }

    /** Elongation of a vein holding {@code size} blocks, in blocks. */
    private static float veinSpan(int size) {
        return size * 0.22f;
    }

    /**
     * Nominal sphere radius for a vein holding {@code size} blocks, before jitter.
     *
     * <p>The coefficient is calibrated, not derived: the spheres in a chain overlap each
     * other and the jitter spreads them, so the union is not the sum of their volumes and
     * no closed form predicts it. {@code OreVeinDistributionTest} measures the realised
     * blocks per vein — retune this against that number, not on paper.
     */
    private static float veinRadius(int size) {
        return (float) Math.cbrt(size * 0.085);
    }

    /**
     * Per-source-chunk vein stream, following {@code CavernCarver.cavernRngSeed}.
     * {@code oreSalt} keeps each ore's stream independent.
     */
    private long veinRngSeed(int cx, int cz, long oreSalt) {
        return ((seed * 6364136223846793005L) ^ ((long) cx * 0x9E3779B97F4A7C15L))
                ^ ((long) cz * 0xC2B2AE3D27D4EB4FL) ^ oreSalt;
    }

    /**
     * Crystals in red sand desert — unchanged per-block rolls, but only over columns in
     * that biome. Behaviour-identical, since the branch could never fire elsewhere, and
     * it keeps the old full-column scan off every other biome.
     */
    private void generateCrystals(ChunkGenerationContext ctx) {
        Chunk chunk = ctx.chunk;
        for (int x = 0; x < ChunkGenerationContext.SIZE; x++) {
            for (int z = 0; z < ChunkGenerationContext.SIZE; z++) {
                if (ctx.biome(x, z) != BiomeType.RED_SAND_DESERT) {
                    continue;
                }
                int worldX = ctx.worldX(x);
                int worldZ = ctx.worldZ(z);
                int columnTop = ctx.height(x, z) - 1;
                int crystalMaxY = columnTop - 4;

                for (int y = CRYSTAL_MIN_Y + 1; y < crystalMaxY; y++) {
                    BlockType current = chunk.getBlock(x, y, z);
                    if (current != BlockType.RED_SAND && current != BlockType.STONE
                            && current != BlockType.MAGMA) {
                        continue;
                    }
                    if (rng.shouldGenerate3D(worldX, y, worldZ, "crystal", CRYSTAL_CHANCE)) {
                        chunk.setBlock(x, y, z, BlockType.CRYSTAL);
                    }
                }
            }
        }
    }

    /**
     * One chunk's grouped-ore bookkeeping: every block a grouped vein wrote, for
     * {@link #pairIsolatedBlocks}, and the deliberate lone blocks held back until after it.
     * Local to a {@link #generate} call, so concurrent chunks never share one.
     */
    private static final class GroupedWrites {
        int[] cells = new int[256];
        int count;
        int[] lone = new int[8];
        BlockType[] loneOre = new BlockType[8];
        int loneCount;

        void add(int cell) {
            if (count == cells.length) {
                cells = Arrays.copyOf(cells, count * 2);
            }
            cells[count++] = cell;
        }

        void addLone(int cell, BlockType ore) {
            if (loneCount == lone.length) {
                lone = Arrays.copyOf(lone, loneCount * 2);
                loneOre = Arrays.copyOf(loneOre, loneCount * 2);
            }
            lone[loneCount] = cell;
            loneOre[loneCount++] = ore;
        }
    }

    /** How a vein's geometry is turned into blocks. */
    private enum VeinShape {
        /** The sphere chain as drawn, however small (coal and iron). */
        CHAIN,
        /** The sphere chain, padded to two blocks if it would cover only one. */
        GROUPED,
        /** A single block at the vein origin, written after the pairing pass. */
        LONE
    }

    /** Writes the half-open Y band {@code [out[0], out[1])} a vein may start in. */
    @FunctionalInterface
    private interface YBand {
        void of(int stoneTop, int[] out);
    }

    /** Chance in [0, 1] that a vein starting at {@code y} is kept. */
    @FunctionalInterface
    private interface DepthWeight {
        float at(int y, int stoneTop);
    }

    /**
     * One ore's vein recipe. The vein origin is drawn uniformly from {@code band}, then
     * kept with probability {@code weight}. {@code grouped} veins are at least two blocks,
     * bar a {@link #LONE_BLOCK_CHANCE} of exactly one.
     */
    private record OreSpec(BlockType block, long salt, int candidates, int sizeMin, int sizeMax,
                           boolean grouped, YBand band, DepthWeight weight) {}
}
