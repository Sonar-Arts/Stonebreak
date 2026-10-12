package com.openmason.main.systems.menus.textureCreator.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** .OMT manifest model validation and file-name helpers. */
class OMTFormatTest {

    private static final OMTFormat.CanvasSize SIZE = new OMTFormat.CanvasSize(16, 16);
    private static final OMTFormat.LayerInfo LAYER = new OMTFormat.LayerInfo("Base", true, 1f, "layer_0.png");

    /**
     * {@code hasValidExtension} used to return the opposite of its name and
     * javadoc (true for "x.png"); every caller compensated with an inverted
     * check, a trap for any new caller. It now means what it says.
     */
    @Test
    @Tag("regression")
    void hasValidExtensionMeansTheFileEndsInOmt() {
        assertTrue(OMTFormat.hasValidExtension("texture.omt"));
        assertTrue(OMTFormat.hasValidExtension("dir/Texture.OMT"));
        assertFalse(OMTFormat.hasValidExtension("texture.png"));
        assertFalse(OMTFormat.hasValidExtension("texture.omt.bak"));
        assertFalse(OMTFormat.hasValidExtension(""));
        assertFalse(OMTFormat.hasValidExtension(null));
    }

    @Test
    void ensureExtensionAppendsOnlyWhenMissing() {
        assertEquals("a.omt", OMTFormat.ensureExtension("a"));
        assertEquals("a.png.omt", OMTFormat.ensureExtension("a.png"));
        assertEquals("a.omt", OMTFormat.ensureExtension("a.omt"));
        assertEquals("a.OMT", OMTFormat.ensureExtension("a.OMT"));
        assertThrows(IllegalArgumentException.class, () -> OMTFormat.ensureExtension(" "));
    }

    @Test
    void generatedEntryNamesFollowTheArchiveConvention() {
        assertEquals("layer_0.png", OMTFormat.generateLayerFilename(0));
        assertEquals("layer_12.png", OMTFormat.generateLayerFilename(12));
        assertEquals("material_3.png", OMTFormat.generateMaterialFilename(3));
        assertThrows(IllegalArgumentException.class, () -> OMTFormat.generateLayerFilename(-1));
        assertThrows(IllegalArgumentException.class, () -> OMTFormat.generateMaterialFilename(-1));
    }

    @Test
    void documentRejectsInconsistentManifests() {
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.Document(" ", SIZE, List.of(LAYER), 0));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.Document("1.0", null, List.of(LAYER), 0));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.Document("1.0", SIZE, List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.Document("1.0", SIZE, List.of(LAYER), 1));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.Document("1.0", SIZE, List.of(LAYER), -1));
    }

    @Test
    void documentCopiesItsListsAndExposesThemReadOnly() {
        List<OMTFormat.LayerInfo> layers = new ArrayList<>(List.of(LAYER));
        OMTFormat.Document doc = new OMTFormat.Document("1.0", SIZE, layers, 0, null, null);
        layers.clear();

        assertEquals(1, doc.layers().size());
        assertTrue(doc.faceMappings().isEmpty());
        assertTrue(doc.materials().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> doc.layers().add(LAYER));
    }

    @Test
    void entryRecordsValidateTheirFields() {
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.CanvasSize(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.LayerInfo("", true, 1f, "layer_0.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.LayerInfo("L", true, 1.01f, "layer_0.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.LayerInfo("L", true, -0.01f, "layer_0.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.LayerInfo("L", true, 0.5f, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new OMTFormat.FaceMappingInfo(0, 0, 0, 0, 1, 1, 45));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.MaterialInfo(-1, "M", "OPAQUE", "m.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.MaterialInfo(0, " ", "OPAQUE", "m.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.MaterialInfo(0, "M", "", "m.png"));
        assertThrows(IllegalArgumentException.class, () -> new OMTFormat.MaterialInfo(0, "M", "OPAQUE", null));

        for (int rotation : new int[]{0, 90, 180, 270}) {
            assertEquals(rotation, new OMTFormat.FaceMappingInfo(1, 0, 0, 0, 1, 1, rotation).uvRotation());
        }
        assertEquals(new OMTFormat.CanvasSize(4, 2), new OMTFormat.CanvasSize(4, 2));
    }
}
