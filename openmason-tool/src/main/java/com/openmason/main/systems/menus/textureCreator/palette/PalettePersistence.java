package com.openmason.main.systems.menus.textureCreator.palette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and saves the saved-palettes library through
 * {@link PreferencesManager} as a single JSON property:
 * {@code {"active":"Default","palettes":{"Default":["FF0000FF",...]}}}.
 * Colors serialize as RRGGBBAA hex (same convention as the color history).
 *
 * Migrates the legacy single-palette comma-separated property
 * ({@code texture.editor.palette}) into a "Default" palette on first load.
 */
public final class PalettePersistence {

    private static final Logger logger = LoggerFactory.getLogger(PalettePersistence.class);

    private final PreferencesManager preferences;
    private final ObjectMapper mapper = new ObjectMapper();

    public PalettePersistence(PreferencesManager preferences) {
        this.preferences = preferences;
    }

    /**
     * Load the persisted library; falls back to the legacy single palette,
     * then to the DB32 default.
     */
    public PaletteLibrary load() {
        PaletteLibrary library = new PaletteLibrary();
        Map<String, List<Integer>> palettes = new LinkedHashMap<>();
        String active = PaletteLibrary.DEFAULT_PALETTE_NAME;

        String json = preferences.getTextureEditorPalettesJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = mapper.readTree(json);
                JsonNode palettesNode = root.path("palettes");
                palettesNode.fields().forEachRemaining(entry -> {
                    List<Integer> colors = new java.util.ArrayList<>();
                    for (JsonNode hex : entry.getValue()) {
                        try {
                            colors.add((int) Long.parseLong(hex.asText(), 16));
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid palette color: {}", hex.asText());
                        }
                    }
                    palettes.put(entry.getKey(), colors);
                });
                if (root.hasNonNull("active")) {
                    active = root.get("active").asText();
                }
            } catch (Exception e) {
                logger.error("Failed to parse palettes JSON — using defaults", e);
                palettes.clear();
            }
        }

        // Legacy migration: single comma-separated palette property
        if (palettes.isEmpty()) {
            List<Integer> legacy = preferences.getTextureEditorPalette();
            if (!legacy.isEmpty()) {
                palettes.put(PaletteLibrary.DEFAULT_PALETTE_NAME, legacy);
                logger.info("Migrated legacy single palette ({} colors)", legacy.size());
            }
        }

        library.setAll(palettes, active); // empty map → DB32 default inside
        return library;
    }

    public void save(PaletteLibrary library) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("active", library.getActiveName());
            ObjectNode palettesNode = root.putObject("palettes");
            for (Map.Entry<String, List<Integer>> entry : library.getAll().entrySet()) {
                ArrayNode colors = palettesNode.putArray(entry.getKey());
                for (int color : entry.getValue()) {
                    colors.add(String.format("%08X", color));
                }
            }
            preferences.setTextureEditorPalettesJson(mapper.writeValueAsString(root));
        } catch (Exception e) {
            logger.error("Failed to save palettes", e);
        }
    }
}
