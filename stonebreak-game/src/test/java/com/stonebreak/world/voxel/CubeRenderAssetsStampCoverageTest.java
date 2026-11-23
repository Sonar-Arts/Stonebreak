package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.stamp.render.StampCache;
import com.openmason.engine.voxel.stamp.render.StampRendererAPI;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.CubeWorld.ModelQuad;
import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stalagmite.Stalagmite;
import com.stonebreak.blocks.stalagmite.StalagmiteState;
import com.stonebreak.format.sbo.SBOFormat;
import com.stonebreak.format.sbo.SBOParseResult;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import com.stonebreak.rendering.sbo.SboBlockModels;
import com.stonebreak.rendering.sbo.StampVariants;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every shipped SBO stamp (default, SBO states, stair facings, stalagmite variants) converts into a host model
 * the native mesher accepts whole: corners inside {@code cv_model_define}'s range
 * ({@link CubeWorld#MODEL_CORNER_MIN}..{@link CubeWorld#MODEL_CORNER_MAX}), nothing clipped, and the real
 * {@code cv_model_define} takes every one of them. The size-3 stalagmites (anchor model y 0..3 / hanging -2..1)
 * used to be clipped at the old [-1, 2] range; they must now keep their tips.
 */
class CubeRenderAssetsStampCoverageTest {

    /** Unit UVs and layer 0: the geometry is what is checked, not the textures (no GL). */
    private static final class Textures implements ITextureCoordProvider, ILayerIndexProvider {
        @Override
        public float[] getBlockFaceUVs(IBlockType blockType, int face) {
            return new float[]{0f, 0f, 1f, 1f};
        }

        @Override
        public int getBlockFaceLayer(IBlockType blockType, int face) {
            return 0;
        }
    }

    @Test
    void everyStampBecomesAModelTheEngineAccepts() {
        SBOBlockRegistry registry = new SBOBlockRegistry();
        assumeTrue(registry.scanAndLoad() > 0, "no SBO blocks on the classpath");
        BlockRegistry.getInstance().loadFrom(registry);
        SBOBlockBridge bridge = new SBOBlockBridge();
        bridge.initialize(registry);

        Map<BlockType, SBOParseResult> stamped = new LinkedHashMap<>();
        for (BlockType type : BlockType.values()) {
            if (bridge.isSBOBlock(type) && !AnimatedBlockRegistry.isAnimatedType(type)) {
                stamped.put(type, bridge.getSBODefinition(type));
            }
        }
        StampRendererAPI api = new StampRendererAPI();
        Textures textures = new Textures();
        assertTrue(api.initialize(SboBlockModels.of(stamped), textures, textures) > 0);
        StampCache cache = api.getStampCache();
        StampVariants.registerAll(cache, stamped.keySet());

        List<String> problems = new ArrayList<>();
        List<String> clipped = new ArrayList<>();
        List<String> drift = new ArrayList<>();
        Map<String, List<ModelQuad>> models = new LinkedHashMap<>();
        for (Map.Entry<BlockType, SBOParseResult> e : stamped.entrySet()) {
            BlockType type = e.getKey();
            for (String key : variantKeys(type, e.getValue())) {
                BlockStamp stamp = key == null ? cache.get(type) : cache.get(type, key);
                if (stamp == null || (key != null && stamp == cache.get(type))) {
                    continue;
                }
                String what = type + (key == null ? "" : " [" + key + "]");
                if (type == BlockType.SNOW) {
                    for (int n = 1; n <= 8; n++) {
                        check(what + " x" + n, CubeRenderAssets.snowQuads(stamp, n, false, true), stamp,
                            problems, clipped, models);
                    }
                } else {
                    List<ModelQuad> quads = CubeRenderAssets.quads(stamp, type, false);
                    check(what, quads, stamp, problems, clipped, models);
                    checkSameSurface(what, quads, stamp, drift);
                }
            }
        }
        assertTrue(problems.isEmpty(), "models that lost geometry or left the range: " + problems);
        assertEquals(List.of(), drift, "models whose drawn triangles differ from their stamp's triangles");
        assertEquals(List.of(), clipped, "models clipped to the engine's corner range");
        assertTrue(models.size() > 20, "too few models checked: " + models.size());
        // The three-cell stalagmites are the models that need the wide range: their tips reach past [-1, 2].
        boolean tallStalagmite = models.entrySet().stream()
            .filter(m -> m.getKey().startsWith(BlockType.LIMESTONE_STALAGMITE.name()))
            .flatMap(m -> m.getValue().stream())
            .anyMatch(q -> { for (float v : q.pos()) if (v > 2f || v < -1f) return true; return false; });
        assertTrue(tallStalagmite, "no stalagmite model reaches past [-1, 2] (size-3 tips missing?)");
        assertEquals(List.of(), spireCullProblems(models), "stalagmite spires culled against their own part cells");

        assumeTrue(com.openmason.engine.voxel.world.CendaVoxelWorld.isAvailable(), "Cenda voxel world unavailable");
        try (CubeWorld world = CubeWorld.create(WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y,
                BlockType.AIR.getId(), null)) {
            int id = 1;
            for (Map.Entry<String, List<ModelQuad>> m : models.entrySet()) {
                int model = id++;
                try {
                    world.defineModel(model, m.getValue());
                } catch (RuntimeException ex) {
                    problems.add(m.getKey() + ": " + ex.getMessage());
                }
            }
        }
        assertEquals(List.of(), problems, "cv_model_define refused");
    }

    private static void check(String what, List<ModelQuad> quads, BlockStamp stamp, List<String> problems,
                              List<String> clipped, Map<String, List<ModelQuad>> models) {
        List<ModelQuad> fitted = CubeRenderAssets.fitModelRange(quads);
        if (fitted != quads) {
            clipped.add(what);
        }
        for (ModelQuad q : fitted) {
            for (float v : q.pos()) {
                if (!(v >= CubeRenderAssets.MODEL_MIN && v <= CubeRenderAssets.MODEL_MAX)) {
                    problems.add(what + ": corner " + v);
                    return;
                }
            }
        }
        if (hasGeometry(stamp) && fitted.isEmpty()) {
            problems.add(what + ": no quads left");
        }
        models.put(what, fitted);
    }

    /**
     * A size 2-3 stalagmite's anchor model reaches through its part cells (the same block, read as solid by the
     * native mesher): no quad of it may cull with the face along the spire axis, or the middle tiers vanish and the
     * tip floats (2026-11-22). Every size-2/3 variant must actually reach out, and size-1 variants keep their culls.
     */
    private static List<String> spireCullProblems(Map<String, List<ModelQuad>> models) {
        List<String> problems = new ArrayList<>();
        int tall = 0;
        for (Map.Entry<String, List<ModelQuad>> m : models.entrySet()) {
            String key = m.getKey();
            if (!key.startsWith(BlockType.LIMESTONE_STALAGMITE.name() + " [stalagmite:")) continue;
            boolean hanging = key.contains("hanging=true");
            boolean big = !key.contains("size=1;");
            int axis = hanging ? com.openmason.engine.voxel.stamp.render.StampFaceConventions.MMS_BOTTOM
                : com.openmason.engine.voxel.stamp.render.StampFaceConventions.MMS_TOP;
            boolean reaches = false;
            for (ModelQuad q : m.getValue()) {
                for (int c = 0; c < 4; c++) {
                    float y = q.pos()[c * 3 + 1];
                    reaches |= hanging ? y < -1e-3f : y > 1f + 1e-3f;
                }
                if (big && q.cullFace() == axis) {
                    problems.add(key + ": quad culls with face " + axis);
                    break;
                }
            }
            if (big) {
                tall++;
                if (!reaches) problems.add(key + ": does not reach its part cells");
            }
        }
        if (tall == 0) problems.add("no size-2/3 stalagmite variants checked");
        return problems;
    }

    /**
     * The model draws exactly the stamp's triangles: every quad is drawn (0,1,2) (0,2,3) by the native mesher
     * (and the shared stamp-quad element buffer), so pairing two triangles into a quad must keep their shared
     * edge as the 0-2 diagonal and their texture layer. A flipped diagonal folds a non-planar pair (stalagmite
     * facets) the other way: a malformed spire.
     */
    private static void checkSameSurface(String what, List<ModelQuad> quads, BlockStamp stamp, List<String> drift) {
        List<String> source = new ArrayList<>();
        List<FaceStamp> all = new ArrayList<>();
        if (stamp.faces() != null) all.addAll(java.util.Arrays.asList(stamp.faces()));
        if (stamp.interior() != null) all.addAll(java.util.Arrays.asList(stamp.interior()));
        if (stamp.protruding() != null) {
            for (FaceStamp[] side : stamp.protruding()) all.addAll(java.util.Arrays.asList(side));
        }
        for (FaceStamp fs : all) {
            if (fs == null) continue;
            float[] p = fs.positions();
            for (int t = 0; t < fs.vertexCount() / 3; t++) {
                float[] tri = new float[9];
                for (int k = 0; k < 9; k++) tri[k] = p[t * 9 + k] + 0.5f;
                int layer = fs.layers().length > t * 3 ? Math.max(0, Math.round(fs.layers()[t * 3])) : 0;
                String key = triangleKey(tri, layer);
                if (key != null) source.add(key);
            }
        }
        List<String> drawn = new ArrayList<>();
        for (ModelQuad q : quads) {
            float[] p = q.pos();
            for (int[] t : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                float[] tri = new float[9];
                for (int c = 0; c < 3; c++) System.arraycopy(p, t[c] * 3, tri, c * 3, 3);
                String key = triangleKey(tri, q.textureLayer());
                if (key != null) drawn.add(key);
            }
        }
        java.util.Collections.sort(source);
        java.util.Collections.sort(drawn);
        if (!source.equals(drawn)) {
            drift.add(what);
        }
    }

    /** A winding-preserving key of one triangle (rotated to start at its smallest vertex); null if degenerate. */
    private static String triangleKey(float[] t, int layer) {
        float ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2];
        float vx = t[6] - t[0], vy = t[7] - t[1], vz = t[8] - t[2];
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        if (cx * cx + cy * cy + cz * cz < 1e-12f) return null;
        String[] v = new String[3];
        for (int i = 0; i < 3; i++) {
            v[i] = String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f",
                t[i * 3] + 0f, t[i * 3 + 1] + 0f, t[i * 3 + 2] + 0f);
        }
        int s = 0;
        for (int i = 1; i < 3; i++) if (v[i].compareTo(v[s]) < 0) s = i;
        return layer + ":" + v[s] + "|" + v[(s + 1) % 3] + "|" + v[(s + 2) % 3];
    }

    private static boolean hasGeometry(BlockStamp stamp) {
        if (stamp.faces() != null) {
            for (FaceStamp f : stamp.faces()) {
                if (f != null && f.vertexCount() > 0) return true;
            }
        }
        if (stamp.interior() != null) {
            for (FaceStamp f : stamp.interior()) {
                if (f != null && f.vertexCount() > 0) return true;
            }
        }
        return false;
    }

    /** null = the default stamp, then every state key the game can store for this block. */
    private static Set<String> variantKeys(BlockType type, SBOParseResult sbo) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(null);
        if (sbo.manifest().states() != null) {
            for (SBOFormat.StateEntry s : sbo.manifest().states()) {
                keys.add(s.name());
            }
        }
        if (type.isStairs()) {
            for (StairState.Facing f : StairState.Facing.values()) {
                keys.add(BlockRenderState.meshVariantKey(StairState.stateStringFor(f)));
            }
        }
        if (type == BlockType.LIMESTONE_STALAGMITE) {
            for (int size = 1; size <= Stalagmite.MAX_SIZE; size++) {
                for (boolean hang : new boolean[]{false, true}) {
                    for (StairState.Facing f : StairState.Facing.values()) {
                        keys.add(BlockRenderState.meshVariantKey(new StalagmiteState(size, hang, f).toStateString()));
                    }
                }
            }
            keys.add(Stalagmite.UPPER);
            keys.add(Stalagmite.LOWER);
        }
        return keys;
    }
}
