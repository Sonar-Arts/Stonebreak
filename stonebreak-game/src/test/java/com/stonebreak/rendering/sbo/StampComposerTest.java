package com.stonebreak.rendering.sbo;

import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StampComposerTest {

    private static final int TOP = 0;
    private static final FaceStamp EMPTY = new FaceStamp(new float[0], new float[0], new float[0], new float[0], 0);

    /** Two triangles covering x in [x0, x1] of the top plane (y = +0.5), z over the whole cell. */
    private static FaceStamp topStrip(float x0, float x1) {
        float y = 0.5f;
        float[] pos = {x0, y, -0.5f, x1, y, -0.5f, x1, y, 0.5f, x0, y, -0.5f, x1, y, 0.5f, x0, y, 0.5f};
        float[] nrm = new float[18];
        for (int v = 0; v < 6; v++) nrm[v * 3 + 1] = 1f;
        return new FaceStamp(pos, nrm, new float[12], new float[6], 6);
    }

    private static BlockStamp withTop(FaceStamp top) {
        FaceStamp[] faces = new FaceStamp[6];
        FaceStamp[] interior = new FaceStamp[6];
        Arrays.fill(faces, EMPTY);
        Arrays.fill(interior, EMPTY);
        faces[TOP] = top;
        return new BlockStamp(faces, interior, new boolean[6]);
    }

    @Test
    void halvesOfAPlaneTogetherOcclude() {
        BlockStamp left = withTop(topStrip(-0.5f, 0f));
        BlockStamp right = withTop(topStrip(0f, 0.5f));
        assertFalse(StampComposer.compose(List.of(left)).occludesFace()[TOP]);

        BlockStamp both = StampComposer.compose(List.of(left, right));
        assertTrue(both.occludesFace()[TOP]);
        assertEquals(12, both.faces()[TOP].vertexCount());
        assertFalse(both.occludesFace()[1], "the bottom plane is still open");
    }

    @Test
    void nothingComposesToAnEmptyStamp() {
        BlockStamp none = StampComposer.compose(List.of());
        for (int f = 0; f < 6; f++) {
            assertEquals(0, none.faces()[f].vertexCount());
            assertFalse(none.occludesFace()[f]);
        }
        assertFalse(StampComposer.isUnitCube(none));
    }

    @Test
    void onlyAClosedShellWithNothingInsideIsAUnitCube() {
        boolean[] all = {true, true, true, true, true, true};
        FaceStamp[] faces = new FaceStamp[6];
        Arrays.fill(faces, topStrip(-0.5f, 0.5f)); // geometry is irrelevant to the check
        FaceStamp[] interior = new FaceStamp[6];
        Arrays.fill(interior, EMPTY);
        assertTrue(StampComposer.isUnitCube(new BlockStamp(faces, interior, all)));

        FaceStamp[] detailed = interior.clone();
        detailed[TOP] = topStrip(-0.1f, 0.1f);
        assertFalse(StampComposer.isUnitCube(new BlockStamp(faces, detailed, all)), "interior geometry");
    }
}
