package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.stamp.render.StampCache;
import com.openmason.engine.voxel.stamp.render.StampEmitter;
import com.openmason.engine.voxel.stamp.render.StampRendererAPI;
import com.openmason.engine.voxel.world.BlockFlags;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.format.sbo.SBOParseResult;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import com.stonebreak.rendering.sbo.SboBlockModels;
import com.stonebreak.rendering.sbo.StampVariants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every shipped block the client's block table flags as a cube gets cube face textures. A block the native packer
 * draws as a cube without {@code setBlockFaces} packs texture layer = its id: limestone (id 44, two stamp variants
 * through its redundant "Base" state) was once left to the headless rule and drew layer 44 - pine planks. Since the
 * 2026-11-22 VRAM pass limestone is a stamp cube (its "Base" variant has the default geometry), and ores with
 * protruding nuggets are DETAIL cubes: cube faces + their full model, drawn only near the camera.
 */
class CubeRenderAssetsCubeVerdictTest {

    /** Unit UVs and layer 0: only the cube/model verdicts are checked (no GL). */
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
    void everyBlockFlaggedACubeHasCubeFaceTextures() {
        SBOBlockRegistry registry = new SBOBlockRegistry();
        assumeTrue(registry.scanAndLoad() > 0, "no SBO blocks on the classpath");
        BlockRegistry.getInstance().loadFrom(registry);
        SBOBlockBridge bridge = new SBOBlockBridge();
        bridge.initialize(registry);

        // As the renderer builds them: every non-animated SBO block, then the state variants.
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
        SboCubeFaces cubes = new SboCubeFaces(new StampEmitter(cache, (b, x, y, z, f, c) -> true));

        short[] flags = StonebreakBlockTable.flags(CubeRenderAssets.cubeOverride(cubes),
            CubeRenderAssets.detailBlocks(cubes));
        List<String> faceless = new ArrayList<>();
        for (BlockType type : stamped.keySet()) {
            if (type == BlockType.AIR || type == BlockType.WATER || cache.get(type) == null) {
                continue;
            }
            boolean flaggedCube = (flags[type.getId()] & BlockFlags.CUBE) != 0;
            // install() sets face textures exactly for these (snow is drawn from its layer models)
            boolean hasFaces = cubes.isCube(type) || (cubes.isPerCellCube(type) && type != BlockType.SNOW);
            if (flaggedCube && !hasFaces) {
                faceless.add(type.name() + " (id " + type.getId() + ")");
            }
        }
        assertEquals(List.of(), faceless, "flagged cubes without face textures (would draw texture layer = id)");

        assertTrue(cubes.isCube(BlockType.LIMESTONE), "limestone's redundant 'Base' state is the same cube");
        assertFalse(cubes.isModel(BlockType.LIMESTONE));
        assertTrue((flags[BlockType.LIMESTONE.getId()] & BlockFlags.CUBE) != 0, "limestone takes the cube path");
        assertTrue((flags[BlockType.LIMESTONE.getId()] & BlockFlags.OPAQUE) != 0, "and still hides its neighbours");
        assertEquals(0, flags[BlockType.LIMESTONE.getId()] & BlockFlags.DETAIL, "no relief on limestone");
        assertTrue(cubes.isCube(BlockType.STONE), "a plain cube stays on the cube path");
        assertFalse(cubes.isDetailCube(BlockType.STONE));

        // Ores: protruding nuggets on an opaque cube = DETAIL cubes (faces far away, the nugget model near).
        List<String> ores = new ArrayList<>();
        for (BlockType type : stamped.keySet()) {
            if (!type.name().endsWith("_ORE") || cache.get(type) == null) continue;
            ores.add(type.name());
            int f = flags[type.getId()];
            assertTrue(cubes.isCube(type) && cubes.isDetailCube(type), type + " is a detail cube");
            assertTrue((f & BlockFlags.CUBE) != 0 && (f & BlockFlags.DETAIL) != 0 && (f & BlockFlags.OPAQUE) != 0,
                type + " flags CUBE | DETAIL | OPAQUE");
            assertTrue(cache.get(type).hasProtrusions(), type + " keeps its nugget model for the detail mode");
        }
        assertFalse(ores.isEmpty(), "the shipped ores were checked");
        // No transparent or per-cell block becomes a detail cube.
        for (BlockType type : stamped.keySet()) {
            if (cubes.isDetailCube(type)) {
                assertFalse(type.isTransparent() || cubes.isPerCellCube(type), type + " must be an opaque plain cube");
            }
        }
    }
}
