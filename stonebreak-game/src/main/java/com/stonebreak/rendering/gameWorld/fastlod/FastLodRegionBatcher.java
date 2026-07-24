package com.stonebreak.rendering.gameWorld.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsMultiDrawBatch;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Region batching for FastLOD nodes — the distant-terrain counterpart of
 * {@code ChunkRegionRenderer}. Each LOD node used to own a full VAO/VBO/EBO
 * and draw individually; at large LOD ranges the resident ring holds
 * 10–20k nodes and even after frustum culling the pass issued thousands of
 * VAO bind + draw pairs per frame (terrain, plus the water sheets twice via
 * the water prepass/color sub-passes) — the dominant frame cost of LOD.
 *
 * <p>Here node meshes (packed on the worker threads via
 * {@code MmsMeshData.toPacked()}) upload into shared per-region arenas —
 * 16×16 chunk columns per LOD region, coarser than the native renderer's 8×8
 * since LOD geometry is far lighter — and each frame the render pass buckets
 * the visible fully-faded nodes per region ({@link MmsChunkRegion}'s
 * cycle-stamp mechanism) so every touched region draws with ONE
 * {@code glMultiDrawElementsBaseVertex}. Nodes mid-crossfade (a handful at
 * ring edges) draw individually via {@link MmsRegionMeshHandle#render()} so
 * the per-node {@code u_lodFade} uniform still applies.
 *
 * <p>Terrain and water sheets live in separate region maps. Water buckets are
 * filled once by the LOD pass and drawn twice (depth prepass + color) by the
 * {@code WaterRenderer} under the same cycle stamp. Only active while region
 * rendering is enabled; the per-node legacy handles remain the fallback.
 *
 * <p>GL-thread confined. Owned by {@code WorldRenderer}; survives world swaps
 * (handles free per-node through the LOD manager's cleanup queue, and
 * {@link #beginCycle()} prunes emptied regions).
 */
public final class FastLodRegionBatcher {

    /** LOD regions span 16x16 chunk columns (shift 4). */
    private static final int LOD_REGION_SHIFT = 4;

    public static final int LAYER_TERRAIN = 0;
    public static final int LAYER_WATER = 1;

    /** The live batcher (owned by WorldRenderer) — debug-overlay stats access. */
    private static volatile FastLodRegionBatcher active;

    public static FastLodRegionBatcher active() {
        return active;
    }

    public FastLodRegionBatcher() {
        active = this;
    }

    private final Map<Long, MmsChunkRegion> terrainRegions = new HashMap<>();
    private final Map<Long, MmsChunkRegion> waterRegions = new HashMap<>();
    private final MmsMultiDrawBatch batch = new MmsMultiDrawBatch(512);
    private final List<MmsChunkRegion> touchedTerrain = new ArrayList<>();
    private final List<MmsChunkRegion> touchedWater = new ArrayList<>();
    private int cycleCounter;

    // Frame stats (published for the debug overlay).
    private int frameRegionDraws;
    private int frameCommands;
    private int publishedRegionDraws;
    private int publishedCommands;

    /**
     * Uploads one packed LOD mesh into its region's arenas. Returns null when
     * the mesh cannot join a region (not packed / u32 indices) — the caller
     * then falls back to a legacy per-node handle.
     */
    public MmsRegionMeshHandle upload(int layer, int chunkX, int chunkZ, MmsMeshData mesh,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        if (mesh == null || mesh.isEmpty() || !mesh.isPacked() || !mesh.hasShortIndices()) {
            return null;
        }
        long key = regionKey(chunkX >> LOD_REGION_SHIFT, chunkZ >> LOD_REGION_SHIFT);
        Map<Long, MmsChunkRegion> regions = layer == LAYER_WATER ? waterRegions : terrainRegions;
        // 256 columns per LOD region (vs the native renderer's 64): start the
        // arenas larger so the initial ring fill doesn't grow-and-compact
        // repeatedly. Water sheets are tiny (≤1 quad per cell).
        MmsChunkRegion region = regions.computeIfAbsent(key,
            k -> layer == LAYER_WATER
                ? new MmsChunkRegion(32 * 1024, 48 * 1024)
                : new MmsChunkRegion(128 * 1024, 192 * 1024));
        return region.upload(mesh.getPackedVertexData(), mesh.getPackedIndexData(),
            mesh.getVertexCount(), mesh.getIndexCount(),
            minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Starts this frame's bucketing cycle: publishes last frame's stats,
     * prunes emptied regions, and returns the stamp for {@link #add} /
     * {@link #drawTerrain} / {@link #drawWater}.
     */
    public int beginCycle() {
        publishedRegionDraws = frameRegionDraws;
        publishedCommands = frameCommands;
        frameRegionDraws = 0;
        frameCommands = 0;
        pruneEmpty(terrainRegions);
        pruneEmpty(waterRegions);
        touchedTerrain.clear();
        touchedWater.clear();
        return ++cycleCounter;
    }

    /** Buckets one visible fully-faded node mesh for this cycle's multidraws. */
    public void add(int layer, MmsRegionMeshHandle handle, int stamp) {
        MmsChunkRegion region = handle.region();
        if (handle.isClosed() || region.isDeleted()) {
            // Defensive: a logic-thread eviction can race this frame's
            // weakly-consistent entry iteration — skip, never bind.
            return;
        }
        if (!region.touchedInCycle(stamp)) {
            (layer == LAYER_WATER ? touchedWater : touchedTerrain).add(region);
        }
        region.cycleMembers(stamp).add(handle);
    }

    /** Draws this cycle's terrain buckets — one multidraw per touched region. */
    public void drawTerrain(int stamp) {
        drawTouched(touchedTerrain, stamp);
    }

    /**
     * Draws this cycle's water-sheet buckets. Called twice per frame by the
     * water pass (depth prepass + color) with the same stamp — buckets persist
     * for the whole cycle.
     */
    public void drawWater(int stamp) {
        drawTouched(touchedWater, stamp);
    }

    private void drawTouched(List<MmsChunkRegion> touched, int stamp) {
        if (touched.isEmpty()) {
            return;
        }
        for (int r = 0; r < touched.size(); r++) {
            MmsChunkRegion region = touched.get(r);
            List<MmsRegionMeshHandle> members = region.cycleMembers(stamp);
            batch.reset();
            for (int m = 0; m < members.size(); m++) {
                MmsRegionMeshHandle h = members.get(m);
                batch.add(h.getIndexCount(), h.indexOffsetBytes(), h.baseVertex());
            }
            region.bind();
            batch.draw(GL11.GL_UNSIGNED_SHORT);
            frameRegionDraws++;
            frameCommands += members.size();
        }
        GL30.glBindVertexArray(0);
    }

    public int publishedRegionDraws() {
        return publishedRegionDraws;
    }

    public int publishedCommands() {
        return publishedCommands;
    }

    /** Deletes every LOD region (renderer shutdown). GL thread. */
    public void cleanup() {
        deleteAll(terrainRegions);
        deleteAll(waterRegions);
        batch.close();
        if (active == this) {
            active = null;
        }
    }

    private static void deleteAll(Map<Long, MmsChunkRegion> regions) {
        for (MmsChunkRegion region : regions.values()) {
            region.delete();
        }
        regions.clear();
    }

    private static void pruneEmpty(Map<Long, MmsChunkRegion> regions) {
        if (regions.isEmpty()) {
            return;
        }
        Iterator<MmsChunkRegion> it = regions.values().iterator();
        while (it.hasNext()) {
            MmsChunkRegion region = it.next();
            if (region.isEmpty()) {
                region.delete();
                it.remove();
            }
        }
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
