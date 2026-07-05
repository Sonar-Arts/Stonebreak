package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import imgui.ImVec2;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless state-machine tests for {@link ScaleToolController}.
 *
 * <p>Uses a stub {@link GenericModelRenderer} (CPU-only, constructor performs no GL)
 * with mesh index == unique index, identity camera matrices and a 200×100 viewport:
 * model (0,0,0) projects to screen (100, 50). Keyboard/right-mouse probes are
 * overridden so no ImGui context is required.
 */
class ScaleToolControllerTest {

    private static final float EPS = 1e-4f;

    /** Overridable input probes so tests run without an ImGui context. */
    private static class TestableScaleToolController extends ScaleToolController {
        boolean escPressed;
        boolean enterPressed;
        boolean rightClicked;

        @Override
        protected boolean isKeyPressed(int glfwKeyCode) {
            if (glfwKeyCode == GLFW.GLFW_KEY_ESCAPE) return escPressed;
            if (glfwKeyCode == GLFW.GLFW_KEY_ENTER) return enterPressed;
            return false;
        }

        @Override
        protected boolean isRightMouseClicked() {
            return rightClicked;
        }
    }

    /** CPU-only model renderer stub: mesh index == unique index, positions in a map. */
    private static class StubModelRenderer extends GenericModelRenderer {
        final Map<Integer, Vector3f> positions = new HashMap<>();

        @Override
        public Vector3f getUniqueVertexPosition(int uniqueIndex) {
            Vector3f pos = positions.get(uniqueIndex);
            return pos != null ? new Vector3f(pos) : null;
        }

        @Override
        public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
            return positions.containsKey(uniqueIndex) ? new int[]{uniqueIndex} : null;
        }

        @Override
        public int getUniqueIndexForMeshVertex(int meshIndex) {
            return positions.containsKey(meshIndex) ? meshIndex : -1;
        }

        @Override
        public Vector3f getVertexPosition(int meshIndex) {
            Vector3f pos = positions.get(meshIndex);
            return pos != null ? new Vector3f(pos) : null;
        }

        @Override
        public void updateVertexPosition(int meshIndex, Vector3f position) {
            positions.put(meshIndex, new Vector3f(position));
        }

