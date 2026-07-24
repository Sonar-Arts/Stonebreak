package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsMultiDrawBatch;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.stonebreak.world.chunk.Chunk;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Region-batched chunk rendering: chunk meshes upload into shared per-region
 * GPU arenas (8x8 chunk columns each, atlas and water layers separate), and
 * every render pass draws ONE {@code glMultiDrawElementsBaseVertex} per
 * visible region instead of a VAO bind + draw call per chunk. At 24-chunk
 * render distance this collapses ~1000+ draws per pass into a few dozen.
 *
 * <p>Frustum culling stays chunk-granular: passes hand in their (possibly
 * sorted) chunk lists and this class buckets them into regions preserving
 * list order, so back-to-front ordering holds at region-then-chunk
 * granularity. For water that is exact enough (its depth prepass makes
 * inter-mesh order irrelevant); for the ice pass, translucent surfaces that
 * overlap ACROSS a region seam can composite slightly out of order — a known,
 * accepted approximation (Sodium orders translucents the same way). Chunks
 * whose meshes could not join a region (non-packed or u32-index meshes —
 * practically never) keep legacy per-chunk handles and are drawn after the
 * region batches.
 *
 * <p>Enabled by default on a GL 3.2+ context; force the legacy per-chunk path
 * with {@code -Dstonebreak.regions=off}. All methods are GL-thread confined.
 */
public final class ChunkRegionRenderer {

    public static final int LAYER_ATLAS = 0;
    public static final int LAYER_WATER = 1;

    private static volatile ChunkRegionRenderer instance;
    private static volatile Boolean enabled;

    /**
     * Whether region rendering is active. First call must happen on the GL
     * thread with a current context (checks GL 3.2 capability).
     */
    public static boolean isEnabled() {
        Boolean value = enabled;
        if (value == null) {
            synchronized (ChunkRegionRenderer.class) {
                value = enabled;
                if (value == null) {
                    String prop = System.getProperty("stonebreak.regions", "on");
                    boolean off = "off".equalsIgnoreCase(prop) || "false".equalsIgnoreCase(prop);
                    boolean capable = false;
                    if (!off) {
                        try {
                            capable = GL.getCapabilities().OpenGL32;
                        } catch (IllegalStateException e) {
                            // No context on this thread — treat as undecided.
                            return false;
                        }
                    }
                    value = !off && capable;
                    enabled = value;
                    System.out.println("[ChunkRegionRenderer] Region rendering "
                        + (value ? "ENABLED (multidraw batching)" : "disabled (legacy per-chunk draws)"));
                }
            }
        }
        return value;
    }

    public static ChunkRegionRenderer getInstance() {
        ChunkRegionRenderer local = instance;
        if (local == null) {
            synchronized (ChunkRegionRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new ChunkRegionRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Map<Long, MmsChunkRegion> atlasRegions = new HashMap<>();
    private final Map<Long, MmsChunkRegion> waterRegions = new HashMap<>();
    private final MmsMultiDrawBatch batch = new MmsMultiDrawBatch(256);
    private final List<MmsChunkRegion> touchedRegions = new ArrayList<>();
    private final List<Chunk> legacyFallback = new ArrayList<>();
    private int cycleCounter;

    // Frame stats for the debug overlay (written on the GL thread,
    // read by the overlay on the same thread).
    private int frameRegionDraws;
    private int frameCommands;
    private int frameLegacyDraws;
    private int publishedRegionDraws;
    private int publishedCommands;
    private int publishedLegacyDraws;

    private ChunkRegionRenderer() {
    }

    /**
     * Frame boundary: publishes last frame's stats and prunes regions whose
     * last mesh was freed (their GL buffers are deleted).
     */
    public void beginFrame() {
        publishedRegionDraws = frameRegionDraws;
        publishedCommands = frameCommands;
        publishedLegacyDraws = frameLegacyDraws;
        frameRegionDraws = 0;
        frameCommands = 0;
        frameLegacyDraws = 0;
        pruneEmpty(atlasRegions);
        pruneEmpty(waterRegions);
    }

    public int publishedRegionDraws() {
        return publishedRegionDraws;
    }

    public int publishedCommands() {
        return publishedCommands;
    }

    public int publishedLegacyDraws() {
        return publishedLegacyDraws;
    }

    /**
     * Uploads a chunk mesh into its region's arenas. Returns null when the
     * mesh cannot join a region (not packed / u32 indices) — the caller then
     * falls back to a legacy per-chunk handle.
     */
    public MmsRegionMeshHandle upload(int layer, int chunkX, int chunkZ, MmsMeshData mesh) {
        if (mesh == null || mesh.isEmpty() || !mesh.isPacked() || !mesh.hasShortIndices()) {
            return null;
        }
        long key = regionKey(chunkX >> MmsChunkRegion.REGION_SHIFT,
            chunkZ >> MmsChunkRegion.REGION_SHIFT);
        Map<Long, MmsChunkRegion> regions = layer == LAYER_WATER ? waterRegions : atlasRegions;
        MmsChunkRegion region = regions.computeIfAbsent(key, k -> new MmsChunkRegion());
        return region.upload(mesh.getPackedVertexData(), mesh.getPackedIndexData(),
            mesh.getVertexCount(), mesh.getIndexCount());
    }

    /**
     * Draws the given chunks' geometry for one layer as region multidraws,
     * preserving the list's order at region granularity (bucket order = first
     * appearance). Chunks carrying only a legacy handle draw individually
     * afterwards. The caller owns all shader/state setup.
     */
    public void drawChunks(List<Chunk> chunks, int layer) {
        int stamp = ++cycleCounter;
        touchedRegions.clear();
        legacyFallback.clear();

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (!chunk.getCcoStateManager().isRenderable()) {
                continue;
            }
            MmsRegionMeshHandle handle = layer == LAYER_WATER
                ? chunk.getRegionWaterHandle() : chunk.getRegionAtlasHandle();
            if (handle != null && (handle.isClosed() || handle.region().isDeleted())) {
                // Defensive: a handle whose region was torn down (or that was
                // closed without the chunk field being cleared yet) must never
                // reach bind() — skip it rather than crash the render loop.
                handle = null;
            }
            if (handle == null) {
                boolean hasLegacy = layer == LAYER_WATER
                    ? chunk.getWaterRenderableHandle() != null
                    : chunk.getMmsRenderableHandle() != null;
                if (hasLegacy) {
                    legacyFallback.add(chunk);
                }
                continue;
            }
            MmsChunkRegion region = handle.region();
            if (!region.touchedInCycle(stamp)) {
                touchedRegions.add(region);
            }
            region.cycleMembers(stamp).add(handle);
        }

        for (int r = 0; r < touchedRegions.size(); r++) {
            MmsChunkRegion region = touchedRegions.get(r);
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
        if (!touchedRegions.isEmpty()) {
            GL30.glBindVertexArray(0);
        }

        for (int i = 0; i < legacyFallback.size(); i++) {
            Chunk chunk = legacyFallback.get(i);
            if (layer == LAYER_WATER) {
                chunk.renderWater();
            } else {
                chunk.render();
            }
            frameLegacyDraws++;
        }
    }

    /** Deletes every region (world unload / renderer shutdown). GL thread. */
    public void reset() {
        deleteAll(atlasRegions);
        deleteAll(waterRegions);
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
