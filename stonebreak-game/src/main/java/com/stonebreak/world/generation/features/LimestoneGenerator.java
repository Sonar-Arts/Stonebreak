package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.ChunkGenerationContext;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Replaces stone with limestone after terrain and ores are in place.
 *
 * <p>Four placements, all of which only ever overwrite {@link BlockType#STONE}:
 * <ol>
 *   <li><b>Cavern formations</b> — inside a cavern or megacavern every stalagmite and
 *       stalactite becomes limestone. A formation is recognised structurally (a stone cell
 *       whose horizontal neighbours are all cave air) rather than by rebuilding the carvers'
 *       formation masks, which would repeat the most expensive part of terrain generation.</li>
 *   <li><b>Cavern flowstone</b> — noise-shaped sheets up to {@link #CAVERN_COAT_DEPTH}
 *       blocks deep over cavern floors, walls and ceilings.</li>
 *   <li><b>Karst caves</b> — within regional karst zones, patchy one-block coatings on the
 *       walls of worm tunnels and ordinary caves.</li>
 *   <li><b>Pockets and beds</b> — blob pockets in solid rock, spawned per source chunk like
 *       {@link OreGenerator}'s veins, and horizontal sediment beds in stratified regions.</li>
 * </ol>
 *
 * <p>This runs in the Java feature pass, after both the fused native generator and the
 * legacy path have produced identical terrain, so neither the kernel nor its parity tests
 * need to know limestone exists.
 *
 * <p>Cave air across a chunk border is read from the neighbour only when it is already
 * resident ({@link com.stonebreak.world.World#getChunkIfLoaded}); an absent neighbour counts
 * as rock. Air is fixed by terrain generation, so a resident neighbour gives the same answer
 * whether or not its own features have been populated.
 */
public class LimestoneGenerator {
    private static final int CHUNK = ChunkGenerationContext.SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    /** Stone stops this far below the surface — mirrors {@code determineBlockType}. */
    private static final int SUBSURFACE_DEPTH = 4;

    // ----- Cavern zones: ellipsoids around each carver's origin that cover its blobs. -----
    private static final float CAVERN_ZONE_RADIUS = 24f;
    private static final float CAVERN_ZONE_HALF_HEIGHT = 16f;
    private static final float MEGA_ZONE_RADIUS = 48f;
    private static final float MEGA_ZONE_HALF_HEIGHT = 36f;

    /** Deepest flowstone sheet, in blocks from the exposed face. */
    private static final int CAVERN_COAT_DEPTH = 2;
    /** Patch-noise threshold for flowstone; lower = more coverage. Megacaverns coat more. */
    private static final float CAVERN_COAT_THRESHOLD = -0.05f;
    private static final float MEGA_COAT_THRESHOLD = -0.20f;

    // ----- Karst caves -----
    private static final float KARST_REGION_FREQ = 0.004f;
    private static final float KARST_REGION_THRESHOLD = 0.20f;
    private static final float KARST_COAT_THRESHOLD = 0.10f;

    private static final float PATCH_FREQ_XZ = 0.07f;
    private static final float PATCH_FREQ_Y = 0.10f;

    // ----- Beds -----
    private static final float STRATA_REGION_FREQ = 0.003f;
    private static final float STRATA_REGION_THRESHOLD = 0.25f;
    /** Region-noise span over which beds grow from nothing to full thickness. */
    private static final float STRATA_FADE = 0.25f;
    private static final float STRATA_WARP_FREQ = 0.012f;
    private static final float STRATA_WARP_AMPLITUDE = 5f;
    /** Vertical period of the bedding pattern and the share of it that is limestone at full strength. */
    private static final float STRATA_PERIOD = 12f;
    private static final float STRATA_MAX_FILL = 0.40f;
    private static final int STRATA_MIN_Y = 6;
    /** Beds stay this far under the surface so they read as rock layers, not a surface skin. */
    private static final int STRATA_SURFACE_CLEARANCE = 8;

    // ----- Pockets -----
    private static final int POCKET_CANDIDATES_PER_CHUNK = 3;
    private static final float POCKET_KEEP_CHANCE = 0.45f;
    private static final float POCKET_RADIUS_MIN = 2.0f;
    private static final float POCKET_RADIUS_MAX = 4.5f;
    private static final int POCKET_BLOBS_MIN = 2;
    private static final int POCKET_BLOBS_MAX = 5;
    private static final float POCKET_BLOB_OFFSET = 3.0f;
    private static final int POCKET_SCAN_RADIUS = (int) Math.ceil(
            (POCKET_RADIUS_MAX + POCKET_BLOB_OFFSET + 1) / CHUNK);
    private static final long POCKET_SALT = 0x11E57011E5701L;

    private static final byte ROCK = 0;
    private static final byte STONE = 1;
    private static final byte AIR = 2;

    private final long seed;
    private final HeightMapGenerator heightMapGenerator;
    private final CavernCarver cavernCarver;
    private final MegaCavernCarver megaCavernCarver;
    private final NoiseGenerator patchNoise;
    private final NoiseGenerator regionNoise;
    private final NoiseGenerator warpNoise;

    /** Per-thread scratch: one cell class per chunk-local voxel, {@code [(x*16+z)*H + y]}. */
    private final ThreadLocal<byte[]> cellScratch =
            ThreadLocal.withInitial(() -> new byte[CHUNK * CHUNK * WORLD_HEIGHT]);

    public LimestoneGenerator(long seed, HeightMapGenerator heightMapGenerator,
                              CavernCarver cavernCarver, MegaCavernCarver megaCavernCarver) {
        this.seed = seed;
        this.heightMapGenerator = heightMapGenerator;
        this.cavernCarver = cavernCarver;
        this.megaCavernCarver = megaCavernCarver;
        this.patchNoise = new NoiseGenerator(seed ^ 0x7A11E57EL, 2, 0.5, 2.0);
        this.regionNoise = new NoiseGenerator(seed ^ 0x6A57C0DEL, 2, 0.5, 2.0);
        this.warpNoise = new NoiseGenerator(seed ^ 0x57A7A000L, 1, 0.5, 2.0);
    }

    public void generate(ChunkGenerationContext ctx) {
        Chunk chunk = ctx.chunk;
        byte[] cells = cellScratch.get();
        int maxTop = classifyCells(ctx, cells);
        if (maxTop <= 1) {
            return;
        }
        List<float[]> zones = cavernZones(ctx.chunkX, ctx.chunkZ);
        Chunk[] neighbours = neighbourChunks(ctx);

        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                int wx = ctx.worldX(lx);
                int wz = ctx.worldZ(lz);
                int stoneTop = Math.min(ctx.height(lx, lz) - SUBSURFACE_DEPTH, WORLD_HEIGHT);
                if (stoneTop <= 1) {
                    continue;
                }
                float karst = regionNoise.noise(wx * KARST_REGION_FREQ, wz * KARST_REGION_FREQ);
                float strata = regionNoise.noise(wx * STRATA_REGION_FREQ + 911f, wz * STRATA_REGION_FREQ - 377f);
                float bedFill = strata > STRATA_REGION_THRESHOLD
                        ? STRATA_MAX_FILL * Math.min(1f, (strata - STRATA_REGION_THRESHOLD) / STRATA_FADE)
                        : 0f;
                float warp = bedFill > 0f
                        ? warpNoise.noise(wx * STRATA_WARP_FREQ, wz * STRATA_WARP_FREQ) * STRATA_WARP_AMPLITUDE
                        : 0f;
                int column = (lx * CHUNK + lz) * WORLD_HEIGHT;

                for (int y = 1; y < stoneTop; y++) {
                    if (cells[column + y] != STONE) {
                        continue;
                    }
                    if (shouldBeLimestone(ctx, cells, neighbours, zones, lx, y, lz, wx, wz,
                            stoneTop, karst, bedFill, warp)) {
                        chunk.setBlock(lx, y, lz, BlockType.LIMESTONE);
                    }
                }
            }
        }

        generatePockets(ctx);
    }

    private boolean shouldBeLimestone(ChunkGenerationContext ctx, byte[] cells, Chunk[] neighbours,
                                      List<float[]> zones, int lx, int y, int lz, int wx, int wz,
                                      int stoneTop, float karst, float bedFill, float warp) {
        int zone = zoneAt(zones, wx, y, wz);
        int exposure = exposureDepth(cells, neighbours, lx, y, lz, zone != 0 ? CAVERN_COAT_DEPTH : 1);

        if (exposure > 0) {
            if (zone != 0 && isFormation(cells, neighbours, lx, y, lz)) {
                return true;
            }
            float patch = patchNoise.noise3D(wx * PATCH_FREQ_XZ, y * PATCH_FREQ_Y, wz * PATCH_FREQ_XZ);
            if (zone != 0) {
                float threshold = zone == 2 ? MEGA_COAT_THRESHOLD : CAVERN_COAT_THRESHOLD;
                // The second block of a sheet needs a stronger patch, so sheets thin at their edges.
                if (patch > threshold + (exposure - 1) * 0.25f) {
                    return true;
                }
            } else if (karst > KARST_REGION_THRESHOLD && exposure == 1 && patch > KARST_COAT_THRESHOLD) {
                return true;
            }
        }

        if (bedFill > 0f && y >= STRATA_MIN_Y && y < stoneTop + SUBSURFACE_DEPTH - STRATA_SURFACE_CLEARANCE) {
            float phase = (y + warp) / STRATA_PERIOD;
            return phase - (float) Math.floor(phase) < bedFill;
        }
        return false;
    }

    /**
     * Fills {@code cells} for the chunk up to its tallest column: {@link #AIR} for open air
     * (water and every other block count as rock, so a flooded cave is left alone),
     * {@link #STONE} for stone, {@link #ROCK} for anything else.
     *
     * @return one past the highest classified y
     */
    private int classifyCells(ChunkGenerationContext ctx, byte[] cells) {
        int maxTop = 0;
        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                maxTop = Math.max(maxTop, Math.min(ctx.height(lx, lz), WORLD_HEIGHT));
            }
        }
        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                int column = (lx * CHUNK + lz) * WORLD_HEIGHT;
                for (int y = 0; y < maxTop; y++) {
                    cells[column + y] = classify(ctx.chunk.getBlock(lx, y, lz));
                }
                // Above maxTop no cell is ever read as STONE, but exposure probes reach one
                // block over the tallest column and must see sky, not last chunk's scratch.
                if (maxTop < WORLD_HEIGHT) {
                    cells[column + maxTop] = AIR;
                }
            }
        }
        return maxTop;
    }

    private static byte classify(BlockType block) {
        if (block == BlockType.STONE) return STONE;
        if (block == BlockType.AIR) return AIR;
        return ROCK;
    }

    /** Neighbour chunks indexed -X, +X, -Z, +Z; null when not resident (or in tests). */
    private static Chunk[] neighbourChunks(ChunkGenerationContext ctx) {
        Chunk[] out = new Chunk[4];
        if (ctx.world == null) {
            return out;
        }
        out[0] = ctx.world.getChunkIfLoaded(ctx.chunkX - 1, ctx.chunkZ);
        out[1] = ctx.world.getChunkIfLoaded(ctx.chunkX + 1, ctx.chunkZ);
        out[2] = ctx.world.getChunkIfLoaded(ctx.chunkX, ctx.chunkZ - 1);
        out[3] = ctx.world.getChunkIfLoaded(ctx.chunkX, ctx.chunkZ + 1);
        return out;
    }

    private static boolean isAir(byte[] cells, Chunk[] neighbours, int lx, int y, int lz) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return false;
        }
        if (lx >= 0 && lx < CHUNK && lz >= 0 && lz < CHUNK) {
            return cells[(lx * CHUNK + lz) * WORLD_HEIGHT + y] == AIR;
        }
        Chunk n;
        if (lx < 0) n = neighbours[0];
        else if (lx >= CHUNK) n = neighbours[1];
        else if (lz < 0) n = neighbours[2];
        else n = neighbours[3];
        if (n == null) {
            return false;
        }
        return n.getBlock(Math.floorMod(lx, CHUNK), y, Math.floorMod(lz, CHUNK)) == BlockType.AIR;
    }

    /** Distance (1..maxDepth) along an axis to the nearest air, or 0 when none is that close. */
    private static int exposureDepth(byte[] cells, Chunk[] neighbours, int lx, int y, int lz, int maxDepth) {
        for (int d = 1; d <= maxDepth; d++) {
            if (isAir(cells, neighbours, lx + d, y, lz) || isAir(cells, neighbours, lx - d, y, lz)
                    || isAir(cells, neighbours, lx, y + d, lz) || isAir(cells, neighbours, lx, y - d, lz)
                    || isAir(cells, neighbours, lx, y, lz + d) || isAir(cells, neighbours, lx, y, lz - d)) {
                return d;
            }
        }
        return 0;
    }

    /**
     * A one-wide stone pillar: all four horizontal neighbours are air. Stalagmites and
     * stalactites are exactly that; a cavern wall never is.
     */
    private static boolean isFormation(byte[] cells, Chunk[] neighbours, int lx, int y, int lz) {
        return isAir(cells, neighbours, lx + 1, y, lz) && isAir(cells, neighbours, lx - 1, y, lz)
                && isAir(cells, neighbours, lx, y, lz + 1) && isAir(cells, neighbours, lx, y, lz - 1);
    }

    /**
     * Origins of every cavern and megacavern whose zone can reach this chunk, as
     * {@code {x, y, z, kind}} with kind 1 = cavern, 2 = megacavern.
     */
    private List<float[]> cavernZones(int chunkX, int chunkZ) {
        List<float[]> zones = new ArrayList<>();
        collectZones(zones, chunkX, chunkZ, CAVERN_ZONE_RADIUS, 1);
        collectZones(zones, chunkX, chunkZ, MEGA_ZONE_RADIUS, 2);
        return zones;
    }

    private void collectZones(List<float[]> zones, int chunkX, int chunkZ, float radius, int kind) {
        int scan = (int) Math.ceil(radius / CHUNK) + 1;
        float minX = chunkX * CHUNK - radius;
        float maxX = (chunkX + 1) * CHUNK + radius;
        float minZ = chunkZ * CHUNK - radius;
        float maxZ = (chunkZ + 1) * CHUNK + radius;
        for (int dcx = -scan; dcx <= scan; dcx++) {
            for (int dcz = -scan; dcz <= scan; dcz++) {
                float[] origin = kind == 1
                        ? cavernCarver.computeCavernOrigin(chunkX + dcx, chunkZ + dcz)
                        : megaCavernCarver.computeCavernOrigin(chunkX + dcx, chunkZ + dcz);
                if (origin == null) continue;
                if (origin[0] < minX || origin[0] > maxX || origin[2] < minZ || origin[2] > maxZ) continue;
                zones.add(new float[] { origin[0], origin[1], origin[2], kind });
            }
        }
    }

    /** 2 inside a megacavern zone, 1 inside a cavern zone, else 0. */
    private static int zoneAt(List<float[]> zones, int wx, int y, int wz) {
        int best = 0;
        for (float[] z : zones) {
            boolean mega = z[3] == 2f;
            float r = mega ? MEGA_ZONE_RADIUS : CAVERN_ZONE_RADIUS;
            float h = mega ? MEGA_ZONE_HALF_HEIGHT : CAVERN_ZONE_HALF_HEIGHT;
            float dx = (wx - z[0]) / r;
            float dy = (y - z[1]) / h;
            float dz = (wz - z[2]) / r;
            if (dx * dx + dy * dy + dz * dz <= 1f) {
                if (mega) return 2;
                best = 1;
            }
        }
        return best;
    }

    /**
     * Blob pockets in solid rock. Spawned per source chunk and scanned in from neighbours,
     * exactly like {@link OreGenerator}'s veins, so a pocket crossing a border is continuous.
     * Every candidate consumes the same draws whether it is kept or not.
     */
    private void generatePockets(ChunkGenerationContext ctx) {
        for (int dcx = -POCKET_SCAN_RADIUS; dcx <= POCKET_SCAN_RADIUS; dcx++) {
            for (int dcz = -POCKET_SCAN_RADIUS; dcz <= POCKET_SCAN_RADIUS; dcz++) {
                spawnPockets(ctx, ctx.chunkX + dcx, ctx.chunkZ + dcz);
            }
        }
    }

    private void spawnPockets(ChunkGenerationContext ctx, int srcCx, int srcCz) {
        Random rng = new Random(pocketRngSeed(srcCx, srcCz));
        int baseX = srcCx * CHUNK;
        int baseZ = srcCz * CHUNK;
        for (int i = 0; i < POCKET_CANDIDATES_PER_CHUNK; i++) {
            int ox = baseX + rng.nextInt(CHUNK);
            int oz = baseZ + rng.nextInt(CHUNK);
            int stoneTop = heightMapGenerator.generateHeight(ox, oz) - SUBSURFACE_DEPTH;
            int oy = stoneTop > 2 ? 1 + rng.nextInt(stoneTop - 1) : 1;
            boolean keep = rng.nextFloat() < POCKET_KEEP_CHANCE && stoneTop > 2;
            int blobs = POCKET_BLOBS_MIN + rng.nextInt(POCKET_BLOBS_MAX - POCKET_BLOBS_MIN + 1);
            for (int b = 0; b < blobs; b++) {
                float bx = ox + (rng.nextFloat() - 0.5f) * 2f * POCKET_BLOB_OFFSET;
                float by = oy + (rng.nextFloat() - 0.5f) * POCKET_BLOB_OFFSET;
                float bz = oz + (rng.nextFloat() - 0.5f) * 2f * POCKET_BLOB_OFFSET;
                float r = POCKET_RADIUS_MIN + rng.nextFloat() * (POCKET_RADIUS_MAX - POCKET_RADIUS_MIN);
                if (keep) {
                    fillBlob(ctx, bx, by, bz, r);
                }
            }
        }
    }

    /** Squashed sphere (pockets lie flatter than tall), stone only, clipped to this chunk. */
    private static void fillBlob(ChunkGenerationContext ctx, float wx, float wy, float wz, float radius) {
        int baseX = ctx.chunkX * CHUNK;
        int baseZ = ctx.chunkZ * CHUNK;
        int r = (int) Math.ceil(radius);
        int cx = Math.round(wx);
        int cy = Math.round(wy);
        int cz = Math.round(wz);
        if (cx + r < baseX || cx - r >= baseX + CHUNK) return;
        if (cz + r < baseZ || cz - r >= baseZ + CHUNK) return;

        Chunk chunk = ctx.chunk;
        float r2 = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            int lx = cx + dx - baseX;
            if (lx < 0 || lx >= CHUNK) continue;
            for (int dz = -r; dz <= r; dz++) {
                int lz = cz + dz - baseZ;
                if (lz < 0 || lz >= CHUNK) continue;
                for (int dy = -r; dy <= r; dy++) {
                    int y = cy + dy;
                    if (y < 1 || y >= WORLD_HEIGHT) continue;
                    float sy = dy * 1.6f;
                    if (dx * dx + sy * sy + dz * dz > r2) continue;
                    if (chunk.getBlock(lx, y, lz) == BlockType.STONE) {
                        chunk.setBlock(lx, y, lz, BlockType.LIMESTONE);
                    }
                }
            }
        }
    }

    private long pocketRngSeed(int cx, int cz) {
        return ((seed * 6364136223846793005L) ^ ((long) cx * 0x9E3779B97F4A7C15L))
                ^ ((long) cz * 0xC2B2AE3D27D4EB4FL) ^ POCKET_SALT;
    }
}
