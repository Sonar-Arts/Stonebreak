package com.stonebreak.world.bench;

import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.world.fastlod.FastLodLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.stonebreak.world.bench.FastLodRingLab.fmt;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual FastLOD ring measurements — gated on {@code -Dstonebreak.bench=true}:
 * <pre>
 * mvn -q test -pl stonebreak-game -am -Dtest=FastLodRingLabTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dstonebreak.bench=true [-Dlodlab.samples=300] [-Dlodlab.full=true]
 * </pre>
 */
class FastLodRingLabTest {

    @AfterEach
    void restore() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
    }

    @Test
    void lab() {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "manual benchmark (-Dstonebreak.bench=true)");
        int samples = Integer.getInteger("lodlab.samples", 300);
        FastLodRingLab lab = new FastLodRingLab(Long.getLong("lodlab.seed", 12345L));
        if (Boolean.getBoolean("lodlab.onlyC")) {
            phaseC();
            return;
        }

        // ── Phase A: per-level node cost ──
        Map<FastLodLevel, List<FastLodRingLab.NodeStat>> per = lab.sampleLevels(samples, 400, 7L);
        double[] meanBytes = new double[FastLodLevel.count()];
        double[] meanMs = new double[FastLodLevel.count()];
        double[] meanQuads = new double[FastLodLevel.count()];
        out("[lod-lab] phase A: %d random nodes per level, seed terrain", samples);
        out("[lod-lab] %-3s %7s %7s %7s %7s %7s %7s %9s %9s %8s %8s", "lvl", "quads", "tops", "sides", "found",
            "canopy", "waterQ", "terrB", "waterB", "sampleMs", "meshMs");
        for (FastLodLevel l : FastLodLevel.values()) {
            List<FastLodRingLab.NodeStat> s = per.get(l);
            double q = s.stream().mapToInt(FastLodRingLab.NodeStat::quads).average().orElse(0);
            double tb = s.stream().mapToLong(FastLodRingLab.NodeStat::terrainBytes).average().orElse(0);
            double wb = s.stream().mapToLong(FastLodRingLab.NodeStat::waterBytes).average().orElse(0);
            double sm = s.stream().mapToDouble(FastLodRingLab.NodeStat::sampleMs).average().orElse(0);
            double mm = s.stream().mapToDouble(FastLodRingLab.NodeStat::meshMs).average().orElse(0);
            meanBytes[l.index()] = tb + wb;
            meanMs[l.index()] = sm + mm;
            meanQuads[l.index()] = q + wb / 16;
            out("[lod-lab] L%-2d %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %9.0f %9.0f %8.3f %8.3f", l.index(), q,
                s.stream().mapToInt(FastLodRingLab.NodeStat::tops).average().orElse(0),
                s.stream().mapToInt(FastLodRingLab.NodeStat::skirts).average().orElse(0),
                s.stream().mapToInt(FastLodRingLab.NodeStat::foundations).average().orElse(0),
                s.stream().mapToInt(FastLodRingLab.NodeStat::trees).average().orElse(0),
                wb / 16, tb, wb, sm, mm);
        }

        // Uncarved node cost per level (what non-ULTRA presets pay at L3/L4).
        Map<FastLodLevel, List<FastLodRingLab.NodeStat>> raw = lab.sampleLevels(samples, 400, 11L, false);
        double[] rawMs = new double[FastLodLevel.count()];
        for (FastLodLevel l : FastLodLevel.values()) {
            rawMs[l.index()] = raw.get(l).stream().mapToDouble(x -> x.sampleMs() + x.meshMs()).average().orElse(0);
        }
        StringBuilder rawLine = new StringBuilder();
        for (FastLodLevel l : FastLodLevel.values()) rawLine.append(fmt(" L%d=%.3f", l.index(), rawMs[l.index()]));
        out("[lod-lab] uncarved ms/node:%s", rawLine);

        // ── Phase B: rings ──
        int[][] rings = {{8, 24}, {8, 48}, {8, 64}, {16, 64}, {24, 64}, {8, 96}, {8, 128}};
        FastLodRingLab.Policy[] policies = {FastLodRingLab.LEGACY_EQUAL,
            FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.LOW),
            FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.MEDIUM),
            FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.HIGH),
            FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.ULTRA)};
        out("[lod-lab] phase B: rings (render R, LOD range L) -> outer edge, nodes per level, est. VRAM, cold-gen CPU (random cold nodes: upper bound; see phase C for a real fill), worst cell px @%dp/%d°",
            (int) FastLodRingLab.SCREEN_H, (int) FastLodRingLab.FOV_Y_DEG);
        out("[lod-lab] %-14s %3s %3s %6s %6s %-34s %8s %8s %8s %7s", "policy", "R", "L", "outerB", "nodes",
            "L0/L1/L2/L3/L4", "kQuads", "VRAM MiB", "genCPU s", "worstPx");
        for (FastLodRingLab.Policy p : policies) {
            for (int[] r : rings) {
                int[] c = FastLodRingLab.counts(p, r[0], r[1]);
                long nodes = 0;
                double bytes = 0, ms = 0, quads = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < c.length; i++) {
                    nodes += c[i];
                    bytes += c[i] * meanBytes[i];
                    ms += c[i] * (p.carves(FastLodLevel.byIndex(i)) ? meanMs[i] : rawMs[i]);
                    quads += c[i] * meanQuads[i];
                    sb.append(i == 0 ? "" : "/").append(c[i]);
                }
                out("[lod-lab] %-14s %3d %3d %6d %6d %-34s %8.1f %8.2f %8.2f %7.1f", p.name(), r[0], r[1],
                    (r[0] + r[1]) * 16, nodes, sb, quads / 1000, bytes / 1048576, ms / 1000,
                    FastLodRingLab.worstCellPx(p, r[0], r[1]));
            }
        }

        for (FastLodLevel l : new FastLodLevel[]{FastLodLevel.L3, FastLodLevel.L4}) {
            out("[lod-lab] raw (uncarved) per-point grid probe %s: %.3f ms/node (carved path above: %.3f)",
                l, lab.rawGridMs(l, samples, 400, 99L), meanMs[l.index()]);
        }

        phaseC();
    }

    private static void phaseC() {
        // ── Phase C: whole rings built cold (fresh terrain system = empty carve-profile
        // cache, like a world load), uploaded through the arena sim under the shipped plan ──
        if (Boolean.parseBoolean(System.getProperty("lodlab.full", "true"))) {
            out("[lod-lab] phase C plan := %s", FastLodRingLab.installShippedPlan());
            Object[][] runs = {
                {FastLodRingLab.LEGACY_EQUAL, 8, 24}, {FastLodRingLab.LEGACY_EQUAL, 8, 48},
                {FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.MEDIUM), 8, 24},
                {FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.MEDIUM), 8, 64},
                {FastLodRingLab.shipped(com.stonebreak.world.fastlod.FastLodQuality.ULTRA), 8, 64},
            };
            for (Object[] r : runs) {
                FastLodRingLab.Policy p = (FastLodRingLab.Policy) r[0];
                FastLodRingLab fresh = new FastLodRingLab(Long.getLong("lodlab.seed", 12345L));
                out("[lod-lab] phase C %s R%d/L%d cold -> %s", p.name(), r[1], r[2],
                    fresh.fullRing(p, (Integer) r[1], (Integer) r[2]));
            }
            com.openmason.engine.vram.VramPlans.reset();
        }
    }

    private static void out(String f, Object... a) {
        System.out.println(fmt(f, a));
    }
}
