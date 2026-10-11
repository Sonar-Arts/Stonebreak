package com.openmason.main.systems.menus.textureCreator.io;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Texture Editor's layered .OMT project format through real files: save
 * then load must reproduce every layer (name, visibility, opacity, pixels
 * including alpha), the active layer, and the optional face mappings and
 * material textures; malformed archives must load as {@code null}, never throw.
 */
@Tag("integration")
class OMTRoundTripTest {

    @TempDir
    Path dir;

    @Test
    @Tag("regression")
    void layersSurviveSaveAndLoadExactly() {
        LayerManager original = new LayerManager(5, 3);
        fillGradient(original.getLayer(0).getCanvas());
        PixelCanvas ink = new PixelCanvas(5, 3);
        ink.setPixel(0, 0, 0x80FF8040);  // semi-transparent
        ink.setPixel(4, 2, 0x00123456);  // fully transparent but with colour bits
        original.addLayerAt(1, new Layer("Ink", ink, false, 0.35f));
        PixelCanvas glow = new PixelCanvas(5, 3);
        glow.fill(0xFF00FFFF);
        original.addLayerAt(2, new Layer("Glow layer #2", glow, true, 0.5f));
        original.setActiveLayer(1);

        Path file = dir.resolve("project.omt");
        assertTrue(new OMTSerializer().save(original, file.toString()));
        LayerManager loaded = new OMTDeserializer().load(file.toString());

        assertNotNull(loaded);
        assertEquals(5, loaded.getCanvasWidth());
        assertEquals(3, loaded.getCanvasHeight());
        assertEquals(original.getLayerCount(), loaded.getLayerCount());
        assertEquals(1, loaded.getActiveLayerIndex());
        for (int i = 0; i < original.getLayerCount(); i++) {
            Layer expected = original.getLayer(i);
            Layer actual = loaded.getLayer(i);
            assertEquals(expected.getName(), actual.getName());
            assertEquals(expected.isVisible(), actual.isVisible());
            assertEquals(expected.getOpacity(), actual.getOpacity(), 0.0f);
            assertArrayEquals(expected.getCanvas().getPixels(), actual.getCanvas().getPixels(),
                    "pixels of layer " + i);
        }
    }

    @Test
    @Tag("regression")
    void saveAppendsTheExtensionOnlyWhenMissing() {
        LayerManager layers = new LayerManager(2, 2);
        OMTSerializer serializer = new OMTSerializer();

        assertTrue(serializer.save(layers, dir.resolve("plain").toString()));
        assertTrue(serializer.save(layers, dir.resolve("UPPER.OMT").toString()));

        assertTrue(Files.isRegularFile(dir.resolve("plain.omt")));
        assertTrue(Files.isRegularFile(dir.resolve("UPPER.OMT")));
        assertFalse(Files.exists(dir.resolve("UPPER.OMT.omt")), "an upper-case extension is already valid");
        assertNotNull(new OMTDeserializer().load(dir.resolve("UPPER.OMT").toString()));
    }

    @Test
    @Tag("regression")
    void faceMappingsMaterialsAndTheirTexturesRoundTrip() {
        LayerManager layers = new LayerManager(4, 4);
        layers.getLayer(0).getCanvas().fill(0xFF808080);
        List<OMTFormat.FaceMappingInfo> mappings = List.of(
                new OMTFormat.FaceMappingInfo(0, 0, 0f, 0f, 0.5f, 0.5f, 0),
                new OMTFormat.FaceMappingInfo(7, 1, 0.25f, 0.125f, 1f, 0.75f, 270));
        List<OMTFormat.MaterialInfo> materials = List.of(
                new OMTFormat.MaterialInfo(0, "Default", "OPAQUE", OMTFormat.generateMaterialFilename(0)),
                new OMTFormat.MaterialInfo(1, "Glass", "TRANSLUCENT", OMTFormat.generateMaterialFilename(1)));
        PixelCanvas glass = new PixelCanvas(2, 3); // material textures need not match the canvas
        glass.setPixel(1, 2, 0x40FFEEDD);
        Map<Integer, PixelCanvas> textures = new LinkedHashMap<>();
        textures.put(1, glass); // material 0 has no texture: skipped on save, absent on load

        Path file = dir.resolve("mapped.omt");
        assertTrue(new OMTSerializer().save(layers, mappings, materials, textures, file.toString()));
        OMTDeserializer.LoadResult result = new OMTDeserializer().loadFull(file.toString());

        assertNotNull(result);
        assertEquals(OMTFormat.FORMAT_VERSION, result.document().version());
        assertEquals(mappings, result.document().faceMappings());
        assertEquals(materials, result.document().materials());
        assertEquals(1, result.materialTextures().size());
        PixelCanvas loadedGlass = result.materialTextures().get(1);
        assertEquals(2, loadedGlass.getWidth());
        assertEquals(3, loadedGlass.getHeight());
        assertArrayEquals(glass.getPixels(), loadedGlass.getPixels());
    }

    @Test
    void legacyManifestWithoutMappingsLoadsWithEmptyLists() throws IOException {
        Path file = dir.resolve("legacy.omt");
        writeArchive(file, Map.of(
                "manifest.json", manifest("1.0", 2, 2, 0, "\"extraFieldFromTheFuture\": 42"),
                "layer_0.png", png(new PixelCanvas(2, 2))));

        OMTDeserializer.LoadResult result = new OMTDeserializer().loadFull(file.toString());

        assertNotNull(result);
        assertTrue(result.document().faceMappings().isEmpty());
        assertTrue(result.document().materials().isEmpty());
        assertTrue(result.materialTextures().isEmpty());
    }

