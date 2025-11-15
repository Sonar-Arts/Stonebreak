package com.openmason.ui.properties.sections;

import com.openmason.ui.preferences.PreferencesPageRenderer;
import com.openmason.ui.properties.interfaces.IPanelSection;
import imgui.ImGui;

/**
 * Model information section component.
 * Displays model statistics (parts, vertices, triangles, variants).
 * Follows SRP - single responsibility of displaying model info.
 */
public class ModelInfoSection implements IPanelSection {

    private String currentModelName;
    private int partCount = 0;
    private int vertexCount = 0;
    private int triangleCount = 0;
    private int variantCount = 0;
    private boolean visible = true;

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        // Use compact blue header box with JetBrains Mono Bold
        PreferencesPageRenderer.renderCompactSectionHeader("Model Information");

        if (currentModelName != null) {
            ImGui.text("Model: " + currentModelName);
            ImGui.text("Parts: " + partCount);
            ImGui.text("Vertices: " + vertexCount);
            ImGui.text("Triangles: " + triangleCount);
            ImGui.text("Variants: " + variantCount);
        } else {
            ImGui.textDisabled("No model loaded");
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Model Information";
    }

    // Public API for updating model information

    /**
     * Set the current model name and update statistics.
     *
     * @param modelName The model name
     */
    public void setModelName(String modelName) {
        this.currentModelName = modelName;
        updateStatistics();
    }

    /**
     * Set the variant count.
     *
     * @param count Number of available variants
     */
    public void setVariantCount(int count) {
        this.variantCount = count;
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
     * Get the current model name.
     *
     * @return The model name, or null if no model is loaded
     */
    public String getCurrentModelName() {
        return currentModelName;
    }

    /**
     * Update model statistics based on model type.
     */
    private void updateStatistics() {
        if (currentModelName == null) {
            partCount = 0;
            vertexCount = 0;
            triangleCount = 0;
            return;
        }

        // Calculate statistics based on model type
        if (currentModelName.toLowerCase().contains("cow")) {
            partCount = 14;
            vertexCount = partCount * 24;
            triangleCount = partCount * 12;
        } else {
            partCount = 0;
            vertexCount = 0;
            triangleCount = 0;
        }
    }
}
