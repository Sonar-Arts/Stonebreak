package com.openmason.main.systems.menus.textureCreator.layers;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerStackSnapshotTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int GREEN = PixelCanvas.packRGBA(0, 255, 0, 255);
    private static final int NOISE = PixelCanvas.packRGBA(9, 9, 9, 9);

    @Test
    void restoreBringsBackPixelsLayersNamesOpacityAndActiveIndex() {
        LayerManager manager = new LayerManager(8, 8);
        manager.getActiveLayer().getCanvas().setPixel(1, 1, RED);
        manager.addLayer("Top");
        manager.getActiveLayer().getCanvas().setPixel(2, 2, GREEN);
        manager.setLayerOpacity(1, 0.5f);

        LayerStackSnapshot snapshot = LayerStackSnapshot.capture(manager);

        // Mutate everything the snapshot covers.
        manager.getLayer(0).getCanvas().setPixel(1, 1, NOISE);
        manager.getLayer(1).getCanvas().setPixel(2, 2, NOISE);
        manager.addLayer("Extra");
        manager.renameLayer(0, "Renamed");
        manager.setLayerOpacity(1, 0.25f);
        manager.setLayerVisibility(1, false);
        manager.setActiveLayer(0);

        snapshot.restore(manager);

        assertEquals(2, manager.getLayerCount());
        assertEquals("Background", manager.getLayer(0).getName());
        assertEquals("Top", manager.getLayer(1).getName());
        assertEquals(1, manager.getActiveLayerIndex());
        assertEquals(0.5f, manager.getLayer(1).getOpacity(), 1e-6);
        assertTrue(manager.getLayer(1).isVisible());
        assertEquals(1.0f, manager.getLayer(0).getOpacity(), 1e-6);
        assertEquals(RED, manager.getLayer(0).getCanvas().getPixel(1, 1));
        assertEquals(GREEN, manager.getLayer(1).getCanvas().getPixel(2, 2));
        assertEquals(0, manager.getLayer(1).getCanvas().getPixel(0, 0));
    }

    @Test
    void restoreHandsFreshCopiesSoTheSnapshotStaysPristine() {
        LayerManager manager = new LayerManager(4, 4);
        manager.getActiveLayer().getCanvas().setPixel(0, 0, RED);
        LayerStackSnapshot snapshot = LayerStackSnapshot.capture(manager);

        manager.getActiveLayer().getCanvas().setPixel(0, 0, NOISE);
        snapshot.restore(manager);
        assertEquals(RED, manager.getActiveLayer().getCanvas().getPixel(0, 0));

        // Corrupt the RESTORED canvas — the snapshot must not share it.
        manager.getActiveLayer().getCanvas().setPixel(0, 0, NOISE);
        manager.renameLayer(0, "Corrupted");
        snapshot.restore(manager);

        assertEquals(RED, manager.getActiveLayer().getCanvas().getPixel(0, 0),
                "a second restore must be as exact as the first");
        assertEquals("Background", manager.getLayer(0).getName());
    }

    @Test
    void captureIsIsolatedFromLaterManagerMutations() {
        LayerManager manager = new LayerManager(4, 4);
        LayerStackSnapshot snapshot = LayerStackSnapshot.capture(manager);

        // Mutating the live stack after capture must not leak into the snapshot.
        manager.getActiveLayer().getCanvas().setPixel(3, 3, NOISE);
        snapshot.restore(manager);
        assertEquals(0, manager.getActiveLayer().getCanvas().getPixel(3, 3));
    }
}
