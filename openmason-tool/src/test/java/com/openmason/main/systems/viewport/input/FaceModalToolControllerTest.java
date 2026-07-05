package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.viewport.input.FaceModalToolController.Kind;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless state-machine tests for {@link FaceModalToolController} (I inset /
 * E extrude).
 *
 * <p>Uses a stub {@link GenericModelRenderer} (CPU-only, constructor performs
 * no GL) that records inset/extrude calls, an overridden face-loop seam so no
 * topology or face renderer is required, identity camera matrices and a
 * 200×100 viewport: the selection centroid (0,0,0) projects to screen
 * (100, 50) and one world unit along +X covers 100 pixels. The test quad faces
 * +Z (view-aligned), so the pixels-per-unit probe exercises the camera-right
 * fallback and extrude uses the vertical mouse delta.
 */
class FaceModalToolControllerTest {

    private static final float EPS = 1e-4f;

    /** Overridable input + geometry seams so tests run without ImGui/GL/topology. */
    private static class TestableFaceModalToolController extends FaceModalToolController {
        boolean escPressed;
        boolean enterPressed;
        boolean rightClicked;
        final Map<Integer, Vector3f[]> loops = new HashMap<>();

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

        @Override
        protected Vector3f[] resolveFaceLoopPositions(int faceId) {
            return loops.get(faceId);
        }
    }

    /** CPU-only model renderer stub recording the topology op calls. */
    private static class StubModelRenderer extends GenericModelRenderer {
        int insetCalls;
        int extrudeCalls;
        int[] lastFaceIds;
        float lastAmount = Float.NaN;
        int[] insetResult = {100};
        int[] extrudeResult = {200};

        @Override
        public int[] insetFaces(int[] faceIds, float amount) {
            insetCalls++;
            lastFaceIds = faceIds.clone();
            lastAmount = amount;
            return insetResult;
        }

        @Override
        public int[] extrudeFaces(int[] faceIds, float offset) {
            extrudeCalls++;
            lastFaceIds = faceIds.clone();
            lastAmount = offset;
            return extrudeResult;
        }
    }

    private TestableFaceModalToolController controller;
    private StubModelRenderer modelRenderer;
    private FaceSelectionState faceSelectionState;
    private ModelCommandHistory commandHistory;
    private int historyPushCount;

    /** Unit quad in the XY plane centered at the origin (normal +Z, view-aligned). */
    private static Vector3f[] xyQuad() {
        return new Vector3f[] {
            new Vector3f(-0.5f, -0.5f, 0f),
            new Vector3f(0.5f, -0.5f, 0f),
            new Vector3f(0.5f, 0.5f, 0f),
            new Vector3f(-0.5f, 0.5f, 0f)
        };
    }

    @BeforeEach
    void setUp() {
        controller = new TestableFaceModalToolController();
        modelRenderer = new StubModelRenderer();
        faceSelectionState = new FaceSelectionState();
        commandHistory = new ModelCommandHistory();
        historyPushCount = 0;
        commandHistory.setOnHistoryChange(() -> historyPushCount++);

        controller.setModelRenderer(modelRenderer);
        controller.setFaceSelectionState(faceSelectionState);
        controller.setCommandHistory(commandHistory, new RendererSynchronizer(
            modelRenderer, null, new VertexSelectionState(), new EdgeSelectionState(), faceSelectionState));

        EditModeManager.getInstance().setMode(EditMode.FACE);
    }

    @AfterEach
    void tearDown() {
        EditModeManager.getInstance().setMode(EditMode.NONE);
    }

    /** Select face 0 (unit XY quad) and register its loop with the controller seam. */
    private void selectQuadFace() {
        Vector3f[] loop = xyQuad();
        controller.loops.put(0, loop);
        faceSelectionState.selectFace(0, loop);
    }

    private static InputContext context(float mouseX, float mouseY, boolean clicked) {
        return new InputContext(
            mouseX, mouseY,
            true, true,
            clicked, false, false,
            0f, new ImVec2(0, 0),
            false, false, false,
            200, 100,
            new Matrix4f(), new Matrix4f(),
            false, false, false);
    }

