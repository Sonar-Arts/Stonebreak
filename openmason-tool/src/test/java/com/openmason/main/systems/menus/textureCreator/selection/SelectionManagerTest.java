package com.openmason.main.systems.menus.textureCreator.selection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single source of truth for the active selection and its change events.
 */
class SelectionManagerTest {

    private final SelectionManager manager = new SelectionManager();
    private final List<SelectionRegion[]> events = new ArrayList<>();

    {
        manager.addSelectionChangeListener((oldSel, newSel) -> events.add(new SelectionRegion[]{oldSel, newSel}));
    }

    @Test
    void setAndClearNotifyWithOldAndNewSelection() {
        RectangularSelection first = new RectangularSelection(0, 0, 1, 1);
        manager.setActiveSelection(first);
        assertTrue(manager.hasActiveSelection());
        assertSame(first, manager.getActiveSelection());

        manager.clearSelection();
        assertNull(manager.getActiveSelection());
        assertFalse(manager.hasActiveSelection());

        assertEquals(2, events.size());
        assertNull(events.get(0)[0]);
        assertSame(first, events.get(0)[1]);
        assertSame(first, events.get(1)[0]);
        assertNull(events.get(1)[1]);
    }

    @Test
    void settingAnEqualSelectionDoesNotNotify() {
        manager.setActiveSelection(new RectangularSelection(1, 1, 4, 4));
        manager.setActiveSelection(new RectangularSelection(4, 4, 1, 1)); // same area, new instance
        manager.clearSelection();
        manager.clearSelection(); // null -> null
        assertEquals(2, events.size());
    }

    @Test
    void sameBoundsButDifferentShapeOrTypeNotifies() {
        manager.setActiveSelection(new RectangularSelection(0, 0, 1, 1));
        // Freeform with identical bounds: different type.
        manager.setActiveSelection(MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(0, 0), MaskSelectionRegion.encode(1, 1))));
        // Same type and bounds, different pixels.
        manager.setActiveSelection(MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(1, 0), MaskSelectionRegion.encode(0, 1))));
        assertEquals(3, events.size());
    }

    @Test
    void anEmptySelectionDoesNotCountAsActive() {
        manager.setActiveSelection(MaskSelectionRegion.empty());
        assertFalse(manager.hasActiveSelection());
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers() {
        SelectionManager isolated = new SelectionManager();
        List<String> calls = new ArrayList<>();
        isolated.addSelectionChangeListener((o, n) -> {
            throw new IllegalStateException("boom");
        });
        isolated.addSelectionChangeListener((o, n) -> calls.add("second"));
        isolated.setActiveSelection(new RectangularSelection(0, 0, 0, 0));
        assertEquals(List.of("second"), calls);
    }

    @Test
    void listenersCanBeRemovedAndAreNotRegisteredTwice() {
        SelectionManager isolated = new SelectionManager();
        List<String> calls = new ArrayList<>();
        SelectionManager.SelectionChangeListener listener = (o, n) -> calls.add("hit");
        isolated.addSelectionChangeListener(listener);
        isolated.addSelectionChangeListener(listener);
        isolated.setActiveSelection(new RectangularSelection(0, 0, 0, 0));
        assertEquals(1, calls.size());

        isolated.removeSelectionChangeListener(listener);
        isolated.clearSelection();
        assertEquals(1, calls.size());
    }
}
