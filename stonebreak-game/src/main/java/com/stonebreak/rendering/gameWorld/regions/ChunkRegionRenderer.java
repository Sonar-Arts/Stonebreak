package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsGpuCuller;
import com.openmason.engine.voxel.mms.mmsRegion.MmsMultiDrawBatch;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionKey;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.openmason.engine.voxel.world.StreamMesh;
import com.openmason.engine.voxel.world.VisibleCubeBuffers;
import com.openmason.engine.vram.VramPlans;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The cube terrain renderer's GPU side (cubic runtime, Stonebreak #447): every cube mesh the native
 * streamer hands out ({@code cv_gpu_mesh} via {@link StreamMesh}) uploads straight from native memory into
 * 3D region arenas ({@link MmsRegionKey}, 8x8x8 cubes = 256^3 blocks, region-local positions on all three
 * axes), and every pass draws ONE multidraw (CPU) or one compute cull + indirect multidraw (GPU, GL 4.3)
 * per region.
 *
 * <h2>Layers</h2>
 * <ul>
 *   <li>{@link #LAYER_ATLAS}: QUAD16 opaque + cutout quads (one region map, two handles per cube);</li>
 *   <li>{@link #LAYER_STAMP}: COMPACT20 stamp/model/snow geometry with u32 indices (drawn with the atlas);</li>
 *   <li>{@link #LAYER_TRANSLUCENT}: QUAD16 translucent quads (ice, glass), drawn sorted after the water;</li>
 *   <li>{@link #LAYER_WATER}: WATERQUAD16 fluid faces (the water renderer's shader).</li>
 * </ul>
 *
 * <h2>Draw decisions = enable bits</h2>
 * A cube draws when its column is READY ({@code CV_EV_COLUMN_READY}) or the FastLOD handover is off, and -
 * in the camera pass - when this frame's visibility walk ({@code cv_visible_cubes}) reached it. The decision
 * is written into every handle's enable bit before each pass ({@link #prepareShadowPass} /
 * {@link #prepareCameraPass}); the GPU cull kernel (MeshMeta flag bit 0) and the CPU twin
 * ({@link MmsChunkRegion#appendVisible} / {@link MmsMultiDrawBatch#add(MmsRegionMeshHandle)}) honour it
 * identically, so the GL 3.2 / 4.1 path and the GL 4.3 path draw the same set.
 *
 * <p>All methods are GL-thread confined. The class name survives from the column era (callers, F3 rows).
 */
public final class ChunkRegionRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkRegionRenderer.class);

    /** QUAD16 opaque + cutout. */
    public static final int LAYER_ATLAS = 0;
    /** WATERQUAD16 fluid faces. */
    public static final int LAYER_WATER = 1;
    /** COMPACT20 stamps (drawn as part of the atlas pass). */
    public static final int LAYER_STAMP = 2;
    /** QUAD16 translucent solids (ice, glass). */
    public static final int LAYER_TRANSLUCENT = 3;
    private static final int LAYER_COUNT = 4;

    /** Extra Y room on water bounds for the vertex-shader waves (clamped to +-0.18 there). */
    private static final float WATER_WAVE_MARGIN = 0.25f;
    /** How far a host model may reach past its cube (CV_MODEL_CORNER_MAX - 1 = 4 cells, + 1 slack). */
    private static final float MODEL_REACH = 5f;

    /**
     * Hi-Z occlusion in the camera pass: ON by default ({@code -Dstonebreak.hiz=off} disables it here,
     * {@code -Dopenmason.hiz=off} is the engine kill switch). The engine builds pyramid level 0 on the GPU from the
     * depth texture (no glReadPixels since 2026-11-18: capture ~0.03 ms CPU, was ~4 ms).
     */
    static final boolean HIZ_ENABLED = MmsGpuCuller.HIZ_DEFAULT
        && !"off".equalsIgnoreCase(System.getProperty("stonebreak.hiz", "on"));

    private static volatile ChunkRegionRenderer instance;
    private static volatile Boolean enabled;
    private static volatile Boolean gpuCullEnabled;

    /**
     * Whether cube rendering can run at all (GL 3.2: buffer textures, gl_VertexID, base-vertex multidraw).
     * First call must happen on the GL thread with a current context. {@code -Dstonebreak.regions=off} turns
     * terrain off (there is no other path any more).
     */
    public static boolean isEnabled() {
        Boolean value = enabled;
        if (value == null) {
            synchronized (ChunkRegionRenderer.class) {
                value = enabled;
                if (value == null) {
                    String prop = System.getProperty("stonebreak.regions", "on");
                    boolean off = "off".equalsIgnoreCase(prop) || "false".equalsIgnoreCase(prop);
                    boolean capable;
                    try {
                        capable = GL.getCapabilities().OpenGL32;
                    } catch (IllegalStateException e) {
                        return false; // No context on this thread — undecided.
                    }
                    value = !off && capable;
                    enabled = value;
                    LOGGER.info("[ChunkRegionRenderer] cube region rendering {}",
                        value ? "ENABLED (3D regions, multidraw)" : "disabled (terrain will not draw)");
                }
            }
        }
        return value;
    }

    /**
     * Whether the GPU-driven cull + multi-draw-indirect path draws the order-independent passes (atlas +
     * stamps of the camera pass, shadow cascades). GL 4.3; {@code -Dstonebreak.gpucull=off} opts out.
     */
    public static boolean isGpuCullEnabled() {
        Boolean value = gpuCullEnabled;
        if (value == null) {
            synchronized (ChunkRegionRenderer.class) {
                value = gpuCullEnabled;
                if (value == null) {
                    String prop = System.getProperty("stonebreak.gpucull", "on");
                    boolean off = "off".equalsIgnoreCase(prop) || "false".equalsIgnoreCase(prop);
                    boolean capable;
                    try {
                        capable = GL.getCapabilities().OpenGL43;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                    value = !off && capable && isEnabled();
                    gpuCullEnabled = value;
                    LOGGER.info("[ChunkRegionRenderer] GPU-driven culling {}", value
                        ? "ENABLED (compute cull + MDI)"
                        : "disabled (" + (off ? "property" : "needs GL 4.3") + ") - CPU multidraw");
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

    /** The instance if one was ever created (debug overlay / screenshot hooks), else null. */
    public static ChunkRegionRenderer peek() {
        return instance;
    }

    @SuppressWarnings("unchecked")
    private final Map<MmsRegionKey, MmsChunkRegion>[] regions = new Map[LAYER_COUNT];
    private final CubeKeyMap<CubeDraw> cubes = new CubeKeyMap<>(4096);
    private final List<CubeDraw> cubeList = new ArrayList<>();
    /** Resident cube meshes per cube layer cy (index cy - MIN_CUBE_Y): the vertical span the walk must reach. */
    private final int[] residentPerCubeY = new int[
        (int) (com.stonebreak.world.operations.WorldConfiguration.MAX_CUBE_Y
            - com.stonebreak.world.operations.WorldConfiguration.MIN_CUBE_Y)];
    /** Column cubes (cx, 0, cz) that are READY. */
    private final CubeKeyMap<Boolean> readyColumns = new CubeKeyMap<>(1024);
    private final MmsMultiDrawBatch batch = new MmsMultiDrawBatch(256);
    private final List<MmsChunkRegion> touchedRegions = new ArrayList<>();
    private int cycleCounter;
    /** Whether a cube of a non-READY column stays hidden (the far LOD covers it). */
    private boolean handover;
    private int hiddenByHandover;

    private MmsGpuCuller gpuCuller;
    private boolean gpuCullerInitFailed;
    private final float[] planes = new float[24];
    private final Vector4f planeTmp = new Vector4f();
    private final FrustumIntersection preCull = new FrustumIntersection();
    private final List<MmsChunkRegion> visibleRegions = new ArrayList<>();
    private final Vector3f regionOrigin = new Vector3f();
    /** Per-region cull answers of the current GPU pass (grown, never shrunk). */
    private int[] regionCullCounts = new int[64];
    private final int[] viewport = new int[4];
    private int lastHiZEarly, lastHiZLate;
    private volatile int publishedHiZEarly, publishedHiZLate;
    private volatile MmsGpuCuller.HiZStats publishedHiZ = MmsGpuCuller.HiZStats.NONE;
    private volatile boolean hizSkippedMultisample;

    // Frame stats (GL thread; published at beginFrame).
    private int frameRegionDraws, frameCommands, frameGpuRegionDraws, frameGpuCommands, framePreCulled;
    private int publishedRegionDraws, publishedCommands, publishedGpuRegionDraws, publishedGpuCommands,
        publishedPreCulled;
    private long uploadsTotal, dropsTotal, uploadFailures;

    private ChunkRegionRenderer() {
        for (int i = 0; i < LAYER_COUNT; i++) {
            regions[i] = new HashMap<>();
        }
    }

    // ─── Mesh intake (MeshConsumer, owner = GL thread) ───────────────────────────────────────────────

    /**
     * Uploads a cube mesh, replacing (and freeing) the cube's previous one; an empty mesh only drops it.
     * The native memory is copied into the arenas before this returns.
     *
     * @param columnReadyHint the world's own view of the cube's column readiness (a detached world counts
     *                        every column READY without sending events)
     * @return the new draw record, or null when the mesh was empty
     */
    public CubeDraw accept(StreamMesh mesh, boolean columnReadyHint) {
        long cx = mesh.cx(), cy = mesh.cy(), cz = mesh.cz();
        drop(cx, cy, cz);
        if (mesh.isEmpty()) {
            return null;
        }
        MmsRegionKey key = MmsRegionKey.ofCube(cx, cy, cz);
        float[] box = localBox(mesh, cx, cy, cz);
        int quads = 0;
        for (int l = 0; l < 3; l++) quads += mesh.quadCount(l);
        quads += mesh.waterCount();
        CubeDraw draw = new CubeDraw(cx, cy, cz, mesh.revision(),
            box[0], box[1], box[2], box[3], box[4], box[5], quads, mesh.byteSize());
        try {
            int n = mesh.quadCount(StreamMesh.OPAQUE);
            if (n > 0) draw.opaque = region(LAYER_ATLAS, key).uploadQuads(mesh.quads(StreamMesh.OPAQUE), n,
                box[0], box[1], box[2], box[3], box[4], box[5]);
            n = mesh.quadCount(StreamMesh.CUTOUT);
            if (n > 0) draw.cutout = region(LAYER_ATLAS, key).uploadQuads(mesh.quads(StreamMesh.CUTOUT), n,
                box[0], box[1], box[2], box[3], box[4], box[5]);
            n = mesh.quadCount(StreamMesh.TRANSLUCENT);
            if (n > 0) draw.translucent = region(LAYER_TRANSLUCENT, key).uploadQuads(
                mesh.quads(StreamMesh.TRANSLUCENT), n, box[0], box[1], box[2], box[3], box[4], box[5]);
            int sv = mesh.stampVertexCount(), si = mesh.stampIndexCount();
            if (sv > 0 && si > 0) {
                if (si != sv / 4 * 6 || (sv & 3) != 0) {
                    // The quad-indexed stamp region relies on cv_gpu_mesh's 4-vertex quads (6 indices each).
                    throw new IllegalStateException("stamp mesh is not whole quads: " + sv + " vertices, "
                        + si + " indices");
                }
                MemorySegment vertices = mesh.stampVertices();
                MemorySegment indices = mesh.stampIndices();
                draw.stamp = region(LAYER_STAMP, key).uploadLocal(vertices, sv, indices, si,
                    box[0], box[1], box[2], box[3], box[4], box[5]);
            }
            n = mesh.waterCount();
            if (n > 0) draw.water = region(LAYER_WATER, key).uploadQuads(mesh.water(), n,
                box[0], box[1] - WATER_WAVE_MARGIN, box[2], box[3], box[4] + WATER_WAVE_MARGIN, box[5]);
        } catch (RuntimeException e) {
            uploadFailures++;
            LOGGER.error("Cube mesh upload failed at ({}, {}, {})", cx, cy, cz, e);
            draw.close();
            return null;
        }
        draw.columnReady = columnReadyHint || readyColumns.get(cx, 0, cz) != null;
        draw.listIndex = cubeList.size();
        cubeList.add(draw);
        cubes.put(cx, cy, cz, draw);
        countResident(cy, 1);
        uploadsTotal++;
        return draw;
    }

    /** The cube's region-local bounds: the mesh's own box, or the whole cube when it reports none. */
    static float[] localBox(StreamMesh mesh, long cx, long cy, long cz) {
        float[] b = {mesh.aabbMin(0), mesh.aabbMin(1), mesh.aabbMin(2),
            mesh.aabbMax(0), mesh.aabbMax(1), mesh.aabbMax(2)};
        if (!(b[3] >= b[0] && b[4] >= b[1] && b[5] >= b[2]) || (b[3] == b[0] && b[4] == b[1] && b[5] == b[2])) {
            float x = MmsRegionKey.cubeLocalOffset(cx);
            float y = MmsRegionKey.cubeLocalOffset(cy);
            float z = MmsRegionKey.cubeLocalOffset(cz);
            b = new float[] {x, y, z, x + 32f, y + 32f, z + 32f};
        }
        return b;
    }

    private MmsChunkRegion region(int layer, MmsRegionKey key) {
        return regions[layer].computeIfAbsent(key, k -> switch (layer) {
            case LAYER_WATER -> new MmsChunkRegion(k, MmsVertexFormat.WATERQUAD16,
                VramPlans.arena(VramPlans.POOL_CHUNK_WATER));
            // Quad-indexed (2026-11-22): stamp quads are 4 vertices drawn (0,1,2) (0,2,3), so the region draws
            // them through the shared stamp-quad element buffer and keeps no index arena (80 instead of 104
            // bytes per stamp quad).
            case LAYER_STAMP -> MmsChunkRegion.quadIndexed(k, MmsVertexFormat.COMPACT20,
                VramPlans.arena(VramPlans.POOL_CHUNK_STAMP));
            default -> new MmsChunkRegion(k, MmsVertexFormat.QUAD16,
                VramPlans.arena(VramPlans.POOL_CHUNK_MESH));
        });
    }

    /** Frees a cube's mesh (unload, empty remesh). */
    public void drop(long cx, long cy, long cz) {
        CubeDraw old = cubes.remove(cx, cy, cz);
        if (old == null) {
            return;
        }
        int at = old.listIndex;
        CubeDraw last = cubeList.removeLast();
        if (last != old) {
            cubeList.set(at, last);
            last.listIndex = at;
        }
        old.listIndex = -1;
        countResident(cy, -1);
        old.close();
        dropsTotal++;
    }

    public CubeDraw get(long cx, long cy, long cz) {
        return cubes.get(cx, cy, cz);
    }

    private void countResident(long cy, int delta) {
        long i = cy - com.stonebreak.world.operations.WorldConfiguration.MIN_CUBE_Y;
        if (i >= 0 && i < residentPerCubeY.length) {
            residentPerCubeY[(int) i] += delta;
        }
    }

    /**
     * Largest vertical distance (in cubes) from cube layer {@code cy} to a resident cube mesh, 0 when none is
     * resident. The surface band keeps terrain loaded at ANY height difference, so a camera far above (or
     * below) the terrain needs a visibility walk that reaches this far down (up).
     */
    public long residentVerticalReach(long cy) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < residentPerCubeY.length; i++) {
            if (residentPerCubeY[i] > 0) {
                long y = com.stonebreak.world.operations.WorldConfiguration.MIN_CUBE_Y + i;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (min > max) {
            return 0;
        }
        return Math.max(Math.max(cy - min, max - cy), 0);
    }

    /** Every resident cube mesh (dense, unordered; do not mutate). */
    public List<CubeDraw> cubes() {
        return cubeList;
    }

    /** CV_EV_COLUMN_READY / UNREADY of column cube (cx, cz). */
    public void setColumnReady(long cx, long cz, boolean ready) {
        if (ready) {
            readyColumns.put(cx, 0, cz, Boolean.TRUE);
        } else {
            readyColumns.remove(cx, 0, cz);
        }
        // Column cubes span the whole world height (cy -12..19): a 32-entry probe beats an index.
        for (long cy = com.stonebreak.world.operations.WorldConfiguration.MIN_CUBE_Y;
             cy < com.stonebreak.world.operations.WorldConfiguration.MAX_CUBE_Y; cy++) {
            CubeDraw d = cubes.get(cx, cy, cz);
            if (d != null) {
                d.columnReady = ready;
            }
        }
    }

    public boolean isColumnReady(long cx, long cz) {
        return readyColumns.get(cx, 0, cz) != null;
    }

    /** Whether non-READY columns hide their detail cubes (the far LOD covers them). */
    public void setHandover(boolean handover) {
        this.handover = handover;
    }

    public boolean handover() {
        return handover;
    }

    /** Whether the cube may draw at all this frame (ignoring camera visibility). */
    public boolean isDrawable(CubeDraw d) {
        return drawable(d);
    }

    boolean drawable(CubeDraw d) {
        return d.columnReady || !handover;
    }

    // ─── Per-frame state ─────────────────────────────────────────────────────────────────────────────

    /** Frame boundary: publishes last frame's stats, deletes emptied regions, trims one arena per layer. */
    public void beginFrame() {
        publishedRegionDraws = frameRegionDraws;
        publishedCommands = frameCommands;
        publishedGpuRegionDraws = frameGpuRegionDraws;
        publishedGpuCommands = frameGpuCommands;
        publishedPreCulled = framePreCulled;
        // Hi-Z counters: harvested here on the GL thread (fence polls, no stall) so F3 reads plain fields.
        publishedHiZ = gpuCuller != null ? gpuCuller.hizStats() : MmsGpuCuller.HiZStats.NONE;
        publishedHiZEarly = lastHiZEarly;
        publishedHiZLate = lastHiZLate;
        frameRegionDraws = frameCommands = frameGpuRegionDraws = frameGpuCommands = framePreCulled = 0;
        for (Map<MmsRegionKey, MmsChunkRegion> map : regions) {
            pruneEmpty(map);
        }
        for (Map<MmsRegionKey, MmsChunkRegion> map : regions) {
            for (MmsChunkRegion region : map.values()) {
                if (region.maybeTrim()) {
                    break;
                }
            }
        }
    }

    /** Enable bits for a light pass (sun cascades, torch views): every drawable cube, no camera visibility. */
    public void prepareShadowPass() {
        for (int i = 0; i < cubeList.size(); i++) {
            CubeDraw d = cubeList.get(i);
            d.setEnabled(drawable(d));
        }
    }

    /**
     * Enable bits for the camera pass: drawable AND reached by visibility walk {@code frame} (or every
     * drawable cube when {@code frame < 0}: no walk this frame).
     */
    public void prepareCameraPass(int frame) {
        int hidden = 0;
        for (int i = 0; i < cubeList.size(); i++) {
            CubeDraw d = cubeList.get(i);
            boolean drawable = drawable(d);
            if (!drawable) hidden++;
            d.setEnabled(drawable && (frame < 0 || d.visibleFrame == frame));
        }
        hiddenByHandover = hidden;
    }

    /** Resident cubes the last camera pass hid because their column is not READY. */
    public int hiddenByHandover() {
        return hiddenByHandover;
    }

    /**
     * Marks the cubes of a {@code cv_visible_cubes} result visible in walk {@code frame} and appends the
     * drawable ones to {@code out} (walk order: near to far). Returns how many were resident.
     */
    public int markVisible(long[] triples, int frame, List<CubeDraw> out) {
        int resident = 0;
        for (int i = 0; i + 2 < triples.length; i += 3) {
            CubeDraw d = cubes.get(triples[i], triples[i + 1], triples[i + 2]);
            if (d == null) {
                continue;
            }
            resident++;
            d.visibleFrame = frame;
            if (out != null && drawable(d)) {
                out.add(d);
            }
        }
        return resident;
    }

    /**
     * {@link #markVisible(long[], int, List)} straight from a walk's reusable native buffers (no copy, no
     * allocation): the {@link VisibleCubeBuffers#visibleCount()} triples of the last walk into {@code buffers}.
     */
    public int markVisible(VisibleCubeBuffers buffers, int frame, List<CubeDraw> out) {
        LongBuffer xyz = buffers.visibleBuffer();
        int n = buffers.visibleCount();
        int resident = 0;
        for (int i = 0, at = 0; i < n; i++, at += 3) {
            CubeDraw d = cubes.get(xyz.get(at), xyz.get(at + 1), xyz.get(at + 2));
            if (d == null) {
                continue;
            }
            resident++;
            d.visibleFrame = frame;
            if (out != null && drawable(d)) {
                out.add(d);
            }
        }
        return resident;
    }

    // ─── Draws ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Draws the atlas + stamp layers of every enabled cube inside {@code viewProjRender} (a RENDER-SPACE
     * clip matrix: camera or light): GPU compute cull + one indirect multidraw per region on GL 4.3, else
     * one CPU-culled multidraw per region. Region boxes are pre-culled on the CPU first. The caller owns
     * shader and state; the enable bits must have been prepared for this pass.
     */
    public void drawSolid(Matrix4f viewProjRender) {
        if (!collectSolidRegions(viewProjRender)) {
            return;
        }
        if (isGpuCullEnabled() && ensureGpuCuller()) {
            gpuCuller.beginPass(planes);
            frameGpuCommands += gpuCullAndDraw();
            frameGpuRegionDraws += visibleRegions.size();
            return;
        }
        drawSolidCpu();
    }

    /**
     * The CAMERA opaque pass: {@link #drawSolid} with Hi-Z occlusion on the GPU path (GL 4.3, engine
     * {@link MmsGpuCuller} two-phase pass). Phase 1 draws what the previous camera pass found visible, the
     * depth it produced becomes this frame's Hi-Z pyramid (read from the bound draw framebuffer inside the
     * current viewport), phase 2 re-tests every enabled in-frustum mesh against it and draws the newly
     * visible ones. The previous frame only decides what draws EARLY, never what is culled, so camera moves
     * cannot open holes. Call it with the opaque state set and nothing but terrain in the depth buffer that
     * is nearer than the sky (sky/clouds draw without depth writes); light passes keep {@link #drawSolid}.
     * The CPU path (GL 3.2 / 4.1) is plain {@link #drawSolid}. Hi-Z is on by default ({@link #HIZ_ENABLED}:
     * {@code -Dstonebreak.hiz=off} disables it); {@code -Dopenmason.hiz=off} is the engine kill switch. Disabled, phase 1 is a
     * plain frustum pass and phase 2 is skipped.
     */
    public void drawCameraSolid(Matrix4f viewProjRender) {
        lastHiZEarly = 0;
        lastHiZLate = 0;
        if (!collectSolidRegions(viewProjRender)) {
            return;
        }
        if (!isGpuCullEnabled() || !ensureGpuCuller()) {
            drawSolidCpu();
            return;
        }
        gpuCuller.beginEarlyPass(planes);
        int early = gpuCullAndDraw();
        frameGpuCommands += early;
        frameGpuRegionDraws += visibleRegions.size();
        lastHiZEarly = early;
        if (!gpuCuller.isHiZEnabled()) {
            return; // phase 1 was a plain frustum pass and drew everything
        }
        captureHiZ(viewProjRender);
        gpuCuller.beginLatePass();
        int late = gpuCullAndDraw();
        lastHiZLate = late;
    }

    /** Region pre-cull for a solid pass; false when no region survives. */
    private boolean collectSolidRegions(Matrix4f viewProjRender) {
        extractPlanes(viewProjRender);
        preCull.set(viewProjRender);
        visibleRegions.clear();
        collectRegions(regions[LAYER_ATLAS]);
        collectRegions(regions[LAYER_STAMP]);
        return !visibleRegions.isEmpty();
    }

    /**
     * cull/endCull/draw/endDraw over {@link #visibleRegions} for the pass the culler was begun with. A region
     * whose cull answers 0 is not drawn: in a Hi-Z late pass that means "phase 1 already drew it", and the
     * region's indirect buffer still holds the early commands.
     */
    private int gpuCullAndDraw() {
        int n = visibleRegions.size();
        if (regionCullCounts.length < n) {
            regionCullCounts = new int[Math.max(n, regionCullCounts.length * 2)];
        }
        int commands = 0;
        for (int i = 0; i < n; i++) {
            int c = gpuCuller.cull(visibleRegions.get(i));
            regionCullCounts[i] = c;
            commands += c;
        }
        gpuCuller.endCull();
        for (int i = 0; i < n; i++) {
            if (regionCullCounts[i] > 0) {
                gpuCuller.draw(visibleRegions.get(i));
            }
        }
        gpuCuller.endDraw();
        return commands;
    }

    /**
     * Builds the Hi-Z pyramid from the bound draw framebuffer's depth inside the current viewport. A
     * multisampled target cannot be read as depth: the pyramid is then skipped and phase 2 treats nothing as
     * occluded (correct, just no occlusion savings).
     */
    private void captureHiZ(Matrix4f viewProjRender) {
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (GL11.glGetInteger(GL13.GL_SAMPLE_BUFFERS) != 0) {
            hizSkippedMultisample = true;
            return;
        }
        hizSkippedMultisample = false;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        gpuCuller.buildHiZ(framebuffer, viewport[0], viewport[1], viewport[2], viewport[3], viewProjRender);
    }

    private void drawSolidCpu() {
        for (int i = 0; i < visibleRegions.size(); i++) {
            MmsChunkRegion region = visibleRegions.get(i);
            batch.reset();
            int added = region.appendVisible(batch, planes);
            if (added == 0) {
                continue;
            }
            region.bind();
            batch.draw(region.indexType());
            frameRegionDraws++;
            frameCommands += added;
        }
        GL30.glBindVertexArray(0);
    }

    // ─── Hi-Z telemetry (F3) ─────────────────────────────────────────────────────────────────────────

    /** Whether the camera pass runs Hi-Z occlusion (GL 4.3 GPU cull path, not {@code -Dopenmason.hiz=off}). */
    public boolean isHiZEnabled() {
        return gpuCuller != null && isGpuCullEnabled() && gpuCuller.isHiZEnabled();
    }

    /**
     * The GPU Hi-Z counters as of the last {@link #beginFrame} (read back asynchronously, so a few frames
     * old), or {@link MmsGpuCuller.HiZStats#NONE}. Plain field read: any thread.
     */
    public MmsGpuCuller.HiZStats hizStats() {
        return publishedHiZ;
    }

    /** Commands the last finished frame's camera pass dispatched in phase 1 / phase 2 (CPU side). */
    public int publishedHiZEarlyCommands() {
        return publishedHiZEarly;
    }

    public int publishedHiZLateCommands() {
        return publishedHiZLate;
    }

    /** True when the last pyramid capture was skipped because the target is multisampled. */
    public boolean hizSkippedMultisample() {
        return hizSkippedMultisample;
    }

    private void collectRegions(Map<MmsRegionKey, MmsChunkRegion> map) {
        // Model geometry may reach MODEL_REACH blocks past its cube (voxel_world.h: oversize models), so a
        // region's meshes can poke out of its 256^3 box by that much.
        float lo = -MODEL_REACH, hi = MmsRegionKey.REGION_BLOCKS + MODEL_REACH;
        for (MmsChunkRegion region : map.values()) {
            if (region.isDeleted() || region.isEmpty()) {
                continue;
            }
            region.renderOrigin(regionOrigin);
            if (preCull.testAab(regionOrigin.x + lo, regionOrigin.y + lo, regionOrigin.z + lo,
                    regionOrigin.x + hi, regionOrigin.y + hi, regionOrigin.z + hi)) {
                visibleRegions.add(region);
            } else {
                framePreCulled++;
            }
        }
    }

    private boolean ensureGpuCuller() {
        if (gpuCullerInitFailed) {
            return false;
        }
        if (gpuCuller == null) {
            try {
                gpuCuller = new MmsGpuCuller();
                gpuCuller.setHiZEnabled(HIZ_ENABLED);
            } catch (RuntimeException e) {
                gpuCullerInitFailed = true;
                LOGGER.error("[ChunkRegionRenderer] GPU cull init failed, CPU multidraw from now on", e);
                return false;
            }
        }
        return true;
    }

    private void extractPlanes(Matrix4f viewProj) {
        for (int p = 0; p < 6; p++) {
            viewProj.frustumPlane(p, planeTmp);
            planes[p * 4] = planeTmp.x;
            planes[p * 4 + 1] = planeTmp.y;
            planes[p * 4 + 2] = planeTmp.z;
            planes[p * 4 + 3] = planeTmp.w;
        }
    }

    /**
     * Draws one layer of the given cubes in list order at region granularity (bucket order = first
     * appearance; inside a region, list order): the translucent and water passes hand in back-to-front
     * lists. {@link #LAYER_ATLAS} draws atlas AND stamps (torch views). Honours the enable bits. The caller
     * owns shader/state.
     */
    public void drawCubes(List<CubeDraw> ordered, int layer) {
        if (ordered.isEmpty()) {
            return;
        }
        int stamp = ++cycleCounter;
        touchedRegions.clear();
        for (int i = 0; i < ordered.size(); i++) {
            CubeDraw d = ordered.get(i);
            switch (layer) {
                case LAYER_ATLAS -> {
                    bucket(d.opaque, stamp);
                    bucket(d.cutout, stamp);
                    bucket(d.stamp, stamp);
                }
                case LAYER_TRANSLUCENT -> bucket(d.translucent, stamp);
                case LAYER_WATER -> bucket(d.water, stamp);
                case LAYER_STAMP -> bucket(d.stamp, stamp);
                default -> { }
            }
        }
        for (int r = 0; r < touchedRegions.size(); r++) {
            MmsChunkRegion region = touchedRegions.get(r);
            if (region.isDeleted()) {
                continue;
            }
            List<MmsRegionMeshHandle> members = region.cycleMembers(stamp);
            batch.reset();
            int added = 0;
            for (int m = 0; m < members.size(); m++) {
                added += batch.add(members.get(m));
            }
            if (added == 0) {
                continue;
            }
            region.bind();
            batch.draw(region.indexType());
            frameRegionDraws++;
            frameCommands += added;
        }
        GL30.glBindVertexArray(0);
    }

    private void bucket(MmsRegionMeshHandle h, int stamp) {
        if (h == null || h.isClosed() || !h.isEnabled()) {
            return;
        }
        MmsChunkRegion region = h.region();
        if (!region.touchedInCycle(stamp)) {
            touchedRegions.add(region);
        }
        region.cycleMembers(stamp).add(h);
    }

    // ─── Lifecycle + telemetry ───────────────────────────────────────────────────────────────────────

    /** Frees every mesh and region (world change / renderer shutdown). GL thread. */
    public void reset() {
        for (int i = 0; i < cubeList.size(); i++) {
            cubeList.get(i).listIndex = -1;
        }
        cubeList.clear();
        cubes.clear();
        java.util.Arrays.fill(residentPerCubeY, 0);
        readyColumns.clear();
        for (Map<MmsRegionKey, MmsChunkRegion> map : regions) {
            for (MmsChunkRegion region : map.values()) {
                region.delete();
            }
            map.clear();
        }
    }

    private static void pruneEmpty(Map<MmsRegionKey, MmsChunkRegion> map) {
        Iterator<MmsChunkRegion> it = map.values().iterator();
        while (it.hasNext()) {
            MmsChunkRegion region = it.next();
            if (region.isEmpty()) {
                region.delete();
                it.remove();
            }
        }
    }

    public int publishedRegionDraws() {
        return publishedRegionDraws;
    }

    public int publishedCommands() {
        return publishedCommands;
    }

    /** Column-era name kept for the F3 row: cube meshes have no per-chunk legacy draws (always 0). */
    public int publishedLegacyDraws() {
        return 0;
    }

    public int publishedGpuRegionDraws() {
        return publishedGpuRegionDraws;
    }

    public int publishedGpuCommands() {
        return publishedGpuCommands;
    }

    public int publishedGpuPreCulledRegions() {
        return publishedPreCulled;
    }

    /** Resident cube meshes. */
    public int residentCubes() {
        return cubeList.size();
    }

    /** Live regions of one layer. */
    public int regionCount(int layer) {
        return regions[layer].size();
    }

    public long uploadsTotal() {
        return uploadsTotal;
    }

    public long dropsTotal() {
        return dropsTotal;
    }

    public long uploadFailures() {
        return uploadFailures;
    }

    /** Tracked GPU bytes of all live regions in one layer (debug/telemetry). */
    public long layerBytes(int layer) {
        long total = 0;
        for (MmsChunkRegion r : regions[layer].values()) {
            total += r.capacityBytes();
        }
        return total;
    }

    /** Live draw commands in one layer (debug/telemetry). */
    public int layerMeshes(int layer) {
        int n = 0;
        for (MmsChunkRegion r : regions[layer].values()) {
            n += r.gpuCommandCount();
        }
        return n;
    }
}
