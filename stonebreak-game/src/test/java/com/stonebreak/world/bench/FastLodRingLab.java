package com.stonebreak.world.bench;

import com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsRegion.MmsArenaSim;
import com.openmason.engine.vram.VramPlans;
import com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.fastlod.FastLodBandPolicy;
import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodMesher;
import com.stonebreak.world.fastlod.FastLodQuality;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.generation.TerrainGenerationSystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FastLOD ring lab: what a LOD ring costs per level, and what a given
 * (render distance, LOD range, band policy) costs in nodes, quads, VRAM,
 * cold generation CPU and worst-case on-screen cell size.
 *
 * <p>Phase A samples random nodes per level from real terrain (pulled
 * LODQUAD16 meshes, quads split by role). Phase B counts every ring's nodes
 * per level exactly and extrapolates from Phase A. Phase C builds the default
 * ring in full through {@link MmsArenaSim} so the extrapolation and the arena
 * slack are checked against a real fill.
 */
final class FastLodRingLab {

    /** Vertical resolution and FOV used for the "worst cell, in pixels" metric. */
    static final double SCREEN_H = 1080;
    static final double FOV_Y_DEG = 70;

    /** Chooses a level for a node at Chebyshev distance {@code d} (chunks); null = no node. */
    interface Policy {
        String name();
        FastLodLevel levelFor(int d, int inner, int range);
        /** Whether nodes at this level are cave-carved (the legacy policy carved everything). */
        default boolean carves(FastLodLevel l) { return true; }
        /** Whether nodes at this level draw trees (the legacy policy: L0 only). */
        default boolean trees(FastLodLevel l) { return l == FastLodLevel.L0; }
    }

    /** The pre-2026-10-03 policy: the ring split into five equal-width bands. */
    static final Policy LEGACY_EQUAL = new Policy() {
        public String name() { return "legacy-equal"; }
        public FastLodLevel levelFor(int d, int inner, int range) {
            if (range <= 0) return null;
            int outer = inner + range;
            int preloadInner = Math.max(0, inner - FastLodBandPolicy.PRELOAD_RING);
            if (d <= preloadInner || d > outer) return null;
            if (d <= inner) return FastLodLevel.finest();
            int bands = FastLodLevel.count();
            int bandWidth = Math.max(1, (range + bands - 1) / bands);
            return FastLodLevel.byIndex(Math.min(bands - 1, (d - inner - 1) / bandWidth));
        }
    };

    /** The shipped distance-proportional policy at one quality preset. */
    static Policy shipped(FastLodQuality q) {
        return new Policy() {
            public String name() { return q.name(); }
            public FastLodLevel levelFor(int d, int inner, int range) {
                return FastLodBandPolicy.levelFor(d, inner, range, q);
            }
            public boolean carves(FastLodLevel l) { return q.carves(l); }
            public boolean trees(FastLodLevel l) { return q.drawsTrees(l); }
        };
    }

    record NodeStat(int tops, int skirts, int foundations, int trees, /* skirts = all lit sides */ long terrainBytes, long waterBytes,
                    int waterQuads, double sampleMs, double meshMs) {
        int quads() { return tops + skirts + foundations + trees; }
    }

    private final TerrainGenerationSystem terrain;
    private final FastLodSampler sampler;
    private final FastLodMesher mesher;

    FastLodRingLab(long seed) {
        MmsVertexFormat.override(MmsVertexFormat.QUAD16);
        terrain = new TerrainGenerationSystem(seed);
        sampler = new FastLodSampler(terrain);
        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(1);
        mesher = new FastLodMesher(textures);
    }

    NodeStat measure(FastLodKey key) {
        long t0 = System.nanoTime();
        FastLodChunkData data = sampler.sample(key);
        long t1 = System.nanoTime();
        FastLodMesher.Result r = mesher.build(data);
        long t2 = System.nanoTime();
        int tops = 0, skirts = 0, foundations = 0, trees = 0;
        long bytes = 0;
        MmsMeshData m = r.mesh();
        if (!m.isEmpty()) {
            byte[] raw = m.getPackedVertexData();
            bytes = raw.length;
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
            for (int q = 0; q < raw.length / MmsLodQuadCodec.QUAD_BYTES; q++) {
                int w0 = b.getInt(q * MmsLodQuadCodec.QUAD_BYTES);
                int w1 = b.getInt(q * MmsLodQuadCodec.QUAD_BYTES + 4);
                int face = MmsLodQuadCodec.face(w0);
                if (MmsLodQuadCodec.alpha(w1)) {
                    trees++; // L0 canopy (trunks count as sides)
                } else if (face == 0 && MmsLodQuadCodec.smooth(w0)) {
                    tops++;
                } else if (!MmsLodQuadCodec.lit(w0)) {
                    foundations++;
                } else {
                    skirts++;
                }
            }
        }
        long wBytes = r.waterMesh() == null ? 0 : r.waterMesh().getPackedVertexData().length;
        return new NodeStat(tops, skirts, foundations, trees, bytes, wBytes, (int) (wBytes / 16),
            (t1 - t0) / 1e6, (t2 - t1) / 1e6);
    }

