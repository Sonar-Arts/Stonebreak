package com.openmason.main.systems.menus.textureCreator.commands;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Undoable layer-stack operations, alone and mixed with pixel strokes through
 * the real {@link CommandHistory}.
 */
class LayerCommandTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);

    @Test
    @Tag("regression")
    void addLayerUndoRedo() {
        LayerManager manager = stack("Background", "A");
        List<Layer> before = new ArrayList<>(manager.getLayers());

        LayerCommand add = LayerCommand.addLayer(manager, "New");
        add.execute();
        assertEquals(List.of("Background", "A", "New"), names(manager));
        assertEquals(2, manager.getActiveLayerIndex());
        Layer added = manager.getLayer(2);

        add.undo();
        assertEquals(before, manager.getLayers());

        add.execute();
        assertSame(added, manager.getLayer(2), "redo re-inserts the same layer (and its pixels)");
    }

    @Test
    @Tag("regression")
    void removeLayerUndoRestoresTheSameLayerAtItsIndex() {
        LayerManager manager = stack("Background", "A", "B");
        manager.getLayer(1).getCanvas().setPixel(0, 0, RED);
        List<Layer> before = new ArrayList<>(manager.getLayers());

        LayerCommand remove = LayerCommand.removeLayer(manager, 1);
        remove.execute();
        assertEquals(List.of("Background", "B"), names(manager));

        remove.undo();
        assertEquals(before, manager.getLayers());
        assertEquals(1, manager.getActiveLayerIndex());
        assertEquals(RED, manager.getLayer(1).getCanvas().getPixel(0, 0));
    }

    @Test
    @Tag("regression")
    void moveLayerUndoRestoresOrder() {
        LayerManager manager = stack("A", "B", "C", "D");
        List<Layer> before = new ArrayList<>(manager.getLayers());

        LayerCommand move = LayerCommand.moveLayer(manager, 0, 3);
        move.execute();
        assertEquals(List.of("B", "C", "D", "A"), names(manager));
        move.undo();
        assertEquals(before, manager.getLayers());
    }

    @Test
    @Tag("regression")
    void duplicateLayerUndoRemovesOnlyTheCopy() {
        LayerManager manager = stack("Background", "A");
        manager.getLayer(1).getCanvas().setPixel(1, 1, RED);
        List<Layer> before = new ArrayList<>(manager.getLayers());

        LayerCommand dup = LayerCommand.duplicateLayer(manager, 1);
        dup.execute();
        assertEquals(List.of("Background", "A", "A (Copy)"), names(manager));
        assertEquals(RED, manager.getLayer(2).getCanvas().getPixel(1, 1));
        assertEquals(2, manager.getActiveLayerIndex());

        dup.undo();
        assertEquals(before, manager.getLayers());
    }

    @Test
    @Tag("regression")
    void randomMixOfLayerAndPixelCommandsUndoesAndRedoesExactly() {
        Random random = new Random(8675309);
        for (int trial = 0; trial < 30; trial++) {
            LayerManager manager = stack("Background");
            CommandHistory history = new CommandHistory();
            StackState initial = StackState.capture(manager);

            int steps = 5 + random.nextInt(20);
            for (int i = 0; i < steps; i++) {
                history.executeCommand(randomCommand(manager, random));
            }
            StackState finalState = StackState.capture(manager);

            while (history.undo()) {
                // drain
            }
            initial.assertMatches(manager, "trial " + trial + " after undo-all");

            while (history.redo()) {
                // replay
            }
            finalState.assertMatches(manager, "trial " + trial + " after redo-all");
        }
    }

    private static Command randomCommand(LayerManager manager, Random random) {
        int count = manager.getLayerCount();
        switch (random.nextInt(5)) {
            case 0:
                return LayerCommand.addLayer(manager, "L" + random.nextInt(1000));
            case 1:
                if (count > 1) {
                    return LayerCommand.removeLayer(manager, random.nextInt(count));
                }
                // fall through: cannot remove the last layer
            case 2:
                if (count > 1) {
                    int from = random.nextInt(count);
                    int to = random.nextInt(count);
                    return LayerCommand.moveLayer(manager, from, to);
                }
                // fall through
            case 3:
                return LayerCommand.duplicateLayer(manager, random.nextInt(count));
            default: {
                PixelCanvas canvas = manager.getLayer(random.nextInt(count)).getCanvas();
                DrawCommand stroke = new DrawCommand(canvas, "stroke");
                for (int i = 0; i < 6; i++) {
                    int x = random.nextInt(canvas.getWidth());
                    int y = random.nextInt(canvas.getHeight());
                    // Recorded, not applied: the history's execute() applies it.
                    stroke.recordPixelChange(x, y, canvas.getPixel(x, y), random.nextInt());
                }
                return stroke;
            }
        }
    }

    /** Layer identities + their pixels, in stack order. */
    private record StackState(List<Layer> layers, List<int[]> pixels) {
        static StackState capture(LayerManager manager) {
            List<int[]> pixels = new ArrayList<>();
            for (Layer layer : manager.getLayers()) {
                pixels.add(layer.getCanvas().getPixels().clone());
            }
            return new StackState(new ArrayList<>(manager.getLayers()), pixels);
        }

        void assertMatches(LayerManager manager, String message) {
            assertEquals(names(layers), names(manager.getLayers()), message);
            for (int i = 0; i < layers.size(); i++) {
                assertSame(layers.get(i), manager.getLayer(i), message + " layer " + i);
                assertArrayEquals(pixels.get(i), manager.getLayer(i).getCanvas().getPixels(),
                        message + " pixels of layer " + i);
            }
        }
    }

    private static LayerManager stack(String... names) {
        LayerManager manager = new LayerManager(4, 4);
        manager.renameLayer(0, names[0]);
        for (int i = 1; i < names.length; i++) {
            manager.addLayer(names[i]);
        }
        return manager;
    }

    private static List<String> names(LayerManager manager) {
        return names(manager.getLayers());
    }

    private static List<String> names(List<Layer> layers) {
        return layers.stream().map(Layer::getName).toList();
    }
}
