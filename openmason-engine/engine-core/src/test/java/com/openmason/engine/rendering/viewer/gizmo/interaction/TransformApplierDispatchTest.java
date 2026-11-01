package com.openmason.engine.rendering.viewer.gizmo.interaction;

import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression net for replacing the gizmo's {@code instanceof PartTransformTarget}
 * downcasts with {@link ITransformTarget} default methods.
 *
 * <p>{@link TransformApplier} routes a drag one of three ways, and the routing was
 * previously expressed as a type test. These cases pin the behaviour that type test
 * produced, so the interface-based version cannot quietly change it:
 *
 * <ol>
 *   <li>an <b>active</b> target absorbs the transform directly;</li>
 *   <li>an <b>inactive</b> target that supports group fallback absorbs it as a group
 *       move — expressed as a <em>delta</em>, not an absolute position;</li>
 *   <li>otherwise it falls through to the viewport's model {@link TransformState}.</li>
 * </ol>
 *
 * <p>Uses a hand-written stub rather than a mock: the tool module has only
 * junit-jupiter on the test classpath (no Mockito, no AssertJ).
 */
class TransformApplierDispatchTest {

    private static final float EPS = 1e-5f;

    /**
     * Records which interface methods the applier reached for, so a test can assert the
     * route taken rather than only the end value.
     */
    private static final class StubTarget implements ITransformTarget {
        private final boolean groupFallback;
        private final Vector3f position = new Vector3f();
        private final Vector3f rotation = new Vector3f();
        private final Vector3f scale = new Vector3f(1, 1, 1);

        Vector3f groupDelta;
        Vector3f groupRotation;
        Vector3f groupScale;
        boolean directPositionCalled;
        boolean snapRequested;
        float snapIncrementSeen;

        StubTarget(boolean groupFallback) {
            this.groupFallback = groupFallback;
        }

        @Override public Vector3f getPosition() { return new Vector3f(position); }
        @Override public Vector3f getRotation() { return new Vector3f(rotation); }
        @Override public Vector3f getScale() { return new Vector3f(scale); }

        @Override public void setPosition(float x, float y, float z) {
            position.set(x, y, z);
            directPositionCalled = true;
        }

        @Override public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
            position.set(x, y, z);
            directPositionCalled = true;
            snapRequested = snap;
            snapIncrementSeen = snapIncrement;
        }

        @Override public void setRotation(float x, float y, float z) { rotation.set(x, y, z); }
        @Override public void setScale(float x, float y, float z) { scale.set(x, y, z); }
        @Override public Vector3f getWorldCenter() { return new Vector3f(position); }
        @Override public boolean isActive() { return true; }
        @Override public String getTargetName() { return "Stub"; }

