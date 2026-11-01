package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.model.ModelBounds;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene composition and per-instance world bounds — all pure math, no GL.
 */
class ModelSceneTest {

    private static final float EPS = 1e-4f;

    /** Unit cube centred on the origin. */
    private static ModelHandle unitCube(String key) {
        ModelBounds bounds = new ModelBounds(
                new Vector3f(-0.5f, -0.5f, -0.5f),
                new Vector3f(0.5f, 0.5f, 0.5f),
                new Vector3f(0, 0, 0),
                new Vector3f(1, 1, 1));
        return new ModelHandle(key, null, key, null, new int[0], bounds);
    }

    @Test
    @DisplayName("instances do not own cache references — only the instance count changes")
    void instancesDoNotOwnCacheReferences() {
        // Cache lifetime belongs to whoever acquired the model. A container that
        // decremented the handle itself would move the counter behind the cache's back,
        // leaving entries that can never be evicted.
        ModelScene scene = new ModelScene();
        ModelHandle cube = unitCube("cube");

        ModelInstance a = scene.add(cube, "A");
        ModelInstance b = scene.add(cube, "B");
        assertEquals(0, cube.refCount(), "adding an instance takes no cache reference");
        assertEquals(2, scene.instanceCountOf(cube));

        scene.remove(a);
        assertEquals(1, scene.instanceCountOf(cube));

        scene.remove(b);
        assertEquals(0, cube.refCount());
        assertTrue(scene.isEmpty());
    }

    @Test
    @DisplayName("clear empties the scene without touching cache references")
    void clearEmptiesWithoutReleasing() {
        ModelScene scene = new ModelScene();
        ModelHandle cube = unitCube("cube");
        scene.add(cube, "A");
        scene.add(cube, "B");
        scene.add(cube, "C");

        scene.clear();

        assertEquals(0, scene.size());
        assertEquals(0, cube.refCount(), "the cache reference is the acquirer's to release");
    }

    @Test
    @DisplayName("instances get distinct ids and are findable by id")
    void instancesHaveDistinctIds() {
        ModelScene scene = new ModelScene();
        ModelHandle cube = unitCube("cube");

        ModelInstance a = scene.add(cube, "A");
        ModelInstance b = scene.add(cube, "B");

        assertFalse(a.id().equals(b.id()));
        assertSame(a, scene.byId(a.id()));
        assertNull(scene.byId("nope"));
    }

    @Test
    @DisplayName("a translated instance's world bounds follow it")
    void worldBoundsFollowTranslation() {
        ModelScene scene = new ModelScene();
        ModelInstance instance = scene.add(unitCube("cube"), "A", new Vector3f(10, 0, -5));

        ModelBounds b = instance.worldBounds();

        assertEquals(9.5f, b.min().x, EPS);
        assertEquals(10.5f, b.max().x, EPS);
        assertEquals(-5.5f, b.min().z, EPS);
        assertEquals(10.0f, b.center().x, EPS);
    }

    @Test
    @DisplayName("a rotated instance's world AABB grows to contain it")
    void worldBoundsInflateUnderRotation() {
        // 45 degrees about Y: the unit cube's footprint widens to sqrt(2). Transforming
        // min/max directly would wrongly keep it at 1.0 and make picking miss the corners.
        ModelScene scene = new ModelScene();
        ModelInstance instance = scene.add(unitCube("cube"), "A");
        instance.transform().setRotation(0, 45, 0);

        ModelBounds b = instance.worldBounds();

        float expectedHalf = (float) (Math.sqrt(2.0) / 2.0);
        assertEquals(expectedHalf, b.max().x, 1e-3f);
        assertEquals(0.5f, b.max().y, EPS, "the rotation axis is unaffected");
    }

    @Test
    @DisplayName("a scaled instance's world bounds scale with it")
    void worldBoundsFollowScale() {
        ModelScene scene = new ModelScene();
        ModelInstance instance = scene.add(unitCube("cube"), "A");
        instance.transform().setScale(4.0f);

        ModelBounds b = instance.worldBounds();

        assertEquals(2.0f, b.max().x, EPS, "scene instances are unbounded, so 4x is allowed");
    }

    @Test
    @DisplayName("scene bounds combine visible instances and ignore hidden ones")
    void sceneBoundsIgnoreHidden() {
        ModelScene scene = new ModelScene();
        ModelHandle cube = unitCube("cube");
        scene.add(cube, "near", new Vector3f(0, 0, 0));
        ModelInstance far = scene.add(cube, "far", new Vector3f(100, 0, 0));

        assertEquals(100.5f, scene.worldBounds().max().x, EPS);

        far.setVisible(false);
        assertEquals(0.5f, scene.worldBounds().max().x, EPS);
    }

    @Test
    @DisplayName("an empty scene reports empty bounds rather than an inverted box")
    void emptySceneBounds() {
        assertEquals(ModelBounds.EMPTY, new ModelScene().worldBounds());
    }

    @Test
    @DisplayName("scene instances are not clamped to the editor's grid")
    void sceneInstancesAreUnbounded() {
        // The model editor confines its model to +/-10; a scene must not inherit that.
        ModelScene scene = new ModelScene();
        ModelInstance instance = scene.add(unitCube("cube"), "A");

        instance.transform().setPosition(500, -300, 42);

        assertEquals(500.0f, instance.transform().getPositionX(), EPS);
        assertEquals(-300.0f, instance.transform().getPositionY(), EPS);
    }
}
