package com.openmason.ui.textureCreator;

import com.openmason.ui.preferences.PreferencesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Texture creator preferences for rendering and UI settings.
 */
public class TextureCreatorPreferences {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorPreferences.class);

    // Opacity limits
    public static final float MIN_OPACITY = 0.0f;
    public static final float MAX_OPACITY = 1.0f;

    // Rotation speed limits (degrees per pixel)
    public static final float MIN_ROTATION_SPEED = 0.1f;
    public static final float MAX_ROTATION_SPEED = 2.0f;

    private final PreferencesManager preferencesManager;

    /**
     * Create preferences with persistence support.
     */
    public TextureCreatorPreferences(PreferencesManager preferencesManager) {
        this.preferencesManager = preferencesManager;
        logger.debug("Texture creator preferences initialized with persistence");
    }

    /**
     * Get grid opacity (for both minor and major grid lines).
     * @return opacity value (0.0 to 1.0)
     */
    public float getGridOpacity() {
        return preferencesManager.getTextureEditorGridOpacity();
    }

    /**
     * Set grid opacity (for both minor and major grid lines).
     * Auto-saves to preferences file.
     * @param opacity opacity value (0.0 to 1.0)
     */
    public void setGridOpacity(float opacity) {
        float clamped = clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        preferencesManager.setTextureEditorGridOpacity(clamped);
        logger.debug("Grid opacity set to: {} (saved to preferences)", clamped);
    }

    /**
     * Get cube net overlay opacity.
     * @return opacity value (0.0 to 1.0)
     */
    public float getCubeNetOverlayOpacity() {
        return preferencesManager.getTextureEditorCubeNetOverlayOpacity();
    }

    /**
     * Set cube net overlay opacity.
     * Auto-saves to preferences file.
     * @param opacity opacity value (0.0 to 1.0)
     */
    public void setCubeNetOverlayOpacity(float opacity) {
        float clamped = clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        preferencesManager.setTextureEditorCubeNetOverlayOpacity(clamped);
        logger.debug("Cube net overlay opacity set to: {} (saved to preferences)", clamped);
    }

    /**
     * Get rotation speed (degrees per pixel of mouse movement).
     * Higher values = faster rotation.
     * @return rotation speed (0.1 to 2.0)
     */
    public float getRotationSpeed() {
        return preferencesManager.getTextureEditorRotationSpeed();
    }

    /**
     * Set rotation speed (degrees per pixel of mouse movement).
     * Higher values = faster rotation.
     * Auto-saves to preferences file.
     * @param speed rotation speed (0.1 to 2.0)
     */
    public void setRotationSpeed(float speed) {
        float clamped = clamp(speed, MIN_ROTATION_SPEED, MAX_ROTATION_SPEED);
        preferencesManager.setTextureEditorRotationSpeed(clamped);
        logger.debug("Rotation speed set to: {} (saved to preferences)", clamped);
    }

    /**
     * Reset all preferences to default values.
     * Auto-saves to preferences file.
     */
    public void resetToDefaults() {
        preferencesManager.resetTextureCreatorToDefaults();
        logger.info("Texture creator preferences reset to defaults (saved to preferences)");
    }

    /**
     * Clamp value between min and max.
     * @param value value to clamp
     * @param min minimum value
     * @param max maximum value
     * @return clamped value
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Check if transparent pixels should be skipped on paste/move operations.
     */
    public boolean isSkipTransparentPixelsOnPaste() {
        return preferencesManager.getTextureEditorSkipTransparentPixelsOnPaste();
    }

    /**
     * Set whether transparent pixels should be skipped on paste/move operations.
     */
    public void setSkipTransparentPixelsOnPaste(boolean skip) {
        preferencesManager.setTextureEditorSkipTransparentPixelsOnPaste(skip);
        logger.debug("Skip transparent pixels on paste set to: {} (saved to preferences)", skip);
    }

    /**
     * Check if shape tool should draw filled shapes.
     */
    public boolean isShapeToolFillMode() {
        return preferencesManager.getTextureEditorShapeToolFillMode();
    }

    /**
     * Set whether shape tool should draw filled shapes.
     */
    public void setShapeToolFillMode(boolean fillMode) {
        preferencesManager.setTextureEditorShapeToolFillMode(fillMode);
        logger.debug("Shape tool fill mode set to: {} (saved to preferences)", fillMode);
    }

    /**
     * Get color history.
     * @return list of colors (packed RGBA ints), empty list if none
     */
    public List<Integer> getColorHistory() {
        return preferencesManager.getTextureEditorColorHistory();
    }

    /**
     * Set color history.
     */
    public void setColorHistory(List<Integer> colorHistory) {
        if (colorHistory == null) {
            colorHistory = new ArrayList<>();
        }
        preferencesManager.setTextureEditorColorHistory(colorHistory);
        logger.debug("Color history updated with {} colors (saved to preferences)", colorHistory.size());
    }

    @Override
    public String toString() {
        return String.format("TextureCreatorPreferences{gridOpacity=%.2f, cubeNetOverlayOpacity=%.2f, rotationSpeed=%.2f, colorHistory=%d colors}",
                           getGridOpacity(), getCubeNetOverlayOpacity(), getRotationSpeed(), getColorHistory().size());
    }
}