        @Override public boolean supportsGroupFallback() { return groupFallback; }
        @Override public void applyGroupTranslationDelta(Vector3f delta) { groupDelta = new Vector3f(delta); }
        @Override public void applyGroupRotation(Vector3f r) { groupRotation = new Vector3f(r); }
        @Override public void applyGroupScale(float x, float y, float z) { groupScale = new Vector3f(x, y, z); }
    }

    // ---------- route 1: an active target takes the transform directly ----------

    @Test
    @DisplayName("an active target receives the absolute position, not a delta")
    void activeTargetTakesAbsolutePosition() {
        TransformState model = new TransformState();
        TransformApplier applier = new TransformApplier(model);
        StubTarget active = new StubTarget(false);
        StubTarget configured = new StubTarget(true);

        applier.applyPosition(new Vector3f(2, 3, 4), active, configured, new Vector3f(1, 1, 1), null);

        assertEquals(new Vector3f(2, 3, 4), active.getPosition());
        assertTrue(active.directPositionCalled);
        assertEquals(null, configured.groupDelta, "the configured target must not also be moved");
        assertEquals(0.0f, model.getPositionX(), EPS, "the model transform must be untouched");
    }

    @Test
    @DisplayName("an active target takes rotation and scale directly too")
    void activeTargetTakesRotationAndScale() {
        TransformApplier applier = new TransformApplier(new TransformState());
        StubTarget active = new StubTarget(false);
        StubTarget configured = new StubTarget(true);

        applier.applyRotation(new Vector3f(10, 20, 30), active, configured);
        applier.applyScale(2, 3, 4, active, configured);

        assertEquals(new Vector3f(10, 20, 30), active.getRotation());
        assertEquals(new Vector3f(2, 3, 4), active.getScale());
        assertEquals(null, configured.groupRotation);
        assertEquals(null, configured.groupScale);
    }

    // ---------- route 2: inactive + group fallback becomes a group move ----------

    @Test
    @DisplayName("with no active target, a group-capable target gets a DELTA, not the absolute position")
    void groupFallbackReceivesDelta() {
        // The delta is what keeps a multi-part drag cohesive; handing over the absolute
        // position would collapse every member onto the same point.
        TransformState model = new TransformState();
        TransformApplier applier = new TransformApplier(model);
        StubTarget configured = new StubTarget(true);

        applier.applyPosition(new Vector3f(5, 5, 5), null, configured, new Vector3f(1, 2, 3), null);

        assertEquals(new Vector3f(4, 3, 2), configured.groupDelta);
        assertFalse(configured.directPositionCalled, "group moves must not go through setPosition");
        assertEquals(0.0f, model.getPositionX(), EPS, "the model transform must be untouched");
    }

    @Test
    @DisplayName("group rotation and scale route to the group methods")
    void groupFallbackRotationAndScale() {
        TransformState model = new TransformState();
        TransformApplier applier = new TransformApplier(model);
        StubTarget configured = new StubTarget(true);

        applier.applyRotation(new Vector3f(0, 90, 0), null, configured);
        applier.applyScale(1.5f, 1.5f, 1.5f, null, configured);

        assertEquals(new Vector3f(0, 90, 0), configured.groupRotation);
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), configured.groupScale);
        assertEquals(0.0f, model.getRotationY(), EPS);
    }

    // ---------- route 3: fall through to the model transform ----------

    @Test
    @DisplayName("a target that does not support group fallback falls through to the model transform")
    void nonGroupTargetFallsThroughToModel() {
        TransformState model = new TransformState();
        TransformApplier applier = new TransformApplier(model);
        StubTarget configured = new StubTarget(false);

        applier.applyPosition(new Vector3f(4, 5, 6), null, configured, new Vector3f(0, 0, 0), null);
        applier.applyRotation(new Vector3f(0, 45, 0), null, configured);
        applier.applyScale(2, 2, 2, null, configured);

        assertEquals(4.0f, model.getPositionX(), EPS);
        assertEquals(5.0f, model.getPositionY(), EPS);
        assertEquals(45.0f, model.getRotationY(), EPS);
        assertEquals(2.0f, model.getScaleX(), EPS);
        assertEquals(null, configured.groupDelta);
        assertFalse(configured.directPositionCalled);
    }

    @Test
    @DisplayName("a null configured target falls through to the model transform")
    void nullTargetFallsThroughToModel() {
        // The old code relied on `instanceof` being false for null; the explicit null
        // check must preserve that.
        TransformState model = new TransformState();
        TransformApplier applier = new TransformApplier(model);

        applier.applyPosition(new Vector3f(1, 2, 3), null, null, new Vector3f(), null);
        applier.applyRotation(new Vector3f(0, 0, 15), null, null);
        applier.applyScale(0.5f, 0.5f, 0.5f, null, null);

        assertEquals(1.0f, model.getPositionX(), EPS);
        assertEquals(15.0f, model.getRotationZ(), EPS);
        assertEquals(0.5f, model.getScaleX(), EPS);
    }

    // ---------- interface defaults ----------

    @Test
    @DisplayName("the interface defaults preserve pre-refactor behaviour for non-part targets")
    void interfaceDefaultsMatchOldFallthrough() {
        // Bone / attachment / model targets previously fell through every
        // `instanceof PartTransformTarget` check. The defaults must reproduce that:
        // never locked, no-op drag lifecycle, no group fallback.
        ITransformTarget plain = new StubTarget(false);

        assertFalse(plain.isLocked());
        assertFalse(plain.supportsGroupFallback());
        plain.beginDrag();       // must not throw
        plain.endDrag();         // must not throw
        plain.beginGroupDrag();  // must not throw
    }
}
