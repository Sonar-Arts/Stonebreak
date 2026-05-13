package com.openmason.main.systems.viewport;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Centralized viewport state management.
 * Consolidates all viewport-related state from multiple legacy state classes.
 * Follows Single Responsibility Principle - only manages viewport state.
 */
public class ViewportUIState {

    // Viewport dimensions
    private int width = 800;
    private int height = 600;

    // Viewport visibility state
    private final ImBoolean gridVisible = new ImBoolean(true);
    private final ImBoolean axesVisible = new ImBoolean(true);
    private final ImBoolean gridSnappingEnabled = new ImBoolean(false);
    private final ImBoolean unrenderedMode = new ImBoolean(false);
    private final ImBoolean showVertices = new ImBoolean(false);
    private final ImBoolean showGizmo = new ImBoolean(true);
    private final ImBoolean showBones = new ImBoolean(true);

    // Window visibility state (legacy — kept for backward compat, superseded by ActiveToolPane)
    private final ImBoolean showCameraControls = new ImBoolean(false);
    private final ImBoolean showRenderingOptions = new ImBoolean(false);
    private final ImBoolean showTransformationControls = new ImBoolean(false);

    // View mode state
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top", "Bottom", "Isometric"};
    private final ImInt currentViewModeIndex = new ImInt(0);

    // Camera mode state
    private final String[] cameraModes = {"Arcball", "First-Person"};
    private final ImInt currentCameraModeIndex = new ImInt(0);

    // Render mode state
    private final String[] renderModes = {"Textured", "Unrendered"};
    private final ImInt currentRenderModeIndex = new ImInt(0);

    // Camera state
    private final ImFloat cameraDistance = new ImFloat(5.0f);
    private final ImFloat cameraPitch = new ImFloat(30.0f);
    private final ImFloat cameraYaw = new ImFloat(45.0f);
    private final ImFloat cameraFOV = new ImFloat(60.0f);

    // Grid snapping state
    private final ImFloat gridSnappingIncrement = new ImFloat(0.5f); // Half block (0.5 units)

    // Initialization state
    private boolean viewportInitialized = false;

    // Focus tracking for input isolation (e.g., Tab key should only cycle edit modes when viewport is focused)
    private boolean viewportFocused = false;

    // Resize threshold to prevent excessive resizing from small ImGui layout fluctuations
    private static final int RESIZE_THRESHOLD = 5;

    // ========== Sliding Tool Pane ==========

    /**
     * Which tool pane is currently open (or animating closed).
     */
    public enum ActiveToolPane {
        NONE,
        CAMERA,
        RENDERING,
        TRANSFORM,
        ADD_PART,
        PART_TRANSFORM,
        ADD_BONE
    }

    private ActiveToolPane activeToolPane = ActiveToolPane.NONE;

    /** Callback invoked when user confirms adding a part from the slideout. Args: (shapeName, partName) */
    private BiConsumer<String, String> addPartCallback;

    /** Callback invoked when user confirms adding a bone from the slideout. Args: (boneName, parentNodeId-or-null) */
    private BiConsumer<String, String> addBoneCallback;

    public ActiveToolPane getActiveToolPane() { return activeToolPane; }

    public void setAddPartCallback(BiConsumer<String, String> callback) { this.addPartCallback = callback; }
    public BiConsumer<String, String> getAddPartCallback() { return addPartCallback; }

    public void setAddBoneCallback(BiConsumer<String, String> callback) { this.addBoneCallback = callback; }
    public BiConsumer<String, String> getAddBoneCallback() { return addBoneCallback; }

    /** Supplier for the currently selected part (for the Part Transform slideout). */
    private Supplier<ModelPartDescriptor> selectedPartSupplier;
    /** Consumer to apply a transform change to the selected part. Args: (partId, newTransform) */
    private BiConsumer<String, PartTransform> applyPartTransform;
    /** Callback after a part transform change to invalidate viewport. */
    private Runnable partTransformInvalidator;

    public void setSelectedPartSupplier(Supplier<ModelPartDescriptor> supplier) { this.selectedPartSupplier = supplier; }
    public Supplier<ModelPartDescriptor> getSelectedPartSupplier() { return selectedPartSupplier; }
    public void setApplyPartTransform(BiConsumer<String, PartTransform> consumer) { this.applyPartTransform = consumer; }
    public BiConsumer<String, PartTransform> getApplyPartTransform() { return applyPartTransform; }
    public void setPartTransformInvalidator(Runnable invalidator) { this.partTransformInvalidator = invalidator; }
    public Runnable getPartTransformInvalidator() { return partTransformInvalidator; }

    /**
     * Toggle a tool pane open/closed. Clicking the same pane closes it;
     * clicking a different pane switches to the new one.
     */
    public void toggleToolPane(ActiveToolPane pane) {
        activeToolPane = (activeToolPane == pane) ? ActiveToolPane.NONE : pane;
    }