    // ── Arming preconditions ──────────────────────────────────────────────

    @Test
    void doesNotArmOutsideFaceMode() {
        EditModeManager.getInstance().setMode(EditMode.VERTEX);
        selectQuadFace();

        controller.startOrConfirm(Kind.INSET);
        assertFalse(controller.isActive());
    }

    @Test
    void doesNotArmWithoutFaceSelection() {
        controller.startOrConfirm(Kind.INSET);
        assertFalse(controller.isActive());

        controller.startOrConfirm(Kind.EXTRUDE);
        assertFalse(controller.isActive());
    }

    @Test
    void armsWithSelectionAndConsumesInput() {
        selectQuadFace();

        controller.startOrConfirm(Kind.INSET);
        assertTrue(controller.isActive());
        assertTrue(controller.isActive(Kind.INSET));
        assertFalse(controller.isActive(Kind.EXTRUDE));
        assertTrue(controller.handleInput(context(150, 50, false)));
    }

    @Test
    void inactiveDoesNotConsumeInput() {
        assertFalse(controller.handleInput(context(150, 50, false)));
    }

    @Test
    void deactivatesWhenFaceLoopCannotBeResolved() {
        // Selection exists but the loop seam has no geometry for face 5
        faceSelectionState.selectFace(5, xyQuad());

        controller.startOrConfirm(Kind.INSET);
        assertTrue(controller.isActive());

        // First frame fails to resolve the loop and deactivates
        assertTrue(controller.handleInput(context(150, 50, false)));
        assertFalse(controller.isActive());
        assertEquals(0, modelRenderer.insetCalls);
    }

    // ── Cancel (mesh never touched) ───────────────────────────────────────

