package com.openmason.main.systems.menus.textureCreator.palette;

import com.openmason.main.systems.menus.preferences.PreferencesManager;

import java.util.List;

/**
 * Loads and saves the texture editor palette through {@link PreferencesManager}
 * (comma-separated hex, same scheme as the color history).
 */
public final class PalettePersistence {

    private final PreferencesManager preferences;

    public PalettePersistence(PreferencesManager preferences) {
        this.preferences = preferences;
    }

    /**
     * Load the persisted palette into a model, or the DB32 default when
     * nothing has been persisted yet.
     */
    public PaletteModel load() {
        List<Integer> saved = preferences.getTextureEditorPalette();
        if (saved.isEmpty()) {
            return PaletteModel.createDefault();
        }
        PaletteModel model = new PaletteModel();
        model.setSwatches(saved);
        return model;
    }

    public void save(PaletteModel model) {
        preferences.setTextureEditorPalette(model.getSwatches());
    }
}
