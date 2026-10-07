package com.stonebreak.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pause menu keeps its lifecycle while a {@link PauseMenu.Presentation} (#297: the shipped UI
 * document) draws it: shown/hidden follow real visibility changes only, every {@code render} call
 * reaches the presentation (the field pause is composited twice), and a presentation that cannot
 * paint falls back to the legacy renderer for that call.
 */
class PauseMenuPresentationTest {

    private static final class Recorder implements PauseMenu.Presentation {
        final List<String> events = new ArrayList<>();
        boolean paints = true;

        @Override public void shown() { events.add("shown"); }
        @Override public void hidden() { events.add("hidden"); }
        @Override public boolean paint(int w, int h) {
            events.add("paint " + w + "x" + h);
            return paints;
        }
    }

    @Test
    void visibilityChangesReachThePresentationOnce() {
        PauseMenu menu = new PauseMenu(null);
        Recorder r = new Recorder();
        menu.setPresentation(r);
        menu.setVisible(true);
        menu.setVisible(true);
        menu.toggleVisibility();
        menu.setVisible(false);
        menu.toggleVisibility();
        assertEquals(List.of("shown", "hidden", "shown"), r.events);
    }

    @Test
    void installingWhileVisibleShowsAndReplacingHidesTheOldOne() {
        PauseMenu menu = new PauseMenu(null);
        menu.setVisible(true);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        menu.setPresentation(first);
        menu.setPresentation(second);
        assertEquals(List.of("shown", "hidden"), first.events);
        assertEquals(List.of("shown"), second.events);
    }

    @Test
    void everyRenderCallPaintsAndHiddenMenusPaintNothing() {
        PauseMenu menu = new PauseMenu(null);
        Recorder r = new Recorder();
        menu.setPresentation(r);
        menu.render(1920, 1080); // hidden: nothing
        menu.setVisible(true);
        menu.render(1920, 1080); // the in-game UI pass
        menu.render(1920, 1080); // the modal pass: the field pause is drawn twice
        assertEquals(List.of("shown", "paint 1920x1080", "paint 1920x1080"), r.events);
    }

    @Test
    void aPresentationThatCannotPaintFallsBackToTheLegacyRenderer() {
        PauseMenu menu = new PauseMenu(null); // no backend: the legacy renderer draws nothing, safely
        Recorder r = new Recorder();
        r.paints = false;
        menu.setPresentation(r);
        menu.setVisible(true);
        menu.render(800, 600);
        assertEquals(List.of("shown", "paint 800x600"), r.events);
    }
}
