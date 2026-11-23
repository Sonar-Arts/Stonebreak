package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.stamp.render.StampCache;
import com.openmason.engine.voxel.stamp.render.StampEmitter;
import com.openmason.engine.voxel.stamp.render.StampFaceConventions;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.CubeWorld.ModelQuad;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The client-side render half of the native voxel world's configuration: per-face texture layers of unit
 * cubes ({@code cv_block_faces_set}) and the host models ({@code cv_model_*}) of everything that is not a
 * cube - SBO stamps (stairs per facing, stalagmites, flowers, cactus, furnace variants...) and snow per
 * layer count - converted from the SBO {@link StampEmitter}'s baked stamps, which the {@code Renderer}
 * builds once the block texture array exists.
 *
 * <p><b>Lifecycle.</b> {@link #setEmitter} is called by the renderer (through {@code MmsAPI}) once; every
 * world that meshes for display registers with {@link #attach} and gets the assets as soon as both exist.
 * State variants are bound lazily: a state name registered in the world's {@link BlockStateRegistry}
 * after attachment is bound for every block whose stamp cache holds that exact variant.
 *
 * <p><b>Conversion.</b> Stamp positions are centred on the cell ({@code [-0.5, 0.5]}) and shifted to the
 * cell-local {@code [0, 1]} the ABI wants; triangle pairs sharing an edge become one quad, an unpaired
 * triangle a degenerate quad. Boundary faces cull with their face, interior and cross-plane (flower) quads
 * never cull, protrusions cull with the side they poke through. Snow is the snow stamp squashed to
 * {@code n/8} for n = 1..8, bound to the SNOW layer (0 reads as 1 layer).
 *
 * <p>Threads: owner (render) thread for {@link #setEmitter}/{@link #attach}; listener callbacks run on
 * whichever thread registered the state name (the CubeWorld calls are thread-safe).
 */
public final class CubeRenderAssets {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeRenderAssets.class);

    private static final int[] UV_C00 = new int[6];
    private static final int[] UV_C10 = new int[6];
    private static final int[] UV_C01 = new int[6];

    static {
        for (int face = 0; face < 6; face++) {
            int uAxis = MmsCuboidGenerator.uAxis(face);
            int vAxis = MmsCuboidGenerator.vAxis(face);
            for (int corner = 0; corner < 4; corner++) {
                int a = (int) MmsCuboidGenerator.cornerOffset(face, corner, uAxis);
                int b = (int) MmsCuboidGenerator.cornerOffset(face, corner, vAxis);
                if (a == 0 && b == 0) UV_C00[face] = corner;
                else if (a == 1 && b == 0) UV_C10[face] = corner;
                else if (a == 0 && b == 1) UV_C01[face] = corner;
            }
        }
    }

    private static volatile StampEmitter emitter;
    private static volatile SboCubeFaces cubes;
    private static final Map<CubeWorld, Attached> ATTACHED = new WeakHashMap<>();

    private CubeRenderAssets() {
    }

    /** One world's model bookkeeping. */
    private static final class Attached implements BlockStateRegistry.Listener {
        final CubeWorld world;
        final BlockStateRegistry states;
        int nextModel = 1;
        boolean installed;

        Attached(CubeWorld world, BlockStateRegistry states) {
            this.world = world;
            this.states = states;
        }

        @Override
        public void onRegistered(int id, String name) {
            StampEmitter e = emitter;
            if (e != null && installed) {
                synchronized (this) {
                    try {
                        bindStateVariants(this, e.getCache(), id, name);
                    } catch (RuntimeException ex) {
                        LOGGER.error("[CubeRenderAssets] binding state '{}' failed", name, ex);
                    }
                }
            }
        }
    }

    /** Sets the stamp emitter (renderer, once the texture array exists) and installs it into attached worlds. */
    public static void setEmitter(StampEmitter stampEmitter) {
        if (stampEmitter == null) {
            return;
        }
        emitter = stampEmitter;
        cubes = new SboCubeFaces(stampEmitter);
        List<Attached> worlds;
        synchronized (ATTACHED) {
            worlds = new ArrayList<>(ATTACHED.values());
        }
        for (Attached a : worlds) {
            install(a);
        }
    }

    /** The emitter, or null before the renderer built it. */
    public static StampEmitter emitter() {
        return emitter;
    }

    /** Registers a display world; installs the assets now when the emitter exists. */
    public static void attach(CubeWorld world, BlockStateRegistry states) {
        Attached a = new Attached(world, states);
        synchronized (ATTACHED) {
            ATTACHED.put(world, a);
        }
        states.addListener(a);
        if (emitter != null) {
            install(a);
        }
    }

    /**
     * Re-applies the setting-dependent half of a display world's configuration: the block table (with the
     * stamp cache's unit-cube verdict), fluid and face rules, and every unit cube's face textures (their alpha
     * test follows {@code BlockType.isTransparent()}, i.e. the leaf-transparency setting). Models are kept (no
     * setting changes them; redefining would burn model ids). The caller re-meshes ({@code CubeStream.remeshAll}).
     * Falls back to the headless block table when {@code world} is not attached or the emitter is not built yet.
     */
    public static void reapply(CubeWorld world) {
        Attached a;
        synchronized (ATTACHED) {
            a = ATTACHED.get(world);
        }
        SboCubeFaces c = cubes;
        if (a == null || c == null || !a.installed) {
            StonebreakBlockTable.apply(world, cubeOverride(), detailBlocks());
            return;
        }
        synchronized (a) {
            try {
                StonebreakBlockTable.apply(a.world, cubeOverride(), detailBlocks());
                for (BlockType type : BlockType.values()) {
                    if (type == BlockType.AIR || type == BlockType.WATER) continue;
                    if (c.isCube(type) || (c.isPerCellCube(type) && type != BlockType.SNOW)) {
                        a.world.setBlockFaces(type.getId(), faceTextures(c, type));
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.error("[CubeRenderAssets] re-apply failed", ex);
            }
        }
    }

    /** Forgets a world (before it closes). */
    public static void detach(CubeWorld world) {
        Attached a;
        synchronized (ATTACHED) {
            a = ATTACHED.remove(world);
        }
        if (a != null) {
            a.states.removeListener(a);
        }
    }

    /**
     * The stamp cache's verdict on which blocks are unit cubes (indexed by id; null = no stamp, keep the
     * headless rule). Null before the emitter exists.
     */
    public static Boolean[] cubeOverride() {
        SboCubeFaces c = cubes;
        return c == null ? null : cubeOverride(c);
    }

    /**
     * The cube blocks that also keep their relief model (ore nuggets) as a native DETAIL model, indexed by id;
     * null before the emitter exists. See {@link SboCubeFaces#isDetailCube}.
     */
    public static boolean[] detailBlocks() {
        SboCubeFaces c = cubes;
        return c == null ? null : detailBlocks(c);
    }

    static boolean[] detailBlocks(SboCubeFaces c) {
        boolean[] out = new boolean[StonebreakBlockTable.maxId() + 1];
        for (BlockType type : BlockType.values()) {
            if (type != BlockType.AIR && type != BlockType.WATER && c.isDetailCube(type)) {
                out[type.getId()] = true;
            }
        }
        return out;
    }

    /** {@link #cubeOverride()} for the verdicts {@code c}. */
    static Boolean[] cubeOverride(SboCubeFaces c) {
        Boolean[] out = new Boolean[StonebreakBlockTable.maxId() + 1];
        for (BlockType type : BlockType.values()) {
            if (type == BlockType.AIR || type == BlockType.WATER) continue;
            if (c.isCube(type) || (c.isPerCellCube(type) && type != BlockType.SNOW)) {
                out[type.getId()] = Boolean.TRUE;
            } else if (c.isModel(type) || type == BlockType.SNOW) {
                // Every stamped non-cube draws from its model, occluding or not: left to the headless rule it
                // would be flagged a cube with no face textures set, and the packer then uses layer = id
                // (limestone, id 44, drew texture layer 44 = pine planks).
                out[type.getId()] = Boolean.FALSE;
            }
        }
        return out;
    }

    private static void install(Attached a) {
        StampEmitter e = emitter;
        SboCubeFaces c = cubes;
        if (e == null || c == null) {
            return;
        }
        synchronized (a) {
            try {
                StonebreakBlockTable.apply(a.world, cubeOverride(), detailBlocks());
                StampCache cache = e.getCache();
                int faces = 0, models = 0;
                for (BlockType type : BlockType.values()) {
                    if (type == BlockType.AIR || type == BlockType.WATER) continue;
                    if (c.isCube(type) || (c.isPerCellCube(type) && type != BlockType.SNOW)) {
                        a.world.setBlockFaces(type.getId(), faceTextures(c, type));
                        faces++;
                        BlockStamp relief = c.isDetailCube(type) ? cache.get(type) : null;
                        if (relief != null) {
                            // DETAIL: the full model (flush faces + nuggets) near the camera, the faces above beyond.
                            int model = a.nextModel++;
                            if (define(a.world, model, quads(relief, type, false), type + " [detail]")) {
                                a.world.bindBlockModel(type.getId(), -1, 0, model);
                                models++;
                            }
                        }
                    } else if (type == BlockType.SNOW) {
                        BlockStamp stamp = cache.get(type);
                        if (stamp != null) {
                            for (int n = 1; n <= 8; n++) {
                                int model = a.nextModel++;
                                if (!define(a.world, model, snowQuads(stamp, n, e.isTranslucent(type), type.isTransparent()),
                                        type + " snow " + n)) {
                                    continue;
                                }
                                a.world.bindBlockModel(type.getId(), VoxelLayers.SNOW, n, model);
                                if (n == 1) a.world.bindBlockModel(type.getId(), VoxelLayers.SNOW, 0, model);
                                models++;
                            }
                        }
                    } else if (!AnimatedBlockRegistry.isAnimatedType(type) && !type.isAnimated()) {
                        BlockStamp stamp = cache.get(type);
                        if (stamp != null) {
                            int model = a.nextModel++;
                            if (define(a.world, model, quads(stamp, type, e.isTranslucent(type)), type.name())) {
                                a.world.bindBlockModel(type.getId(), -1, 0, model);
                                models++;
                            }
                        }
                    }
                }
                a.installed = true;
                for (String name : a.states.names()) {
                    int id = a.states.find(name);
                    models += bindStateVariants(a, cache, id, name);
                }
                LOGGER.info("[CubeRenderAssets] installed {} cube face sets and {} models", faces, models);
            } catch (RuntimeException ex) {
                LOGGER.error("[CubeRenderAssets] install failed", ex);
            }
        }
    }

    /** Binds every block's exact stamp variant for state {@code name}; returns the number of models. */
    private static int bindStateVariants(Attached a, StampCache cache, int id, String name) {
        int bound = 0;
        for (BlockType type : BlockType.values()) {
            if (type == BlockType.AIR || type == BlockType.WATER || type == BlockType.SNOW
                    || AnimatedBlockRegistry.isAnimatedType(type) || type.isAnimated()) {
                continue;
            }
            BlockStamp exact = cache.get(type, name);
            if (exact == null || exact == cache.get(type)) {
                continue;
            }
            int model = a.nextModel++;
            StampEmitter e = emitter;
            if (define(a.world, model, quads(exact, type, e != null && e.isTranslucent(type)), type + " [" + name + "]")) {
                a.world.bindBlockModel(type.getId(), VoxelLayers.STATE, id, model);
                bound++;
            }
        }
        return bound;
    }

    /**
     * Defines one model; a model the engine refuses is skipped with a warning (its block draws nothing in that
     * state) instead of aborting every model after it. Quads beyond the ABI's corner range
     * ({@link CubeWorld#MODEL_CORNER_MIN}..{@link CubeWorld#MODEL_CORNER_MAX}, 4 cells past the anchor) are
     * clipped first ({@link #fitModelRange}) - no shipped model reaches that far (size-3 stalagmites span 3 cells
     * and stay whole). Quads beyond [-1, 2] are never culled by the engine.
     */
    private static boolean define(CubeWorld world, int model, List<ModelQuad> quads, String what) {
        List<ModelQuad> fitted = fitModelRange(quads);
        if (fitted != quads) {
            LOGGER.warn("[CubeRenderAssets] model {} reaches beyond [{}, {}] of its cell: clipped ({} -> {} quads)",
                what, (int) MODEL_MIN, (int) MODEL_MAX, quads.size(), fitted.size());
        }
        try {
            world.defineModel(model, fitted);
            return true;
        } catch (RuntimeException ex) {
            LOGGER.warn("[CubeRenderAssets] model {} refused by the engine, skipped: {}", what, ex.getMessage());
            return false;
        }
    }

    /** {@code cv_model_define}'s corner range (cell-local; a model may reach 4 cells past its anchor). */
    static final float MODEL_MIN = CubeWorld.MODEL_CORNER_MIN;
    static final float MODEL_MAX = CubeWorld.MODEL_CORNER_MAX;

    /**
     * {@code quads} with every quad that leaves {@code [MODEL_MIN, MODEL_MAX]^3} clipped to that box
     * (Sutherland-Hodgman on the quad's polygon, uv interpolated, the convex result re-split into quads in the
     * same winding; a quad wholly outside disappears). Returns {@code quads} itself when nothing needed it,
     * which is every shipped model since the engine range grew to [-4, 5] (the size-3 stalagmites, y 0..3 /
     * hanging -2..1, used to be clipped at [-1, 2]); kept as a guard for future oversize assets.
     */
    static List<ModelQuad> fitModelRange(List<ModelQuad> quads) {
        boolean any = false;
        for (ModelQuad q : quads) {
            if (!inRange(q)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return quads;
        }
        List<ModelQuad> out = new ArrayList<>(quads.size());
        for (ModelQuad q : quads) {
            if (inRange(q)) {
                out.add(q);
                continue;
            }
            List<float[]> poly = new ArrayList<>(8);
            for (int c = 0; c < 4; c++) {
                float[] v = {q.pos()[c * 3], q.pos()[c * 3 + 1], q.pos()[c * 3 + 2], q.uv()[c * 2], q.uv()[c * 2 + 1]};
                if (poly.isEmpty() || !samePoint(poly.get(poly.size() - 1), v)) {
                    poly.add(v);
                }
            }
            for (int axis = 0; axis < 3 && poly.size() >= 3; axis++) {
                poly = clip(poly, axis, MODEL_MIN, true);
                poly = clip(poly, axis, MODEL_MAX, false);
            }
            if (poly.size() < 3) {
                continue;
            }
            for (int i = 1; i + 1 < poly.size(); i += 2) {
                float[] a = poly.get(0), b = poly.get(i), c = poly.get(i + 1);
                float[] d = i + 2 < poly.size() ? poly.get(i + 2) : c;
                out.add(quadOf(q, a, b, c, d));
            }
        }
        return out;
    }

    private static boolean inRange(ModelQuad q) {
        for (float v : q.pos()) {
            if (!(v >= MODEL_MIN && v <= MODEL_MAX)) {
                return false;
            }
        }
        return true;
    }

    /** One Sutherland-Hodgman pass against the plane {@code p[axis] = bound} (keep >= when {@code low}). */
    private static List<float[]> clip(List<float[]> poly, int axis, float bound, boolean low) {
        List<float[]> out = new ArrayList<>(poly.size() + 2);
        for (int i = 0; i < poly.size(); i++) {
            float[] cur = poly.get(i);
            float[] nxt = poly.get((i + 1) % poly.size());
            boolean curIn = low ? cur[axis] >= bound : cur[axis] <= bound;
            boolean nxtIn = low ? nxt[axis] >= bound : nxt[axis] <= bound;
            if (curIn) {
                out.add(cur);
            }
            if (curIn != nxtIn) {
                float t = (bound - cur[axis]) / (nxt[axis] - cur[axis]);
                float[] v = new float[5];
                for (int k = 0; k < 5; k++) {
                    v[k] = cur[k] + (nxt[k] - cur[k]) * t;
                }
                v[axis] = bound;
                if (out.isEmpty() || !samePoint(out.get(out.size() - 1), v)) {
                    out.add(v);
                }
            }
        }
        if (out.size() > 1 && samePoint(out.get(0), out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean samePoint(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) < 1e-6f && Math.abs(a[1] - b[1]) < 1e-6f && Math.abs(a[2] - b[2]) < 1e-6f;
    }

    private static ModelQuad quadOf(ModelQuad src, float[] a, float[] b, float[] c, float[] d) {
        float[] pos = new float[12];
        float[] uv = new float[8];
        float[][] vs = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 3; k++) {
                pos[i * 3 + k] = Math.max(MODEL_MIN, Math.min(MODEL_MAX, vs[i][k]));
            }
            uv[i * 2] = vs[i][3];
            uv[i * 2 + 1] = vs[i][4];
        }
        return new ModelQuad(pos, uv, src.normal().clone(), src.textureLayer(), src.cullFace(), src.flags());
    }

    /** The six QUAD16 face textures of a unit-cube block (layer + D4 orientation + alpha test). */
    static CubeWorld.FaceTexture[] faceTextures(SboCubeFaces c, BlockType type) {
        CubeWorld.FaceTexture[] out = new CubeWorld.FaceTexture[6];
        boolean alpha = type.isTransparent() && !type.isTranslucent();
        for (int face = 0; face < 6; face++) {
            float[] tc = c.texCoords(type, face);
            float u00 = tc[UV_C00[face] * 2], v00 = tc[UV_C00[face] * 2 + 1];
            int orient = MmsQuadCodec.orientation(u00, v00,
                tc[UV_C10[face] * 2] - u00, tc[UV_C10[face] * 2 + 1] - v00,
                tc[UV_C01[face] * 2] - u00, tc[UV_C01[face] * 2 + 1] - v00);
            out[face] = new CubeWorld.FaceTexture(Math.max(0, Math.round(c.layer(type, face))), Math.max(0, orient), alpha);
        }
        return out;
    }

    /** Model quads of a stamp (see the class comment for the cull rules). */
    static List<ModelQuad> quads(BlockStamp stamp, BlockType type, boolean translucent) {
        int flags = translucent ? CubeWorld.MODEL_TRANSLUCENT : type.isTransparent() ? CubeWorld.MODEL_CUTOUT : 0;
        boolean cross = type.isFlower() || type.name().endsWith("SAPLING");
        List<ModelQuad> out = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            if (stamp.faces() != null && stamp.faces()[face] != null) {
                appendQuads(out, stamp.faces()[face], cross ? CubeWorld.CULL_NEVER : face, flags, 1f);
            }
            if (stamp.interior() != null && stamp.interior()[face] != null) {
                appendQuads(out, stamp.interior()[face], CubeWorld.CULL_NEVER, flags, 1f);
            }
        }
        if (stamp.protruding() != null) {
            for (int side = 0; side < 6; side++) {
                for (int face = 0; face < 6; face++) {
                    FaceStamp fs = stamp.protruding()[side][face];
                    if (fs != null) {
                        appendQuads(out, fs, cross ? CubeWorld.CULL_NEVER : side, flags, 1f);
                    }
                }
            }
        }
        return type == BlockType.LIMESTONE_STALAGMITE ? openSpireAxis(out) : out;
    }

    /**
     * A stalagmite's anchor model draws the whole spire: a size 2-3 one reaches up (hanging: down) through
     * its part cells, which hold the same block. The native mesher reads that neighbour as solid (a
     * stalagmite is non-transparent host geometry) and would cull every quad facing it, so the spire lost
     * its middle tiers and only the quads reaching past the next cell (never culled) were left, floating
     * above the stub. The old Java emitter had the same rule as a cull policy ("never cull a stalagmite
     * face against another stalagmite", Renderer); here it is baked into the model: when the model
     * reaches out of its cell along the spire axis, the quads culled with that face never cull.
     * Size-1 models (and the empty part-cell models) stay inside the cell and are unchanged.
     */
    static List<ModelQuad> openSpireAxis(List<ModelQuad> quads) {
        boolean up = false, down = false;
        for (ModelQuad q : quads) {
            for (int c = 0; c < 4; c++) {
                float y = q.pos()[c * 3 + 1];
                up |= y > 1f + 1e-3f;
                down |= y < -1e-3f;
            }
        }
        if (!up && !down) {
            return quads;
        }
        List<ModelQuad> out = new ArrayList<>(quads.size());
        for (ModelQuad q : quads) {
            boolean open = (up && q.cullFace() == StampFaceConventions.MMS_TOP)
                || (down && q.cullFace() == StampFaceConventions.MMS_BOTTOM);
            out.add(open ? new ModelQuad(q.pos(), q.uv(), q.normal(), q.textureLayer(), CubeWorld.CULL_NEVER,
                q.flags()) : q);
        }
        return out;
    }

    /** The snow stamp at {@code layers}/8 height: the top only culls when full, the sides with their face. */
    static List<ModelQuad> snowQuads(BlockStamp stamp, int layers, boolean translucent, boolean transparent) {
        int flags = translucent ? CubeWorld.MODEL_TRANSLUCENT : transparent ? CubeWorld.MODEL_CUTOUT : 0;
        float height = layers / 8f;
        List<ModelQuad> out = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            if (stamp.faces() == null || stamp.faces()[face] == null) continue;
            int cull = face == 0 && layers < 8 ? CubeWorld.CULL_NEVER : face;
            appendQuads(out, stamp.faces()[face], cull, flags, height);
        }
        return out;
    }

    /**
     * Appends the triangles of {@code fs} as quads: a pair sharing an edge (same texture layer and normal - a
     * quad carries one of each) becomes the quad
     * (pk+1, q, pk+2, pk) in the first triangle's winding, anything else a degenerate quad per triangle.
     * The native mesher draws every model quad as (0,1,2) (0,2,3), so the shared edge must be the 0-2 diagonal:
     * the quad then draws exactly the two source triangles, planar or not. (It used to be (pk, pk+1, q, pk+2),
     * whose 0-2 diagonal is the OTHER one: harmless on flat squares, but every non-planar pair - stalagmite
     * facets - folded the wrong way and the spires rendered malformed.)
     * {@code yScale} squashes positions (and side-face v) for snow layers.
     */
    static void appendQuads(List<ModelQuad> out, FaceStamp fs, int cullFace, int flags, float yScale) {
        int tris = fs.vertexCount() / 3;
        float[] p = fs.positions();
        float[] uv = fs.atlasUVs();
        float[] n = fs.normals();
        float[] ly = fs.layers();
        int t = 0;
        while (t < tris) {
            int a0 = t * 3;
            int[] quad = null;
            if (t + 1 < tris && layerOf(ly, a0) == layerOf(ly, a0 + 3) && sameNormal(n, a0, a0 + 3)) {
                quad = pairQuad(p, a0, a0 + 3);
            }
            int[] verts = quad != null ? quad : new int[]{a0, a0 + 1, a0 + 2, a0 + 2};
            float[] pos = new float[12];
            float[] tex = new float[8];
            for (int c = 0; c < 4; c++) {
                int v = verts[c];
                pos[c * 3] = p[v * 3] + 0.5f;
                pos[c * 3 + 1] = (p[v * 3 + 1] + 0.5f) * yScale;
                pos[c * 3 + 2] = p[v * 3 + 2] + 0.5f;
                tex[c * 2] = uv.length > v * 2 ? uv[v * 2] : 0f;
                float vv = uv.length > v * 2 + 1 ? uv[v * 2 + 1] : 0f;
                tex[c * 2 + 1] = yScale != 1f && Math.abs(n[a0 * 3 + 1]) < 0.5f ? 1f - (1f - vv) * yScale : vv;
            }
            float[] normal = {n[a0 * 3], n[a0 * 3 + 1], n[a0 * 3 + 2]};
            int layer = layerOf(ly, a0);
            out.add(new ModelQuad(pos, tex, normal, layer, cullFace, flags));
            t += quad != null ? 2 : 1;
        }
    }

    private static boolean sameNormal(float[] n, int u, int v) {
        if (n.length < (Math.max(u, v) + 1) * 3) return false;
        return n[u * 3] * n[v * 3] + n[u * 3 + 1] * n[v * 3 + 1] + n[u * 3 + 2] * n[v * 3 + 2] > 0.9999f;
    }

    private static int layerOf(float[] layers, int vertex) {
        return layers.length > vertex ? Math.max(0, Math.round(layers[vertex])) : 0;
    }

    /**
     * The quad of two triangles at a and b sharing an edge (winding of a), or null. Corner 0 and 2 are the shared
     * edge, so the (0,1,2) (0,2,3) split reproduces b then a.
     */
    private static int[] pairQuad(float[] p, int a, int b) {
        int[] matchInA = {-1, -1, -1}; // for each vertex of A: matching vertex of B
        int matches = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (same(p, a + i, b + j)) {
                    matchInA[i] = b + j;
                    matches++;
                    break;
                }
            }
        }
        if (matches != 2) {
            return null;
        }
        int k = matchInA[0] < 0 ? 0 : matchInA[1] < 0 ? 1 : 2; // A's unique vertex
        int q = -1;
        for (int j = 0; j < 3; j++) {
            boolean used = false;
            for (int i = 0; i < 3; i++) {
                if (matchInA[i] == b + j) used = true;
            }
            if (!used) q = b + j;
        }
        return new int[]{a + (k + 1) % 3, q, a + (k + 2) % 3, a + k};
    }

    private static boolean same(float[] p, int u, int v) {
        return Math.abs(p[u * 3] - p[v * 3]) < 1e-5f && Math.abs(p[u * 3 + 1] - p[v * 3 + 1]) < 1e-5f
            && Math.abs(p[u * 3 + 2] - p[v * 3 + 2]) < 1e-5f;
    }
}
