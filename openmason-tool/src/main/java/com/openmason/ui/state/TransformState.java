package com.openmason.ui.state;

import imgui.type.ImFloat;
import imgui.type.ImString;

/**
 * Centralized transform state management.
 * Follows Single Responsibility Principle - only manages transform-related state.
 */
public class TransformState {

    // Transform properties
    private final ImFloat rotationX = new ImFloat(0.0f);
    private final ImFloat rotationY = new ImFloat(0.0f);
    private final ImFloat rotationZ = new ImFloat(0.0f);
    private final ImFloat scale = new ImFloat(1.0f);

    // Texture Variant State
    private final ImString textureVariant = new ImString("Default", 256);
    private final String[] textureVariants = {"Default", "Angus", "Highland", "Jersey"};
    private int currentTextureVariantIndex = 0;

    // Getters

    public ImFloat getRotationX() {
        return rotationX;
    }

    public ImFloat getRotationY() {
        return rotationY;
    }

    public ImFloat getRotationZ() {
        return rotationZ;
    }

    public ImFloat getScale() {
        return scale;
    }

    public ImString getTextureVariant() {
        return textureVariant;
    }

    public String[] getTextureVariants() {
        return textureVariants;
    }

    public int getCurrentTextureVariantIndex() {
        return currentTextureVariantIndex;
    }

    public void setCurrentTextureVariantIndex(int index) {
        if (index >= 0 && index < textureVariants.length) {
            this.currentTextureVariantIndex = index;
            this.textureVariant.set(textureVariants[index]);
        }
    }

    public String getCurrentTextureVariant() {
        return textureVariants[currentTextureVariantIndex];
    }

    /**
     * Reset all transform properties to defaults.
     */
    public void reset() {
        rotationX.set(0.0f);
        rotationY.set(0.0f);
        rotationZ.set(0.0f);
        scale.set(1.0f);
        currentTextureVariantIndex = 0;
        textureVariant.set("Default");
    }

    /**
     * Get texture variant index by name.
     */
    public int getVariantIndexByName(String variantName) {
        for (int i = 0; i < textureVariants.length; i++) {
            if (textureVariants[i].equalsIgnoreCase(variantName)) {
                return i;
            }
        }
        return 0; // Default to first variant if not found
    }
}
