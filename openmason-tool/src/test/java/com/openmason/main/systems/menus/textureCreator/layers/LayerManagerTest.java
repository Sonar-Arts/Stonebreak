package com.openmason.main.systems.menus.textureCreator.layers;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer stack behaviour: add/remove/reorder/duplicate/merge, visibility and
 * opacity, compositing and its cache, and which layer stays active.
 */
class LayerManagerTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int GREEN = PixelCanvas.packRGBA(0, 255, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);

    @Test
    void startsWithOneActiveBackgroundLayer() {
        LayerManager manager = new LayerManager(4, 3);
        assertEquals(1, manager.getLayerCount());
        assertEquals("Background", manager.getActiveLayer().getName());
        assertEquals(0, manager.getActiveLayerIndex());
        assertEquals(4, manager.getActiveLayer().getCanvas().getWidth());
        assertThrows(IllegalArgumentException.class, () -> new LayerManager(0, 4));
    }

    @Test
    void addedLayersStackOnTopAndBecomeActive() {
        LayerManager manager = new LayerManager(4, 4);
        manager.addLayer("A");
        manager.addLayer("B");
        assertEquals(List.of("Background", "A", "B"), names(manager));
        assertEquals(2, manager.getActiveLayerIndex());

        Layer inserted = new Layer("Mid", 4, 4);
        manager.addLayerAt(1, inserted);
        assertSame(inserted, manager.getActiveLayer());
        assertEquals(List.of("Background", "Mid", "A", "B"), names(manager));
        assertThrows(IndexOutOfBoundsException.class, () -> manager.addLayerAt(9, inserted));
    }

    @Test
    void theLastLayerCannotBeRemoved() {
        LayerManager manager = new LayerManager(4, 4);
        assertThrows(IllegalStateException.class, () -> manager.removeLayer(0));
        manager.addLayer("A");
        assertThrows(IndexOutOfBoundsException.class, () -> manager.removeLayer(2));
    }

    @Test
    void removingTheActiveLayerActivatesTheOneBelow() {
        LayerManager manager = stack("Background", "A", "B", "C");
        manager.setActiveLayer(2); // B
        manager.removeLayer(2);
        assertEquals("A", manager.getActiveLayer().getName());

        manager.setActiveLayer(0);
        manager.removeLayer(0); // bottom: the layer that slid down becomes active
        assertEquals("A", manager.getActiveLayer().getName());

        manager.setActiveLayer(1); // C, the top
        manager.removeLayer(1);
        assertEquals("A", manager.getActiveLayer().getName());
    }

    @Test
    @Tag("regression")
    void removingALayerBelowTheActiveOneKeepsTheSameLayerActive() {
        // Used to leave the index unchanged, silently switching painting to the
        // layer above the one the user had selected.
        LayerManager manager = stack("Background", "A", "B", "C");
        manager.setActiveLayer(2); // B
        manager.removeLayer(0);
        assertEquals("B", manager.getActiveLayer().getName());

        manager.removeLayer(2); // above the active one: no shift
        assertEquals("B", manager.getActiveLayer().getName());
    }

    @Test
    @Tag("regression")
    void movingALayerAcrossTheActiveOneKeepsTheSameLayerActive() {
        for (int from = 0; from < 4; from++) {
            for (int to = 0; to < 4; to++) {
                for (int active = 0; active < 4; active++) {
                    LayerManager manager = stack("L0", "L1", "L2", "L3");
                    manager.setActiveLayer(active);
                    Layer activeBefore = manager.getActiveLayer();

                    manager.moveLayer(from, to);

                    assertSame(activeBefore, manager.getActiveLayer(),
                            "move " + from + "->" + to + " with active " + active);
                }
            }
        }
    }

    @Test
    void moveReordersAndValidatesIndices() {
        LayerManager manager = stack("A", "B", "C");
        manager.moveLayer(0, 2);
        assertEquals(List.of("B", "C", "A"), names(manager));
        manager.moveLayer(2, 0);
        assertEquals(List.of("A", "B", "C"), names(manager));
        assertThrows(IndexOutOfBoundsException.class, () -> manager.moveLayer(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> manager.moveLayer(0, 3));
    }

    @Test
    void duplicateInsertsAnIndependentCopyAboveTheOriginal() {
        LayerManager manager = new LayerManager(4, 4);
        manager.getActiveLayer().getCanvas().setPixel(1, 1, RED);
        manager.setLayerOpacity(0, 0.4f);

        manager.duplicateLayer(0);

        Layer copy = manager.getLayer(1);
        assertEquals("Background (Copy)", copy.getName());
        assertEquals(1, manager.getActiveLayerIndex());
        assertEquals(0.4f, copy.getOpacity(), 1e-6);
        assertNotSame(manager.getLayer(0).getCanvas(), copy.getCanvas());
        copy.getCanvas().setPixel(1, 1, BLUE);
        assertEquals(RED, manager.getLayer(0).getCanvas().getPixel(1, 1));
    }

    @Test
    void visibilityOpacityAndRenameReplaceTheImmutableLayerButShareItsCanvas() {
        LayerManager manager = new LayerManager(2, 2);
        PixelCanvas canvas = manager.getLayer(0).getCanvas();

        manager.setLayerVisibility(0, false);
        manager.setLayerOpacity(0, 0.25f);
        manager.renameLayer(0, "Base");

        Layer layer = manager.getLayer(0);
        assertFalse(layer.isVisible());
        assertEquals(0.25f, layer.getOpacity(), 1e-6);
        assertEquals("Base", layer.getName());
        assertSame(canvas, layer.getCanvas());
        assertThrows(IllegalArgumentException.class, () -> manager.setLayerOpacity(0, 1.5f));
        assertThrows(IllegalArgumentException.class, () -> manager.renameLayer(0, "  "));
    }

    @Test
    void layerRejectsInvalidConstruction() {
        PixelCanvas canvas = new PixelCanvas(1, 1);
        assertThrows(IllegalArgumentException.class, () -> new Layer(null, canvas, true, 1f));
        assertThrows(IllegalArgumentException.class, () -> new Layer("x", null, true, 1f));
        assertThrows(IllegalArgumentException.class, () -> new Layer("x", canvas, true, -0.1f));
    }

    // ---- compositing ----

    @Test
    void compositeStacksBottomToTopAndSkipsHiddenLayers() {
        LayerManager manager = new LayerManager(2, 1);
        manager.getLayer(0).getCanvas().fill(RED);
        manager.addLayer("Top");
        manager.getLayer(1).getCanvas().setPixel(1, 0, GREEN);

        PixelCanvas composite = manager.compositeLayersToCanvas();
        assertEquals(RED, composite.getPixel(0, 0));
        assertEquals(GREEN, composite.getPixel(1, 0));

        manager.setLayerVisibility(1, false);
        assertEquals(RED, manager.compositeLayersToCanvas().getPixel(1, 0));
    }

    @Test
    @Tag("regression")
    void layerOpacityScalesAlphaInTheComposite() {
        LayerManager manager = new LayerManager(1, 1);
        manager.getLayer(0).getCanvas().fill(RED);
        manager.setLayerOpacity(0, 0.5f);
        int[] rgba = PixelCanvas.unpackRGBA(manager.compositeLayersToCanvas().getPixel(0, 0));
        assertEquals(255, rgba[0]);
        assertEquals(128, rgba[3]); // round(255 * 0.5), same rounding as merge-down
    }

    @Test
    void compositeIsCachedUntilMarkedDirty() {
        LayerManager manager = new LayerManager(2, 2);
        PixelCanvas first = manager.compositeLayersToCanvas();
        assertSame(first, manager.compositeLayersToCanvas());

        // Direct pixel edits do not invalidate the cache on their own...
        manager.getActiveLayer().getCanvas().setPixel(0, 0, RED);
        assertSame(first, manager.compositeLayersToCanvas());
        assertEquals(0, first.getPixel(0, 0));

        // ...callers must mark it dirty.
        manager.markCompositeDirty();
        PixelCanvas second = manager.compositeLayersToCanvas();
        assertNotSame(first, second);
        assertEquals(RED, second.getPixel(0, 0));

        // Structural operations invalidate automatically.
        manager.addLayer("X");
        assertNotSame(second, manager.compositeLayersToCanvas());
    }

    @Test
    void compositeExcludingOmitsOnlyThatLayer() {
        LayerManager manager = new LayerManager(1, 1);
        manager.getLayer(0).getCanvas().fill(RED);
        manager.addLayer("Top");
        manager.getLayer(1).getCanvas().fill(BLUE);

        assertEquals(BLUE, manager.compositeLayersToCanvas().getPixel(0, 0));
        assertEquals(RED, manager.compositeLayersExcluding(manager.getLayer(1)).getPixel(0, 0));
    }

    // ---- merge down ----

    @Test
    void mergeDownRemovesTheUpperLayerAndKeepsTheLowerLayersProperties() {
        LayerManager manager = new LayerManager(2, 2);
        manager.renameLayer(0, "Base");
        manager.getLayer(0).getCanvas().fill(RED);
        manager.addLayer("Top");
        manager.getLayer(1).getCanvas().setPixel(0, 0, BLUE);
        manager.addLayer("Above");

        manager.mergeLayerDown(1);

        assertEquals(List.of("Base", "Above"), names(manager));
        assertEquals(0, manager.getActiveLayerIndex());
        assertEquals(BLUE, manager.getLayer(0).getCanvas().getPixel(0, 0));
        assertEquals(RED, manager.getLayer(0).getCanvas().getPixel(1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> manager.mergeLayerDown(0));
    }

    @Test
    @Tag("regression")
    void mergeDownPreservesTheCompositedImage() {
        // Merging is a flatten of two layers: the picture must not change. Random
        // translucent pixels and a translucent upper layer exercise the rounding.
        Random random = new Random(2024);
        for (int trial = 0; trial < 20; trial++) {
            LayerManager manager = new LayerManager(8, 8);
            randomize(manager.getLayer(0).getCanvas(), random);
            manager.addLayer("Top");
            randomize(manager.getLayer(1).getCanvas(), random);
            manager.setLayerOpacity(1, random.nextInt(101) / 100f);

            int[] before = manager.compositeLayersToCanvas().getPixels().clone();
            manager.mergeLayerDown(1);
            int[] after = manager.compositeLayersToCanvas().getPixels();

            assertArrayEquals(before, after, "trial " + trial);
        }
    }

    @Test
    void replaceLayersClampsTheActiveIndexAndRejectsEmptyStacks() {
        LayerManager manager = new LayerManager(2, 2);
        manager.replaceLayers(List.of(new Layer("X", 2, 2), new Layer("Y", 2, 2)), 99);
        assertEquals(1, manager.getActiveLayerIndex());
        assertThrows(IllegalArgumentException.class, () -> manager.replaceLayers(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> manager.replaceLayers(null, 0));
    }

    @Test
    void layersViewIsUnmodifiable() {
        LayerManager manager = new LayerManager(2, 2);
        assertThrows(UnsupportedOperationException.class, () -> manager.getLayers().clear());
        assertTrue(manager.getLayers().size() == 1);
    }

    static LayerManager stack(String... names) {
        LayerManager manager = new LayerManager(2, 2);
        manager.renameLayer(0, names[0]);
        for (int i = 1; i < names.length; i++) {
            manager.addLayer(names[i]);
        }
        return manager;
    }

    static List<String> names(LayerManager manager) {
        return manager.getLayers().stream().map(Layer::getName).toList();
    }

    private static void randomize(PixelCanvas canvas, Random random) {
        int[] pixels = canvas.getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt();
        }
    }
}
