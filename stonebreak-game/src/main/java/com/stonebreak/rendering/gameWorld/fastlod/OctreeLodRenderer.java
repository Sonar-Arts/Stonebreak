package com.stonebreak.rendering.gameWorld.fastlod;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsSharedQuadIndexBuffer;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.world.CubeStore;
import com.openmason.engine.voxel.world.LodMesh;
import com.openmason.engine.voxel.world.LodPolicy;
import com.openmason.engine.voxel.world.LodStats;
import com.stonebreak.world.World;
import com.stonebreak.world.fastlod.FastLodManager;
import com.stonebreak.world.fastlod.OctreeLod;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * GPU side of the octree LOD ({@link OctreeLod}, Phase 5): creates the rendered world's LOD next to its FastLOD
 * manager, ticks it once per frame and keeps one GPU mesh per drawn node.
 *
 * <p><b>Meshes.</b> A node's LODQUAD16 NODE-MODE records (terrain, and the tops of its fluid cells) go to their own
 * buffer texture and draw through the shared quad index buffer with the world shader's node-mode pull path
 * ({@code aOrigin.w = -(4 + level)}: corner = cell * 2^level blocks + the node origin made camera-relative in
 * double here); the water tops draw through {@code water.vert}'s node path (surface 0.125 below the cell top, as
 * the near water and the FastLOD sheets) in the water pass. The origin rides the generic vertex attribute 5 (no
 * array is enabled on this renderer's VAO), reset to (0, 0, 0, 1) after every draw.
 *
 * <p><b>Handover.</b> SHOW fades a node in (screen-door {@code u_lodFade}), or shows it solid at once when the
 * detail under it just stopped drawing; HIDE keeps the node drawn, unfaded, for {@link #HIDE_HOLD_SECONDS} so the
 * nodes / detail / FastLOD footprints that replace it are already there, then frees it. FastLOD draws only the
 * FALLBACK footprints ({@link FastLodRenderPass}).
 *
 * <p>GL-thread confined. {@code -Dstonebreak.octreeLod=off} leaves FastLOD alone (pre-octree behaviour).
 */
public final class OctreeLodRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OctreeLodRenderer.class);

    /** How long a hidden node keeps drawing (its replacement fades in over ~0.3 s). */
    static final float HIDE_HOLD_SECONDS = 0.45f;
    static final float FADE_IN_PER_SEC = 3.5f;
    /** Mesh bytes handed out per frame for roots not touched by readiness changes. */
    static final long MESH_BYTES_PER_TICK = 8L << 20;
    private static final float MAX_FRAME_DT = 0.1f;

    private static final boolean ENABLED =
        !"off".equalsIgnoreCase(System.getProperty("stonebreak.octreeLod", "on"));

    private static volatile OctreeLodRenderer active;

    /** One drawn node. */
    static final class Node {
        final int level;
        final long x, y, z;
        long originX, originY, originZ;
        int cellSize;
        int quadBuffer, quadTexture, quadCount;
        int waterBuffer, waterTexture, waterCount;
        float minX, minY, minZ, maxX, maxY, maxZ;
        float fade;
        boolean hiding;
        float hold;
        long bytes;

        Node(int level, long x, long y, long z) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record Key(int level, long x, long y, long z) {
    }

    private final Map<Key, Node> nodes = new HashMap<>();
    private final List<Node> drawList = new ArrayList<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private int vao;
    private OctreeLod bound;
    /** The FastLOD manager creation was last attempted for (a failure is not retried every frame). */
    private FastLodManager attemptedFor;
    private long lastFrameNanos;
    private long gpuBytes;
    private int drawnLastFrame;
    private int culledLastFrame;
    private long uploadsTotal;

    private final OctreeLod.NodeSink sink = new OctreeLod.NodeSink() {
        @Override
        public void show(int level, long x, long y, long z, LodMesh mesh, boolean replace, boolean snap) {
            onShow(level, x, y, z, mesh, snap);
        }

        @Override
        public void hide(int level, long x, long y, long z) {
            Node n = nodes.get(new Key(level, x, y, z));
            if (n != null && !n.hiding) {
                n.hiding = true;
                n.hold = n.fade > 0f ? HIDE_HOLD_SECONDS : 0f;
            }
        }
    };

    public OctreeLodRenderer() {
        active = this;
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** The live octree LOD this renderer draws, or null (FastLOD alone then). */
    public OctreeLod octree() {
        OctreeLod o = bound;
        return o != null && !o.isClosed() ? o : null;
    }

    // ─── per-frame update ────────────────────────────────────────────────────────────────────────────────────

    /**
     * GL / owner thread, once per frame after the stream tick: creates the world's octree LOD next to its FastLOD
     * manager when missing, ticks it from the viewer position and applies the events.
     */
    public void update(World world, FastLodManager manager, double vx, double vy, double vz) {
        OctreeLod o = null;
        if (ENABLED && manager != null && world != null && world.isRenderOnly()) {
            o = manager.octree();
            if (o == null && attemptedFor != manager) {
                attemptedFor = manager;
                o = createFor(world);
                if (o != null) {
                    manager.attachOctree(o);
                }
            }
            if (o != null && o.isClosed()) {
                o = null;
            }
        }
        if (o != bound) {
            freeAll();
            bound = o;
        }
        if (o == null) {
            return;
        }
        WorldConfiguration config = world.getConfig();
        LodPolicy policy = null;
        if (config.isLodEnabled() && config.getLodRange() > 0) {
            int radius = (config.getRenderDistance() + config.getLodRange()) * WorldConfiguration.CHUNK_SIZE;
            policy = OctreeLod.policy(config.getLodQuality().blocksPerCell(), radius,
                config.getHorizontalRenderRadiusBlocks());
        }
        try {
            o.tick(vx, vy, vz, policy, MESH_BYTES_PER_TICK, sink);
            maybeLog(o);
        } catch (RuntimeException e) {
            LOGGER.error("Octree LOD tick failed; closing it (FastLOD takes over)", e);
            o.close();
            freeAll();
            bound = null;
        }
    }

    /**
     * Which cube store and LOD directory the rendered world's LOD uses: singleplayer / host = the integrated
     * server's store (shared handle) and {@code <world>/lod}; the menu showcase = its cache folder; a remote client
     * = no store and a per-seed folder next to the worlds.
     */
    private static OctreeLod createFor(World world) {
        CubeStore store = null;
        Path dir = null;
        try {
            var server = com.stonebreak.network.MultiplayerSession.getServer();
            var ctx = server != null ? server.worldContext() : null;
            var level = ctx != null ? ctx.serverLevel() : null;
            if (level != null) {
                store = level.world().cubeStore();
                String cache = level.lodCachePath();
                if (cache != null && !cache.isEmpty()) {
                    dir = Paths.get(cache, "lod");
                }
            } else {
                // A DaedalusTGM-Exp world's climate layout (belts keeps the pre-layout folder name) and height curve (v1
                // keeps the old name) are its own terrain; Standard worlds ignore both, so their name never carries them.
                boolean daedalus = world.getGeneratorType() == com.stonebreak.world.generation.TerrainGeneratorType.DIFFUSION;
                var climate = world.getClimateLayout();
                String layoutSuffix = !daedalus || climate == com.stonebreak.world.generation.ClimateLayout.DEFAULT
                    ? "" : "-" + climate.name();
                if (daedalus && world.getHeightCurve() != com.stonebreak.world.generation.HeightCurveVersion.V1) {
                    layoutSuffix += "-c" + world.getHeightCurve().number();
                }
                // Standard shape 1 keeps the old name; later shapes are their own terrain
                if (!daedalus && world.getStandardShape() != com.stonebreak.world.generation.StandardShapeVersion.V1) {
                    layoutSuffix += "-s" + world.getStandardShape().number();
                }
                // Two servers with the same seed are not the same world, nor are two terrain models (review-java
                // LOW 3): the store is keyed by the server's address and, for Daedalus worlds, the model too.
                var view = com.stonebreak.network.MultiplayerSession.getClient();
                if (view != null) {
                    String model = daedalus ? view.serverTerrainModel().path() + "|" + view.serverTerrainModel().contentId()
                        : "";
                    if (!view.serverKey().isEmpty() || !model.isEmpty()) {
                        layoutSuffix += "-" + shortHash(view.serverKey() + "#" + model);
                    }
                }
                dir = com.stonebreak.world.save.WorldStorage.menuShowcaseDir().getParent().resolve("remote-lod")
                    .resolve(world.getSeed() + "-" + world.getGeneratorType() + "-" + world.getTerrainScale()
                        + layoutSuffix);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Octree LOD: no store/dir ({}); memory only", e.toString());
        }
        return OctreeLod.create(world, store, dir);
    }

    /** 12 hex digits of SHA-256: a folder-name-safe key part. */
    private static String shortHash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private void onShow(int level, long x, long y, long z, LodMesh mesh, boolean snap) {
        Key key = new Key(level, x, y, z);
        Node n = nodes.get(key);
        boolean fresh = n == null;
        if (fresh) {
            n = new Node(level, x, y, z);
            n.fade = 0f;
        } else {
            release(n);
        }
        n.hiding = false;
        n.hold = 0f;
        if (snap) {
            n.fade = 1f;
        }
        n.cellSize = mesh.cellSize();
        n.originX = mesh.originX();
        n.originY = mesh.originY();
        n.originZ = mesh.originZ();
        float[] b = mesh.bounds();
        n.minX = b[0];
        n.minY = b[1];
        n.minZ = b[2];
        n.maxX = b[3];
        n.maxY = b[4];
        n.maxZ = b[5];
        n.quadCount = mesh.quadCount();
        n.waterCount = mesh.waterCount();
        if (n.quadCount > 0) {
            n.quadBuffer = GL15.glGenBuffers();
            n.quadTexture = upload(n.quadBuffer, mesh.quads(), n.quadCount);
        }
        if (n.waterCount > 0) {
            n.waterBuffer = GL15.glGenBuffers();
            n.waterTexture = upload(n.waterBuffer, mesh.water(), n.waterCount);
        }
        n.bytes = (long) (n.quadCount + n.waterCount) * LodMesh.QUAD_BYTES;
        gpuBytes += n.bytes;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, n.bytes);
        uploadsTotal++;
        if (fresh) {
            nodes.put(key, n);
        }
    }

    private static int upload(int buffer, MemorySegment records, int count) {
        long bytes = (long) count * LodMesh.QUAD_BYTES;
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.nglBufferData(GL31.GL_TEXTURE_BUFFER, bytes, records.address(), GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, buffer);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        return tex;
    }

    private void release(Node n) {
        if (n.quadTexture != 0) GL11.glDeleteTextures(n.quadTexture);
        if (n.quadBuffer != 0) GL15.glDeleteBuffers(n.quadBuffer);
        if (n.waterTexture != 0) GL11.glDeleteTextures(n.waterTexture);
        if (n.waterBuffer != 0) GL15.glDeleteBuffers(n.waterBuffer);
        n.quadTexture = n.quadBuffer = n.waterTexture = n.waterBuffer = 0;
        n.quadCount = n.waterCount = 0;
        gpuBytes -= n.bytes;
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, n.bytes);
        n.bytes = 0;
    }

    private void freeAll() {
        for (Node n : nodes.values()) {
            release(n);
        }
        nodes.clear();
        drawList.clear();
    }

    // ─── drawing ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opaque LOD terrain: advances every node's fade once, frustum-tests the nodes against the render-space
     * {@code viewProj} and draws them with the bound world shader (polygon offset as FastLOD: detail wins coplanar
     * ground). Collects the drawn nodes for {@link #drawWater}.
     */
    public void drawTerrain(ShaderProgram shader, Matrix4fc viewProj) {
        drawList.clear();
        drawnLastFrame = 0;
        culledLastFrame = 0;
        if (nodes.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : Math.min((now - lastFrameNanos) * 1e-9f, MAX_FRAME_DT);
        lastFrameNanos = now;
        frustum.set(viewProj);
        for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
            Node n = it.next();
            if (advance(n, dt)) {
                release(n);
                it.remove();
                continue;
            }
            if (n.fade <= 0f || (n.quadCount == 0 && n.waterCount == 0)) {
                continue;
            }
            float ox = RenderOrigin.relativeX(n.originX);
            float oy = RenderOrigin.relativeY(n.originY);
            float oz = RenderOrigin.relativeZ(n.originZ);
            float cs = n.cellSize;
            if (!frustum.testAab(ox + n.minX * cs, oy + n.minY * cs, oz + n.minZ * cs,
                    ox + n.maxX * cs, oy + n.maxY * cs, oz + n.maxZ * cs)) {
                culledLastFrame++;
                continue;
            }
            drawList.add(n);
        }
        if (drawList.isEmpty()) {
            return;
        }
        bindVao();
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(FastLodRenderPass.LOD_DEPTH_SLOPE, FastLodRenderPass.LOD_DEPTH_UNITS);
        float boundFade = 1f;
        for (int i = 0; i < drawList.size(); i++) {
            Node n = drawList.get(i);
            if (n.quadCount == 0) {
                continue;
            }
            if (n.fade != boundFade) {
                shader.setUniform("u_lodFade", n.fade);
                boundFade = n.fade;
            }
            draw(n, n.quadTexture, n.quadCount);
            drawnLastFrame++;
        }
        if (boundFade != 1f) {
            shader.setUniform("u_lodFade", 1f);
        }
        GL11.glPolygonOffset(0f, 0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        unbindVao();
    }

    /**
     * The water tops of this frame's drawn nodes through the bound water shader ({@code uLodFade}); called by the
     * water pass (depth prepass and colour) after {@link #drawTerrain}.
     */
    public void drawWater(ShaderProgram waterShader) {
        if (drawList.isEmpty()) {
            return;
        }
        boolean any = false;
        float boundFade = 1f;
        for (int i = 0; i < drawList.size(); i++) {
            Node n = drawList.get(i);
            if (n.waterCount == 0) {
                continue;
            }
            if (!any) {
                bindVao();
                any = true;
            }
            if (n.fade != boundFade) {
                waterShader.setUniform("uLodFade", n.fade);
                boundFade = n.fade;
            }
            draw(n, n.waterTexture, n.waterCount);
        }
        if (any) {
            if (boundFade != 1f) {
                waterShader.setUniform("uLodFade", 1f);
            }
            unbindVao();
        }
    }

    /** Fade step; true when a hidden node is done and must be freed. */
    static boolean advance(Node n, float dt) {
        if (n.hiding) {
            if (n.hold <= 0f) {
                return true;
            }
            n.hold -= dt;
            return false;
        }
        n.fade = Math.min(1f, n.fade + dt * FADE_IN_PER_SEC);
        return false;
    }

    private void bindVao() {
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, MmsSharedQuadIndexBuffer.id());
        } else {
            GL30.glBindVertexArray(vao);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MmsQuadCodec.QUAD_TEXTURE_UNIT);
    }

    private static void unbindVao() {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(0);
        // VAOs without an origin array read the generic value: back to identity (MmsVertexFormat contract).
        GL20.glVertexAttrib4f(MmsVertexFormat.ORIGIN_LOCATION, 0f, 0f, 0f, 1f);
    }

    private static void draw(Node n, int texture, int quads) {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GL20.glVertexAttrib4f(MmsVertexFormat.ORIGIN_LOCATION, RenderOrigin.relativeX(n.originX),
            RenderOrigin.relativeY(n.originY), RenderOrigin.relativeZ(n.originZ), nodeTag(n.level));
        int max = MmsQuadCodec.MAX_QUADS_PER_DRAW;
        for (int first = 0; first < quads; first += max) {
            int count = Math.min(max, quads - first);
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_SHORT, 0L, first * 4);
        }
    }

    private static final boolean LOG = Boolean.getBoolean("stonebreak.octreeLod.log");
    private long lastLogNanos;

    /** {@code -Dstonebreak.octreeLod.log=true}: one status line every 5 s (smoke runs). */
    private void maybeLog(OctreeLod o) {
        if (!LOG) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        int[] perLevel = new int[OctreeLod.MAX_LEVEL + 1];
        for (Node n : nodes.values()) {
            perLevel[Math.min(n.level, OctreeLod.MAX_LEVEL)]++;
        }
        LodStats s = o.stats();
        System.out.println("[octree-lod] nodes " + nodes.size() + " per level " + java.util.Arrays.toString(perLevel)
            + ", drawn " + drawnLastFrame + ", culled " + culledLastFrame + ", gpu " + (gpuBytes >> 10) + " KiB, fallback "
            + o.fallbackFootprints() + ", shows " + o.showsTotal() + ", hides " + o.hidesTotal()
            + (s == null ? "" : ", built " + s.nodesBuiltTotal() + ", loaded " + s.nodesLoadedTotal() + ", meshes "
            + s.meshesBuiltTotal() + ", dirty " + s.nodesDirty() + ", jobs " + s.jobsQueued() + "+" + s.jobsRunning()
            + ", standby " + s.standbyNodes() + ", ready cols " + s.readyColumns() + ", tick " + s.tickUsLast()
            + " us (max " + s.tickUsMax() + "), errors " + s.errorsTotal()));
    }

    /** The {@code aOrigin.w} tag of node-mode records of {@code level} (world.vert / water.vert decode it). */
    public static float nodeTag(int level) {
        return -(4f + level);
    }

    /** GL thread: frees every node mesh and the VAO (renderer shutdown). */
    public void cleanup() {
        freeAll();
        bound = null;
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (active == this) {
            active = null;
        }
    }

    // ─── F3 ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** F3 rows ({label, value}) of the live octree LOD. */
    public static List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        OctreeLodRenderer r = active;
        if (r == null) {
            return rows;
        }
        OctreeLod o = r.octree();
        if (o == null) {
            rows.add(new String[] {"Octree LOD", ENABLED ? "off (no LOD manager / not a rendered world)"
                : "off (-Dstonebreak.octreeLod=off: FastLOD only)"});
            return rows;
        }
        LodStats s = o.stats();
        rows.add(new String[] {"Octree LOD", String.format("%d nodes (%d drawn, %d culled), %.1f MiB GPU, %d fallback",
            r.nodes.size(), r.drawnLastFrame, r.culledLastFrame, r.gpuBytes / (1024.0 * 1024.0),
            o.fallbackFootprints())});
        if (s != null) {
            rows.add(new String[] {"LOD Build", String.format(
                "%d dirty, %d+%d jobs, %d built, %d loaded, %d meshes, %d cached (%.0f MiB)",
                s.nodesDirty(), s.jobsQueued(), s.jobsRunning(), s.nodesBuiltTotal(), s.nodesLoadedTotal(),
                s.meshesBuiltTotal(), s.nodesCached(), s.cacheBytes() / (1024.0 * 1024.0))});
            rows.add(new String[] {"LOD Tick", String.format("%d us (max %d), select %d us, %d standby, %d errors",
                s.tickUsLast(), s.tickUsMax(), s.selectUsLast(), s.standbyNodes(), s.errorsTotal())});
        }
        return rows;
    }
}
