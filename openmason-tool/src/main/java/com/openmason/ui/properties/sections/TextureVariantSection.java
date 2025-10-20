package com.openmason.ui.properties.sections;

import com.openmason.ui.properties.interfaces.IPanelSection;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Texture variant selection section component.
 * Handles texture variant dropdown and selection.
 * Follows SRP - single responsibility of texture variant UI.
 */
public class TextureVariantSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(TextureVariantSection.class);

    private String[] availableVariants = new String[0];
    private final ImInt selectedVariantIndex = new ImInt(0);
    private String selectedVariant = "default";
    private boolean visible = true;
    private Consumer<String> onVariantChanged;

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        if (ImGui.collapsingHeader("Texture Variants", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();

            if (availableVariants.length > 0) {
                ImGui.text("Current Variant:");
                if (ImGui.combo("##variant", selectedVariantIndex, availableVariants)) {
                    if (selectedVariantIndex.get() >= 0 && selectedVariantIndex.get() < availableVariants.length) {
                        String newVariant = availableVariants[selectedVariantIndex.get()];
                        if (!newVariant.equals(selectedVariant)) {
                            handleVariantChange(newVariant);
                        }
                    }
                }

                ImGui.text("Available: " + availableVariants.length + " variants");

            } else {
                ImGui.textDisabled("No variants available");
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
        return "Texture Variants";
    }

    // Public API

    /**
     * Set available texture variants.
     *
     * @param variants Array of variant names
     */
    public void setAvailableVariants(String[] variants) {
        this.availableVariants = variants;
        if (variants.length > 0) {
            selectedVariantIndex.set(0);
            selectedVariant = variants[0].toLowerCase();
        } else {
            selectedVariantIndex.set(0);
            selectedVariant = "default";
        }
    }

    /**
     * Set the variant changed callback.
     *
     * @param callback Callback invoked when variant changes
     */
    public void setOnVariantChanged(Consumer<String> callback) {
        this.onVariantChanged = callback;
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
     * Get the currently selected variant.
     *
     * @return The selected variant name
     */
    public String getSelectedVariant() {
        return selectedVariant;
    }

    /**
     * Get the number of available variants.
     *
     * @return Variant count
     */
    public int getVariantCount() {
        return availableVariants.length;
    }

    /**
     * Reset to default variant.
     */
    public void resetToDefault() {
        if (availableVariants.length > 0) {
            selectedVariantIndex.set(0);
            handleVariantChange("default");
        }
    }

    /**
     * Handle variant change and notify callback.
     */
    private void handleVariantChange(String newVariant) {
        selectedVariant = newVariant;
        logger.debug("Texture variant changed to: {}", newVariant);

        if (onVariantChanged != null) {
            onVariantChanged.accept(newVariant);
        }
    }
}
