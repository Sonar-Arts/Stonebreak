package com.openmason.ui.properties.sections;

import com.openmason.ui.properties.components.Vec3SliderGroup;
import com.openmason.ui.properties.interfaces.IPanelSection;
import com.openmason.ui.properties.interfaces.ITransformState;
import com.openmason.ui.properties.interfaces.IViewportConnector;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Transform controls section component.
 * Handles position, rotation, and scale sliders with uniform scaling support.
 * Follows SRP - single responsibility of transform UI.
 */
public class TransformSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(TransformSection.class);

    private final ITransformState transformState;
    private IViewportConnector viewportConnector;
    private boolean visible = true;
    private boolean showAdvancedOptions = false;

    private Vec3SliderGroup positionSliders;
    private Vec3SliderGroup rotationSliders;
    private Vec3SliderGroup scaleSliders;

    private Consumer<Void> onTransformChanged;

    /**
     * Create a transform section.
     *
     * @param transformState The transform state to manage
     */
    public TransformSection(ITransformState transformState) {
        this.transformState = transformState;
        initializeSliders();
    }

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        if (ImGui.collapsingHeader("Transform", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();

            // Sync from viewport if connected and not interacting
            syncFromViewportIfNeeded();

            // Position controls
            positionSliders.render();
            ImGui.separator();

            // Rotation controls
            rotationSliders.render();
            ImGui.separator();

            // Scale controls
            renderScaleControls();
            ImGui.separator();

            // Reset button
            if (ImGui.button("Reset Transform")) {
                resetTransform();
            }

            ImGui.unindent();
        }
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
     * Set whether to show advanced options.
     *
     * @param show true to show advanced options
     */
    public void setShowAdvancedOptions(boolean show) {
        this.showAdvancedOptions = show;
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
     * Initialize slider groups.
     */
    private void initializeSliders() {
        // Position sliders
        positionSliders = Vec3SliderGroup.createPosition(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ(),
            axis -> {
                transformState.markUserInteraction();
                notifyTransformChanged();
            }
        );

        // Rotation sliders
        rotationSliders = Vec3SliderGroup.createRotation(
            transformState.getRotationX(),
            transformState.getRotationY(),
            transformState.getRotationZ(),
            axis -> {
                transformState.markUserInteraction();
                notifyTransformChanged();
            }
        );

        // Scale sliders (will be rendered with custom logic for uniform scaling)
        float minScale = viewportConnector != null ? viewportConnector.getMinScale() : 0.1f;
        float maxScale = viewportConnector != null ? viewportConnector.getMaxScale() : 3.0f;

        scaleSliders = Vec3SliderGroup.createScale(
            transformState.getScaleX(),
            transformState.getScaleY(),
            transformState.getScaleZ(),
            minScale,
            maxScale,
            axis -> {
                transformState.markUserInteraction();
                notifyTransformChanged();
            }
        );
    }

    /**
     * Render scale controls with uniform scaling support.
     */
    private void renderScaleControls() {
        ImGui.text("Scale:");

        // Uniform mode checkbox
        if (ImGui.checkbox("Uniform Scaling", transformState.getUniformScaleMode())) {
            transformState.setUniformScaleMode(transformState.getUniformScaleMode().get());

            // Sync to viewport if connected
            if (viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.setGizmoUniformScaling(transformState.getUniformScaleMode().get());
            }
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Toggle between uniform (all axes) and non-uniform (per-axis) scaling");
        }

        ImGui.separator();

        // Get scale constraints
        float minScale = viewportConnector != null ? viewportConnector.getMinScale() : 0.1f;
        float maxScale = viewportConnector != null ? viewportConnector.getMaxScale() : 3.0f;

        // Render scale boundary warnings
        renderScaleBoundaryWarnings(minScale, maxScale);

        // Render scale sliders with uniform scaling support
        if (transformState.getUniformScaleMode().get()) {
            scaleSliders.renderWithUniformScale(true, (axis, newValue) -> {
                transformState.markUserInteraction();
                transformState.applyUniformScale(axis, newValue, minScale, maxScale);
                notifyTransformChanged();
            });
        } else {
            scaleSliders.render();
        }
    }

    /**
     * Render scale boundary warnings.
     */
    private void renderScaleBoundaryWarnings(float minScale, float maxScale) {
        boolean atMinScale = Math.abs(transformState.getScaleX().get() - minScale) < 0.01f ||
                             Math.abs(transformState.getScaleY().get() - minScale) < 0.01f ||
                             Math.abs(transformState.getScaleZ().get() - minScale) < 0.01f;
        boolean atMaxScale = Math.abs(transformState.getScaleX().get() - maxScale) < 0.01f ||
                             Math.abs(transformState.getScaleY().get() - maxScale) < 0.01f ||
                             Math.abs(transformState.getScaleZ().get() - maxScale) < 0.01f;

        if (atMinScale || atMaxScale) {
            if (atMinScale) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f); // Orange
                ImGui.text("⚠ Minimum scale reached");
                ImGui.popStyleColor();
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f); // Orange
                ImGui.text("⚠ Maximum scale reached");
                ImGui.popStyleColor();
            }
        }
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
                viewportConnector.requestRender();
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
