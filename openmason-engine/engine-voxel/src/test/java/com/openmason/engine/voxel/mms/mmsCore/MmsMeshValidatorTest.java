package com.openmason.engine.voxel.mms.mmsCore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate between mesh builders and the GPU upload path. Every check here answers a crash that
 * would otherwise surface as a driver fault or garbage geometry frames later: indices past the
 * vertex count, NaN positions, flag arrays out of step with the vertex count, index counts that
 * are not whole triangles. The disable switch must also genuinely disable — it is the production
 * fast path.
 */
class MmsMeshValidatorTest {

    @AfterEach
    void restoreValidation() {
        MmsMeshValidator.setValidationEnabled(true);
    }

    /** One well-formed triangle in SoA form. */
    private static MmsMeshData triangle() {
        return new MmsMeshData(
                new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0},
                new float[] {0, 0, 1, 0, 1, 1},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 0},
                new float[] {0, 0, 0},
                new float[] {0, 0, 0},
                new int[] {0, 1, 2}, 3);
    }

    @Test
    void aWellFormedTrianglePasses() {
        assertTrue(MmsMeshValidator.validate(triangle()).isValid());
    }

    @Test
    void anEmptyMeshIsValid() {
        assertTrue(MmsMeshValidator.validate(MmsMeshData.empty()).isValid());
    }

    @Test
    void aNullMeshIsNot() {
        assertFalse(MmsMeshValidator.validate(null).isValid());
    }

    @Test
    void anIndexPastTheLastVertexIsCaught() {
        MmsMeshValidator.ValidationResult result =
                MmsMeshValidator.validateIndexBounds(new int[] {0, 1, 3}, 3, 3);

        assertFalse(result.isValid(), "index 3 with 3 vertices reads past the buffer");
        assertTrue(MmsMeshValidator.validateIndexBounds(new int[] {0, 1, 2}, 3, 3).isValid());
    }

    @Test
    void aNegativeIndexIsCaught() {
        assertFalse(MmsMeshValidator.validateIndexBounds(new int[] {0, -1, 2}, 3, 3).isValid());
    }

    @Test
    void nonFinitePositionsAreCaught() {
        assertFalse(MmsMeshValidator.validateFiniteValues(
                new float[] {0, 0, 0, Float.NaN, 0, 0}).isValid());
        assertFalse(MmsMeshValidator.validateFiniteValues(
                new float[] {0, Float.POSITIVE_INFINITY, 0}).isValid());
        assertTrue(MmsMeshValidator.validateFiniteValues(new float[] {1, 2, 3}).isValid());
    }

    @Test
    void flagArraysMustMatchTheVertexCount() {
        MmsMeshValidator.ValidationResult result = MmsMeshValidator.validateArraySizes(
                new float[9], new float[6], new float[9],
                new float[2], // water flags for only two of three vertices
                new float[3],
                new int[3], 3);

        assertFalse(result.isValid());
    }

    @Test
    void anIndexCountThatIsNotWholeTrianglesIsCaught() {
        MmsMeshValidator.ValidationResult result = MmsMeshValidator.validateArraySizes(
                new float[9], new float[6], new float[9], new float[3], new float[3],
                new int[4], 4);

        assertFalse(result.isValid(), "4 indices cannot form whole triangles");
    }

    @Test
    void anIndexCountBeyondTheArrayIsCaught() {
        assertFalse(MmsMeshValidator.validateArraySizes(
                new float[9], new float[6], new float[9], new float[3], new float[3],
                new int[3], 6).isValid());
        assertFalse(MmsMeshValidator.validateArraySizes(
                new float[9], new float[6], new float[9], new float[3], new float[3],
                new int[3], -3).isValid());
    }

    @Test
    void memoryLimitsAreEnforced() {
        assertTrue(MmsMeshValidator.validateMemoryLimits(100, 300, 1_000_000L).isValid());
        assertFalse(MmsMeshValidator.validateMemoryLimits(1_000_000, 3_000_000, 1_000L).isValid());
    }

    @Test
    void theDisableSwitchSkipsEveryCheck() {
        MmsMeshValidator.setValidationEnabled(false);

        assertTrue(MmsMeshValidator.validateIndexBounds(new int[] {0, 1, 99}, 3, 3).isValid(),
                "disabled validation is the production fast path and must not spend the checks");
        assertTrue(MmsMeshValidator.validateFiniteValues(new float[] {Float.NaN}).isValid());
    }
}
