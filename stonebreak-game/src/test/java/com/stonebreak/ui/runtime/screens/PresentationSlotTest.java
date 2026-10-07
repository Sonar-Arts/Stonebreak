package com.stonebreak.ui.runtime.screens;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle every migrated legacy screen shares (#299): the presentation hears real visibility
 * changes only, a hidden screen paints nothing, and a presentation that cannot paint leaves the
 * call to the legacy renderer.
 */
class PresentationSlotTest {

    private static final class Recorder implements ScreenPresentation {
        final List<String> events = new ArrayList<>();
        boolean paints = true;

        @Override public void shown() { events.add("shown"); }
        @Override public void hidden() { events.add("hidden"); }
        @Override public boolean paint(int w, int h) {
            events.add("paint");
            return paints;
        }
        @Override public boolean showing() { return paints; }
    }

    @Test
    void onlyRealVisibilityChangesReachThePresentation() {
        PresentationSlot slot = new PresentationSlot();
        Recorder r = new Recorder();
        slot.install(r);
        assertTrue(slot.setVisible(true));
        assertFalse(slot.setVisible(true));
        slot.setVisible(false);
        slot.setVisible(false);
        assertEquals(List.of("shown", "hidden"), r.events);
    }

    @Test
    void installingWhileVisibleShowsAndReplacingHidesTheOldOne() {
        PresentationSlot slot = new PresentationSlot();
        slot.setVisible(true);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        slot.install(first);
        slot.install(second);
        assertEquals(List.of("shown", "hidden"), first.events);
        assertEquals(List.of("shown"), second.events);
    }

    @Test
    void hiddenScreensPaintNothingAndFailedPaintsFallBack() {
        PresentationSlot slot = new PresentationSlot();
        Recorder r = new Recorder();
        slot.install(r);
        assertFalse(slot.paint(800, 600), "hidden");
        slot.setVisible(true);
        assertTrue(slot.paint(800, 600));
        assertTrue(slot.showing());
        r.paints = false;
        assertFalse(slot.paint(800, 600), "the legacy renderer draws this call");
        assertFalse(slot.showing());
        assertEquals(List.of("shown", "paint", "paint"), r.events);
    }

    @Test
    void withoutAPresentationTheLegacyScreenDraws() {
        PresentationSlot slot = new PresentationSlot();
        slot.setVisible(true);
        assertFalse(slot.paint(800, 600));
        assertFalse(slot.showing());
    }
}
