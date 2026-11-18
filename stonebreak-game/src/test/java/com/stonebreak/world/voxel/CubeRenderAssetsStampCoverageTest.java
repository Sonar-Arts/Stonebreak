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
                    check(what, CubeRenderAssets.quads(stamp, type, false), stamp, problems, clipped, models);
                }
            }
        }
        assertTrue(problems.isEmpty(), "models that lost geometry or left the range: " + problems);
        assertEquals(List.of(), clipped, "models clipped to the engine's corner range");
        assertTrue(models.size() > 20, "too few models checked: " + models.size());
        // The three-cell stalagmites are the models that need the wide range: their tips reach past [-1, 2].
        boolean tallStalagmite = models.entrySet().stream()
            .filter(m -> m.getKey().startsWith(BlockType.LIMESTONE_STALAGMITE.name()))
            .flatMap(m -> m.getValue().stream())
            .anyMatch(q -> { for (float v : q.pos()) if (v > 2f || v < -1f) return true; return false; });
        assertTrue(tallStalagmite, "no stalagmite model reaches past [-1, 2] (size-3 tips missing?)");

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
