package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelSource;
import com.openmason.engine.rendering.viewer.scene.OmoModelLoader;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene's undo history must never disagree with the scene it belongs to.
 *
 * <p>These pin the three ways they could drift: entries surviving a scene swap (undoing
 * into a scene they never belonged to), entries outliving their deleted instance (Ctrl+Z
 * steps that visibly do nothing), and a committed gizmo drag not marking the document
 * dirty (the move silently lost on close).
 */
class SceneUndoHistoryTest {

    /** Fabricates handles without parsing an OMO or touching GL. */
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

    /** Registers a stub model and places one instance of it. */
    private static ModelInstance placeInstance(SceneService service, String key) throws IOException {
        ModelCache cache = new ModelCache(new StubSource(), handle -> { });
        ModelHandle handle = cache.acquireBytes(key, new byte[]{1}, key);
        SceneModelRef ref = new SceneModelRef(key, key + ".omo", null, key + ".omo",
                new byte[]{1}, handle, ResolutionStatus.REFERENCED);
        service.getDocument().registerModel(ref);
        return service.placeInstance(ref, key, 0, 0, 0);
    }

    private static SceneGizmoUndoBridge bridgeFor(SceneService service, ModelInstance selected,
                                                  ModelCommandHistory history, boolean[] dirtyFlag) {
        return new SceneGizmoUndoBridge(
                history,
                () -> selected,
                service.getDocument().scene()::byId,
                () -> dirtyFlag[0] = true);
    }

    private static void commitDrag(SceneGizmoUndoBridge bridge, Vector3f from, Vector3f to) {
        Vector3f rot = new Vector3f();
        Vector3f scale = new Vector3f(1, 1, 1);
        bridge.onTransformCommitted(TransformUndoSink.Mode.TRANSLATE,
                from, rot, scale, to, rot, scale);
    }

    @Test
    @DisplayName("a committed gizmo drag pushes an undoable entry AND marks the scene dirty")
    void commitRecordsAndMarksDirty() throws IOException {
        SceneService service = newService();
        ModelInstance instance = placeInstance(service, "well");
        instance.transform().setPosition(5, 0, 0);
        service.getDocument().clearDirty(); // simulate a just-saved scene

        ModelCommandHistory history = new ModelCommandHistory();
        boolean[] dirty = {false};
        commitDrag(bridgeFor(service, instance, history, dirty),
                new Vector3f(0, 0, 0), new Vector3f(5, 0, 0));

        assertTrue(history.canUndo(), "the drag must be undoable");
        assertTrue(dirty[0], "a gizmo-only edit must not close without a save prompt");

        history.undo();
        assertEquals(0f, instance.transform().getPositionX(), 1e-6f);
        history.redo();
        assertEquals(5f, instance.transform().getPositionX(), 1e-6f);
    }

    @Test
    @DisplayName("no selected instance at commit time records nothing")
    void commitWithoutSelectionIsIgnored() throws IOException {
        SceneService service = newService();
        placeInstance(service, "well");

        ModelCommandHistory history = new ModelCommandHistory();
        boolean[] dirty = {false};
        commitDrag(bridgeFor(service, null, history, dirty),
                new Vector3f(), new Vector3f(5, 0, 0));

        assertFalse(history.canUndo());
        assertFalse(dirty[0]);
    }

    @Test
    @DisplayName("an entry whose instance left the scene degrades to a no-op, not a write into a detached transform")
    void staleEntryIsHarmless() throws IOException {
        SceneService service = newService();
        ModelInstance instance = placeInstance(service, "well");

        ModelCommandHistory history = new ModelCommandHistory();
        commitDrag(bridgeFor(service, instance, history, new boolean[1]),
                new Vector3f(), new Vector3f(5, 0, 0));

        service.getDocument().scene().remove(instance);
        instance.transform().setPosition(5, 0, 0);

        history.undo(); // must not touch the removed instance's transform
        assertEquals(5f, instance.transform().getPositionX(), 1e-6f,
                "the command resolves by id and finds nothing to write into");
    }

    @Test
    @DisplayName("removeIf purges a deleted instance's entries from both stacks")
    void purgeDropsEntriesFromBothStacks() throws IOException {
        SceneService service = newService();
        ModelInstance kept = placeInstance(service, "well");
        ModelInstance deleted = placeInstance(service, "stall");

        ModelCommandHistory history = new ModelCommandHistory();
        SceneGizmoUndoBridge keptBridge = bridgeFor(service, kept, history, new boolean[1]);
        SceneGizmoUndoBridge deletedBridge = bridgeFor(service, deleted, history, new boolean[1]);

        commitDrag(keptBridge, new Vector3f(), new Vector3f(1, 0, 0));
        commitDrag(deletedBridge, new Vector3f(), new Vector3f(2, 0, 0));
        commitDrag(deletedBridge, new Vector3f(2, 0, 0), new Vector3f(3, 0, 0));
        history.undo(); // one of the deleted instance's entries now sits on the redo stack

        String deletedId = deleted.id();
        history.removeIf(cmd ->
                cmd instanceof SceneInstanceTransformCommand c && deletedId.equals(c.instanceId()));

        assertFalse(history.canRedo(), "the redo-stack entry belonged to the deleted instance");
        assertTrue(history.canUndo(), "the surviving instance's entry stays");
        history.undo();
        assertEquals(0f, kept.transform().getPositionX(), 1e-6f);
        assertFalse(history.canUndo(), "nothing else may remain");
    }

    @Test
    @DisplayName("newScene and clearCurrentScene fire the scene-replaced hook; ordinary edits do not")
    void sceneReplacedFiresOnlyOnSwap() throws IOException {
        SceneService service = newService();
        int[] replaced = {0};
        service.setOnSceneReplaced(() -> replaced[0]++);

        placeInstance(service, "well");
        service.markDirty();
        assertEquals(0, replaced[0], "edits must not clear the undo history");

        service.newScene("Second");
        assertEquals(1, replaced[0]);

        service.clearCurrentScene();
        assertEquals(2, replaced[0]);
    }

    @Test
    @DisplayName("a failed openScene leaves the current scene — and its history — alone")
    void failedOpenDoesNotFireSceneReplaced() {
        SceneService service = newService();
        int[] replaced = {0};
        service.setOnSceneReplaced(() -> replaced[0]++);

        assertFalse(service.openScene("/definitely/not/there.omsc", null));
        assertEquals(0, replaced[0]);
    }
}
