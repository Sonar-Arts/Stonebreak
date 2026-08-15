package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.engine.rendering.viewer.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.input.CameraInputController;
import com.openmason.main.systems.viewport.input.GizmoInputController;
import com.openmason.main.systems.viewport.input.InputContext;
import com.openmason.main.systems.viewport.input.MouseCaptureManager;
import imgui.ImGui;
import imgui.ImVec2;

/**
 * Mouse input for the Scene Viewer: camera navigation and gizmo dragging.
 *
 * <p>Both controllers are the model editor's own — this class only builds the per-frame
 * {@link InputContext} from the scene viewport's image rectangle and routes between them.
 * Reusing them is what keeps orbit feel, drag thresholds and axis-handle behaviour
 * identical across the two 3D surfaces instead of slowly diverging.
 *
 * <p>Routing mirrors the editor's priority order: an in-progress camera drag wins
 * outright, then the gizmo gets first refusal, then the camera as the fallthrough. The
 * mesh-editing tiers the editor also routes (vertex/edge/face) have no counterpart here.
 */
public class SceneViewportInput {

    private final CameraInputController cameraController;
    private final GizmoInputController gizmoController = new GizmoInputController();
    private final MouseCaptureManager mouseCaptureManager = new MouseCaptureManager();

    private boolean gizmoConsumedInput;

    public SceneViewportInput(ViewerCamera camera) {
        this.cameraController = new CameraInputController(camera, mouseCaptureManager);
    }

    /** Point the gizmo controller at the renderer it should hit-test against. */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        gizmoController.setGizmoRenderer(gizmoRenderer);
        // The vertex/edge/face renderers stay unset: those exist so the editor's gizmo
        // yields to precise mesh editing, and a scene viewport has no mesh editing.
    }

    /**
     * Feed one frame of input.
     *
     * @param imagePos    screen position of the viewport image's top-left corner
     * @param imageWidth  image width in pixels
     * @param imageHeight image height in pixels
     * @param hovered     whether the viewport window is hovered
     * @param camera      camera supplying this frame's matrices
     */
    public void handleInput(ImVec2 imagePos, float imageWidth, float imageHeight,
                            boolean hovered, ViewerCamera camera) {
        gizmoConsumedInput = false;

        ImVec2 mouse = ImGui.getMousePos();
        float localX = mouse.x - imagePos.x;
        float localY = mouse.y - imagePos.y;
        boolean inBounds = hovered
                && localX >= 0 && localX < imageWidth
                && localY >= 0 && localY < imageHeight;

        InputContext context = new InputContext(
                localX,
                localY,
                inBounds,
                hovered,
                ImGui.isMouseClicked(0),
                ImGui.isMouseDown(0),
                ImGui.isMouseReleased(0),
                ImGui.getIO().getMouseWheel(),
                ImGui.getIO().getMouseDelta(),
                ImGui.isMouseClicked(2),
                ImGui.isMouseDown(2),
                ImGui.isMouseReleased(2),
                (int) imageWidth,
                (int) imageHeight,
                camera.getViewMatrix(),
                camera.getProjectionMatrix(),
                ImGui.getIO().getKeyShift(),
                ImGui.getIO().getKeyCtrl(),
                ImGui.getIO().getKeyAlt()
        );

        // An active camera drag keeps priority until it ends, so a stray pass over a
        // gizmo handle mid-orbit cannot hijack the drag.
        if (cameraController.isDragging()) {
            cameraController.handleInput(context);
            return;
        }

        if (gizmoController.handleInput(context)) {
            gizmoConsumedInput = true;
            return;
        }

        cameraController.handleInput(context);
    }

    /**
     * True when the camera or the gizmo used this frame's input, so the click must not
     * also be treated as a selection.
     */
    public boolean consumedInput() {
        return gizmoConsumedInput || cameraController.isDragging();
    }
}
