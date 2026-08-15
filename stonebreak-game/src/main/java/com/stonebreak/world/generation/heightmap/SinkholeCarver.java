package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.BitSet;
import java.util.Random;

/**
 * Sinkholes — vadose dolines that open the cave network to the sky.
 *
 * <p>Everything else underground relies on a carver <em>happening</em> to breach. That is a
 * probabilistic entrance, and the whole reason the cave system was unreachable before this
 * rework is that the probability had silently gone to zero. A sinkhole is a deterministic
 * one: it starts at the surface and is aimed at a worm origin that is known to exist, using
 * the same predictable-anchor trick {@link PerlinWormCarver} uses for its connectors — the
 * spawn RNG stream is replayed to learn where a neighbouring tunnel begins, without walking
 * it. If the shaft reaches that point, the network is open.
 *
 * <p>This is also the correct geology, not just a convenience. A doline forms where surface
 * water finds its way into the rock and enlarges the opening as it descends, so a shaft from
 * the surface down into a phreatic system is exactly the real relationship between the two.
 *
 * <p>Shape: a cone, wide at the surface and narrowing with depth, with a noise-perturbed rim
 * so it does not read as a drilled hole. Deterministic from {@code (seed, chunk)} and gated
 * per carved column through {@link WaterGuard} — a sinkhole into a riverbed would drain it.
 */
public final class SinkholeCarver {

    /**
     * 1 in N chunks spawns a sinkhole — roughly one per 6-7 chunks of span.
     *
     * <p>Denser than it first seems it should be, on purpose. This carver's job is to make
     * the cave network reliably enterable, and a rate that leaves multi-hundred-block
     * stretches of surface with no way in fails that job however good the caves below are.
     * A sinkhole is also small (a few blocks across), so it reads as an occasional feature
     * rather than as clutter.
     */
    private static final int SINKHOLE_CHUNK_DIVISOR = 40;

    /** Mouth radius at the surface, in blocks. */
    private static final float MOUTH_RADIUS_MIN = 3.5f;
    private static final float MOUTH_RADIUS_MAX = 7.0f;
    /** Fraction of the mouth radius remaining at the bottom of the shaft. */
    private static final float THROAT_FRACTION = 0.45f;

    /** Depth bounds, used when there is no worm anchor to aim at. */
    private static final int DEPTH_MIN = 25;
    private static final int DEPTH_MAX = 70;
    /** Hard cap on shaft depth even when aiming at a deep anchor. */
    private static final int DEPTH_LIMIT = 150;

    /** Chunk radius searched for a worm origin to open into. */
    private static final int ANCHOR_SEARCH_RADIUS = 3;

    /** Rim perturbation amplitude (blocks) and wavelength. */
    private static final float RIM_AMP = 1.6f;
    private static final float RIM_SCALE = 1f / 9f;

    /** Worst-case reach: mouth radius plus the origin's offset inside its chunk. */
    private static final float MAX_REACH = MOUTH_RADIUS_MAX + RIM_AMP + WorldConfiguration.CHUNK_SIZE;
    public static final int SCAN_RADIUS =
            (int) Math.ceil(MAX_REACH / WorldConfiguration.CHUNK_SIZE) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int WATER_CLEARANCE = (int) Math.ceil(MOUTH_RADIUS_MAX) + 2;

    private final long seed;
    private final HeightMapGenerator heightMapGenerator;
    private final NoiseGenerator rimNoise;
    private PerlinWormCarver wormCarver;

