package com.stonebreak.blocks.multipart;

import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampCache;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.sbo.MultipartStamps;
import com.stonebreak.rendering.sbo.StampComposer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped leaves' rounded shape is SBO data: a 64-row {@code multipart} table over
 * {@code full_cube} on all six sides, drawing one {@code Round_*} state per neighbour combination
 * that has a rounded edge and the plain cube otherwise. Pins the generated geometry to the
 * triangle counts of the procedural shape it replaced, so a re-export can't silently change it.
 */
class LeafMultipartShapeTest {

    /**
     * Triangles per combination, indexed by the EXPOSED-side mask (bit = MMS face: up, down,
     * north, south, east, west; exposed = neighbour is not a full cube). 0 = a plain cube.
     */
    private static final int[] TRIANGLES = {
            0, 0, 0, 0, 0, 36, 36, 52, 0, 36, 36, 52, 0, 52, 52, 76,
            0, 36, 36, 52, 36, 60, 60, 84, 36, 60, 60, 84, 52, 84, 84, 116,
            0, 36, 36, 52, 36, 60, 60, 84, 36, 60, 60, 84, 52, 84, 84, 116,
            0, 52, 52, 76, 52, 84, 84, 116, 52, 84, 84, 116, 76, 116, 116, 156};

    @Test
    void everyLeafRoundsExactlyWhereTwoExposedSidesMeet() throws Exception {
        String[][] leaves = {{"LEAVES", "SB_Leaves"}, {"PINE_LEAVES", "SB_Pine_Leaves"},
                {"ELM_LEAVES", "SB_Elm_Leaves"}, {"CYPRESS_LEAVES", "SB_Cypress_Leaves"},
                {"DRY_OAK_LEAVES", "SB_Dry_Oak_Leaves"}};
        for (String[] pair : leaves) {
            check(pair[0], pair[1]);
        }
    }

    private static void check(String blockName, String sbo) throws Exception {
        BlockType leaves = BlockType.valueOf(blockName);
        MultipartShape shape = MultipartRegistry.of(leaves);
        assertNotNull(shape, blockName + " must carry a multipart table");
        assertEquals(64, shape.maskCount(), "six full_cube atoms");
        assertTrue(!shape.hasBoxes(), "leaves keep their full-cube collision");

        SBOStampCache cache = new SBOStampCache();
        assertTrue(new SBOMeshProcessor().process(leaves,
                new SBOParser().parse(Path.of("src/main/resources/sbo/blocks/" + sbo + ".sbo")),
                (block, face) -> new float[]{0f, 0f, 1f, 1f}, (block, face) -> face, cache));
        MultipartStamps.register(cache, shape);

        for (int mask = 0; mask < 64; mask++) {
            int exposed = ~mask & 63; // a mask bit is set when that side's neighbour IS a full cube
            BlockStamp stamp = cache.get(leaves, shape.key(mask));
            String where = blockName + " exposed=" + Integer.toBinaryString(exposed);
            if (TRIANGLES[exposed] == 0) {
                assertTrue(StampComposer.isUnitCube(stamp), where + ": a plain cube");
                assertEquals("Cube", shape.states(mask).getFirst(), where);
            } else {
                assertTrue(!StampComposer.isUnitCube(stamp), where + ": rounded");
                assertEquals(TRIANGLES[exposed], triangles(stamp), where);
                assertInsideCell(stamp, where);
            }
        }
    }

    private static int triangles(BlockStamp stamp) {
        int vertices = 0;
        for (int f = 0; f < 6; f++) {
            vertices += stamp.faces()[f].vertexCount() + stamp.interior()[f].vertexCount();
            for (FaceStamp along : stamp.protruding()[f]) vertices += along.vertexCount();
        }
        return vertices / 3;
    }

    private static void assertInsideCell(BlockStamp stamp, String where) {
        for (int f = 0; f < 6; f++) {
            for (FaceStamp part : new FaceStamp[]{stamp.faces()[f], stamp.interior()[f]}) {
                float[] p = part.positions();
                for (float c : p) {
                    assertTrue(c >= -0.5f - 1e-5f && c <= 0.5f + 1e-5f, where + ": vertex outside the cell");
                }
            }
            for (FaceStamp along : stamp.protruding()[f]) {
                assertEquals(0, along.vertexCount(), where + ": nothing pokes into a neighbour");
            }
        }
    }
}
