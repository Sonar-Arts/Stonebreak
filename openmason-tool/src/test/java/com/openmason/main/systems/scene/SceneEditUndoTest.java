package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import com.openmason.engine.rendering.viewer.scene.InstanceTransformTarget;
import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import com.openmason.engine.rendering.viewer.scene.ModelSource;
import com.openmason.engine.rendering.viewer.scene.OmoModelLoader;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every scene edit — not just a gizmo drag — must be one undo step, and undoing a delete
 * must bring back the <em>same</em> instance so older entries for it keep working.
 */
class SceneEditUndoTest {

    private static final class StubSource implements ModelSource {
        @Override
        public OmoModelLoader.Loaded load(Path path) {
            return new OmoModelLoader.Loaded(null, path.toString(), new int[0]);
        }

        @Override
        public OmoModelLoader.Loaded load(byte[] omoBytes, String displayName) {
            return new OmoModelLoader.Loaded(null, displayName, new int[0]);
        }
    }

    private static SceneService newService() {
        return new SceneService(new ModelCache(new StubSource(), handle -> { }));
    }

    private static ModelInstance placeInstance(SceneService service, String key) throws IOException {
        ModelCache cache = new ModelCache(new StubSource(), handle -> { });
        ModelHandle handle = cache.acquireBytes(key, new byte[]{1}, key);
        SceneModelRef ref = new SceneModelRef(key, key + ".omo", null, key + ".omo",
                new byte[]{1}, handle, ResolutionStatus.REFERENCED);
        service.getDocument().registerModel(ref);
        return service.placeInstance(ref, key, 0, 0, 0);
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("undoing a delete restores the same instance object, at its old index")
    void undoDeleteRestoresIdentityAndOrder() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance a = placeInstance(service, "a");
        ModelInstance b = placeInstance(service, "b");
        ModelInstance c = placeInstance(service, "c");

        ModelCommandHistory history = new ModelCommandHistory();
        SceneInstanceLifecycleCommand removal = SceneInstanceLifecycleCommand.removed(doc, b, "Delete b");
        removal.execute();
        history.pushCompleted(removal);

        assertNull(doc.scene().byId(b.id()));
        assertEquals(List.of(a, c), doc.instances());

        history.undo();
        assertSame(b, doc.scene().byId(b.id()), "same object, same id");
        assertEquals(List.of(a, b, c), doc.instances(), "restored at its original position");

        history.redo();
        assertEquals(List.of(a, c), doc.instances());
    }

    @Test
    @DisplayName("a transform entry recorded before a delete works again after undo-delete")
    void olderEntriesSurviveDeleteUndo() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance well = placeInstance(service, "well");

