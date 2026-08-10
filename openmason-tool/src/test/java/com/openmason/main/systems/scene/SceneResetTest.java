package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelSource;
import com.openmason.engine.rendering.viewer.scene.OmoModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scene must not outlive the project it belongs to.
 *
 * <p>Its models are resolved against a project root, so carrying one into another project
 * leaves it referencing files that are not there. These pin both halves of the reset: the
 * document empties, and — less visibly — every model reference goes back to the cache, so
 * the GPU copies do not accumulate across a session's worth of project switches.
 */
class SceneResetTest {

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

    private record Fixture(SceneService service, ModelCache cache, List<ModelHandle> disposed) {}

    private static Fixture newFixture() {
        List<ModelHandle> disposed = new ArrayList<>();
        ModelCache cache = new ModelCache(new StubSource(), disposed::add);
        return new Fixture(new SceneService(cache), cache, disposed);
    }

    /** Registers a model the way opening or dropping one does, and places instances. */
    private static SceneModelRef addModel(Fixture f, String key, int instances) throws IOException {
        ModelHandle handle = f.cache().acquireBytes(key, new byte[]{1}, key);
        SceneModelRef ref = new SceneModelRef(key, key + ".omo", null, key + ".omo",
                new byte[]{1}, handle, ResolutionStatus.REFERENCED);
        f.service().getDocument().registerModel(ref);
        for (int i = 0; i < instances; i++) {
            f.service().placeInstance(ref, key + i, 0, 0, 0);
        }
        return ref;
    }

    @Test
    @DisplayName("clearing the scene empties the document")
    void clearEmptiesDocument() throws IOException {
        Fixture f = newFixture();
        addModel(f, "well", 3);
        f.service().getDocument().setCurrentScenePath("/proj/Scenes/town.omsc");

        f.service().clearCurrentScene();

        SceneDocument doc = f.service().getDocument();
        assertTrue(doc.instances().isEmpty());
        assertTrue(doc.models().isEmpty());
        assertFalse(doc.hasCurrentScene(), "the scene path must not survive into the next project");
        assertFalse(f.service().hasUnsavedChanges());
    }

    @Test
    @DisplayName("clearing releases every model back to the cache")
    void clearReleasesModels() throws IOException {
        // The leak this guards: a SceneModelRef holds an acquire-reference of its own,
        // separate from the ones its instances hold. Clearing only the instances would
        // pin the model — and its GPU textures — for the rest of the session.
        Fixture f = newFixture();
        addModel(f, "well", 2);
        addModel(f, "stall", 1);
        assertEquals(2, f.cache().size());

        f.service().clearCurrentScene();

        assertEquals(0, f.cache().size(), "no model may stay loaded after the scene is dropped");
        assertEquals(2, f.disposed().size(), "both models were freed");
    }

    @Test
    @DisplayName("starting a new scene releases the previous one's models")
    void newSceneReleasesPreviousModels() throws IOException {
        Fixture f = newFixture();
        addModel(f, "well", 1);

        f.service().newScene("Second");

        assertEquals(0, f.cache().size());
        assertEquals("Second", f.service().getCurrentSceneName());
        assertTrue(f.service().getDocument().instances().isEmpty());
    }

    @Test
    @DisplayName("repeated project switches do not accumulate loaded models")
    void repeatedSwitchesDoNotAccumulate() throws IOException {
        // The symptom the release exists to prevent: opening project after project would
        // otherwise grow the cache without bound.
        Fixture f = newFixture();

        for (int project = 0; project < 5; project++) {
            addModel(f, "model" + project, 2);
            assertEquals(1, f.cache().size(), "only the current project's model is loaded");
            f.service().clearCurrentScene();
        }

        assertEquals(0, f.cache().size());
        assertEquals(5, f.disposed().size());
    }

    @Test
    @DisplayName("clearing an already-empty scene is safe")
    void clearingEmptySceneIsSafe() {
        Fixture f = newFixture();

        f.service().clearCurrentScene();
        f.service().clearCurrentScene();

        assertEquals(0, f.cache().size());
        assertTrue(f.disposed().isEmpty());
    }

    @Test
    @DisplayName("the change callback fires on reset so the UI can drop its selection")
    void resetNotifiesListeners() throws IOException {
        Fixture f = newFixture();
        addModel(f, "well", 1);

        boolean[] notified = {false};
        f.service().setOnSceneChanged(() -> notified[0] = true);

        f.service().clearCurrentScene();

        assertTrue(notified[0], "a stale selection would otherwise point at a removed instance");
    }
}
