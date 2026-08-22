package com.stonebreak.world.generation.heightmap;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeSurfaceConfig;
import com.stonebreak.world.generation.biomes.BiomeSurfaceConfig.Entry;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The noise cave field: cheese chambers and spaghetti tunnels, carved out of would-be-solid
 * terrain, with density and shape driven by depth below the local surface.
 *
 * <h2>What changed and why</h2>
 *
 * <p>This class used to be a single 3D simplex channel thresholded per block, gated by a
 * per-biome intensity. That had two problems. Its intensity table set {@code DESERT},
 * {@code BEACH} and {@code ICE_FIELDS} to {@code 0.00}, so a third of the world's biomes had
 * no caves <em>at any depth</em> — a surface property deciding what exists 300 blocks down.
 * And a single thresholded noise field produces disconnected blobs: there is no reason for
 * one pocket to touch the next, so most carved volume was unreachable even where entrances
 * existed.
 *
 * <h2>The two shapes</h2>
 *
 * <p><b>Cheese</b> — a low-frequency channel thresholded directly ({@code n > t}). Big
 * rounded chambers with pillars between them. The threshold comes from a depth spline, so
 * chambers are rare and small near the surface and open up with depth.
 *
 * <p><b>Spaghetti</b> — the useful trick. The zero-isosurface of a 3D noise field is a
 * <em>sheet</em>; the intersection of two independent sheets is a <em>curve</em>. So testing
 * {@code |n1| < t && |n2| < t} carves genuine tubes that run for hundreds of blocks, with
 * long-range connectivity, from a stateless per-block test — no random walk, no cross-chunk
 * scan, no scan radius. This is what turns isolated pockets into a network.
 *
 * <h2>Galleries without a second sampling pass</h2>
 *
 * <p>The spaghetti thickness is widened by {@link CaveWaterTable#galleryWeight}, which peaks
 * at each (present or former) water table. Concentrating carving into a slab around those
 * levels <em>is</em> a horizontal gallery, so the epiphreatic look comes from modulating a
 * threshold rather than from re-sampling the noise at a different vertical squash. That
 * matters: per-zone squash would mean one noise fill per zone per channel — nine per chunk
 * instead of three.
 *
 * <p>Biome config still governs the {@link #OVERHANG_DEPTH} blocks at the very top of a
 * column, where carving is a surface-appearance decision (cliffs, hoodoos) and genuinely is
 * the biome's business — both how hard it carves and, via
 * {@link BiomeSurfaceConfig.Entry#cragSurface}, at what scale it carves (see
 * {@link #CRAG_SCALE}). Below that, depth decides. That band is additionally damped with
 * altitude ({@link #peakDamp}) — being an appearance decision is exactly why it cannot be
 * altitude-blind, since the same intensity that undercuts a cliff eats through a summit.
 *
 * <h2>Why the band is a union and not a branch</h2>
 *
 * <p>That top band used to <em>replace</em> the cave test rather than add to it, which made it
 * a lid: no chamber or tunnel could exist in the top 16 blocks even where one had climbed to
 * meet it. The band now carves <em>or</em> the cave test does. At these thresholds the cave
 * test almost never fires that shallow on its own — the cheese curve is still above 0.93 at
 * depth 16 and the spaghetti fade has barely opened — so this is a union that lets a cave
 * already there break through, not a second source of surface holes.
 *
 * <p>Backends: on the native (FastNoise2) backend the chunk pipeline calls
 * {@link #prepareChunk} once and queries the returned {@link Field} — a handful of SIMD volume
 * fills replace hundreds of thousands of per-block samples. Per-point {@link #isSolid} remains
 * the Java-backend path.
 */
public final class Density3D {
    /** Below this Y the world is always solid (protects bedrock floor). */
    private static final int CAVE_FLOOR = 8;
    /** Top N blocks of the column are governed by the biome's overhangIntensity. */
    private static final int OVERHANG_DEPTH = 16;

    /**
     * Altitude taper on the overhang band's intensity.
     *
     * <p>Every other band in this class is measured relative to the local surface, and that
     * is deliberate: a cave belongs at a depth, not at a Y, and the comments on
     * {@code PerlinWormCarver.ORIGIN_DEPTH_MIN} and {@code CavernCarver.CAVERN_DEPTH_MIN}
     * record that absolute-Y bands were a previous bug. This one is the exception, because
     * it is not a depth statement — it is a <em>silhouette</em> statement. Carried up a
     * mountain unchanged, an intensity that reads as a cliff undercut at y=100 reads as a
     * hole punched through the summit at y=145, because up there the band <em>is</em> the
     * silhouette: there is nothing above it for a hole to hide under.
     *
     * <p>Which is why {@code STONY_PEAKS} looked shredded. It carries the highest
     * {@code overhangIntensity} in the table (0.35, so solid only where
     * {@code cheese < 0.30}) <em>and</em> it is the biome that sits on summits, so the
     * hardest surface carving in the generator was being applied exactly where it is least
     * hidden.
     *
     * <p>The knots are placed against the measured height distribution of {@code STONY_PEAKS}
     * columns (p10 ≈ 95, p50 ≈ 122, p90 ≈ 144, max ≈ 177): full strength up to the bottom of
     * that spread, floored from its p90 up. Below {@link #PEAK_DAMP_START} nothing changes at
     * all, which is what keeps low stony outcrops and {@code BADLANDS} mesas (p90 ≈ 82,
     * entirely under the knee) carving exactly as they did.
     */
    private static final int PEAK_DAMP_START = 100;
    private static final int PEAK_DAMP_END = 145;
    /** Multiplier on the biome intensity at and above {@link #PEAK_DAMP_END}. */
    private static final float PEAK_DAMP_FLOOR = 0.55f;

    /**
     * Crag: the overhang band's channel for biomes that opt in via
     * {@link BiomeSurfaceConfig.Entry#cragSurface}.
     *
     * <p>These are the density parameters the single-channel field used before the cheese and
     * spaghetti split, and they are kept verbatim because that wavelength is what makes a hard
     * surface intensity read as broken cliff faces. Reusing the cheese channel here was the
     * quiet regression: same threshold, same fraction of blocks carved, but at nearly four
     * times the wavelength the carve arrives as one crater instead of many crags.
     *
     * <p>Only the top {@link #OVERHANG_DEPTH} blocks of an opted-in column read this. Depth
     * still owns everything below the band, for every biome.
     */
    private static final float CRAG_SCALE = 1f / 26f;
    private static final float CRAG_Y_SQUASH = 1.8f;

    /** Cheese: low frequency, flattened, so chambers are wider than tall. */
    private static final float CHEESE_SCALE = 1f / 96f;
    private static final float CHEESE_Y_SQUASH = 1.6f;

    /**
     * Spaghetti: mid frequency. Two channels, sampled identically but independently seeded.
     *
     * <p>The wavelength is the lever that trades tunnel <em>count</em> for tunnel
     * <em>size</em>, and it is the one that matters when caves are too cramped. A tube is the
     * band where {@code |n| < t}, so its width is {@code t / |grad n|} — stretching the field
     * out flattens the gradient and the same threshold cuts a wider tube, while the sheets
     * intersect in fewer places so there are fewer of them. Raising {@link #SPAG_THICKNESS}
     * alone does the opposite of what is wanted here: it widens what exists but also promotes
     * every near-miss between the two sheets into another thin tube, so the average passage
     * barely grows.
     */
    private static final float SPAG_SCALE = 1f / 68f;
    /**
     * Vertical squash of the spaghetti sample. Above 1 the field is compressed in Y, so tubes
     * come out wider than tall — at 1.25 a tunnel you could walk across was one you had to
     * crouch through. Kept slightly above 1 so passages still read as flattened rather than
     * as round bores, which is what makes them look water-cut.
     */
    private static final float SPAG_Y_SQUASH = 1.08f;
    /**
     * Half-thickness of a spaghetti tube in noise units.
     *
     * <p>Sets how much of the world is tunnel, near enough independently of how big each one
     * is: widening the band both fattens existing tubes and creates new ones, and those two
     * effects cancel in the volume-per-tube. Treat this as the <em>quantity</em> knob and
     * {@link #SPAG_SCALE} as the <em>size</em> knob.
     */
    private static final float SPAG_THICKNESS = 0.085f;
    /** Extra thickness at a gallery level — this is what makes galleries the roomy storeys. */
    private static final float SPAG_GALLERY_BONUS = 0.070f;
    /** Tunnels fade in over this depth range so they do not shred the surface. */
    private static final int SPAG_FADE_START = 10;
    private static final int SPAG_FADE_END = 34;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final NoiseGenerator cheeseJava;
    private final NoiseGenerator spag1Java;
    private final NoiseGenerator spag2Java;
    private final NoiseGenerator cragJava;
    private final long cheeseNode;
    private final long spag1Node;
    private final long spag2Node;
    private final long cragNode;
    private final int cheeseSeed;
    private final int spag1Seed;
    private final int spag2Seed;
    private final int cragSeed;
    private final CaveWaterTable waterTable;

    /**
     * Cheese carve threshold as a function of depth below the local surface. Above the first
     * knot nothing carves, which is what keeps chambers from opening onto the sky.
     */
    private final SplineInterpolator cheeseThreshold;

    public Density3D(long seed, HeightMapGenerator heightMapGenerator) {
        this.cheeseJava = new NoiseGenerator(seed + 17, 2, 0.5, 2.0);
        this.spag1Java = new NoiseGenerator(seed + 331, 2, 0.5, 2.0);
        this.spag2Java = new NoiseGenerator(seed + 733, 2, 0.5, 2.0);
        // Its own seed, deliberately not cheese's: sharing +17 would correlate every surface
        // crag with the chamber underneath it.
        this.cragJava = new NoiseGenerator(seed + 1187, 2, 0.5, 2.0);
        this.cheeseNode = TerrainNoise.native3DNode(2, 0.5, 2.0, CHEESE_SCALE);
        this.spag1Node = TerrainNoise.native3DNode(2, 0.5, 2.0, SPAG_SCALE);
        this.spag2Node = TerrainNoise.native3DNode(2, 0.5, 2.0, SPAG_SCALE);
        this.cragNode = TerrainNoise.native3DNode(2, 0.5, 2.0, CRAG_SCALE);
        this.cheeseSeed = TerrainNoise.nativeSeed(seed + 17);
        this.spag1Seed = TerrainNoise.nativeSeed(seed + 331);
        this.spag2Seed = TerrainNoise.nativeSeed(seed + 733);
        this.cragSeed = TerrainNoise.nativeSeed(seed + 1187);
        TerrainNoise.destroyOnCollect(this, cheeseNode);
        TerrainNoise.destroyOnCollect(this, spag1Node);
        TerrainNoise.destroyOnCollect(this, spag2Node);
        TerrainNoise.destroyOnCollect(this, cragNode);
        this.waterTable = new CaveWaterTable(seed, heightMapGenerator);

        this.cheeseThreshold = new SplineInterpolator();
        // Lowering a knot widens the chambers at that depth. The 0 and 18 knots are left
        // alone: they are what keeps chambers from opening onto the sky, and the extra
        // volume wanted here is wanted underground, not as holes in the landscape.
        for (double[] knot : CHEESE_KNOTS) {
            this.cheeseThreshold.addPoint(knot[0], knot[1]);
        }
    }

    /**
     * One cave-noise node's parameters for the fused native generator — must mirror the
     * constructor exactly. There are three now (cheese + two spaghetti) where the
     * single-channel field had one, plus the crag channel the overhang band reads.
     */
    public record NodeParams(int seed, int octaves, float gain, float lacunarity, float frequency) {}

    /** The exact node parameters this class builds for {@code worldSeed}, in fill order. */
    public static NodeParams[] nodeParams(long worldSeed) {
        return new NodeParams[] {
            new NodeParams(TerrainNoise.nativeSeed(worldSeed + 17), 2, 0.5f, 2.0f, CHEESE_SCALE),
            new NodeParams(TerrainNoise.nativeSeed(worldSeed + 331), 2, 0.5f, 2.0f, SPAG_SCALE),
            new NodeParams(TerrainNoise.nativeSeed(worldSeed + 733), 2, 0.5f, 2.0f, SPAG_SCALE),
            new NodeParams(TerrainNoise.nativeSeed(worldSeed + 1187), 2, 0.5f, 2.0f, CRAG_SCALE),
        };
    }

    /** Y-squash per node, in {@link #nodeParams} order. */
    public static float[] nodeYSquash() {
        return new float[] {CHEESE_Y_SQUASH, SPAG_Y_SQUASH, SPAG_Y_SQUASH, CRAG_Y_SQUASH};
    }

    /**
     * The depth-to-threshold curve, declared here rather than read off the instance so the
     * fused native generator evaluates the identical spline: the kernel context is built
     * before any Density3D exists, and a single source for the knots is what stops the two
     * implementations drifting.
     */
    private static final double[][] CHEESE_KNOTS = {
        {0, 2.0}, {18, 0.90}, {45, 0.68}, {90, 0.55}, {160, 0.47}, {250, 0.44}};

    /** Threshold-spline X coordinates. */
    public static double[] thresholdSplineXs() {
        return knotColumn(0);
    }

    /** Threshold-spline Y coordinates. */
    public static double[] thresholdSplineYs() {
        return knotColumn(1);
    }

    /** Point count of the threshold spline, as a one-element array for the kernel ABI. */
    public static int[] thresholdSplineSizes() {
        return new int[] {CHEESE_KNOTS.length};
    }

    private static double[] knotColumn(int column) {
        double[] out = new double[CHEESE_KNOTS.length];
        int i = 0;
        for (double[] knot : CHEESE_KNOTS) {
            out[i++] = knot[column];
        }
        return out;
    }

    /**
     * @param surfaceHeight final terrain height for this column (post-erosion)
     * @return true if the block should remain solid; false to carve to air
     */
    public boolean isSolid(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        if (y < CAVE_FLOOR || y >= surfaceHeight) {
            return true;
        }
        float cheese = cheeseJava.noise3D(
            worldX * CHEESE_SCALE, y * CHEESE_Y_SQUASH * CHEESE_SCALE, worldZ * CHEESE_SCALE);
        if (y >= surfaceHeight - OVERHANG_DEPTH) {
            Entry cfg = BiomeSurfaceConfig.get(biome);
            float band = cfg.cragSurface
                ? cragJava.noise3D(
                    worldX * CRAG_SCALE, y * CRAG_Y_SQUASH * CRAG_SCALE, worldZ * CRAG_SCALE)
                : cheese;
            if (!solidInOverhangBand(band, cfg, surfaceHeight)) {
                return false;
            }
        }
        float s1 = spag1Java.noise3D(
            worldX * SPAG_SCALE, y * SPAG_Y_SQUASH * SPAG_SCALE, worldZ * SPAG_SCALE);
        float s2 = spag2Java.noise3D(
            worldX * SPAG_SCALE, y * SPAG_Y_SQUASH * SPAG_SCALE, worldZ * SPAG_SCALE);
        int table = waterTable.tableAt(worldX, worldZ);
        return solidAt(cheese, s1, s2, y, surfaceHeight, table);
    }

    /**
     * Batch-fills the chunk's cave-noise volumes in three native calls. Returns null on the
     * Java backend (or when no column reaches above the cave floor) — callers then use
     * per-point {@link #isSolid}.
     *
     * <p>The crag volume is filled only when some column in the chunk belongs to a biome that
     * opts into it. Most chunks contain no such column, so the fourth fill costs nothing in the
     * common case — and where it is skipped, no column can ask for it either.
     *
     * @param heights     the chunk's 16x16 final-height grid, indexed [x*16+z]
     * @param waterLevels co-located water levels, for pinning the table to real water
     * @param biomes      co-located biomes, to decide whether the crag channel is needed
     */
    public Field prepareChunk(int chunkX, int chunkZ, int[] heights, int[] waterLevels,
                              BiomeType[] biomes) {
        if (cheeseNode == 0L || spag1Node == 0L || spag2Node == 0L || cragNode == 0L) {
            return null;
        }
        int maxSurface = 0;
        for (int h : heights) {
            maxSurface = Math.max(maxSurface, h);
        }
        if (maxSurface <= CAVE_FLOOR) {
            return null;
        }
        int yCount = maxSurface - CAVE_FLOOR;
        float[] cheese = fill(cheeseNode, cheeseSeed, CHEESE_Y_SQUASH, chunkX, chunkZ, yCount);
        float[] spag1 = fill(spag1Node, spag1Seed, SPAG_Y_SQUASH, chunkX, chunkZ, yCount);
        float[] spag2 = fill(spag2Node, spag2Seed, SPAG_Y_SQUASH, chunkX, chunkZ, yCount);
        float[] crag = needsCrag(biomes)
            ? fill(cragNode, cragSeed, CRAG_Y_SQUASH, chunkX, chunkZ, yCount)
            : null;
        int[] table = waterTable.tableForChunk(chunkX, chunkZ, heights, waterLevels);
        return new Field(this, cheese, spag1, spag2, crag, table, yCount);
    }

    /** True when any column in the chunk reads the crag channel. */
    private static boolean needsCrag(BiomeType[] biomes) {
        if (biomes == null) {
            return true; // caller withheld the profile — fill rather than risk a missing volume
        }
        for (BiomeType b : biomes) {
            if (b != null && BiomeSurfaceConfig.get(b).cragSurface) {
                return true;
            }
        }
        return false;
    }

    private float[] fill(long node, int seed, float ySquash, int chunkX, int chunkZ, int yCount) {
        float[] volume = new float[yCount * CHUNK_SIZE * CHUNK_SIZE];
        // FastNoise2 axis mapping (X innermost): fnX = worldZ, fnY = worldX, fnZ = squashed
        // Y — output lands as [(y-CAVE_FLOOR)*256 + x*16 + z] with no reshuffle. Frequency
        // is inside the node.
        boolean ok = CendaKernels.fillGrid3D(node, volume,
            (float) (chunkZ * CHUNK_SIZE), (float) (chunkX * CHUNK_SIZE), CAVE_FLOOR * ySquash,
            CHUNK_SIZE, CHUNK_SIZE, yCount,
            1f, 1f, ySquash,
            seed);
        if (!ok) {
            throw new IllegalStateException("Cenda 3D density fill failed");
        }
        return volume;
    }

    /**
     * The carve decision, shared by both backends so they cannot drift apart.
     *
     * @param table this column's cave water table (see {@link CaveWaterTable})
     */
    private boolean solidAt(float cheese, float s1, float s2, int y, int surfaceHeight, int table) {
        int depth = surfaceHeight - y;
        if (cheese > cheeseThreshold.interpolate(depth)) {
            return false;
        }
        // Two noise sheets intersect in a curve: this is the tube test.
        float thickness = (SPAG_THICKNESS + SPAG_GALLERY_BONUS * CaveWaterTable.galleryWeight(table, y))
            * spaghettiFade(depth);
        if (thickness > 0f && Math.abs(s1) < thickness && Math.abs(s2) < thickness) {
            return false;
        }
        return true;
    }

    /** Tunnels ramp in with depth so they do not open the surface into a lattice of holes. */
    private static float spaghettiFade(int depth) {
        if (depth <= SPAG_FADE_START) {
            return 0f;
        }
        if (depth >= SPAG_FADE_END) {
            return 1f;
        }
        return (depth - SPAG_FADE_START) / (float) (SPAG_FADE_END - SPAG_FADE_START);
    }

    /**
     * The top of a column, where carving decides how the surface reads (cliffs, hoodoos,
     * overhangs) and so is legitimately the biome's business — unlike cave depth, which is
     * not. The biome sets how hard this band carves and, through
     * {@link BiomeSurfaceConfig.Entry#cragSurface}, at what scale; {@link #peakDamp} sets how
     * much of that survives the climb to a summit.
     *
     * @param n the already-selected channel sample for this biome — crag if the biome opts in,
     *          cheese otherwise. Selected by the caller so each backend samples its own way.
     */
    private static boolean solidInOverhangBand(float n, Entry cfg, int surfaceHeight) {
        float intensity = cfg.overhangIntensity * peakDamp(surfaceHeight);
        if (intensity <= 0f) {
            return true;
        }
        return n < (1f - 2f * intensity);
    }

    /**
     * How much of the biome's surface-carve intensity survives at this column's altitude.
     * See {@link #PEAK_DAMP_START} for why this band, alone in this class, is keyed on
     * absolute Y.
     *
     * <p>The native port in {@code generator.cpp} mirrors this expression operand for
     * operand. The kernels build with {@code -ffp-contract=off} precisely so that an
     * identically-ordered float expression stays bit-identical across the two backends;
     * reassociating it here would break {@code FusedChunkGenParityTest}.
     */
    private static float peakDamp(int surfaceHeight) {
        if (surfaceHeight <= PEAK_DAMP_START) {
            return 1f;
        }
        if (surfaceHeight >= PEAK_DAMP_END) {
            return PEAK_DAMP_FLOOR;
        }
        float t = (surfaceHeight - PEAK_DAMP_START) / (float) (PEAK_DAMP_END - PEAK_DAMP_START);
        return 1f - (1f - PEAK_DAMP_FLOOR) * t;
    }

    /** Per-chunk cave-noise volumes produced by {@link #prepareChunk}. */
    public static final class Field {
        private final Density3D owner;
        private final float[] cheese;
        private final float[] spag1;
        private final float[] spag2;
        /** Null when no column in this chunk opts into the crag channel. */
        private final float[] crag;
        private final int[] table;
        private final int yCount;

        private Field(Density3D owner, float[] cheese, float[] spag1, float[] spag2,
                      float[] crag, int[] table, int yCount) {
            this.owner = owner;
            this.cheese = cheese;
            this.spag1 = spag1;
            this.spag2 = spag2;
            this.crag = crag;
            this.table = table;
            this.yCount = yCount;
        }

        /** Same contract as {@link Density3D#isSolid}, with chunk-local x/z. */
        public boolean isSolid(int localX, int y, int localZ, int surfaceHeight, BiomeType biome) {
            if (y < CAVE_FLOOR || y >= surfaceHeight) {
                return true;
            }
            int yIndex = y - CAVE_FLOOR;
            if (yIndex >= yCount) {
                return true;
            }
            int i = (yIndex * CHUNK_SIZE + localX) * CHUNK_SIZE + localZ;
            if (y >= surfaceHeight - OVERHANG_DEPTH) {
                Entry cfg = BiomeSurfaceConfig.get(biome);
                // crag is non-null whenever any column opts in, and only an opted-in column
                // can reach this branch with cragSurface set.
                float band = (cfg.cragSurface && crag != null) ? crag[i] : cheese[i];
                if (!solidInOverhangBand(band, cfg, surfaceHeight)) {
                    return false;
                }
            }
            int column = localX * CHUNK_SIZE + localZ;
            return owner.solidAt(cheese[i], spag1[i], spag2[i], y, surfaceHeight, table[column]);
        }
    }
}
