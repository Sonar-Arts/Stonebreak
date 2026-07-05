package com.openmason.engine.rendering.model.gmr.editable;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VertexWelderTest {

    @Test
    void cubeSoupWeldsTo8() {
        VertexWelder.WeldResult result = VertexWelder.weld(TestMeshes.cubeSoupVertices());
        assertEquals(24, result.soupToWelded().length);
        assertEquals(8, result.weldedCount());
    }

    @Test
    void coincidentWithinEpsilonMerge() {
        float nudge = GMRConstants.POSITION_EPSILON * 0.5f;
        float[] vertices = {
            0, 0, 0,
            nudge, 0, 0,      // within epsilon of the first
            1, 0, 0,
        };
        VertexWelder.WeldResult result = VertexWelder.weld(vertices);
        assertEquals(2, result.weldedCount());
        assertEquals(result.soupToWelded()[0], result.soupToWelded()[1]);
        assertNotEquals(result.soupToWelded()[0], result.soupToWelded()[2]);
    }

    @Test
    void justBeyondEpsilonStaysDistinct() {
        float apart = GMRConstants.POSITION_EPSILON * 3.0f;
        float[] vertices = {
            0, 0, 0,
            apart, 0, 0,
        };
        VertexWelder.WeldResult result = VertexWelder.weld(vertices, GMRConstants.POSITION_EPSILON);
        assertEquals(2, result.weldedCount());
    }

    @Test
    void representativePositionIsFirstOccurrence() {
        float nudge = GMRConstants.POSITION_EPSILON * 0.5f;
        float[] vertices = {
            1, 2, 3,
            1 + nudge, 2, 3,
        };
        VertexWelder.WeldResult result = VertexWelder.weld(vertices);
        assertEquals(1, result.weldedCount());
        assertEquals(1.0f, result.weldedPositions()[0], 0.0f);
    }

    @Test
    void negativeCoordinatesAcrossCellBoundaries() {
        // Positions straddling cell boundaries near the origin must still weld.
        float nudge = GMRConstants.POSITION_EPSILON * 0.9f;
        float[] vertices = {
            -nudge / 2, 0, 0,
             nudge / 2, 0, 0,
        };
        VertexWelder.WeldResult result = VertexWelder.weld(vertices);
        assertEquals(1, result.weldedCount());
    }

    @Test
    void invalidLengthThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> VertexWelder.weld(new float[]{1, 2}));
    }

    @Test
    void emptyInputYieldsEmptyResult() {
        VertexWelder.WeldResult result = VertexWelder.weld(new float[0]);
        assertEquals(0, result.weldedCount());
        assertEquals(0, result.soupToWelded().length);
    }
}
