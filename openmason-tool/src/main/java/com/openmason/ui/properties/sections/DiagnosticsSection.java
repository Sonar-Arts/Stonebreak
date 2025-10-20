package com.openmason.ui.properties.sections;

import com.openmason.rendering.ModelRenderer;
import com.openmason.ui.properties.interfaces.IPanelSection;
import com.openmason.ui.properties.interfaces.IViewportConnector;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Diagnostics section component (advanced mode only).
 * Shows actual rendered transformation matrices from GPU.
 * Follows SRP - single responsibility of diagnostic display.
 */
public class DiagnosticsSection implements IPanelSection {

    private IViewportConnector viewportConnector;
    private boolean visible = false; // Hidden by default (advanced mode only)

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        if (ImGui.collapsingHeader("Diagnostics")) {
            ImGui.indent();

            renderDiagnosticContent();

            ImGui.unindent();
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Diagnostics";
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
     * Set visibility of this section.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    // Private helper methods

    /**
     * Render diagnostic content.
     */
    private void renderDiagnosticContent() {
        if (viewportConnector == null || !viewportConnector.isConnected()) {
            ImGui.textColored(1.0f, 0.6f, 0.6f, 1.0f, "Viewport not available");
            ImGui.text("Diagnostics require an active 3D viewport");
            return;
        }

        Object rendererObj = viewportConnector.getModelRenderer();
        if (!(rendererObj instanceof ModelRenderer)) {
            ImGui.textColored(1.0f, 0.6f, 0.6f, 1.0f, "Model renderer not available");
            return;
        }

        ModelRenderer renderer = (ModelRenderer) rendererObj;

        ImGui.text("Actual Rendered Coordinates:");
        ImGui.text("(Retrieved from GPU transformation matrices)");
        ImGui.separator();

        Map<String, Matrix4f> transforms = renderer.getRenderedTransformations();

        if (transforms.isEmpty()) {
            ImGui.textColored(0.8f, 0.8f, 0.0f, 1.0f, "No render data available");
            ImGui.text("Model must be rendered at least once");
        } else {
            renderPartDiagnostics(renderer, transforms);
        }

        if (ImGui.button("Refresh Diagnostics")) {
            // Diagnostics are automatically updated on each render
        }
    }

    /**
     * Render diagnostics for each model part.
     */
    private void renderPartDiagnostics(ModelRenderer renderer, Map<String, Matrix4f> transforms) {
        for (String partName : transforms.keySet()) {
            ModelRenderer.DiagnosticData data = renderer.getPartDiagnostics(partName);
            if (data != null) {
                if (ImGui.treeNode(partName)) {
                    renderPartData(data);
                    ImGui.treePop();
                }
            }
        }

        ImGui.separator();
        ImGui.text(String.format("Total parts rendered: %d", transforms.size()));
    }

    /**
     * Render diagnostic data for a single part.
     */
    private void renderPartData(ModelRenderer.DiagnosticData data) {
        // Position
        ImGui.text(String.format("Position: (%.3f, %.3f, %.3f)",
            data.position.x, data.position.y, data.position.z));

        // Rotation (in degrees)
        Vector3f rotDeg = data.getRotationDegrees();
        ImGui.text(String.format("Rotation: (%.1f°, %.1f°, %.1f°)",
            rotDeg.x, rotDeg.y, rotDeg.z));

        // Scale
        ImGui.text(String.format("Scale: (%.3f, %.3f, %.3f)",
            data.scale.x, data.scale.y, data.scale.z));

        // Matrix (collapsed by default)
        if (ImGui.treeNode("Transformation Matrix")) {
            renderTransformMatrix(data.transformMatrix);
            ImGui.treePop();
        }
    }

    /**
     * Render a 4x4 transformation matrix.
     */
    private void renderTransformMatrix(Matrix4f m) {
        ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m00(), m.m01(), m.m02(), m.m03()));
        ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m10(), m.m11(), m.m12(), m.m13()));
        ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m20(), m.m21(), m.m22(), m.m23()));
        ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m30(), m.m31(), m.m32(), m.m33()));
    }
}
