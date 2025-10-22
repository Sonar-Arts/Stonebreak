package com.openmason.ui.components.textureCreator;

import com.openmason.ui.preferences.PreferencesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture creator preferences for rendering and UI settings.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only manages preferences state
 * - Provides observable properties for UI binding
 * - Delegates persistence to PreferencesManager
 *
 * @author Open Mason Team
 */
public class TextureCreatorPreferences {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorPreferences.class);

    // Opacity limits
    public static final float MIN_OPACITY = 0.0f;
    public static final float MAX_OPACITY = 1.0f;

    // Default opacity value (must match PreferencesManager default)
    public static final float DEFAULT_GRID_OPACITY = 0.5f;

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
     * Get grid alpha value as integer (0-255).
     * Useful for ImGui color calculations.
     * @return alpha value (0-255)
     */
    public int getGridAlpha() {
        return (int) (getGridOpacity() * 255.0f);
    }

    @Override
    public String toString() {
        return String.format("TextureCreatorPreferences{gridOpacity=%.2f}", getGridOpacity());
    }
}
