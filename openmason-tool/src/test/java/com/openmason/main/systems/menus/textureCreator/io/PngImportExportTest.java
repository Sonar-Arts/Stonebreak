package com.openmason.main.systems.menus.textureCreator.io;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PNG export/import through real files (STB), the import-resize rule
 * (nearest neighbour) and drag-and-drop import (PNG centred/cropped as a new
 * layer, .OMT flattened or layer-by-layer, size mismatches refused).
 */
@Tag("integration")
class PngImportExportTest {

    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;

    @TempDir
    Path dir;

    @Test
    @Tag("regression")
    void pngRoundTripPreservesEveryChannelIncludingAlpha() {
        PixelCanvas canvas = new PixelCanvas(4, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                canvas.setPixel(x, y, PixelCanvas.packRGBA(x * 60, y * 100, 200 - x * 10, 40 + x * 50 + y));
            }
        }
        canvas.setPixel(0, 0, 0x00ABCDEF); // alpha 0 with colour bits

        Path file = dir.resolve("roundtrip.png");
        assertTrue(new TextureExporter().exportToPNG(canvas, file.toString()));
        PixelCanvas loaded = new TextureImporter().importFromPNG(file.toString());

        assertNotNull(loaded);
        assertEquals(4, loaded.getWidth());
        assertEquals(3, loaded.getHeight());
        assertArrayEquals(canvas.getPixels(), loaded.getPixels());
    }

    @Test
    void exportAppendsPngExtensionAndRejectsBadInput() {
        PixelCanvas canvas = new PixelCanvas(2, 2);
        TextureExporter exporter = new TextureExporter();

        assertTrue(exporter.exportToPNG(canvas, dir.resolve("noext").toString()));
        assertTrue(Files.isRegularFile(dir.resolve("noext.png")));
        assertTrue(exporter.exportToPNG(canvas, dir.resolve("UPPER.PNG").toString()));
        assertFalse(Files.exists(dir.resolve("UPPER.PNG.png")));

        assertFalse(exporter.exportToPNG(null, dir.resolve("x.png").toString()));
        assertFalse(exporter.exportToPNG(canvas, "  "));
        assertFalse(exporter.exportToPNG(canvas, dir.resolve("missing/dir/x.png").toString()));
    }

    @Test
    void dimensionsAreReadWithoutDecoding() {
        Path file = writePng("dims.png", new PixelCanvas(7, 5));

        assertArrayEquals(new int[]{7, 5}, new TextureImporter().getPNGDimensions(file.toString()));
        assertNull(new TextureImporter().getPNGDimensions(dir.resolve("absent.png").toString()));
        assertNull(new TextureImporter().getPNGDimensions(""));
    }

    @Test
    void corruptOrMissingPngImportsAsNull() throws IOException {
        Path corrupt = dir.resolve("corrupt.png");
        Files.write(corrupt, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0});
        TextureImporter importer = new TextureImporter();

        assertNull(importer.importFromPNG(corrupt.toString()));
        assertNull(importer.importFromPNG(dir.resolve("absent.png").toString()));
        assertNull(importer.importFromPNG(null));
        assertNull(importer.importFromPNGResized(corrupt.toString(), 4, 4));
        assertNull(importer.getPNGDimensions(corrupt.toString()));
    }

    @Test
    void resizedImportUsesNearestNeighbour() {
        PixelCanvas quad = new PixelCanvas(2, 2);
        quad.setPixel(0, 0, RED);
        quad.setPixel(1, 0, GREEN);
        quad.setPixel(0, 1, BLUE);
        quad.setPixel(1, 1, WHITE);
        Path file = writePng("quad.png", quad);
        TextureImporter importer = new TextureImporter();

        PixelCanvas up = importer.importFromPNGResized(file.toString(), 4, 4);
        assertArrayEquals(new int[]{
                RED, RED, GREEN, GREEN,
                RED, RED, GREEN, GREEN,
                BLUE, BLUE, WHITE, WHITE,
                BLUE, BLUE, WHITE, WHITE}, up.getPixels());

        PixelCanvas down = importer.importFromPNGResized(file.toString(), 1, 1);
        assertArrayEquals(new int[]{RED}, down.getPixels(), "downscale samples the top-left source pixel");

        PixelCanvas same = importer.importFromPNGResized(file.toString(), 2, 2);
        assertArrayEquals(quad.getPixels(), same.getPixels());
    }

    @Test
    void droppedSmallPngIsCentredOnANewLayer() {
        PixelCanvas small = new PixelCanvas(2, 2);
        small.fill(RED);
        Path file = writePng("sticker.png", small);
        LayerManager layers = new LayerManager(6, 4);
        DragDropHandler handler = new DragDropHandler();

        int imported = handler.processDroppedPNGFiles(new String[]{file.toString()}, layers);

        assertEquals(1, imported);
        assertEquals(2, layers.getLayerCount());
        Layer layer = layers.getLayer(1);
        assertEquals("sticker", layer.getName());
        assertEquals(1, layers.getActiveLayerIndex(), "the dropped layer becomes active");
        assertEquals(new Rectangle(2, 1, 2, 2), handler.getLastImportedBounds());
        PixelCanvas canvas = layer.getCanvas();
        assertEquals(6, canvas.getWidth());
        assertEquals(RED, canvas.getPixel(2, 1));
        assertEquals(RED, canvas.getPixel(3, 2));
        assertEquals(0, canvas.getPixel(1, 1));
        assertEquals(0, canvas.getPixel(4, 2));
    }

    @Test
    void droppedLargePngIsCentredAndCropped() {
        PixelCanvas big = new PixelCanvas(6, 6);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                big.setPixel(x, y, PixelCanvas.packRGBA(x * 40, y * 40, 0, 255));
            }
        }
        Path file = writePng("big.png", big);
        LayerManager layers = new LayerManager(2, 2);

        assertEquals(1, new DragDropHandler().processDroppedPNGFiles(new String[]{file.toString()}, layers));

        PixelCanvas canvas = layers.getLayer(1).getCanvas();
        assertEquals(big.getPixel(2, 2), canvas.getPixel(0, 0), "the centre 2x2 of the source is kept");
        assertEquals(big.getPixel(3, 3), canvas.getPixel(1, 1));
    }

    @Test
    void nonPngAndBrokenDropsAreSkippedWithoutStoppingTheBatch() throws IOException {
        Path good = writePng("good.png", new PixelCanvas(2, 2));
        Path text = dir.resolve("readme.txt");
        Files.writeString(text, "hello");
        Path broken = dir.resolve("broken.png");
        Files.write(broken, new byte[]{1, 2, 3});
        LayerManager layers = new LayerManager(2, 2);

        int imported = new DragDropHandler().processDroppedPNGFiles(
                new String[]{text.toString(), broken.toString(), good.toString()}, layers);

        assertEquals(1, imported);
        assertEquals(2, layers.getLayerCount());
        assertEquals(0, new DragDropHandler().processDroppedPNGFiles(null, layers));
    }

    @Test
    void droppedOmtCanBeFlattenedOrImportedLayerByLayer() throws Exception {
        LayerManager source = new LayerManager(3, 3);
        source.getLayer(0).getCanvas().fill(RED);
        PixelCanvas top = new PixelCanvas(3, 3);
        top.setPixel(1, 1, BLUE);
        source.addLayerAt(1, new Layer("Top", top, true, 1f));
        source.addLayerAt(2, new Layer("Hidden", new PixelCanvas(3, 3), false, 0.5f));
        Path file = dir.resolve("art.omt");
        assertTrue(new OMTSerializer().save(source, file.toString()));
        DragDropHandler handler = new DragDropHandler();

        LayerManager flattened = new LayerManager(3, 3);
        handler.importOMTFlattened(file.toString(), flattened);
        assertEquals(2, flattened.getLayerCount());
        assertEquals("art", flattened.getLayer(1).getName());
        assertEquals(BLUE, flattened.getLayer(1).getCanvas().getPixel(1, 1));
        assertEquals(RED, flattened.getLayer(1).getCanvas().getPixel(0, 0));

        LayerManager all = new LayerManager(3, 3);
        handler.importOMTAllLayers(file.toString(), all);
        assertEquals(4, all.getLayerCount());
        assertEquals("Top", all.getLayer(2).getName());
        assertFalse(all.getLayer(3).isVisible());
        assertEquals(0.5f, all.getLayer(3).getOpacity(), 0f);
    }

    @Test
    void droppedOmtOfAnotherSizeIsRefused() {
        Path file = dir.resolve("other.omt");
        assertTrue(new OMTSerializer().save(new LayerManager(4, 4), file.toString()));
        LayerManager target = new LayerManager(3, 3);
        DragDropHandler handler = new DragDropHandler();

        assertThrows(Exception.class, () -> handler.importOMTFlattened(file.toString(), target));
        assertThrows(Exception.class, () -> handler.importOMTAllLayers(file.toString(), target));
        assertThrows(Exception.class, () -> handler.importOMTAllLayers(dir.resolve("absent.omt").toString(), target));
        assertEquals(1, target.getLayerCount(), "a refused drop adds nothing");
    }

    private Path writePng(String name, PixelCanvas canvas) {
        Path file = dir.resolve(name);
        assertTrue(new TextureExporter().exportToPNG(canvas, file.toString()));
        return file;
    }
}
