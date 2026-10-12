package com.stonebreak.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pause menu keeps its lifecycle while a {@link PauseMenu.Presentation} (#297: the shipped UI
 * document) draws it: shown/hidden follow real visibility changes only, and every {@code render}
 * call reaches the presentation (the field pause is composited twice).
 */
class PauseMenuPresentationTest {

    private static final class Recorder implements PauseMenu.Presentation {
        final List<String> events = new ArrayList<>();

        @Override public void shown() { events.add("shown"); }
        @Override public void hidden() { events.add("hidden"); }
        @Override public boolean paint(int w, int h) {
            events.add("paint " + w + "x" + h);
            return true;
        }
    }

    @Test
    void visibilityChangesReachThePresentationOnce() {
        PauseMenu menu = new PauseMenu();
        Recorder r = new Recorder();
        menu.setPresentation(r);
        menu.setVisible(true);
        menu.setVisible(true);
        menu.setVisible(false);
        menu.setVisible(false);
        menu.setVisible(true);
        assertEquals(List.of("shown", "hidden", "shown"), r.events);
    }

    @Test
    void installingWhileVisibleShowsAndReplacingHidesTheOldOne() {
        PauseMenu menu = new PauseMenu();
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
        PauseMenu menu = new PauseMenu();
        Recorder r = new Recorder();
        menu.setPresentation(r);
        menu.render(1920, 1080); // hidden: nothing
        menu.setVisible(true);
        menu.render(1920, 1080); // the in-game UI pass
        menu.render(1920, 1080); // the modal pass: the field pause is drawn twice
        assertEquals(List.of("shown", "paint 1920x1080", "paint 1920x1080"), r.events);
    }
}
