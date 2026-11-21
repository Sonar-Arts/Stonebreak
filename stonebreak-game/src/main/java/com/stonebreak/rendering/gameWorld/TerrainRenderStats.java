package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.voxel.mms.mmsRegion.MmsGpuCuller;
import com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * The terrain renderer's F3 rows (cubic runtime): published once per frame by {@link WorldRenderer} on the
 * GL thread, read by the debug overlay ({@link #rows()} = label/value pairs, ready to print). Replaces the
 * column-era "Chunk Draws" accounting.
 */
public final class TerrainRenderStats {

    private static volatile Snapshot last = new Snapshot(0, 0, 0, 0, 0L, 0, 0L, 0, false, false);

    /** One frame's numbers. */
    public record Snapshot(int residentCubes, int visibleCubes, int walkFrame, int missingReported,
                           long missingTotal, int proxiesDrawn, long walkNanos, int readyColumnsHidden,
                           boolean handover, boolean walkActive) {
    }

    private TerrainRenderStats() {
    }

    static void publish(Snapshot snapshot) {
        last = snapshot;
    }

    public static Snapshot last() {
        return last;
    }

    /** F3 rows: {label, value}. Empty when the cube renderer never ran. */
    public static List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        ChunkRegionRenderer regions = ChunkRegionRenderer.peek();
        if (regions == null) {
            return rows;
        }
        Snapshot s = last;
        rows.add(new String[] {"Cube Meshes", String.format("%d resident, %d visible, %d/%d/%d/%d regions",
            s.residentCubes(), s.visibleCubes(),
            regions.regionCount(ChunkRegionRenderer.LAYER_ATLAS),
            regions.regionCount(ChunkRegionRenderer.LAYER_STAMP),
            regions.regionCount(ChunkRegionRenderer.LAYER_TRANSLUCENT),
            regions.regionCount(ChunkRegionRenderer.LAYER_WATER))});
        rows.add(new String[] {"Cube Draws", String.format("%d cmds / %d region draws (CPU)",
            regions.publishedCommands(), regions.publishedRegionDraws())});
        if (ChunkRegionRenderer.isGpuCullEnabled()) {
            rows.add(new String[] {"GPU Cull", String.format("%d cmds / %d regions / %d pre-culled",
                regions.publishedGpuCommands(), regions.publishedGpuRegionDraws(),
                regions.publishedGpuPreCulledRegions())});
        }
        rows.add(new String[] {"Visibility", s.walkActive()
            ? String.format("walk %.2f ms, %d missing (%d reported), %d proxies", s.walkNanos() / 1e6,
                s.missingTotal(), s.missingReported(), s.proxiesDrawn())
            : "frustum only (no cv_visible_cubes walk)"});
        rows.add(new String[] {"Hi-Z", hizRow(ChunkRegionRenderer.isGpuCullEnabled(), regions.isHiZEnabled(),
            regions.hizStats(), regions.publishedHiZEarlyCommands(), regions.publishedHiZLateCommands(),
            regions.hizSkippedMultisample())});
        rows.add(new String[] {"Handover", s.handover()
            ? String.format("on, %d cubes hidden (column not READY)", s.readyColumnsHidden())
            : "off (every resident cube draws)"});
        FastLodRegionBatcher lod = FastLodRegionBatcher.active();
        if (lod != null) {
            rows.add(new String[] {"LOD Draws", String.format("%d cmds / %d region draws",
                lod.publishedCommands(), lod.publishedRegionDraws())});
        }
        rows.addAll(com.stonebreak.rendering.gameWorld.fastlod.OctreeLodRenderer.rows());
        rows.add(new String[] {"Cube Uploads", String.format("%d uploads, %d drops, %d failed",
            regions.uploadsTotal(), regions.dropsTotal(), regions.uploadFailures())});
        return rows;
    }

    /**
     * The F3 Hi-Z value. {@code stats} = the culler's GPU counters (a few frames old): {@code tested} enabled
     * in-frustum meshes re-tested in phase 2, {@code occluded} of them hidden by the pyramid; {@code early} /
     * {@code late} = commands each phase dispatched last frame (CPU side).
     */
    static String hizRow(boolean gpuCull, boolean enabled, MmsGpuCuller.HiZStats stats, int early, int late,
                         boolean skippedMultisample) {
        if (!gpuCull) {
            return "off (CPU multidraw path, needs GL 4.3)";
        }
        if (!enabled) {
            return "off (-Dstonebreak.hiz=off or -Dopenmason.hiz=off)";
        }
        if (skippedMultisample) {
            return "on, no pyramid (multisampled target)";
        }
        if (stats == null || stats.frame() < 0) {
            return String.format("on, %d/%d cmds early/late, no GPU counters yet", early, late);
        }
        // Short: the F3 value column is narrow.
        return String.format("%d/%d occluded, %d+%d drawn", stats.occluded(), stats.tested(),
            stats.earlyDrawn(), stats.lateDrawn());
    }
}
