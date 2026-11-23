package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.world.BlockFlags;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.lighting.BlockOpacity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every block is to the native world: flags, fluid, face rules, and that a world accepts the table. */
class StonebreakBlockTableTest {

    @Test
    void fluidsAndFoliageDoNotStopSkyLight() {
        short[] flags = StonebreakBlockTable.flags();
        assertEquals(0, flags[BlockType.AIR.getId()]);
        assertEquals(0, flags[BlockType.WATER.getId()] & BlockFlags.SKY_OPAQUE, "the band must reach the seabed");
        assertEquals(0, flags[BlockType.LEAVES.getId()] & BlockFlags.SKY_OPAQUE);
        assertEquals(0, flags[BlockType.ICE.getId()] & BlockFlags.SKY_OPAQUE);
        for (BlockType t : BlockType.values()) {
            boolean sky = (flags[t.getId()] & BlockFlags.SKY_OPAQUE) != 0;
            assertEquals(BlockOpacity.isOpaque(t), sky, t.name());
        }
    }

    @Test
    void solidCubesAreOpaqueCubesAndShapesAreHostGeometry() {
        short[] flags = StonebreakBlockTable.flags();
        int stone = flags[BlockType.STONE.getId()];
        assertTrue((stone & BlockFlags.CUBE) != 0 && (stone & BlockFlags.OPAQUE) != 0
            && (stone & BlockFlags.VIS_OPAQUE) != 0);
        int ice = flags[BlockType.ICE.getId()];
        assertTrue((ice & BlockFlags.TRANSLUCENT) != 0 && (ice & BlockFlags.TRANSPARENT) != 0);
        for (BlockType shaped : new BlockType[]{BlockType.ROSE, BlockType.OAK_STAIRS, BlockType.SNOW,
                BlockType.CACTUS, BlockType.LIMESTONE_STALAGMITE, BlockType.TORCH_PLACED, BlockType.OAK_DOOR}) {
            int f = flags[shaped.getId()];
            assertEquals(0, f & BlockFlags.CUBE, shaped.name());
            assertTrue((f & BlockFlags.HOST_GEOMETRY) != 0, shaped.name());
        }
    }

    @Test
    void overridesWinOverTheHeadlessRule() {
        Boolean[] override = new Boolean[StonebreakBlockTable.maxId() + 1];
        override[BlockType.STONE.getId()] = Boolean.FALSE;
        short[] flags = StonebreakBlockTable.flags(override);
        assertTrue((flags[BlockType.STONE.getId()] & BlockFlags.HOST_GEOMETRY) != 0);
    }

    @Test
    void detailFlagOnlyMarksOpaqueCubes() {
        boolean[] detail = new boolean[StonebreakBlockTable.maxId() + 1];
        detail[BlockType.STONE.getId()] = true;   // opaque cube: gets DETAIL
        detail[BlockType.LEAVES.getId()] = true;  // transparent cube: never DETAIL
        detail[BlockType.ROSE.getId()] = true;    // host geometry: never DETAIL
        short[] flags = StonebreakBlockTable.flags(null, detail);
        int stone = flags[BlockType.STONE.getId()];
        assertTrue((stone & BlockFlags.DETAIL) != 0 && (stone & BlockFlags.CUBE) != 0 && (stone & BlockFlags.OPAQUE) != 0);
        assertEquals(0, flags[BlockType.LEAVES.getId()] & BlockFlags.DETAIL);
        assertEquals(0, flags[BlockType.ROSE.getId()] & BlockFlags.DETAIL);
        assertEquals(0, StonebreakBlockTable.flags()[BlockType.STONE.getId()] & BlockFlags.DETAIL, "headless: no DETAIL");
    }

    @Test
    void appliesToANativeWorld() {
        try (CubeWorld w = CubeWorld.create(-384, 640, BlockType.AIR.getId(), null)) {
            StonebreakBlockTable.apply(w);
            w.createCube(0, 2, 0, BlockType.AIR.getId());
            w.set(1, 70, 1, BlockType.STONE.getId());
            w.set(1, 71, 1, BlockType.WATER.getId());
            assertEquals(71, w.surfaceY(1, 1), "water is not sky-opaque: the surface is on the stone");
            assertEquals(72, w.topY(1, 1), "TOP counts the water");
            w.setLayer(VoxelLayers.WATER, 1, 71, 1, VoxelLayers.waterToRaw(3));
            assertEquals(3, VoxelLayers.waterToGame(w.getLayer(VoxelLayers.WATER, 1, 71, 1)));
        }
    }
}
