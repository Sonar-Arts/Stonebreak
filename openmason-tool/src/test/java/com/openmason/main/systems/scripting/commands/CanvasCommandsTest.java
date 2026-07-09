package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.scripting.doc.FakeCanvasSurface;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasCommandsTest {

    private static final int[] RED = {255, 0, 0, 255};
    private static final int[] GREEN = {0, 255, 0, 255};
    private static final int RED_PACKED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int TRANSPARENT = 0;

    private FakeCanvasSurface surface;
    private ModelCommands cmds;
    private CanvasCommands canvas;

    @BeforeEach
    void setUp() {
        surface = new FakeCanvasSurface(16, 16);
        cmds = new ModelCommands(new HeadlessModelDocument(), new ObjectMapper(), null, surface);
        canvas = cmds.canvas();
    }

    private int activePixel(int x, int y) {
        return surface.activeCanvas().getPixel(x, y);
    }

    // ===================== Painting =====================

    @Test
    void paintPrimitivesChangeTheActiveLayer() {
        assertEquals(16 * 16, canvas.fill(null, RED));
        assertEquals(RED_PACKED, activePixel(0, 0));
        assertEquals(RED_PACKED, activePixel(15, 15));

        // Sub-rect fill and outline rect.
        canvas.fill(new int[]{0, 0, 4, 4}, GREEN);
        assertEquals(PixelCanvas.packRGBA(0, 255, 0, 255), activePixel(3, 3));
        assertEquals(RED_PACKED, activePixel(4, 4));
        canvas.rect(new int[]{8, 8, 4, 4}, GREEN, false);
        assertEquals(PixelCanvas.packRGBA(0, 255, 0, 255), activePixel(8, 8));
        assertEquals(RED_PACKED, activePixel(9, 9), "outline rect leaves the interior");

        // Line + set_pixels, verified through the region query too.
        canvas.line(0, 15, 15, 0, new int[]{0, 0, 255, 255});
        assertEquals(PixelCanvas.packRGBA(0, 0, 255, 255), activePixel(0, 15));
        canvas.setPixels(new int[]{5, 5, 9, 8, 7, 6});
        assertArrayEquals(new int[]{9, 8, 7, 6}, canvas.region(5, 5, 1, 1).rgba());

        assertTrue(surface.modifiedCount() > 0, "each paint must invalidate the preview");
    }

    @Test
    void activeSelectionConstrainsWrites() {
        SelectionManager selection = new SelectionManager();
        selection.setActiveSelection(new RectangularSelection(0, 0, 7, 7));
        surface.activeCanvas().setSelectionManager(selection);

        int changed = canvas.fill(null, RED);
        assertEquals(8 * 8, changed, "only pixels inside the selection change");
        assertEquals(RED_PACKED, activePixel(0, 0));
        assertEquals(RED_PACKED, activePixel(7, 7));
        assertEquals(TRANSPARENT, activePixel(8, 8));
        assertEquals(TRANSPARENT, activePixel(15, 15));

        // Flood is blocked at the selection edge as well.
        int flooded = canvas.flood(1, 1, GREEN);
        assertEquals(8 * 8, flooded);
        assertEquals(TRANSPARENT, activePixel(8, 1));
    }

    @Test
    void outOfBoundsPixelsAreSilentlySkipped() {
        int changed = canvas.setPixels(new int[]{
                -1, -1, 255, 0, 0, 255,
                100, 100, 255, 0, 0, 255,
                3, 3, 255, 0, 0, 255});
        assertEquals(1, changed);
        assertEquals(RED_PACKED, activePixel(3, 3));
    }

    // ===================== Layers =====================

    @Test
    void layerLifecycleAndValidation() {
        CanvasCommands.LayerInfo top = canvas.addLayer("Top");
        assertEquals(1, top.index());
        assertTrue(top.active());
        assertEquals("Top", top.name());
        assertEquals(2, canvas.layerInfos().size());

        // Painting hits the new ACTIVE layer, not the background.
        canvas.fill(null, RED);
        assertEquals(RED_PACKED, surface.layers().getLayer(1).getCanvas().getPixel(0, 0));
        assertEquals(TRANSPARENT, surface.layers().getLayer(0).getCanvas().getPixel(0, 0));

        CanvasCommands.LayerInfo updated =
                canvas.setLayer(0, true, false, "Base", 0.5f);
        assertTrue(updated.active());
        assertFalse(updated.visible());
        assertEquals("Base", updated.name());
        assertEquals(0.5f, updated.opacity(), 1e-6);
        assertEquals(0, surface.layers().getActiveLayerIndex());

        // At least one field required; opacity range checked; index checked.
        assertThrows(CommandException.class, () -> canvas.setLayer(0, null, null, null, null));
        assertThrows(CommandException.class, () -> canvas.setLayer(0, null, null, null, 1.5f));
        assertThrows(CommandException.class, () -> canvas.setLayer(5, true, null, null, null));

        canvas.removeLayer(1);
        assertEquals(1, canvas.layerInfos().size());
        CommandException last = assertThrows(CommandException.class, () -> canvas.removeLayer(0));
        assertTrue(last.getMessage().contains("last layer"));
        assertThrows(CommandException.class, () -> canvas.removeLayer(7));
    }

    // ===================== Export (deferred) =====================

    @Test
    void exportValidatesPathAndDefersUntilFlush(@TempDir Path tmp) {
        CommandException relative = assertThrows(CommandException.class,
                () -> canvas.exportPng("out/canvas.png"));
        assertTrue(relative.hint().contains("absolute"));
        assertThrows(CommandException.class, () -> canvas.exportPng("  "));

        String path = tmp.resolve("canvas.png").toString();
        canvas.exportPng(path);
        assertTrue(surface.exportedPaths().isEmpty(),
                "exportPng must only queue — a failing script writes nothing");

        List<String> written = canvas.flushExports();
        assertEquals(List.of(path), written);
        assertEquals(List.of(path), surface.exportedPaths());

        // Queue drained: a second flush writes nothing more.
        assertTrue(canvas.flushExports().isEmpty());

        // A surface write failure surfaces as a teaching error at flush time.
        canvas.exportPng(path);
        surface.failExports();
        CommandException failed = assertThrows(CommandException.class, canvas::flushExports);
        assertTrue(failed.getMessage().contains(path));
    }

    // ===================== Teaching errors =====================

    @Test
    void nullSurfaceGetsTeachingError() {
        CanvasCommands closed = new ModelCommands(
                new HeadlessModelDocument(), new ObjectMapper()).canvas();
        CommandException e = assertThrows(CommandException.class, () -> closed.fill(null, RED));
        assertTrue(e.getMessage().contains("texture editor"));
        assertTrue(e.hint().contains("live"));
        assertThrows(CommandException.class, () -> closed.addLayer("x"));
        assertThrows(CommandException.class, () -> closed.exportPng("/tmp/x.png"));
    }

    // ===================== Journal =====================

    @Test
    void beforeSnapshotRollsTheWholeStackBack() {
        assertFalse(surface.touched());
        canvas.fill(null, RED);
        canvas.addLayer("Top");
        canvas.fill(null, GREEN);
        canvas.setLayer(0, null, null, "Renamed", 0.25f);
        assertTrue(surface.touched(), "first mutation must capture the before-snapshot");

        surface.rollback();
        assertEquals(1, surface.layers().getLayerCount());
        assertEquals("Background", surface.layers().getLayer(0).getName());
        assertEquals(1.0f, surface.layers().getLayer(0).getOpacity(), 1e-6);
        assertEquals(TRANSPARENT, activePixel(0, 0), "pixels revert to the pre-run state");
    }

    // ===================== Trace =====================

    @Test
    void canvasOpsAreTraced(@TempDir Path tmp) {
        canvas.fill(null, RED);
        canvas.line(0, 0, 3, 3, GREEN);
        canvas.addLayer("Top");
        canvas.setLayer(0, true, null, null, null);
        canvas.removeLayer(1);
        canvas.setPixels(new int[]{1, 1, 2, 2, 2, 255});
        canvas.exportPng(tmp.resolve("x.png").toString());

        List<String> ops = cmds.opsTrace().stream().map(n -> n.get("op").asText()).toList();
        assertEquals(List.of("canvas_fill", "canvas_line", "canvas_add_layer",
                "canvas_set_layer", "canvas_remove_layer", "canvas_set_pixels",
                "canvas_export_png"), ops);
    }
}
