package com.openmason.engine.rendering.viewer.picking;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ray-vs-instance picking. Pure JOML — no GL, no camera object.
 */
class ScenePickerTest {

    private final ScenePicker picker = new ScenePicker();

    private static ModelHandle unitCube() {
        ModelBounds bounds = new ModelBounds(
                new Vector3f(-0.5f, -0.5f, -0.5f),
                new Vector3f(0.5f, 0.5f, 0.5f),
                new Vector3f(0, 0, 0),
                new Vector3f(1, 1, 1));
        return new ModelHandle("cube", null, "cube", null, new int[0], bounds);
    }

    private static CoordinateSystem.Ray ray(Vector3f origin, Vector3f direction) {
        return new CoordinateSystem.Ray(origin, direction);
    }

    @Test
    @DisplayName("picks the instance under the ray")
    void picksInstanceUnderRay() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");

        Optional<PickResult> hit = picker.pick(scene,
                ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1)));

        assertTrue(hit.isPresent());
        assertSame(cube, hit.get().instance());
        assertEquals(9.5f, hit.get().distance(), 1e-3f);
    }

    @Test
    @DisplayName("a ray that misses everything returns empty")
    void missReturnsEmpty() {
        ModelScene scene = new ModelScene();
        scene.add(unitCube(), "A");

        assertTrue(picker.pick(scene, ray(new Vector3f(50, 0, -10), new Vector3f(0, 0, 1))).isEmpty());
    }

    @Test
    @DisplayName("the nearest of several overlapping instances wins")
    void nearestWins() {
        ModelScene scene = new ModelScene();
        ModelHandle cube = unitCube();
        ModelInstance far = scene.add(cube, "far", new Vector3f(0, 0, 10));
        ModelInstance near = scene.add(cube, "near", new Vector3f(0, 0, 2));

        Optional<PickResult> hit = picker.pick(scene,
                ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1)));

        assertTrue(hit.isPresent());
        assertSame(near, hit.get().instance(), "must not depend on insertion order");
        assertEquals("near", hit.get().instance().name());
        assertTrue(hit.get().distance() < 12.0f);
        assertSame(cube, far.model());
    }

    @Test
    @DisplayName("hidden instances are not pickable")
    void hiddenInstanceSkipped() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");
        cube.setVisible(false);

        assertTrue(picker.pick(scene, ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1))).isEmpty());
    }

    @Test
    @DisplayName("locked instances are not pickable")
    void lockedInstanceSkipped() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");
        cube.setLocked(true);

        assertTrue(picker.pick(scene, ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1))).isEmpty());
    }

    @Test
    @DisplayName("picking respects a translated instance")
    void picksTranslatedInstance() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A", new Vector3f(20, 0, 0));

        // Aimed at the origin: misses. Aimed at x=20: hits.
        assertTrue(picker.pick(scene, ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1))).isEmpty());

        Optional<PickResult> hit = picker.pick(scene,
                ray(new Vector3f(20, 0, -10), new Vector3f(0, 0, 1)));
        assertTrue(hit.isPresent());
        assertSame(cube, hit.get().instance());
    }

    @Test
    @DisplayName("picking respects a scaled instance's larger silhouette")
    void picksScaledInstance() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");

        // x=2 is outside the unit cube but inside a 10x one.
        assertTrue(picker.pick(scene, ray(new Vector3f(2, 0, -10), new Vector3f(0, 0, 1))).isEmpty());

        cube.transform().setScale(10.0f);
        assertTrue(picker.pick(scene, ray(new Vector3f(2, 0, -10), new Vector3f(0, 0, 1))).isPresent());
    }

    @Test
    @DisplayName("a scaled instance reports a world-space distance")
    void scaledInstanceDistanceIsWorldSpace() {
        // The ray is transformed into local space, where a 10x scale shrinks it; the
        // returned distance must be converted back or instances become incomparable.
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");
        cube.transform().setScale(10.0f);

        Optional<PickResult> hit = picker.pick(scene,
                ray(new Vector3f(0, 0, -10), new Vector3f(0, 0, 1)));

        assertTrue(hit.isPresent());
        assertEquals(5.0f, hit.get().distance(), 1e-3f, "front face of a 10-wide cube sits 5 units away");
    }

    @Test
    @DisplayName("picking respects a rotated instance")
    void picksRotatedInstance() {
        ModelScene scene = new ModelScene();
        ModelInstance cube = scene.add(unitCube(), "A");
        cube.transform().setRotation(0, 45, 0);

        // A corner of the rotated cube reaches out to ~0.707 on X, past the unrotated 0.5.
        Optional<PickResult> hit = picker.pick(scene,
                ray(new Vector3f(0.6f, 0, -10), new Vector3f(0, 0, 1)));

        assertTrue(hit.isPresent(), "the rotated silhouette must be pickable");
    }

    @Test
    @DisplayName("an empty scene and a null ray are handled")
    void emptyAndNullInputs() {
        assertTrue(picker.pick(new ModelScene(), ray(new Vector3f(), new Vector3f(0, 0, 1))).isEmpty());
        assertTrue(picker.pick(null, ray(new Vector3f(), new Vector3f(0, 0, 1))).isEmpty());
        assertTrue(picker.pick(new ModelScene(), null).isEmpty());
    }
}
