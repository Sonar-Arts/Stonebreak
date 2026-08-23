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

    // ===================== Shapes / outline / grid =====================

    @Test
    void ellipseFilledAndRing() {
        assertTrue(canvas.ellipse(new int[]{0, 0, 16, 16}, RED, true) > 0);
        assertEquals(RED_PACKED, activePixel(8, 8), "centre is inside");
        assertEquals(TRANSPARENT, activePixel(0, 0), "corner is outside the disc");

        surface = new FakeCanvasSurface(16, 16);
        cmds = new ModelCommands(new HeadlessModelDocument(), new ObjectMapper(), null, surface);
        canvas = cmds.canvas();
        canvas.ellipse(new int[]{0, 0, 16, 16}, RED, false);
        assertEquals(TRANSPARENT, activePixel(8, 8), "ring leaves the interior");
        assertEquals(RED_PACKED, activePixel(8, 0), "top of the ring");
        assertThrows(CommandException.class, () -> canvas.ellipse(null, RED, true));
    }

    @Test
    void outlineGrowsAndNeverGoesBlack() {
        canvas.fill(new int[]{4, 4, 4, 4}, RED);
        int changed = canvas.outline(false, null);
        assertEquals(16, changed, "4 sides x 4 pixels of outer border");
        int edge = activePixel(3, 4);
        int[] c = PixelCanvas.unpackRGBA(edge);
        assertTrue(c[0] > 0 && c[0] < 255, "darker red, not black: " + c[0]);
        assertTrue(c[2] > 0, "outline is pushed cool (some blue)");
        assertEquals(RED_PACKED, activePixel(4, 4), "outer outline does not touch the body");

        canvas.outline(true, GREEN);
        assertEquals(PixelCanvas.packRGBA(0, 255, 0, 255), activePixel(3, 4),
                "inner outline recolours the (now larger) silhouette's edge row with the fixed colour");
    }

    @Test
    void paintGridAndDescribeRoundTrip() {
        int changed = canvas.paintGrid(List.of("AB", ".A"), java.util.Map.of("A", "#ff0000", "B", "0,255,0"),
                2, 3, false);
        assertEquals(3, changed);
        assertEquals(RED_PACKED, activePixel(2, 3));
        assertEquals(PixelCanvas.packRGBA(0, 255, 0, 255), activePixel(3, 3));
        assertEquals(TRANSPARENT, activePixel(2, 4));
        assertEquals(RED_PACKED, activePixel(3, 4));

        var d = canvas.describe(null, new int[]{2, 3, 2, 2}, 0, 72, true, true);
        assertEquals(List.of("AB", ".A"), d.rows());
        assertEquals("#ff0000", d.legend().get(0).hex());
        assertEquals(List.of("y3: A@2 B@3", "y4: A@3"), d.rle());
        assertEquals("y3|ff0000 00ff00", d.hexRows().get(0));
        assertEquals("canvas_paint_grid", cmds.opsTrace().get(0).get("op").asText());

        assertThrows(CommandException.class,
                () -> canvas.paintGrid(List.of("Z"), java.util.Map.of(), 0, 0, false));
        assertThrows(CommandException.class,
                () -> canvas.describe(null, new int[]{10, 10, 10, 10}, 0, 72, false, false));
    }

    @Test
    void describeReadsCompositeAndSpecificLayer() {
        canvas.fill(null, RED);
        canvas.addLayer("top");
        canvas.fill(new int[]{0, 0, 1, 1}, GREEN);
        assertEquals("A", canvas.describe(null, new int[]{0, 0, 1, 1}, 0, 72, false, false)
                .rows().get(0), "active (top) layer sees only green");
        assertEquals(2, canvas.describe(-1, null, 0, 72, false, false).legendSize(),
                "composite sees red + green");
        assertEquals(1, canvas.describe(0, null, 0, 72, false, false).legendSize());
        assertThrows(CommandException.class, () -> canvas.describe(5, null, 0, 72, false, false));
    }

    // ===================== Layer reorder / duplicate / merge =====================

    @Test
    void moveDuplicateAndMergeLayers() {
        canvas.fill(null, RED);                       // layer 0: red
        canvas.addLayer("top");                       // layer 1 active, empty
        canvas.fill(new int[]{0, 0, 2, 2}, GREEN);
        canvas.setLayer(1, null, null, null, 0.5f);

        var dup = canvas.duplicateLayer(1);
        assertEquals(2, dup.index());
        assertTrue(dup.active());
        assertEquals(3, surface.layers().getLayerCount());

        canvas.moveLayer(2, 0);
        assertEquals(0, surface.layers().getActiveLayerIndex(), "active index follows the moved layer");
        assertEquals("Background", surface.layers().getLayer(1).getName());

        canvas.moveLayer(0, 2);
        canvas.mergeLayerDown(2);                     // duplicate (50% green) onto 'top' (50% green)
        assertEquals(2, surface.layers().getLayerCount());
        assertEquals(1, surface.layers().getActiveLayerIndex());
        // The duplicate's opaque green is scaled by its 0.5 layer opacity, then alpha-over'd onto
        // 'top', whose own pixels are fully opaque — so the result is opaque green and 'top'
        // keeps its layer opacity (0.5) untouched.
        int[] merged = PixelCanvas.unpackRGBA(surface.layers().getLayer(1).getCanvas().getPixel(0, 0));
        assertArrayEquals(new int[]{0, 255, 0, 255}, merged);
        assertEquals(0.5f, surface.layers().getLayer(1).getOpacity(), 1e-6);
        assertEquals(TRANSPARENT, surface.layers().getLayer(1).getCanvas().getPixel(5, 5),
                "merging transparent source pixels leaves the destination alone");

        // A half-transparent source over an empty destination keeps its scaled alpha.
        canvas.addLayer("wash");
        canvas.fill(new int[]{5, 5, 1, 1}, new int[]{0, 0, 255, 200});
        canvas.setLayer(2, null, null, null, 0.5f);
        canvas.mergeLayerDown(2);
        int[] wash = PixelCanvas.unpackRGBA(surface.layers().getLayer(1).getCanvas().getPixel(5, 5));
        assertEquals(100, wash[3], "200 * 0.5");

        assertThrows(CommandException.class, () -> canvas.mergeLayerDown(0));
        assertThrows(CommandException.class, () -> canvas.moveLayer(0, 9));
        List<String> ops = cmds.opsTrace().stream().map(n -> n.get("op").asText()).toList();
        assertTrue(ops.containsAll(List.of("canvas_duplicate_layer", "canvas_move_layer", "canvas_merge_down")));
    }
}