    /**
     * Cost of the same node grid probed with the RAW (uncarved) per-point
     * height — an upper bound for a carve-free coarse sampler.
     */
    double rawGridMs(FastLodLevel level, int n, int span, long rngSeed) {
        Random rng = new Random(rngSeed);
        int stride = level.stride();
        int cs = level.cellSize();
        long sink = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int bx = (rng.nextInt(2 * span) - span) * 16, bz = (rng.nextInt(2 * span) - span) * 16;
            for (int x = 0; x < stride; x++) {
                for (int z = 0; z < stride; z++) {
                    sink += terrain.getFinalTerrainHeightAt(bx + (x - 1) * cs, bz + (z - 1) * cs);
                    sink += terrain.getBiomeAt(bx + (x - 1) * cs, bz + (z - 1) * cs).ordinal();
                }
            }
        }
        if (sink == 42) System.out.print("");
        return (System.nanoTime() - t0) / 1e6 / n;
    }

    /** Installs the shipped CEARL plan's arena policies (vram context 0, like the chunk lab). */
    static String installShippedPlan() {
        try (java.io.InputStream in = FastLodRingLab.class.getResourceAsStream("/cearl/stonebreak.CEARL")) {
            if (in == null) return "builtin (plan not on classpath)";
            String src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var plan = com.openmason.engine.cearl.CearlCompiler.compile(src, "cearl/stonebreak.CEARL",
                Map.of("vram", 0L)).plan();
            if (plan != null) VramPlans.install(plan);
            return "cearl/stonebreak.CEARL";
        } catch (Exception e) {
            return "builtin (" + e.getMessage() + ")";
        }
    }

    /** Phase A: per-level means over {@code n} random nodes within ±{@code span} chunks. */
    Map<FastLodLevel, List<NodeStat>> sampleLevels(int n, int span, long rngSeed) {
        return sampleLevels(n, span, rngSeed, true);
    }

    Map<FastLodLevel, List<NodeStat>> sampleLevels(int n, int span, long rngSeed, boolean carved) {
        Map<FastLodLevel, List<NodeStat>> out = new HashMap<>();
        for (FastLodLevel level : FastLodLevel.values()) {
            Random rng = new Random(rngSeed + level.index());
            // warm-up (JIT, noise tables)
            for (int i = 0; i < 20; i++) {
                measure(FastLodKey.of(level, rng.nextInt(2 * span) - span, rng.nextInt(2 * span) - span, carved));
            }
            List<NodeStat> stats = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                stats.add(measure(FastLodKey.of(level, rng.nextInt(2 * span) - span, rng.nextInt(2 * span) - span, carved)));
            }
            out.put(level, stats);
        }
        return out;
    }

    /** Exact node count per level for one ring. */
    static int[] counts(Policy p, int inner, int range) {
        int[] c = new int[FastLodLevel.count()];
        int outer = inner + range;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                FastLodLevel l = p.levelFor(Math.max(Math.abs(dx), Math.abs(dz)), inner, range);
                if (l != null) c[l.index()]++;
            }
        }
        return c;
    }

    /**
     * Worst on-screen size (pixels, vertical) of one cell anywhere in the
     * visible LOD ring (beyond the native disk): cellSize / distance projected
     * at {@link #SCREEN_H} × {@link #FOV_Y_DEG}. Measured at the node's near
     * edge — the closest that cell size is ever seen.
     */
    static double worstCellPx(Policy p, int inner, int range) {
        double k = (SCREEN_H / 2) / Math.tan(Math.toRadians(FOV_Y_DEG / 2));
        double worst = 0;
        for (int d = inner + 1; d <= inner + range; d++) {
            FastLodLevel l = p.levelFor(d, inner, range);
            if (l == null) continue;
            double nearBlocks = (d - 1) * 16.0 + 8.0; // player mid-chunk
            worst = Math.max(worst, l.cellSize() / nearBlocks * k);
        }
        return worst;
    }

    /** Phase C: build every node of one ring, upload through the arena sim per LOD region. */
    Map<String, Object> fullRing(Policy p, int inner, int range) {
        Map<Long, MmsArenaSim> terrainRegions = new HashMap<>();
        Map<Long, MmsArenaSim> waterRegions = new HashMap<>();
        Map<Long, MmsArenaSim> terrainSparse = new HashMap<>();
        Map<Long, MmsArenaSim> waterSparse = new HashMap<>();
        int outer = inner + range;
        long used = 0, quads = 0, nodes = 0;
        double[] levelMs = new double[FastLodLevel.count()];
        int[] levelN = new int[FastLodLevel.count()];
        long t0 = System.nanoTime();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                FastLodLevel l = p.levelFor(Math.max(Math.abs(dx), Math.abs(dz)), inner, range);
                if (l == null) continue;
                NodeStat s = measure(FastLodKey.of(l, dx, dz, p.carves(l), p.trees(l)));
                nodes++;
                levelMs[l.index()] += s.sampleMs() + s.meshMs();
                levelN[l.index()]++;
                long region = (((long) (dx >> FastLodRegionBatcher.LOD_REGION_SHIFT)) << 32)
                    | ((dz >> FastLodRegionBatcher.LOD_REGION_SHIFT) & 0xFFFFFFFFL);
                if (s.terrainBytes() > 0) {
                    terrainRegions.computeIfAbsent(region, k -> new MmsArenaSim(
                        VramPlans.arena(VramPlans.POOL_LOD_TERRAIN), 4, 0, false, 0))
                        .upload((int) (s.terrainBytes() / 4), (int) (s.terrainBytes() / 16 * 6));
                    terrainSparse.computeIfAbsent(region, k -> new MmsArenaSim(
                        VramPlans.arena(VramPlans.POOL_LOD_TERRAIN), 4, 0, true, 65536))
                        .upload((int) (s.terrainBytes() / 4), (int) (s.terrainBytes() / 16 * 6));
                }
                if (s.waterBytes() > 0) {
                    waterRegions.computeIfAbsent(region, k -> new MmsArenaSim(
                        VramPlans.arena(VramPlans.POOL_LOD_WATER), 4, 0, false, 0))
                        .upload((int) (s.waterBytes() / 4), (int) (s.waterBytes() / 16 * 6));
                    waterSparse.computeIfAbsent(region, k -> new MmsArenaSim(
                        VramPlans.arena(VramPlans.POOL_LOD_WATER), 4, 0, true, 65536))
                        .upload((int) (s.waterBytes() / 4), (int) (s.waterBytes() / 16 * 6));
                }
                used += s.terrainBytes() + s.waterBytes();
                quads += s.quads() + s.waterQuads();
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        long reserved = 0;
        int grows = 0;
        for (MmsArenaSim a : terrainRegions.values()) grows += a.report().growEvents();
        for (MmsArenaSim a : waterRegions.values()) grows += a.report().growEvents();
        List<Long> tUsed = new ArrayList<>(), wUsed = new ArrayList<>();
        for (MmsArenaSim a : terrainRegions.values()) {
            reserved += a.report().reservedBytes();
            tUsed.add(a.report().usedBytes());
        }
        for (MmsArenaSim a : waterRegions.values()) {
            reserved += a.report().reservedBytes();
            wUsed.add(a.report().usedBytes());
        }
        tUsed.sort(null);
        wUsed.sort(null);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("nodes", nodes);
        m.put("quads", quads);
        m.put("usedMiB", used / 1048576.0);
        long sparseCommitted = 0;
        for (MmsArenaSim a : terrainSparse.values()) sparseCommitted += a.report().reservedBytes();
        for (MmsArenaSim a : waterSparse.values()) sparseCommitted += a.report().reservedBytes();
        m.put("reservedMiB(copy)", reserved / 1048576.0);
        m.put("committedMiB(sparse)", sparseCommitted / 1048576.0);
        m.put("growEvents", grows);
        m.put("terrainRegionKiB p50/p90/max", pct(tUsed));
        m.put("waterRegionKiB p50/p90/max", pct(wUsed));
        m.put("terrainRegions", terrainRegions.size());
        m.put("waterRegions", waterRegions.size());
        m.put("buildSecondsSingleThread", secs);
        StringBuilder per = new StringBuilder();
        for (int i = 0; i < levelN.length; i++) {
            per.append(fmt("L%d=%.2f ", i, levelN[i] == 0 ? 0 : levelMs[i] / levelN[i]));
        }
        m.put("warmMsPerNode", per.toString().trim());
        return m;
    }

    private static String pct(List<Long> v) {
        if (v.isEmpty()) return "-";
        return fmt("%.0f/%.0f/%.0f", v.get(v.size() / 2) / 1024.0, v.get((int) (v.size() * 0.9)) / 1024.0,
            v.get(v.size() - 1) / 1024.0);
    }

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