    @Test
    void newerFormatVersionStillLoads() throws IOException {
        Path file = dir.resolve("future.omt");
        writeArchive(file, Map.of(
                "manifest.json", manifest("9.9", 2, 2, 0, null),
                "layer_0.png", png(new PixelCanvas(2, 2)),
                "notes.txt", "ignored".getBytes(StandardCharsets.UTF_8)));

        assertNotNull(new OMTDeserializer().load(file.toString()));
    }

    @Test
    void malformedArchivesLoadAsNull() throws IOException {
        byte[] layer2x2 = png(new PixelCanvas(2, 2));
        OMTDeserializer deserializer = new OMTDeserializer();

        Path notZip = dir.resolve("garbage.omt");
        Files.write(notZip, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertNull(deserializer.load(notZip.toString()), "not a zip");

        Path noManifest = dir.resolve("no-manifest.omt");
        writeArchive(noManifest, Map.of("layer_0.png", layer2x2));
        assertNull(deserializer.load(noManifest.toString()), "missing manifest");

        Path badJson = dir.resolve("bad-json.omt");
        writeArchive(badJson, Map.of("manifest.json", "{ not json".getBytes(StandardCharsets.UTF_8),
                "layer_0.png", layer2x2));
        assertNull(deserializer.load(badJson.toString()), "unparseable manifest");

        Path missingLayer = dir.resolve("missing-layer.omt");
        writeArchive(missingLayer, Map.of("manifest.json", manifest("1.0", 2, 2, 0, null)));
        assertNull(deserializer.load(missingLayer.toString()), "manifest names a layer file that is absent");

        Path wrongSize = dir.resolve("wrong-size.omt");
        writeArchive(wrongSize, Map.of("manifest.json", manifest("1.0", 2, 2, 0, null),
                "layer_0.png", png(new PixelCanvas(3, 2))));
        assertNull(deserializer.load(wrongSize.toString()), "layer PNG does not match the canvas size");

        Path corruptPng = dir.resolve("corrupt-png.omt");
        writeArchive(corruptPng, Map.of("manifest.json", manifest("1.0", 2, 2, 0, null),
                "layer_0.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0}));
        assertNull(deserializer.load(corruptPng.toString()), "undecodable PNG");

        Path badActive = dir.resolve("bad-active.omt");
        writeArchive(badActive, Map.of("manifest.json", manifest("1.0", 2, 2, 3, null),
                "layer_0.png", layer2x2));
        assertNull(deserializer.load(badActive.toString()), "active layer index out of range");

        Path badSize = dir.resolve("bad-size.omt");
        writeArchive(badSize, Map.of("manifest.json", manifest("1.0", 0, 2, 0, null),
                "layer_0.png", layer2x2));
        assertNull(deserializer.load(badSize.toString()), "non-positive canvas size");
    }

    @Test
    void wrongExtensionAndMissingFilesAreRejected() throws IOException {
        Path real = dir.resolve("real.omt");
        assertTrue(new OMTSerializer().save(new LayerManager(2, 2), real.toString()));
        Path renamed = dir.resolve("real.zip");
        Files.copy(real, renamed);
        OMTDeserializer deserializer = new OMTDeserializer();

        assertNull(deserializer.load(renamed.toString()));
        assertNull(deserializer.load(dir.resolve("absent.omt").toString()));
        assertNull(deserializer.load(""));
        assertNull(deserializer.load(null));

        assertTrue(deserializer.validateFilePath(real.toString()));
        assertFalse(deserializer.validateFilePath(renamed.toString()));
        assertFalse(deserializer.validateFilePath(dir.toString() + "/absent.omt"));
    }

    @Test
    void savingIntoAMissingDirectoryFailsCleanly() {
        Path target = dir.resolve("no/such/dir/tex.omt");

        assertFalse(new OMTSerializer().save(new LayerManager(2, 2), target.toString()));
        assertFalse(new OMTSerializer().save(null, dir.resolve("x.omt").toString()));
        assertFalse(new OMTSerializer().save(new LayerManager(2, 2), " "));
    }

    @Test
    void exporterAndImporterDelegateToTheOmtCodec() {
        LayerManager layers = new LayerManager(3, 3);
        layers.getLayer(0).getCanvas().setPixel(1, 1, 0xFF0000FF);
        Path file = dir.resolve("via-facade.omt");

        assertTrue(new TextureExporter().exportToOMT(layers, file.toString()));
        LayerManager loaded = new TextureImporter().importFromOMT(file.toString());

        assertNotNull(loaded);
        assertEquals(0xFF0000FF, loaded.getLayer(0).getCanvas().getPixel(1, 1));
        assertFalse(new TextureExporter().exportToOMT(null, file.toString()));
        assertNull(new TextureImporter().importFromOMT(" "));
    }

    // ------------------------------------------------------------------

    private static void fillGradient(PixelCanvas canvas) {
        for (int y = 0; y < canvas.getHeight(); y++) {
            for (int x = 0; x < canvas.getWidth(); x++) {
                canvas.setPixel(x, y, PixelCanvas.packRGBA(x * 50, y * 80, 255 - x * 40, 255));
            }
        }
    }

    private static byte[] png(PixelCanvas canvas) {
        return OMTSerializer.encodeRGBAToPNG(canvas.getPixelsAsRGBABytes(), canvas.getWidth(), canvas.getHeight());
    }

    private static byte[] manifest(String version, int width, int height, int active, String extraField) {
        String json = "{"
                + "\"version\":\"" + version + "\","
                + "\"canvasSize\":{\"width\":" + width + ",\"height\":" + height + "},"
                + "\"layers\":[{\"name\":\"Base\",\"visible\":true,\"opacity\":1.0,\"dataFile\":\"layer_0.png\"}],"
                + (extraField != null ? extraField + "," : "")
                + "\"activeLayerIndex\":" + active
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeArchive(Path file, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }
}