    public SinkholeCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.heightMapGenerator = heightMapGenerator;
        this.rimNoise = new NoiseGenerator(seed + 911, 1, 0.5, 2.0);
    }

    /**
     * Wires in the worm carver so a sinkhole can aim at a tunnel that is known to exist.
     * Optional — without it a sinkhole still cuts a shaft, it just picks its own depth.
     */
    public void setWormCarver(PerlinWormCarver wormCarver) {
        this.wormCarver = wormCarver;
    }

    /** See {@link RavineCarver#hasRavine} for why the splitmix64 finalizer is not optional. */
    public boolean hasSinkhole(int cx, int cz) {
        long h = seed ^ 0x51E0DD1E51E0DD1EL;
        h ^= (long) cx * 0xFF51AFD7ED558CCDL;
        h = Long.rotateLeft(h, 31);
        h ^= (long) cz * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return Math.floorMod(h, SINKHOLE_CHUNK_DIVISOR) == 0;
    }

    private long sinkholeRngSeed(int cx, int cz) {
        return ((seed * 0x2545F4914F6CDD1DL) ^ ((long) cx * 0x9E3779B97F4A7C15L))
                ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
    }

    /** Carve mask for the target chunk, packed by {@link LocalBlockKey#pack(int,int,int)}. */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights, int[] waterLevels) {
        int[] waterGuard =
                WaterGuard.guardPlane(targetHeights, waterLevels, heightMapGenerator, chunkX, chunkZ);
        BitSet mask = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasSinkhole(srcCx, srcCz)) continue;
                carveSinkhole(srcCx, srcCz, chunkX, chunkZ, targetHeights, waterGuard, mask);
            }
        }
        return mask;
    }

    private void carveSinkhole(int srcCx, int srcCz, int targetCx, int targetCz,
                               int[] targetHeights, int[] waterGuard, BitSet mask) {
        Random rng = new Random(sinkholeRngSeed(srcCx, srcCz));
        int ox = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        int oz = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float mouth = MOUTH_RADIUS_MIN + rng.nextFloat() * (MOUTH_RADIUS_MAX - MOUTH_RADIUS_MIN);
        int fallbackDepth = DEPTH_MIN + rng.nextInt(DEPTH_MAX - DEPTH_MIN);

        // Never open a shaft in a column that holds water.
        if (heightMapGenerator.waterLevel(ox, oz) != TerrainTile.NO_WATER) return;

        int surface = heightMapGenerator.generateHeight(ox, oz);
        int floor = surface - fallbackDepth;

        // Aim at a real tunnel if one is in range: replaying the worm spawn RNG gives its
        // origin without walking it, so the shaft can be cut to exactly the depth that
        // opens into it.
        if (wormCarver != null) {
            float[] anchor = wormCarver.nearestWormOrigin(srcCx, srcCz, ANCHOR_SEARCH_RADIUS);
            if (anchor != null) {
                int anchorY = Math.round(anchor[1]);
                if (anchorY < surface - 8) {
                    floor = Math.max(anchorY - 2, surface - DEPTH_LIMIT);
                }
            }
        }
        if (floor < 2) floor = 2;
        if (floor >= surface) return;

        int r = (int) Math.ceil(mouth + RIM_AMP);
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        if (ox + r < targetBaseX || ox - r >= targetBaseX + CHUNK_SIZE) return;
        if (oz + r < targetBaseZ || oz - r >= targetBaseZ + CHUNK_SIZE) return;

        int span = surface - floor;
        for (int dx = -r; dx <= r; dx++) {
            int bx = ox + dx - targetBaseX;
            if (bx < 0 || bx >= CHUNK_SIZE) continue;
            for (int dz = -r; dz <= r; dz++) {
                int bz = oz + dz - targetBaseZ;
                if (bz < 0 || bz >= CHUNK_SIZE) continue;

                int idx = bx * CHUNK_SIZE + bz;
                int colSurface = targetHeights[idx];
                if (colSurface <= 1) continue;

                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                float rim = rimNoise.noise3D((ox + dx) * RIM_SCALE, 0f, (oz + dz) * RIM_SCALE) * RIM_AMP;

                int top = Math.min(colSurface, surface);
                for (int by = floor; by < top; by++) {
                    // Cone: full mouth radius at the surface, narrowing to the throat.
                    float t = span <= 0 ? 1f : (by - floor) / (float) span;
                    float radius = mouth * (THROAT_FRACTION + (1f - THROAT_FRACTION) * t) + rim;
                    if (radius <= 0f || dist >= radius) continue;
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    if (WaterGuard.seals(waterGuard, idx, by, WATER_CLEARANCE)) continue;
                    mask.set(LocalBlockKey.pack(bx, by, bz));
                }
            }
        }
    }
}