    @Test
    void escapeCancelsWithoutTouchingMesh() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);
        controller.handleInput(context(150, 50, false)); // begin (d0 = 50px)
        controller.handleInput(context(120, 50, false)); // amount > 0

        controller.escPressed = true;
        assertTrue(controller.handleInput(context(120, 50, false)));
        assertFalse(controller.isActive());
        assertEquals(0, modelRenderer.insetCalls);
        assertEquals(0, modelRenderer.extrudeCalls);
        assertFalse(commandHistory.canUndo());
        // Selection is untouched on cancel
        assertTrue(faceSelectionState.hasSelection());
    }

    @Test
    void rightClickCancelsWithoutTouchingMesh() {
        selectQuadFace();
        controller.startOrConfirm(Kind.EXTRUDE);
        controller.handleInput(context(100, 50, false)); // begin
        controller.handleInput(context(100, 30, false)); // amount > 0

        controller.rightClicked = true;
        assertTrue(controller.handleInput(context(100, 30, false)));
        assertFalse(controller.isActive());
        assertEquals(0, modelRenderer.extrudeCalls);
        assertFalse(commandHistory.canUndo());
    }

    @Test
    void editModeChangeCancels() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);
        assertTrue(controller.isActive());

        EditModeManager.getInstance().setMode(EditMode.EDGE);
        assertFalse(controller.handleInput(context(150, 50, false)));
        assertFalse(controller.isActive());
        assertEquals(0, modelRenderer.insetCalls);
    }

    // ── Confirm ───────────────────────────────────────────────────────────

    @Test
    void confirmWithZeroAmountIsCancel() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);
        controller.handleInput(context(150, 50, false)); // begin — amount 0

        // Click without ever moving → confirm at amount 0 → treated as cancel
        assertTrue(controller.handleInput(context(150, 50, true)));
        assertFalse(controller.isActive());
        assertEquals(0, modelRenderer.insetCalls);
        assertFalse(commandHistory.canUndo());
        assertTrue(faceSelectionState.hasSelection());
    }

    @Test
    void insetConfirmCallsEngineOnceAndPushesOneUndoCommand() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);

        // Begin at 50px from the projected centroid (100,50); ppu = 100 (camera-right probe)
        controller.handleInput(context(150, 50, false));
        // Move 30px toward the centroid → amount = 30 / 100 = 0.3
        controller.handleInput(context(120, 50, false));

        // Click confirms: engine op once, exactly one history push
        assertTrue(controller.handleInput(context(120, 50, true)));
        assertFalse(controller.isActive());
        assertEquals(1, modelRenderer.insetCalls);
        assertEquals(0, modelRenderer.extrudeCalls);
        assertNotNull(modelRenderer.lastFaceIds);
        assertEquals(1, modelRenderer.lastFaceIds.length);
        assertEquals(0, modelRenderer.lastFaceIds[0]);
        assertEquals(0.3f, modelRenderer.lastAmount, EPS);
        assertEquals(1, historyPushCount);
        assertTrue(commandHistory.canUndo());
        assertEquals("Inset Faces", commandHistory.getUndoDescription());
        // Face selection cleared — its cached data is stale after the topology change
        assertFalse(faceSelectionState.hasSelection());
    }

    @Test
    void extrudeConfirmCallsEngineOnceWithSignedAmount() {
        selectQuadFace();
        controller.startOrConfirm(Kind.EXTRUDE);

        controller.handleInput(context(100, 50, false)); // begin
        // View-aligned normal → vertical fallback: 20px up at ppu 100 → +0.2
        controller.handleInput(context(100, 30, false));

        controller.enterPressed = true;
        assertTrue(controller.handleInput(context(100, 30, false)));
        assertFalse(controller.isActive());
        assertEquals(1, modelRenderer.extrudeCalls);
        assertEquals(0, modelRenderer.insetCalls);
        assertEquals(0.2f, modelRenderer.lastAmount, EPS);
        assertEquals(1, historyPushCount);
        assertEquals("Extrude Faces", commandHistory.getUndoDescription());
        assertFalse(faceSelectionState.hasSelection());
    }

    @Test
    void sameKeyAgainConfirms() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);
        controller.handleInput(context(150, 50, false));
        controller.handleInput(context(120, 50, false));

        // I pressed again → startOrConfirm on the active kind commits
        controller.startOrConfirm(Kind.INSET);
        assertFalse(controller.isActive());
        assertEquals(1, modelRenderer.insetCalls);
        assertEquals(1, historyPushCount);
    }

    @Test
    void nullEngineResultRecordsNothing() {
        selectQuadFace();
        modelRenderer.insetResult = null; // engine op failure

        controller.startOrConfirm(Kind.INSET);
        controller.handleInput(context(150, 50, false));
        controller.handleInput(context(120, 50, false));
        controller.handleInput(context(120, 50, true));

        assertFalse(controller.isActive());
        assertEquals(1, modelRenderer.insetCalls);
        assertEquals(0, historyPushCount);
        assertFalse(commandHistory.canUndo());
    }

    // ── Kind switching ────────────────────────────────────────────────────

    @Test
    void switchingKindRestartsCleanly() {
        selectQuadFace();
        controller.startOrConfirm(Kind.INSET);
        controller.handleInput(context(150, 50, false));
        controller.handleInput(context(120, 50, false)); // inset amount > 0

        // E while insetting: cancels the inset (no op) and arms extrude
        controller.startOrConfirm(Kind.EXTRUDE);
        assertTrue(controller.isActive(Kind.EXTRUDE));
        assertFalse(controller.isActive(Kind.INSET));
        assertEquals(0, modelRenderer.insetCalls);
        assertEquals(0, modelRenderer.extrudeCalls);
        assertFalse(commandHistory.canUndo());

        // The restarted extrude works end-to-end
        controller.handleInput(context(100, 50, false)); // begin
        controller.handleInput(context(100, 30, false)); // +0.2
        controller.handleInput(context(100, 30, true));  // confirm
        assertFalse(controller.isActive());
        assertEquals(1, modelRenderer.extrudeCalls);
        assertEquals(0.2f, modelRenderer.lastAmount, EPS);
        assertEquals(1, historyPushCount);
    }
}