    /**
     * Close any open tool pane.
     */
    public void closeToolPane() {
        activeToolPane = ActiveToolPane.NONE;
    }

    // ========== Dimension Getters ==========

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    // Getters for visibility state
    public ImBoolean getGridVisible() { return gridVisible; }
    public ImBoolean getAxesVisible() { return axesVisible; }
    public ImBoolean getGridSnappingEnabled() { return gridSnappingEnabled; }
    public ImBoolean getUnrenderedMode() { return unrenderedMode; }
    public ImBoolean getShowVertices() { return showVertices; }
    public ImBoolean getShowGizmo() { return showGizmo; }
    public ImBoolean getShowBones() { return showBones; }

    // Getters for window visibility (legacy)
    public ImBoolean getShowCameraControls() { return showCameraControls; }
    public ImBoolean getShowRenderingOptions() { return showRenderingOptions; }
    public ImBoolean getShowTransformationControls() { return showTransformationControls; }

    // Getters for view mode
    public String[] getViewModes() { return viewModes; }
    public ImInt getCurrentViewModeIndex() { return currentViewModeIndex; }
    public String getCurrentViewMode() { return viewModes[currentViewModeIndex.get()]; }

    // Getters for camera mode
    public String[] getCameraModes() { return cameraModes; }
    public ImInt getCurrentCameraModeIndex() { return currentCameraModeIndex; }
    public String getCurrentCameraMode() { return cameraModes[currentCameraModeIndex.get()]; }

    // Getters for render mode
    public String[] getRenderModes() { return renderModes; }
    public ImInt getCurrentRenderModeIndex() { return currentRenderModeIndex; }
    public String getCurrentRenderMode() { return renderModes[currentRenderModeIndex.get()]; }

    // Getters for camera state
    public ImFloat getCameraDistance() { return cameraDistance; }
    public ImFloat getCameraPitch() { return cameraPitch; }
    public ImFloat getCameraYaw() { return cameraYaw; }
    public ImFloat getCameraFOV() { return cameraFOV; }

    // Getters for grid snapping
    public ImFloat getGridSnappingIncrement() { return gridSnappingIncrement; }

    // Initialization state
    public void setViewportInitialized(boolean initialized) { this.viewportInitialized = initialized; }

    /**
     * Reset camera state to defaults.
     */
    public void resetCameraState() {
        cameraDistance.set(5.0f);
        cameraPitch.set(30.0f);
        cameraYaw.set(45.0f);
        cameraFOV.set(60.0f);
    }

    /**
     * Reset render mode to Textured (index 0) and unrendered off.
     * Also closes any open tool pane.
     * Called when loading a new model so display state doesn't carry over.
     */
    public void resetRenderMode() {
        currentRenderModeIndex.set(0); // "Textured"
        unrenderedMode.set(false);
        closeToolPane();
    }

    /**
     * Update camera state from viewport camera.
     */
    public void updateCameraState(float distance, float pitch, float yaw, float fov) {
        cameraDistance.set(distance);
        cameraPitch.set(pitch);
        cameraYaw.set(yaw);
        cameraFOV.set(fov);
    }

    /**
     * Check if dimensions have changed significantly.
     */
    public boolean dimensionsChanged(int newWidth, int newHeight) {
        int widthDiff = Math.abs(newWidth - width);
        int heightDiff = Math.abs(newHeight - height);
        return widthDiff >= RESIZE_THRESHOLD || heightDiff >= RESIZE_THRESHOLD;
    }

    /**
     * Get aspect ratio.
     */
    public float getAspectRatio() {
        return height > 0 ? (float) width / height : 1.0f;
    }

    /**
     * Toggle methods for convenience.
     */
    public void toggleGrid() {
        gridVisible.set(!gridVisible.get());
    }

    public void toggleAxes() {
        axesVisible.set(!axesVisible.get());
    }

    public void toggleUnrendered() {
        unrenderedMode.set(!unrenderedMode.get());
    }

    public void toggleGizmo() {
        showGizmo.set(!showGizmo.get());
    }

    public void toggleBones() {
        showBones.set(!showBones.get());
    }

    public void toggleGridSnapping() {
        gridSnappingEnabled.set(!gridSnappingEnabled.get());
    }

    public boolean isInitialized() {
        return viewportInitialized;
    }

    // Focus state accessors
    public boolean isViewportFocused() { return viewportFocused; }
    public void setViewportFocused(boolean focused) { this.viewportFocused = focused; }

    @Override
    public String toString() {
        return String.format("ViewportUIState{%dx%d, grid=%s, axes=%s, wireframe=%s, gizmo=%s, vertices=%s, initialized=%s, snapping=%s (%.4f)}",
                width, height, gridVisible.get(), axesVisible.get(), unrenderedMode.get(), showGizmo.get(),
                showVertices.get(), viewportInitialized, gridSnappingEnabled.get(), gridSnappingIncrement.get());
    }
}
