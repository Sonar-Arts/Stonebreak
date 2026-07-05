package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import imgui.ImVec2;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless state-machine tests for {@link BoxSelectController}.
 *
 * <p>Identity camera matrices with a 200×100 viewport: model (x, y, 0) projects
 * to screen ((x + 1) * 100, (1 − y) * 50) — so model (0,0,0) is screen (100, 50).
 * The keyboard probe is overridden so no ImGui context is required.
 */
class BoxSelectControllerTest {

    /** Overridable Esc probe so tests run without an ImGui context. */
    private static class TestableBoxSelectController extends BoxSelectController {
        boolean escPressed;

        @Override
        protected boolean isKeyPressed(int glfwKeyCode) {
            return glfwKeyCode == GLFW.GLFW_KEY_ESCAPE && escPressed;
        }
    }

    /** CPU-only vertex renderer stub with fixed positions (GL paths overridden). */
    private static class StubVertexRenderer extends VertexRenderer {
        // Screen positions (identity MVP, 200×100): v0 (100,50), v1 (180,10), v2 (10,50)
        private final float[] positions = {
            0.0f, 0.0f, 0.0f,
            0.8f, 0.8f, 0.0f,
            -0.9f, 0.0f, 0.0f
        };
        Set<Integer> lastSelectionSet;
        boolean selectionCleared;

        @Override
        public float[] getAllVertexPositions() {
            return positions;
        }

        @Override
        public int getVertexCount() {
            return 3;
        }

        @Override
        public Vector3f getVertexPosition(int index) {
            if (index < 0 || index >= 3) {
                return null;
            }
            return new Vector3f(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
        }

        @Override
        public void clearSelection() {
            selectionCleared = true;
        }

        @Override
        public void updateSelectionSet(Set<Integer> indices) {
            lastSelectionSet = new LinkedHashSet<>(indices);
        }
    }

    private TestableBoxSelectController controller;
    private StubVertexRenderer vertexRenderer;
    private VertexSelectionState vertexSelectionState;

    @BeforeEach
    void setUp() {
        controller = new TestableBoxSelectController();
        vertexRenderer = new StubVertexRenderer();
        vertexSelectionState = new VertexSelectionState();

        controller.setVertexRenderer(vertexRenderer);
        controller.setVertexSelectionState(vertexSelectionState);

        EditModeManager.getInstance().setMode(EditMode.VERTEX);
    }

    @AfterEach
    void tearDown() {
        EditModeManager.getInstance().setMode(EditMode.NONE);
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

    // ── Arming ────────────────────────────────────────────────────────────

    @Test
    void doesNotArmWithoutEditMode() {
        EditModeManager.getInstance().setMode(EditMode.NONE);

        controller.toggle();
        assertFalse(controller.isActive());
    }

    @Test
    void togglesOnAndOff() {
        controller.toggle();
        assertTrue(controller.isActive());

        controller.toggle();
        assertFalse(controller.isActive());
    }

    @Test
    void armedConsumesAllInputWithoutRect() {
        controller.toggle();

        assertTrue(controller.handleInput(context(50, 50, false, false, false)));
        assertNull(controller.getActiveRect());
        assertTrue(controller.isActive());
    }

    @Test
    void inactiveDoesNotConsumeInput() {
        assertFalse(controller.handleInput(context(50, 50, false, false, false)));
    }

    // ── Rect tracking ─────────────────────────────────────────────────────

    @Test
    void pressStartsRectAndDragNormalizesAllDirections() {
        controller.toggle();

        // Press at (60, 60)
        assertTrue(controller.handleInput(context(60, 60, true, false, false)));
        assertNotNull(controller.getActiveRect());

        // Drag up-left to (20, 30) → rect normalizes regardless of direction
        assertTrue(controller.handleInput(context(20, 30, false, false, false)));
        assertArrayEquals(new float[]{20f, 30f, 60f, 60f}, controller.getActiveRect(), 1e-4f);
    }

    @Test
    void escapeCancelsWithoutSelecting() {
        vertexSelectionState.toggleVertex(2, new Vector3f(-0.9f, 0, 0));

        controller.toggle();
        controller.handleInput(context(60, 60, true, false, false));

        controller.escPressed = true;
        assertTrue(controller.handleInput(context(120, 70, false, false, false)));
        assertFalse(controller.isActive());
        assertNull(controller.getActiveRect());

        // Existing selection untouched
        assertEquals(1, vertexSelectionState.getSelectionCount());
        assertTrue(vertexSelectionState.isSelected(2));
    }

    @Test
    void editModeChangeDeactivates() {
        controller.toggle();
        EditModeManager.getInstance().setMode(EditMode.NONE);

        assertFalse(controller.handleInput(context(50, 50, false, false, false)));
        assertFalse(controller.isActive());
    }

    // ── Release selection (vertex mode) ───────────────────────────────────

    @Test
    void releaseReplacesSelectionWithVerticesInRect() {
        // Pre-existing selection is replaced (no Shift)
        vertexSelectionState.toggleVertex(2, new Vector3f(-0.9f, 0, 0));

        controller.toggle();
        controller.handleInput(context(90, 40, true, false, false));   // Press
        controller.handleInput(context(110, 60, false, false, false)); // Drag
        controller.handleInput(context(110, 60, false, true, false));  // Release

        // Only v0 (screen 100,50) is inside rect (90,40)-(110,60)
        assertFalse(controller.isActive());
        assertEquals(1, vertexSelectionState.getSelectionCount());
        assertTrue(vertexSelectionState.isSelected(0));
        assertFalse(vertexSelectionState.isSelected(2));
        assertTrue(vertexRenderer.selectionCleared);
        assertEquals(Set.of(0), vertexRenderer.lastSelectionSet);
    }

    @Test
    void shiftAtReleaseAddsToSelection() {
        vertexSelectionState.toggleVertex(2, new Vector3f(-0.9f, 0, 0));

        controller.toggle();
        controller.handleInput(context(90, 40, true, false, false));
        controller.handleInput(context(110, 60, false, true, true)); // Release with Shift

        assertEquals(2, vertexSelectionState.getSelectionCount());
        assertTrue(vertexSelectionState.isSelected(0));
        assertTrue(vertexSelectionState.isSelected(2));
        assertFalse(vertexRenderer.selectionCleared);
        assertEquals(Set.of(0, 2), vertexRenderer.lastSelectionSet);
    }

    @Test
    void emptyRectClearsSelectionWithoutShift() {
        vertexSelectionState.toggleVertex(2, new Vector3f(-0.9f, 0, 0));

        controller.toggle();
        controller.handleInput(context(190, 90, true, false, false));  // Press in an empty corner
        controller.handleInput(context(195, 95, false, true, false));  // Release

        assertEquals(0, vertexSelectionState.getSelectionCount());
        assertTrue(vertexRenderer.selectionCleared);
    }

    @Test
    void oneShotDeactivatesAfterRelease() {
        controller.toggle();
        controller.handleInput(context(90, 40, true, false, false));
        controller.handleInput(context(110, 60, false, true, false));

        assertFalse(controller.isActive());
        assertFalse(controller.handleInput(context(50, 50, false, false, false)));
    }
}
