package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.topology.MeshFace;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.ToolPreviewRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.util.FaceModalMath;
import com.openmason.main.systems.viewport.util.ScreenProjectionUtil;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Modal input controller for Blender-style face inset (I key) and extrude
 * (E key). One controller handles both tools via {@link Kind} — the state
 * machine, mouse-to-amount mapping and preview plumbing are shared; only the
 * amount convention, preview geometry and engine op differ.
 *
 * <p>Face edit mode with a non-empty face selection required. The engine op
 * ({@link GenericModelRenderer#insetFaces} / {@link GenericModelRenderer#extrudeFaces})
 * is called exactly ONCE on confirm with the final amount; the live preview is
 * tool-side overlay lines only ({@link ToolPreviewRenderer}), so cancel is free
 * and undo is a single snapshot command. Multi-face selections are handled
 * per-face individually (that is what the engine ops do).
 *
 * <p>State machine:
 * <ul>
 *   <li>INACTIVE → I/E (face mode + selection) → PENDING_START</li>
 *   <li>PENDING_START → first frame with a valid context → ADJUSTING</li>
 *   <li>ADJUSTING → left click / Enter / same key again → engine op + snapshot undo → INACTIVE</li>
 *   <li>ADJUSTING → Esc / right click → clear preview (mesh untouched) → INACTIVE</li>
 *   <li>Confirm with amount 0 is treated as cancel (no op, no snapshot)</li>
 * </ul>
 */
public class FaceModalToolController {

    private static final Logger logger = LoggerFactory.getLogger(FaceModalToolController.class);

    /** Amounts at or below this (absolute) confirm as a cancel — no op, no snapshot. */
    private static final float MIN_CONFIRM_AMOUNT = 1e-5f;

    /** Which face tool the controller is running. */
    public enum Kind {
        INSET("Inset Faces"),
        EXTRUDE("Extrude Faces");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** Undo-history label for this tool. */
        public String label() {
            return label;
        }
    }

    private enum Phase {
        INACTIVE,
        PENDING_START,
        ADJUSTING
    }

    // Dependencies (set via setters, same pattern as other input controllers)
    private GenericModelRenderer modelRenderer;
    private FaceRenderer faceRenderer;
    private FaceSelectionState faceSelectionState;
    private TransformState transformState;
    private ToolPreviewRenderer previewRenderer;

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;

    // Tool state
    private Phase phase = Phase.INACTIVE;
    private Kind kind;

    // Per-selection data resolved at ADJUSTING start
    private int[] faceIds;
    private List<Vector3f[]> faceLoops;
    private List<Vector3f> faceNormals;
    private Vector2f centroidScreen;
    private Vector2f normalScreenDir; // null when the average normal is view-aligned
    private float pixelsPerUnit;
    private float mouse0X;
    private float mouse0Y;
    private float insetD0;
    private float amount;

    /**
     * Start the given tool, or — if this tool is already active — confirm it
     * (Blender-style: press I to inset, press I again to confirm). Pressing the
     * OTHER tool's key while active cancels the current tool (mesh untouched)
     * and arms the new one, so I→E restarts cleanly.
     */
    public void startOrConfirm(Kind requestedKind) {
        if (phase != Phase.INACTIVE) {
            if (requestedKind == kind) {
                confirm();
                return;
            }
            cancel();
            // Fall through to arm the other kind
        }

        if (EditModeManager.getInstance().getCurrentMode() != EditMode.FACE) {
            logger.debug("{} tool requires Face edit mode", requestedKind);
            return;
        }
        if (faceSelectionState == null || !faceSelectionState.hasSelection()) {
            logger.debug("{} tool requires a non-empty face selection", requestedKind);
            return;
        }

        kind = requestedKind;
        phase = Phase.PENDING_START;
        logger.info("{} tool armed", requestedKind);
    }

    /**
     * @return true if the tool is currently active (either kind)
     */
    public boolean isActive() {
        return phase != Phase.INACTIVE;
    }

    /**
     * @param queriedKind Kind to query
     * @return true if the tool is currently active with the given kind
     */
    public boolean isActive(Kind queriedKind) {
        return phase != Phase.INACTIVE && kind == queriedKind;
    }

    // =========================================================================
    // DEPENDENCY SETTERS
    // =========================================================================

    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
    }

    public void setFaceRenderer(FaceRenderer faceRenderer) {
        this.faceRenderer = faceRenderer;
    }

    public void setFaceSelectionState(FaceSelectionState faceSelectionState) {
        this.faceSelectionState = faceSelectionState;
    }

    public void setTransformState(TransformState transformState) {
        this.transformState = transformState;
    }

    public void setPreviewRenderer(ToolPreviewRenderer previewRenderer) {
        this.previewRenderer = previewRenderer;
    }

    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    // =========================================================================
    // INPUT HANDLING
    // =========================================================================

    /**
     * Handle input for the inset/extrude tool.
     *
     * @param context Input context with mouse and keyboard state
     * @return true if input was consumed (blocks lower-priority controllers)
     */
    public boolean handleInput(InputContext context) {
        if (phase == Phase.INACTIVE) {
            return false;
        }

        // Deactivate if the edit mode changed underneath the tool
        if (EditModeManager.getInstance().getCurrentMode() != EditMode.FACE) {
            cancel();
            return false;
        }

        // Esc or right click cancels (mesh was never touched — preview only)
        if (isKeyPressed(GLFW.GLFW_KEY_ESCAPE) || isRightMouseClicked()) {
            cancel();
            return true;
        }

        if (phase == Phase.PENDING_START) {
            if (!beginAdjusting(context)) {
                deactivate();
            }
            return true;
        }

        // ADJUSTING: confirm on left click or Enter
        if ((context.mouseInBounds && context.mouseClicked)
                || isKeyPressed(GLFW.GLFW_KEY_ENTER) || isKeyPressed(GLFW.GLFW_KEY_KP_ENTER)) {
            confirm();
            return true;
        }

        // Recompute the amount from the mouse and rebuild the preview
        amount = computeAmount(context.mouseX, context.mouseY);
        updatePreview();

        return true; // Consume all input while active
    }

    /**
     * Begin the live adjust: resolve the selected face loops/normals, project
     * the selection centroid, and establish the pixels-per-world-unit scale and
     * the mouse reference values.
     *
     * @return true if adjusting started, false if the selection could not be resolved
     */
    private boolean beginAdjusting(InputContext context) {
        if (context.viewportWidth <= 0 || context.viewportHeight <= 0
                || context.viewMatrix == null || context.projectionMatrix == null) {
            return false;
        }
        if (faceSelectionState == null) {
            return false;
        }

        Set<Integer> selected = new TreeSet<>(faceSelectionState.getSelectedFaceIndices());
        if (selected.isEmpty()) {
            return false;
        }

        // Resolve loops + normals for every selected face
        faceIds = new int[selected.size()];
        faceLoops = new ArrayList<>(selected.size());
        faceNormals = new ArrayList<>(selected.size());
        Vector3f selectionCentroid = new Vector3f();
        Vector3f averageNormal = new Vector3f();
        int cornerCount = 0;
        int i = 0;
        for (int faceId : selected) {
            Vector3f[] loop = resolveFaceLoopPositions(faceId);
            if (loop == null || loop.length < 3) {
                logger.debug("{} tool: could not resolve a loop for face {}", kind, faceId);
                return false;
            }
            faceIds[i++] = faceId;
            faceLoops.add(loop);
            Vector3f normal = FaceModalMath.newellNormal(loop);
            faceNormals.add(normal);
            averageNormal.add(normal);
            for (Vector3f corner : loop) {
                selectionCentroid.add(corner);
            }
            cornerCount += loop.length;
        }
        selectionCentroid.div(cornerCount);
        if (averageNormal.lengthSquared() > 1e-12f) {
            averageNormal.normalize();
        }

        // Project the model-space centroid to screen space (projection * view * model)
        Matrix4f modelMatrix = (transformState != null)
            ? transformState.getTransformMatrix()
            : new Matrix4f();
        Matrix4f mvp = new Matrix4f(context.projectionMatrix).mul(context.viewMatrix).mul(modelMatrix);
        centroidScreen = ScreenProjectionUtil.projectToScreen(
            selectionCentroid, mvp, context.viewportWidth, context.viewportHeight);
        if (centroidScreen == null) {
            logger.debug("{} tool: selection centroid is behind the camera", kind);
            return false;
        }

        // Pixels-per-world-unit along the average normal; fall back to a
        // camera-right probe when the normal is view-aligned (degenerate)
        pixelsPerUnit = FaceModalMath.pixelsPerUnit(
            selectionCentroid, averageNormal, mvp, context.viewportWidth, context.viewportHeight);
        normalScreenDir = FaceModalMath.screenDirection(
            selectionCentroid, averageNormal, mvp, context.viewportWidth, context.viewportHeight);
        if (pixelsPerUnit <= FaceModalMath.MIN_PIXELS_PER_UNIT) {
            Matrix4f modelView = new Matrix4f(context.viewMatrix).mul(modelMatrix);
            // Camera right expressed in model space (row 0 of the modelview rotation)
            Vector3f cameraRight = new Vector3f(modelView.m00(), modelView.m10(), modelView.m20());
            if (cameraRight.lengthSquared() > 1e-12f) {
                cameraRight.normalize();
            }
            pixelsPerUnit = FaceModalMath.pixelsPerUnit(
                selectionCentroid, cameraRight, mvp, context.viewportWidth, context.viewportHeight);
            normalScreenDir = null; // extrude falls back to the vertical mouse delta
        }
        if (pixelsPerUnit <= FaceModalMath.MIN_PIXELS_PER_UNIT) {
            logger.debug("{} tool: degenerate projection, cannot map mouse to world units", kind);
            return false;
        }

        mouse0X = context.mouseX;
        mouse0Y = context.mouseY;
        insetD0 = new Vector2f(mouse0X, mouse0Y).distance(centroidScreen);
        amount = 0f;

        if (previewRenderer != null) {
            previewRenderer.setActive(true);
        }
        phase = Phase.ADJUSTING;

        logger.info("{} tool started: {} faces, ppu={}px/unit", kind, faceIds.length,
            String.format("%.1f", pixelsPerUnit));
        return true;
    }

    /**
     * Map the current mouse position to the tool amount (world units).
     */
    private float computeAmount(float mouseX, float mouseY) {
        if (kind == Kind.INSET) {
            return FaceModalMath.insetAmount(insetD0, mouseX, mouseY, centroidScreen, pixelsPerUnit);
        }
        return FaceModalMath.extrudeAmount(mouse0X, mouse0Y, mouseX, mouseY, normalScreenDir, pixelsPerUnit);
    }

    /**
     * Rebuild the overlay preview segments for every selected face at the
     * current amount.
     */
    private void updatePreview() {
        if (previewRenderer == null) {
            return;
        }

        int totalFloats = 0;
        List<float[]> perFace = new ArrayList<>(faceLoops.size());
        for (int i = 0; i < faceLoops.size(); i++) {
            float[] segments = (kind == Kind.INSET)
                ? FaceModalMath.insetPreviewSegments(faceLoops.get(i), amount)
                : FaceModalMath.extrudePreviewSegments(faceLoops.get(i), faceNormals.get(i), amount);
            perFace.add(segments);
            totalFloats += segments.length;
        }

        float[] all = new float[totalFloats];
        int offset = 0;
        for (float[] segments : perFace) {
            System.arraycopy(segments, 0, all, offset, segments.length);
            offset += segments.length;
        }
        previewRenderer.setLines(all);
    }

    /**
     * Confirm: run the engine op once with the final amount, record a single
     * snapshot undo command, and clear the (now stale) face selection.
     * An amount of 0 is treated as a cancel.
     */
    private void confirm() {
        if (phase != Phase.ADJUSTING || modelRenderer == null
                || Math.abs(amount) <= MIN_CONFIRM_AMOUNT) {
            cancel();
            return;
        }

        // Capture snapshot before the topology change for undo
        MeshSnapshot before = (commandHistory != null && synchronizer != null)
            ? MeshSnapshot.capture(modelRenderer) : null;

        int[] newFaceIds = (kind == Kind.INSET)
            ? modelRenderer.insetFaces(faceIds, amount)
            : modelRenderer.extrudeFaces(faceIds, amount);

        if (newFaceIds == null) {
            logger.warn("{} failed for {} faces at amount {} — mesh unchanged",
                kind, faceIds.length, String.format("%.4f", amount));
            deactivate();
            return;
        }

        if (before != null) {
            MeshSnapshot after = MeshSnapshot.capture(modelRenderer);
            commandHistory.pushCompleted(SnapshotCommand.custom(
                kind.label(), before, after, modelRenderer, synchronizer));
        }

        // The selection's cached face data is stale after the topology change —
        // clear it (same convention as the edge-subdivision service)
        if (faceSelectionState != null) {
            faceSelectionState.clearSelection();
        }
        if (faceRenderer != null) {
            faceRenderer.clearSelection();
        }

        logger.info("{} committed: {} faces at amount {}, {} new faces",
            kind, faceIds.length, String.format("%.4f", amount), newFaceIds.length);
        deactivate();
    }

    /**
     * Cancel: clear the preview and deactivate — the mesh was never touched.
     */
    private void cancel() {
        if (phase != Phase.INACTIVE) {
            logger.info("{} tool cancelled", kind);
        }
        deactivate();
    }

    private void deactivate() {
        phase = Phase.INACTIVE;
        kind = null;
        faceIds = null;
        faceLoops = null;
        faceNormals = null;
        centroidScreen = null;
        normalScreenDir = null;
        pixelsPerUnit = 0f;
        insetD0 = 0f;
        amount = 0f;
        if (previewRenderer != null) {
            previewRenderer.setActive(false);
        }
    }

    // =========================================================================
    // GEOMETRY RESOLUTION (overridable seam for headless tests)
    // =========================================================================

    /**
     * Resolve a face id to its ordered corner positions (model space).
     * Prefers the topology loop (ordered, winding authoritative); falls back to
     * the face renderer's deduped triangle-corner positions.
     *
     * @return Ordered loop positions, or null if the face cannot be resolved
     */
    protected Vector3f[] resolveFaceLoopPositions(int faceId) {
        if (modelRenderer != null) {
            MeshTopology topology = modelRenderer.getTopology();
            if (topology != null) {
                MeshFace face = topology.getFace(faceId);
                if (face != null && face.vertexCount() >= 3) {
                    Vector3f[] loop = new Vector3f[face.vertexCount()];
                    int[] vertexIndices = face.vertexIndices();
                    for (int i = 0; i < loop.length; i++) {
                        loop[i] = modelRenderer.getUniqueVertexPosition(vertexIndices[i]);
                        if (loop[i] == null) {
                            return null;
                        }
                    }
                    return loop;
                }
            }
        }
        if (faceRenderer != null) {
            return faceRenderer.getTriangleVertexPositionsForFace(faceId);
        }
        return null;
    }

    // =========================================================================
    // INPUT PROBES (overridable seams for headless state-machine tests)
    // =========================================================================

    /**
     * @return true if the key was pressed this frame (ImGui-backed in production)
     */
    protected boolean isKeyPressed(int glfwKeyCode) {
        return KeyCodeTranslator.isKeyPressed(glfwKeyCode);
    }

    /**
     * @return true if the right mouse button was clicked this frame (ImGui-backed in production)
     */
    protected boolean isRightMouseClicked() {
        return imgui.ImGui.isMouseClicked(1);
    }
}
