package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.panes.propertyPane.inspector.InspectorRow;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.ITransformState;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Transform controls section: single-line Position / Rotation / Scale rows in
 * the Unity-style label|X|Y|Z inspector layout, plus uniform-scale toggle and
 * a Reset action. The section header is drawn by the coordinator's foldout.
 */
public class TransformSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(TransformSection.class);

    private final ITransformState transformState;
    private IViewportConnector viewportConnector;
    private boolean visible = true;

    private Consumer<Void> onTransformChanged;

    /**
     * Create a transform section.
     *
     * @param transformState The transform state to manage
     */
    public TransformSection(ITransformState transformState) {
        this.transformState = transformState;
    }

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        // Sync from viewport if connected and not interacting
        syncFromViewportIfNeeded();

        // Position
        if (InspectorRow.vector3Row("Position", "ts_pos",
                transformState.getPositionX(), transformState.getPositionY(), transformState.getPositionZ(),
                0.01f, "%.3f")) {
            transformState.markUserInteraction();
            notifyTransformChanged();
        }

        // Rotation
        if (InspectorRow.vector3Row("Rotation", "ts_rot",
                transformState.getRotationX(), transformState.getRotationY(), transformState.getRotationZ(),
                0.5f, "%.1f")) {
            transformState.markUserInteraction();
            notifyTransformChanged();
        }

        // Scale (+ uniform toggle)
        renderScaleControls();
        ImGui.spacing();

        // Reset button
        ImVec4 dimCol = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Button, dimCol.x, dimCol.y, dimCol.z, 0.15f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, dimCol.x, dimCol.y, dimCol.z, 0.30f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, dimCol.x, dimCol.y, dimCol.z, 0.45f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        if (ImGui.button("Reset", -1, 0)) {
            resetTransform();
        }
        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Transform";
    }

    // Public API

    /**
     * Set the viewport connector.
     *
     * @param connector The viewport connector
     */
    public void setViewportConnector(IViewportConnector connector) {
        this.viewportConnector = connector;
    }

    /**
     * Set the transform changed callback.
     *
     * @param callback Callback invoked when transform changes
     */
    public void setOnTransformChanged(Consumer<Void> callback) {
        this.onTransformChanged = callback;
    }

    /**
     * Set visibility of this section.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Reset transform to defaults.
     */
    public void resetTransform() {
        transformState.reset();

        // Reset viewport if connected
        if (viewportConnector != null && viewportConnector.isConnected()) {
            viewportConnector.resetModelTransform();
            viewportConnector.setGizmoUniformScaling(true);
        } else {
            notifyTransformChanged();
        }

        logger.debug("Transform reset to defaults");
    }

    // Private helper methods

    /**
     * Render the scale row with uniform-scaling support: when Uniform is on,
     * an edit on any axis snaps all three to the same value.
     */
    private void renderScaleControls() {
        boolean scaleChanged = InspectorRow.vector3Row("Scale", "ts_scl",
                transformState.getScaleX(), transformState.getScaleY(), transformState.getScaleZ(),
                0.01f, "%.3f");

        if (InspectorRow.checkboxField("Uniform", "ts_uniform", transformState.getUniformScaleMode())) {
            transformState.setUniformScaleMode(transformState.getUniformScaleMode().get());
            if (viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.setGizmoUniformScaling(transformState.getUniformScaleMode().get());
            }
        }

        if (!scaleChanged) {
            return;
        }
        transformState.markUserInteraction();

        if (transformState.getUniformScaleMode().get()) {
            float minScale = viewportConnector != null ? viewportConnector.getMinScale() : 0.1f;
            float maxScale = viewportConnector != null ? viewportConnector.getMaxScale() : 3.0f;
            float sx = transformState.getScaleX().get();
            float sy = transformState.getScaleY().get();
            float sz = transformState.getScaleZ().get();
            float uniform = Math.max(sx, Math.max(sy, sz));
            uniform = Math.min(Math.max(uniform, minScale), maxScale);
            transformState.getScaleX().set(uniform);
            transformState.getScaleY().set(uniform);
            transformState.getScaleZ().set(uniform);
        }
        notifyTransformChanged();
    }

    /**
     * Sync transform state from viewport if needed.
     */
    private void syncFromViewportIfNeeded() {
        if (viewportConnector == null || !viewportConnector.isConnected()) {
            transformState.ensureSafeDefaults();
            return;
        }

        if (transformState.isUserInteracting()) {
            return; // Don't sync while user is interacting
        }

        try {
            transformState.syncFrom(
                viewportConnector.getModelPositionX(),
                viewportConnector.getModelPositionY(),
                viewportConnector.getModelPositionZ(),
                viewportConnector.getModelRotationX(),
                viewportConnector.getModelRotationY(),
                viewportConnector.getModelRotationZ(),
                viewportConnector.getModelScaleX(),
                viewportConnector.getModelScaleY(),
                viewportConnector.getModelScaleZ(),
                viewportConnector.getGizmoUniformScaling()
            );
        } catch (Exception e) {
            logger.warn("Error syncing transform from viewport, using safe defaults", e);
            transformState.ensureSafeDefaults();
        }
    }

    /**
     * Notify that transform changed and update viewport.
     */
    private void notifyTransformChanged() {
        // Update viewport if connected
        if (viewportConnector != null && viewportConnector.isConnected()) {
            try {
                viewportConnector.setModelTransform(
                    transformState.getPositionX().get(),
                    transformState.getPositionY().get(),
                    transformState.getPositionZ().get(),
                    transformState.getRotationX().get(),
                    transformState.getRotationY().get(),
                    transformState.getRotationZ().get(),
                    transformState.getScaleX().get(),
                    transformState.getScaleY().get(),
                    transformState.getScaleZ().get()
                );
            } catch (Exception e) {
                logger.error("Error updating viewport transform", e);
            }
        }

        // Invoke callback
        if (onTransformChanged != null) {
            onTransformChanged.accept(null);
        }
    }
}
