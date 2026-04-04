package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.menus.panes.propertyPane.components.Vec3SliderGroup;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.ITransformState;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Transform controls section component.
 * Handles position, rotation, and scale with colored axis labels.
 * Follows SRP - single responsibility of transform UI.
 */
public class TransformSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(TransformSection.class);

    private final ITransformState transformState;
    private IViewportConnector viewportConnector;
    private boolean visible = true;

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

        ImGuiComponents.renderCompactSectionHeader("Transform");
        ImGui.spacing();

        // Sync from viewport if connected and not interacting
        syncFromViewportIfNeeded();

        // Position
        renderTransformGroup("Position", "ts_pos",
                transformState.getPositionX(), transformState.getPositionY(), transformState.getPositionZ(),
                0.01f, "%.3f");
        ImGui.spacing();

        // Rotation
        renderTransformGroup("Rotation", "ts_rot",
                transformState.getRotationX(), transformState.getRotationY(), transformState.getRotationZ(),
                0.5f, "%.1f");
        ImGui.spacing();

        // Scale
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
        ImVec4 dimCol = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, dimCol.x, dimCol.y, dimCol.z, dimCol.w);
        ImGui.textUnformatted("Scale");
        ImGui.popStyleColor();

        // Uniform mode checkbox
        if (ImGui.checkbox("Uniform", transformState.getUniformScaleMode())) {
            transformState.setUniformScaleMode(transformState.getUniformScaleMode().get());
            if (viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.setGizmoUniformScaling(transformState.getUniformScaleMode().get());
            }
        }

        float minScale = viewportConnector != null ? viewportConnector.getMinScale() : 0.1f;
        float maxScale = viewportConnector != null ? viewportConnector.getMaxScale() : 3.0f;

        if (transformState.getUniformScaleMode().get()) {
            // Uniform: single drag float for all axes
            boolean scaleChanged = renderAxisField("X", "ts_sclx", transformState.getScaleX(), 0.01f, "%.3f",
                    0.85f, 0.25f, 0.25f);
            scaleChanged |= renderAxisField("Y", "ts_scly", transformState.getScaleY(), 0.01f, "%.3f",
                    0.25f, 0.72f, 0.25f);
            scaleChanged |= renderAxisField("Z", "ts_sclz", transformState.getScaleZ(), 0.01f, "%.3f",
                    0.25f, 0.45f, 0.90f);

            if (scaleChanged) {
                transformState.markUserInteraction();
                // Apply uniform: find which changed and sync all to it
                float sx = transformState.getScaleX().get();
                float sy = transformState.getScaleY().get();
                float sz = transformState.getScaleZ().get();
                // Determine which axis changed by checking what differs
                float uniform = Math.max(sx, Math.max(sy, sz));
                uniform = Math.min(Math.max(uniform, minScale), maxScale);
                transformState.getScaleX().set(uniform);
                transformState.getScaleY().set(uniform);
                transformState.getScaleZ().set(uniform);
                notifyTransformChanged();
            }
        } else {
            boolean scaleChanged = renderAxisField("X", "ts_sclx", transformState.getScaleX(), 0.01f, "%.3f",
                    0.85f, 0.25f, 0.25f);
            scaleChanged |= renderAxisField("Y", "ts_scly", transformState.getScaleY(), 0.01f, "%.3f",
                    0.25f, 0.72f, 0.25f);
            scaleChanged |= renderAxisField("Z", "ts_sclz", transformState.getScaleZ(), 0.01f, "%.3f",
                    0.25f, 0.45f, 0.90f);
            if (scaleChanged) {
                transformState.markUserInteraction();
                notifyTransformChanged();
            }
        }
    }

    /**
     * Render a labeled transform group with colored axis pill fields.
     */
    private boolean renderTransformGroup(String groupLabel, String id,
                                          ImFloat x, ImFloat y, ImFloat z,
                                          float speed, String format) {
        boolean changed = false;

        ImVec4 dimCol = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, dimCol.x, dimCol.y, dimCol.z, dimCol.w);
        ImGui.textUnformatted(groupLabel);
        ImGui.popStyleColor();

        boolean xChanged = renderAxisField("X", id + "x", x, speed, format, 0.85f, 0.25f, 0.25f);
        boolean yChanged = renderAxisField("Y", id + "y", y, speed, format, 0.25f, 0.72f, 0.25f);
        boolean zChanged = renderAxisField("Z", id + "z", z, speed, format, 0.25f, 0.45f, 0.90f);

        if (xChanged || yChanged || zChanged) {
            transformState.markUserInteraction();
            notifyTransformChanged();
            changed = true;
        }

        return changed;
    }

    /**
     * Render a single axis field: colored label pill + drag float filling remaining width.
     */
    private boolean renderAxisField(String axisLabel, String id, ImFloat value,
                                     float speed, String format,
                                     float colorR, float colorG, float colorB) {
        boolean changed = false;
        float pillWidth = 18.0f;
        float pillHeight = ImGui.getFrameHeight();
        float spacing = 4.0f;
        float fieldWidth = ImGui.getContentRegionAvailX() - pillWidth - spacing;

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursor = ImGui.getCursorScreenPos();

        // Draw colored pill background
        int pillColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 0.25f);
        int textColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 1.0f);
        drawList.addRectFilled(cursor.x, cursor.y,
                cursor.x + pillWidth, cursor.y + pillHeight, pillColor, 3.0f);

        // Center axis letter in pill
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, axisLabel);
        float textX = cursor.x + (pillWidth - textSize.x) * 0.5f;
        float textY = cursor.y + (pillHeight - textSize.y) * 0.5f;
        drawList.addText(textX, textY, textColor, axisLabel);

        // Advance past pill
        ImGui.dummy(pillWidth, pillHeight);
        ImGui.sameLine(0, spacing);

        // Drag float
        ImGui.pushItemWidth(fieldWidth);
        if (ImGui.dragFloat("##" + id, value.getData(), speed, 0, 0, format)) {
            changed = true;
        }
        ImGui.popItemWidth();

        return changed;
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
