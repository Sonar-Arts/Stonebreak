package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsGpuCuller;
import com.openmason.engine.voxel.mms.mmsRegion.MmsMultiDrawBatch;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
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
 * with {@code -Dstonebreak.regions=off}. On GL 4.3+ the order-independent
 * passes (opaque, shadow cascades) additionally upgrade to GPU-driven culling:
 * a compute shader frustum-tests every live mesh per region and the region
 * draws with one {@code glMultiDrawElementsIndirect} — see
 * {@link #drawLayerGpuCulled} ({@code -Dstonebreak.gpucull=off} opts out).
 * Order-dependent passes (ice, water) stay on the CPU-listed multidraw so
 * back-to-front ordering holds. All methods are GL-thread confined.
 */
public final class ChunkRegionRenderer {

    public static final int LAYER_ATLAS = 0;
    public static final int LAYER_WATER = 1;

    private static volatile ChunkRegionRenderer instance;
    private static volatile Boolean enabled;
    private static volatile Boolean gpuCullEnabled;

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

    /**
     * Whether the GPU-driven cull + multi-draw-indirect path is active for
     * order-independent passes (opaque, shadow cascades). Requires region
     * rendering plus a GL 4.3 context; force off with
     * {@code -Dstonebreak.gpucull=off}. First call needs a current context.
     */
    public static boolean isGpuCullEnabled() {
        Boolean value = gpuCullEnabled;
        if (value == null) {
            synchronized (ChunkRegionRenderer.class) {
                value = gpuCullEnabled;
                if (value == null) {
                    String prop = System.getProperty("stonebreak.gpucull", "on");
                    boolean off = "off".equalsIgnoreCase(prop) || "false".equalsIgnoreCase(prop);
                    boolean capable = false;
                    if (!off) {
                        try {
                            capable = GL.getCapabilities().OpenGL43;
                        } catch (IllegalStateException e) {
                            // No context on this thread — treat as undecided.
                            return false;
                        }
                    }
                    value = !off && capable && isEnabled();
                    gpuCullEnabled = value;
                    System.out.println("[ChunkRegionRenderer] GPU-driven culling "
                        + (value ? "ENABLED (compute cull + MDI)"
                                 : "disabled (" + (off ? "property" : "needs regions + GL 4.3") + ")"));
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

    // GPU-cull pass state (lazily created; null after an init failure, which
    // permanently reverts those passes to the CPU multidraw path).
    private MmsGpuCuller gpuCuller;
    private boolean gpuCullerInitFailed;
    private final float[] gpuPlanes = new float[24];
    private final Vector4f gpuPlaneTmp = new Vector4f();
    private final FrustumIntersection gpuPreCull = new FrustumIntersection();
    private final List<MmsChunkRegion> gpuVisibleRegions = new ArrayList<>();

    // Frame stats for the debug overlay (written on the GL thread,
    // read by the overlay on the same thread).
    private int frameRegionDraws;
    private int frameCommands;
    private int frameLegacyDraws;
    private int frameGpuRegionDraws;
    private int frameGpuCommands;
    private int frameGpuPreCulledRegions;
    private int publishedRegionDraws;
    private int publishedCommands;
    private int publishedLegacyDraws;
    private int publishedGpuRegionDraws;
    private int publishedGpuCommands;
    private int publishedGpuPreCulledRegions;

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
        publishedGpuRegionDraws = frameGpuRegionDraws;
        publishedGpuCommands = frameGpuCommands;
        publishedGpuPreCulledRegions = frameGpuPreCulledRegions;
        frameRegionDraws = 0;
        frameCommands = 0;
        frameLegacyDraws = 0;
        frameGpuRegionDraws = 0;
        frameGpuCommands = 0;
        frameGpuPreCulledRegions = 0;
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

    public int publishedGpuRegionDraws() {
        return publishedGpuRegionDraws;
    }

    public int publishedGpuCommands() {
        return publishedGpuCommands;
    }

    public int publishedGpuPreCulledRegions() {
        return publishedGpuPreCulledRegions;
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
        // World-space chunk box for the GPU cull (full height, matching the
        // CPU chunk frustum test's quality).
        float minX = chunkX * (float) WorldConfiguration.CHUNK_SIZE;
        float minZ = chunkZ * (float) WorldConfiguration.CHUNK_SIZE;
        return region.upload(mesh.getPackedVertexData(), mesh.getPackedIndexData(),
            mesh.getVertexCount(), mesh.getIndexCount(),
            minX, 0f, minZ,
            minX + WorldConfiguration.CHUNK_SIZE, WorldConfiguration.WORLD_HEIGHT,
            minZ + WorldConfiguration.CHUNK_SIZE);
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

    /**
     * GPU-driven draw for one layer of an order-independent pass (opaque,
     * shadow cascade): regions surviving a CPU region-AABB pre-cull each get a
     * compute-shader per-mesh frustum cull writing indirect commands, then ONE
     * {@code glMultiDrawElementsIndirect} per region — no per-chunk CPU
     * visibility work at all. Draws EVERY live mesh passing the frustum
     * (including chunks a CPU list would have excluded, e.g. just outside the
     * render-distance walk — visually benign for opaque/shadow geometry).
     *
     * <p>Frustum planes are extracted from {@code viewProj} (camera or cascade
     * light matrix). Returns false when the cull program is unavailable (init
     * failure) — the caller must then fall back to {@link #drawChunks}. The
     * caller owns all shader/state setup; chunks stuck on legacy handles still
     * need {@link #drawLegacyOnly} afterwards.
     */
    public boolean drawLayerGpuCulled(int layer, Matrix4f viewProj) {
        if (gpuCullerInitFailed) {
            return false;
        }
        if (gpuCuller == null) {
            try {
                gpuCuller = new MmsGpuCuller();
            } catch (RuntimeException e) {
                gpuCullerInitFailed = true;
                System.err.println("[ChunkRegionRenderer] GPU cull init failed, "
                    + "falling back to CPU multidraw: " + e.getMessage());
                return false;
            }
        }

        Map<Long, MmsChunkRegion> regions = layer == LAYER_WATER ? waterRegions : atlasRegions;
        if (regions.isEmpty()) {
            return true;
        }

        // Region-level pre-cull: one CPU AABB test per 8x8-column region
        // decides whether it dispatches at all.
        gpuPreCull.set(viewProj);
        float regionBlocks = MmsChunkRegion.REGION_SPAN * (float) WorldConfiguration.CHUNK_SIZE;
        gpuVisibleRegions.clear();
        for (Map.Entry<Long, MmsChunkRegion> entry : regions.entrySet()) {
            MmsChunkRegion region = entry.getValue();
            if (region.isDeleted() || region.isEmpty()) {
                continue;
            }
            long key = entry.getKey();
            float minX = (int) (key >> 32) * regionBlocks;
            float minZ = (int) key * regionBlocks;
            if (gpuPreCull.testAab(minX, 0f, minZ,
                    minX + regionBlocks, WorldConfiguration.WORLD_HEIGHT, minZ + regionBlocks)) {
                gpuVisibleRegions.add(region);
            } else {
                frameGpuPreCulledRegions++;
            }
        }
        if (gpuVisibleRegions.isEmpty()) {
            return true;
        }

        for (int p = 0; p < 6; p++) {
            viewProj.frustumPlane(p, gpuPlaneTmp);
            gpuPlanes[p * 4] = gpuPlaneTmp.x;
            gpuPlanes[p * 4 + 1] = gpuPlaneTmp.y;
            gpuPlanes[p * 4 + 2] = gpuPlaneTmp.z;
            gpuPlanes[p * 4 + 3] = gpuPlaneTmp.w;
        }

        gpuCuller.beginPass(gpuPlanes);
        int commands = 0;
        for (int i = 0; i < gpuVisibleRegions.size(); i++) {
            commands += gpuCuller.cull(gpuVisibleRegions.get(i));
        }
        gpuCuller.endCull();
        for (int i = 0; i < gpuVisibleRegions.size(); i++) {
            gpuCuller.draw(gpuVisibleRegions.get(i));
        }
        gpuCuller.endDraw();

        frameGpuRegionDraws += gpuVisibleRegions.size();
        frameGpuCommands += commands;
        return true;
    }

    /**
     * Draws only the chunks that could NOT join a region (legacy per-chunk
     * handles — non-packed/u32 meshes, practically never). Companion to
     * {@link #drawLayerGpuCulled}, which covers everything region-resident.
     */
    public void drawLegacyOnly(List<Chunk> chunks, int layer) {
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (!chunk.getCcoStateManager().isRenderable()) {
                continue;
            }
            MmsRegionMeshHandle handle = layer == LAYER_WATER
                ? chunk.getRegionWaterHandle() : chunk.getRegionAtlasHandle();
            if (handle != null && !handle.isClosed() && !handle.region().isDeleted()) {
                continue; // Drawn by the region path.
            }
            if (layer == LAYER_WATER) {
                if (chunk.getWaterRenderableHandle() != null) {
                    chunk.renderWater();
                    frameLegacyDraws++;
                }
            } else if (chunk.getMmsRenderableHandle() != null) {
                chunk.render();
                frameLegacyDraws++;
            }
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