        @Override
        public ModelPartManager getPartManager() {
            return null; // Skip part sync in headless tests
        }
    }

    private TestableScaleToolController controller;
    private StubModelRenderer modelRenderer;
    private VertexSelectionState vertexSelectionState;
    private EdgeSelectionState edgeSelectionState;
    private FaceSelectionState faceSelectionState;
    private ModelCommandHistory commandHistory;

    @BeforeEach
    void setUp() {
        controller = new TestableScaleToolController();
        modelRenderer = new StubModelRenderer();
        vertexSelectionState = new VertexSelectionState();
        edgeSelectionState = new EdgeSelectionState();
        faceSelectionState = new FaceSelectionState();
        commandHistory = new ModelCommandHistory();

        controller.setModelRenderer(modelRenderer);
        controller.setVertexSelectionState(vertexSelectionState);
        controller.setEdgeSelectionState(edgeSelectionState);
        controller.setFaceSelectionState(faceSelectionState);
        controller.setCommandHistory(commandHistory, new RendererSynchronizer(
            modelRenderer, null, vertexSelectionState, edgeSelectionState, faceSelectionState));

        EditModeManager.getInstance().setMode(EditMode.VERTEX);
    }

    @AfterEach
    void tearDown() {
        EditModeManager.getInstance().setMode(EditMode.NONE);
    }

    /** Two selected vertices symmetric about the origin (pivot = origin → screen (100, 50)). */
    private void selectSymmetricPair() {
        modelRenderer.positions.put(0, new Vector3f(-0.5f, 0, 0));
        modelRenderer.positions.put(1, new Vector3f(0.5f, 0, 0));
        vertexSelectionState.toggleVertex(0, new Vector3f(-0.5f, 0, 0));
        vertexSelectionState.toggleVertex(1, new Vector3f(0.5f, 0, 0));
    }

    private static InputContext context(float mouseX, float mouseY,
                                        boolean clicked, boolean released, boolean shift) {
        return new InputContext(
            mouseX, mouseY,
            true, true,
            clicked, false, released,
            0f, new ImVec2(0, 0),
            false, false, false,
            200, 100,
            new Matrix4f(), new Matrix4f(),
            shift, false, false);
    }

    // ── Arming preconditions ──────────────────────────────────────────────

    @Test
    void doesNotArmWithoutEditMode() {
        EditModeManager.getInstance().setMode(EditMode.NONE);
        selectSymmetricPair();

        controller.startOrConfirm();
        assertFalse(controller.isActive());
    }

    @Test
    void doesNotArmWithoutSelection() {
        controller.startOrConfirm();
        assertFalse(controller.isActive());
    }

    @Test
    void armsWithSelectionAndConsumesInput() {
        selectSymmetricPair();

        controller.startOrConfirm();
        assertTrue(controller.isActive());
        assertTrue(controller.handleInput(context(150, 50, false, false, false)));
    }

    @Test
    void deactivatesWhenSelectionCannotBeResolved() {
        // Selection exists but the model has no vertex positions for it
        vertexSelectionState.toggleVertex(42, new Vector3f());

        controller.startOrConfirm();
        assertTrue(controller.isActive());

        // First frame fails to establish a session and deactivates
        assertTrue(controller.handleInput(context(150, 50, false, false, false)));
        assertFalse(controller.isActive());
    }

    // ── Scale + confirm ───────────────────────────────────────────────────

    @Test
    void scalesAboutCentroidAndCommitsOnClick() {
        selectSymmetricPair();
        controller.startOrConfirm();

        // Frame 1: establish session; pivot (0,0,0) → screen (100,50); mouse at 50px → d0 = 50
        assertTrue(controller.handleInput(context(150, 50, false, false, false)));
        assertTrue(controller.isActive());

        // Frame 2: mouse at 100px from pivot → factor 2
        assertTrue(controller.handleInput(context(200, 50, false, false, false)));
        assertEquals(-1f, modelRenderer.positions.get(0).x, EPS);
        assertEquals(1f, modelRenderer.positions.get(1).x, EPS);

        // Frame 3: left click confirms → commit + deactivate
        assertTrue(controller.handleInput(context(200, 50, true, false, false)));
        assertFalse(controller.isActive());
        assertEquals(-1f, modelRenderer.positions.get(0).x, EPS);
        assertTrue(commandHistory.canUndo());
    }

    @Test
    void enterConfirms() {
        selectSymmetricPair();
        controller.startOrConfirm();
        controller.handleInput(context(150, 50, false, false, false));
        controller.handleInput(context(200, 50, false, false, false));

        controller.enterPressed = true;
        assertTrue(controller.handleInput(context(200, 50, false, false, false)));
        assertFalse(controller.isActive());
        assertTrue(commandHistory.canUndo());
    }

    @Test
    void sAgainConfirms() {
        selectSymmetricPair();
        controller.startOrConfirm();
        controller.handleInput(context(150, 50, false, false, false));
        controller.handleInput(context(200, 50, false, false, false));

        // S pressed again → startOrConfirm on an active tool commits
        controller.startOrConfirm();
        assertFalse(controller.isActive());
        assertTrue(commandHistory.canUndo());
        assertEquals(-1f, modelRenderer.positions.get(0).x, EPS);
    }

    @Test
    void confirmWithoutMovementRecordsNoUndoStep() {
        selectSymmetricPair();
        controller.startOrConfirm();
        controller.handleInput(context(150, 50, false, false, false));

        // Confirm at the initial mouse position (factor 1) → no-op commit
        controller.handleInput(context(150, 50, true, false, false));
        assertFalse(controller.isActive());
        assertFalse(commandHistory.canUndo());
        assertEquals(-0.5f, modelRenderer.positions.get(0).x, EPS);
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Test
    void escapeRevertsAndDeactivates() {
        selectSymmetricPair();
        controller.startOrConfirm();
        controller.handleInput(context(150, 50, false, false, false));
        controller.handleInput(context(200, 50, false, false, false));
        assertEquals(-1f, modelRenderer.positions.get(0).x, EPS);

        controller.escPressed = true;
        assertTrue(controller.handleInput(context(200, 50, false, false, false)));
        assertFalse(controller.isActive());
        assertEquals(-0.5f, modelRenderer.positions.get(0).x, EPS);
        assertEquals(0.5f, modelRenderer.positions.get(1).x, EPS);
        assertFalse(commandHistory.canUndo());
    }

    @Test
    void rightClickRevertsAndDeactivates() {
        selectSymmetricPair();
        controller.startOrConfirm();
        controller.handleInput(context(150, 50, false, false, false));
        controller.handleInput(context(200, 50, false, false, false));

        controller.rightClicked = true;
        assertTrue(controller.handleInput(context(200, 50, false, false, false)));
        assertFalse(controller.isActive());
        assertEquals(-0.5f, modelRenderer.positions.get(0).x, EPS);
    }

    @Test
    void editModeChangeCancels() {
        selectSymmetricPair();
        controller.startOrConfirm();
        assertTrue(controller.isActive());

        EditModeManager.getInstance().setMode(EditMode.EDGE);
        assertFalse(controller.handleInput(context(150, 50, false, false, false)));
        assertFalse(controller.isActive());
    }
}