        ModelCommandHistory history = new ModelCommandHistory();
        well.transform().setPosition(5, 0, 0);
        history.pushCompleted(new SceneInstanceTransformCommand(well.id(), doc.scene()::byId, "Move",
                new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1),
                new Vector3f(5, 0, 0), new Vector3f(), new Vector3f(1, 1, 1)));

        SceneInstanceLifecycleCommand removal = SceneInstanceLifecycleCommand.removed(doc, well, "Delete");
        removal.execute();
        history.pushCompleted(removal);

        history.undo(); // delete
        history.undo(); // move
        assertEquals(0f, well.transform().getPositionX(), 1e-6f,
                "the move entry resolves the restored instance by id and undoes it");
    }

    @Test
    @DisplayName("a placement is undoable")
    void placementIsUndoable() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance well = placeInstance(service, "well");

        ModelCommandHistory history = new ModelCommandHistory();
        history.pushCompleted(SceneInstanceLifecycleCommand.added(doc, well, "Place"));

        history.undo();
        assertTrue(doc.instances().isEmpty());
        history.redo();
        assertSame(well, doc.scene().byId(well.id()));
    }

    // -------------------------------------------------------------- properties

    @Test
    @DisplayName("rename, visibility and lock are each one undo step")
    void propertyCommandsRoundTrip() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance well = placeInstance(service, "well");
        ModelCommandHistory history = new ModelCommandHistory();

        history.executeCommand(new SceneInstancePropertyCommand(well.id(), doc.scene()::byId,
                SceneInstancePropertyCommand.Property.NAME, "well", "Old Well"));
        history.executeCommand(new SceneInstancePropertyCommand(well.id(), doc.scene()::byId,
                SceneInstancePropertyCommand.Property.VISIBLE, true, false));
        history.executeCommand(new SceneInstancePropertyCommand(well.id(), doc.scene()::byId,
                SceneInstancePropertyCommand.Property.LOCKED, false, true));

        assertEquals("Old Well", well.name());
        assertFalse(well.isVisible());
        assertTrue(well.isLocked());

        history.undo();
        assertFalse(well.isLocked());
        history.undo();
        assertTrue(well.isVisible());
        history.undo();
        assertEquals("well", well.name());
        assertFalse(history.canUndo());
    }

    // -------------------------------------------------------------- composite

    @Test
    @DisplayName("a composite undoes its parts in reverse and redoes them in order")
    void compositeOrder() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance a = placeInstance(service, "a");
        ModelInstance b = placeInstance(service, "b");
        ModelCommandHistory history = new ModelCommandHistory();

        SceneInstanceLifecycleCommand ra = SceneInstanceLifecycleCommand.removed(doc, a, "Delete a");
        ra.execute();
        SceneInstanceLifecycleCommand rb = SceneInstanceLifecycleCommand.removed(doc, b, "Delete b");
        rb.execute();
        history.pushCompleted(new SceneCompositeCommand("Delete Instances", List.of(ra, rb)));

        assertTrue(doc.instances().isEmpty());
        history.undo();
        assertEquals(List.of(a, b), doc.instances(), "both back, original order");
        history.redo();
        assertTrue(doc.instances().isEmpty());
    }

    // ------------------------------------------------------------- group drag

    private static ModelScene sceneOf(int count) {
        ModelBounds bounds = new ModelBounds(new Vector3f(-0.5f), new Vector3f(0.5f),
                new Vector3f(), new Vector3f(1, 1, 1));
        ModelHandle handle = new ModelHandle("cube", null, "cube", null, new int[0], bounds);
        ModelScene scene = new ModelScene();
        for (int i = 0; i < count; i++) {
            scene.add(handle, "i" + i);
        }
        return scene;
    }

    @Test
    @DisplayName("followers move by the primary's translation delta, keeping the group's shape")
    void followersTranslateByDelta() {
        ModelScene scene = sceneOf(3);
        List<ModelInstance> all = scene.instances();
        all.get(1).transform().setPosition(2, 0, 0);
        all.get(2).transform().setPosition(0, 0, 3);

        InstanceTransformTarget target = new InstanceTransformTarget();
        target.setInstance(all.get(0));
        target.setFollowers(List.of(all.get(1), all.get(2)));

        target.beginDrag();
        target.setPosition(1, 1, 0);
        target.setPosition(1.5f, 1, 0); // a second frame: deltas are from drag START, not cumulative
        target.endDrag();

        assertEquals(3.5f, all.get(1).transform().getPositionX(), 1e-6f);
        assertEquals(1f, all.get(1).transform().getPositionY(), 1e-6f);
        assertEquals(1.5f, all.get(2).transform().getPositionX(), 1e-6f);
        assertEquals(3f, all.get(2).transform().getPositionZ(), 1e-6f);
    }

    @Test
    @DisplayName("followers scale by the primary's ratio and rotate by its delta; locked ones stay put")
    void followersScaleAndRotate() {
        ModelScene scene = sceneOf(3);
        List<ModelInstance> all = scene.instances();
        all.get(1).transform().setScale(2, 2, 2);
        all.get(2).setLocked(true);

        InstanceTransformTarget target = new InstanceTransformTarget();
        target.setInstance(all.get(0));
        target.setFollowers(all);   // primary and locked are filtered out
        assertEquals(1, target.followers().size());

        target.beginDrag();
        target.setScale(2, 1, 1);
        target.setRotation(0, 90, 0);
        target.endDrag();

        assertEquals(4f, all.get(1).transform().getScaleX(), 1e-6f);
        assertEquals(2f, all.get(1).transform().getScaleY(), 1e-6f);
        assertEquals(90f, all.get(1).transform().getRotationY(), 1e-6f);
        assertEquals(0f, all.get(2).transform().getRotationY(), 1e-6f, "locked follower untouched");
    }

    @Test
    @DisplayName("a group drag lands in the history as one entry that moves everyone back")
    void groupDragIsOneUndoEntry() throws IOException {
        SceneService service = newService();
        SceneDocument doc = service.getDocument();
        ModelInstance a = placeInstance(service, "a");
        ModelInstance b = placeInstance(service, "b");
        b.transform().setPosition(2, 0, 0);

        InstanceTransformTarget target = new InstanceTransformTarget();
        target.setInstance(a);
        target.setFollowers(List.of(b));
        ModelCommandHistory history = new ModelCommandHistory();
        SceneGizmoUndoBridge bridge = new SceneGizmoUndoBridge(history, target::instance,
                doc.scene()::byId, () -> { }, target::followerStarts);

        // Same order as the gizmo: beginDrag, writes, commit, THEN endDrag.
        target.beginDrag();
        target.setPosition(1, 0, 0);
        bridge.onTransformCommitted(TransformUndoSink.Mode.TRANSLATE,
                new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1),
                new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1, 1, 1));
        target.endDrag();

        assertEquals(3f, b.transform().getPositionX(), 1e-6f);
        history.undo();
        assertEquals(0f, a.transform().getPositionX(), 1e-6f);
        assertEquals(2f, b.transform().getPositionX(), 1e-6f, "the follower came back in the same step");
        assertFalse(history.canUndo(), "exactly one entry");
    }

    // ---------------------------------------------------------------- insert

    @Test
    @DisplayName("ModelScene.insert clamps the index and ignores an instance already present")
    void insertClampsAndDedupes() {
        ModelScene scene = sceneOf(2);
        List<ModelInstance> all = scene.instances();
        ModelInstance first = all.get(0);
        scene.remove(first);

        scene.insert(99, first);
        assertEquals(List.of(all.get(1), first), scene.instances());
        scene.insert(0, first);
        assertEquals(2, scene.size(), "no duplicate");
        assertNotNull(scene.byId(first.id()));
    }
}
