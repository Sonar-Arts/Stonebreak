package com.openmason.engine.rendering.viewer.transform;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression guard for making {@code TransformState}'s clamping policy a parameter.
 *
 * <p>The ±10 grid extent and the [0.1, 3.0] scale range were hardcoded constants and
 * are load-bearing model-editor behaviour: they are what stops the edited model being
 * dragged off-screen. The values asserted here are transcribed from the pre-refactor
 * source, so the no-arg constructor must keep reproducing them exactly.
 */
class TransformStateLimitsTest {

    private static final float EPS = 1e-5f;

    @Test
    @DisplayName("default constructor keeps the editor's historical position clamp of +/-10")
    void defaultClampsPositionToGrid() {
        TransformState t = new TransformState();

        t.setPosition(50.0f, -50.0f, 3.5f);

        assertEquals(10.0f, t.getPositionX(), EPS);
        assertEquals(-10.0f, t.getPositionY(), EPS);
        assertEquals(3.5f, t.getPositionZ(), EPS, "in-range values pass through untouched");
    }

    @Test
    @DisplayName("default constructor keeps the editor's historical scale clamp of [0.1, 3.0]")
    void defaultClampsScale() {
        TransformState t = new TransformState();

        t.setScale(99.0f);
        assertEquals(3.0f, t.getScaleX(), EPS);

        t.setScale(0.0001f);
        assertEquals(0.1f, t.getScaleX(), EPS);

        t.setScale(2.0f, 0.05f, 7.0f);
        assertEquals(2.0f, t.getScaleX(), EPS);
        assertEquals(0.1f, t.getScaleY(), EPS);
        assertEquals(3.0f, t.getScaleZ(), EPS);
    }

    @Test
    @DisplayName("the snapping overload clamps after snapping, exactly as before")
    void snappingOverloadStillClamps() {
        TransformState t = new TransformState();

        // Snap to 0.5 then clamp: 12.3 -> 12.5 -> 10.0
        t.setPosition(12.3f, 1.26f, -0.24f, true, 0.5f);

        assertEquals(10.0f, t.getPositionX(), EPS);
        assertEquals(1.5f, t.getPositionY(), EPS);
        assertEquals(-0.0f, t.getPositionZ(), EPS);
    }

    @Test
    @DisplayName("UNBOUNDED applies no clamping, so scene instances can be placed anywhere")
    void unboundedDoesNotClamp() {
        TransformState t = new TransformState(TransformLimits.UNBOUNDED);

        t.setPosition(1234.5f, -987.0f, 0.25f);
        assertEquals(1234.5f, t.getPositionX(), EPS);
        assertEquals(-987.0f, t.getPositionY(), EPS);

        t.setScale(42.0f);
        assertEquals(42.0f, t.getScaleX(), EPS);

        // Negative scale (a mirrored instance) survives — the editor default cannot
        // express this because its minimum scale is positive.
        t.setScale(-1.0f, 1.0f, 1.0f);
        assertEquals(-1.0f, t.getScaleX(), EPS);
    }

    @Test
    @DisplayName("getTransformMatrix returns a defensive copy")
    void transformMatrixIsDefensivelyCopied() {
        TransformState t = new TransformState();
        t.setPosition(1.0f, 2.0f, 3.0f);

        Matrix4f first = t.getTransformMatrix();
        Matrix4f second = t.getTransformMatrix();
        assertNotSame(first, second, "callers must not be able to mutate the cached matrix");

        first.translate(100.0f, 0.0f, 0.0f);
        Vector3f translation = t.getTransformMatrix().getTranslation(new Vector3f());
        assertEquals(1.0f, translation.x, EPS, "mutating the returned copy must not affect the state");
    }

    @Test
    @DisplayName("transform matrix composes as translate * rotateXYZ * scale")
    void transformMatrixComposition() {
        TransformState t = new TransformState();
        t.setPosition(1.0f, 2.0f, 3.0f);
        t.setRotation(0.0f, 90.0f, 0.0f);
        t.setScale(2.0f);

        Matrix4f expected = new Matrix4f()
                .translate(1.0f, 2.0f, 3.0f)
                .rotateXYZ(0.0f, (float) Math.toRadians(90.0), 0.0f)
                .scale(2.0f, 2.0f, 2.0f);

        assertEquals(expected, t.getTransformMatrix());
    }

    @Test
    @DisplayName("reset restores defaults but preserves the limits and the gizmo flag")
    void resetPreservesLimitsAndGizmoFlag() {
        TransformState t = new TransformState(TransformLimits.UNBOUNDED);
        t.setGizmoEnabled(true);
        t.setPosition(500.0f, 0.0f, 0.0f);

        t.reset();

        assertEquals(0.0f, t.getPositionX(), EPS);
        assertEquals(1.0f, t.getScaleX(), EPS);
        assertEquals(TransformLimits.UNBOUNDED, t.getLimits());
        // gizmoEnabled is deliberately NOT reset — it stays in sync with GizmoState.
        assertEquals(true, t.isGizmoEnabled());
    }

    @Test
    @DisplayName("an inverted scale range is rejected at construction")
    void invertedScaleRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TransformLimits(1.0f, 5.0f, 2.0f));
    }

    @Test
    @DisplayName("a negative position extent is rejected at construction")
    void negativePositionExtentRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TransformLimits(-1.0f, 0.1f, 3.0f));
    }
}
