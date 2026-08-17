package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Random;

/**
 * Places ores within already-generated stone columns.
 *
 * <p>Coal and iron generate as <b>veins</b>: chains of overlapping spheres, not
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
     */
    private static final int IRON_DEEP_PEAK_Y = 60;
    private static final int IRON_TROUGH_Y = WorldConfiguration.SEA_LEVEL;
    private static final int IRON_HIGH_PEAK_Y = 420;
    private static final float IRON_TROUGH_WEIGHT = 0.15f;

    /** Veins attempted per source chunk. Coal is unweighted, so this is also its yield. */
    private static final int COAL_VEINS_PER_CHUNK = 30;
    /**
     * Iron candidates per source chunk. Each survives with probability
     * {@link #ironDepthWeight(int)}, which averages near 0.5 over a typical column, so
     * roughly half of these become veins.
     */
    private static final int IRON_VEIN_CANDIDATES_PER_CHUNK = 28;

    private static final int COAL_SIZE_MIN = 8;
    private static final int COAL_SIZE_MAX = 24;
    private static final int IRON_SIZE_MIN = 4;
    private static final int IRON_SIZE_MAX = 14;

    /** Independent RNG streams per ore, mixed into the per-chunk vein seed. */
    private static final long COAL_SALT = 0x517CC1B727220A95L;
    private static final long IRON_SALT = 0x2545F4914F6CDD1DL;

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
     * sphere radius, plus per-step jitter. Sized off the largest vein (coal at
     * {@link #COAL_SIZE_MAX}) so raising the size constants widens the scan with it.
     */
    private static final int MAX_VEIN_REACH =
            (int) Math.ceil(veinSpan(COAL_SIZE_MAX) * 0.5f
                    + veinRadius(COAL_SIZE_MAX) * RADIUS_JITTER_MAX
                    + STEP_JITTER);
    /** Source chunks to scan in each direction so border-straddling veins are not clipped. */
    private static final int SCAN_RADIUS =
            (int) Math.ceil((double) MAX_VEIN_REACH / ChunkGenerationContext.SIZE);

    /** Stone stops this far below the surface — mirrors {@code determineBlockType}. */
    private static final int SUBSURFACE_DEPTH = 4;

    private static final int CRYSTAL_MIN_Y = 20;
    private static final float CRYSTAL_CHANCE = 0.02f;

    private final DeterministicRandom rng;
    private final HeightMapGenerator heightMapGenerator;
    private final long seed;

    public OreGenerator(DeterministicRandom rng, HeightMapGenerator heightMapGenerator, long seed) {
        this.rng = rng;
        this.heightMapGenerator = heightMapGenerator;
        this.seed = seed;
    }

    public void generate(ChunkGenerationContext ctx) {
        // Coal before iron: both only overwrite STONE, so the first writer wins an
        // overlap. That preserves the old pickOre precedence, where the coal roll was
        // tested first and returned before iron was considered.
        generateVeins(ctx, BlockType.COAL_ORE);
        generateVeins(ctx, BlockType.IRON_ORE);
        generateCrystals(ctx);
    }

    /**
     * Scans every source chunk whose veins could reach this one and places whatever
     * lands inside it.
     */
    private void generateVeins(ChunkGenerationContext ctx, BlockType ore) {
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                spawnVeins(ctx, ore, ctx.chunkX + dcx, ctx.chunkZ + dcz);
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
    private void spawnVeins(ChunkGenerationContext ctx, BlockType ore, int srcCx, int srcCz) {
        boolean iron = ore == BlockType.IRON_ORE;
        int candidates = iron ? IRON_VEIN_CANDIDATES_PER_CHUNK : COAL_VEINS_PER_CHUNK;
        int sizeMin = iron ? IRON_SIZE_MIN : COAL_SIZE_MIN;
        int sizeMax = iron ? IRON_SIZE_MAX : COAL_SIZE_MAX;
        Random veinRng = new Random(veinRngSeed(srcCx, srcCz, iron ? IRON_SALT : COAL_SALT));

        int baseX = srcCx * ChunkGenerationContext.SIZE;
        int baseZ = srcCz * ChunkGenerationContext.SIZE;

        for (int i = 0; i < candidates; i++) {
            int ox = baseX + veinRng.nextInt(ChunkGenerationContext.SIZE);
            int oz = baseZ + veinRng.nextInt(ChunkGenerationContext.SIZE);
            // Highest stone in the origin column. The degenerate branch skips a draw, but
            // stoneTop depends only on (ox, oz) — never on which chunk is being populated —
            // so every replay of this source chunk takes the same branch and stays aligned.
            int stoneTop = heightMapGenerator.generateHeight(ox, oz) - SUBSURFACE_DEPTH;
            int oy = stoneTop > 1 ? 1 + veinRng.nextInt(stoneTop - 1) : 1;
            int size = sizeMin + veinRng.nextInt(sizeMax - sizeMin + 1);

            // Rejection sampling against the depth curve. Drawn unconditionally so coal
            // and iron consume identical draw counts per candidate.
            float keepRoll = veinRng.nextFloat();
            boolean keep = stoneTop > 1 && (!iron || keepRoll < ironDepthWeight(oy));

            placeVein(ctx, ore, ox, oy, oz, size, veinRng, keep);
        }
    }

    /**
     * Steps a chain of spheres along a random axis through {@code (ox, oy, oz)}, writing
     * ore into {@code ctx}'s chunk wherever a sphere covers stone.
     *
     * <p>Always consumes the same draws regardless of {@code keep} or of whether the vein
     * overlaps this chunk at all; {@code keep == false} suppresses only the writes.
     */
    private void placeVein(ChunkGenerationContext ctx, BlockType ore,
                           int ox, int oy, int oz, int size, Random veinRng, boolean keep) {
        // Random axis on the unit sphere, then flattened.
        double theta = veinRng.nextDouble() * Math.PI * 2;
        double pitch = (veinRng.nextDouble() - 0.5) * Math.PI;
        float axisX = (float) (Math.cos(pitch) * Math.cos(theta));
        float axisY = (float) Math.sin(pitch) * AXIS_Y_DAMP;
        float axisZ = (float) (Math.cos(pitch) * Math.sin(theta));

        float span = veinSpan(size);
        float radius = veinRadius(size);
        int steps = 2 + size / 8;

        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps - 0.5f;
            float jx = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER;
            float jy = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER * AXIS_Y_DAMP;
            float jz = (veinRng.nextFloat() - 0.5f) * 2f * STEP_JITTER;
            float r = radius * (RADIUS_JITTER_MIN
                    + veinRng.nextFloat() * (RADIUS_JITTER_MAX - RADIUS_JITTER_MIN));
            if (keep) {
                fillSphere(ctx, ore, ox + axisX * span * t + jx,
                        oy + axisY * span * t + jy, oz + axisZ * span * t + jz, r);
            }
        }
    }

    /**
     * Writes ore over the stone inside one sphere, clipped to this chunk. Rejects on the
     * bounding box first so the vast majority of spheres — those belonging to neighbouring
     * chunks' veins — cost nothing.
     */
    private void fillSphere(ChunkGenerationContext ctx, BlockType ore,
                            float wx, float wy, float wz, float radius) {
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
                    // The one state check. Stone only, so cave voids, air, bedrock,
                    // surface layers and already-placed ore are all left alone.
                    if (chunk.getBlock(lx, y, lz) == BlockType.STONE) {
                        chunk.setBlock(lx, y, lz, ore);
                    }
                }
            }
        }
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
     * {@code oreSalt} keeps coal and iron independent.
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
}
