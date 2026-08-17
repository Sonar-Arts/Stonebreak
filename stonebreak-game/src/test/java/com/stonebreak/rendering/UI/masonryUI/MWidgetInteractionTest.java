package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MasonryUI widgets carry their own hit-testing and click state, so the interaction layer tests
 * without a single draw call — {@code render(MasonryUI)} is the only method that touches Skija.
 * These pin the base widget's bounds contract and the button/toggle click rules every menu
 * screen is built on: disabled widgets swallow nothing, and a toggle flip always reaches its
 * callback.
 */
class MWidgetInteractionTest {

    // ── MWidget bounds ───────────────────────────────────────────────────────

    @Test
    void hitTestingIsInclusiveOfItsOwnEdges() {
        MButton widget = new MButton("x").bounds(10, 20, 100, 30);

        assertTrue(widget.contains(10, 20), "top-left corner is part of the widget");
        assertTrue(widget.contains(110, 50), "bottom-right corner too");
        assertFalse(widget.contains(9.5f, 20));
        assertFalse(widget.contains(10, 50.5f));
    }

    @Test
    void hoverTracksTheMouseAndSticksOnTheWidget() {
        MButton widget = new MButton("x").bounds(0, 0, 50, 20);

        assertTrue(widget.updateHover(25, 10));
        assertTrue(widget.isHovered());
        assertFalse(widget.updateHover(100, 100));
        assertFalse(widget.isHovered());
    }

    // ── MButton ──────────────────────────────────────────────────────────────

    @Test
    void aClickInsideFiresTheAction() {
        AtomicInteger fired = new AtomicInteger();
        MButton button = new MButton("Play").bounds(0, 0, 80, 24).onClick(fired::incrementAndGet);

        assertTrue(button.handleClick(40, 12));
        assertEquals(1, fired.get());
        assertFalse(button.handleClick(200, 12), "a miss consumes nothing");
        assertEquals(1, fired.get());
    }

    @Test
    void aDisabledButtonNeverFires() {
        AtomicInteger fired = new AtomicInteger();
        MButton button = new MButton("Play").bounds(0, 0, 80, 24)
                .enabled(false).onClick(fired::incrementAndGet);

        button.click();

        assertEquals(0, fired.get());
    }

    // ── MToggle ──────────────────────────────────────────────────────────────

    @Test
    void everyToggleFlipReachesTheCallback() {
        AtomicInteger flips = new AtomicInteger();
        MToggle toggle = new MToggle("Wireframes");
        toggle.bounds(0, 0, 200, 24);
        toggle.onToggle(flips::incrementAndGet);

        assertTrue(toggle.handleClick(100, 12));
        assertTrue(toggle.isChecked());
        assertTrue(toggle.handleClick(100, 12));
        assertFalse(toggle.isChecked());
        assertEquals(2, flips.get(), "settings live-update off this callback — a silent flip desyncs them");
    }

    @Test
    void aMissedClickLeavesTheToggleAlone() {
        MToggle toggle = new MToggle("Wireframes", true);
        toggle.bounds(0, 0, 200, 24);

        assertFalse(toggle.handleClick(300, 12));
        assertTrue(toggle.isChecked());
    }

    @Test
    void aDisabledToggleHoldsItsState() {
        AtomicInteger flips = new AtomicInteger();
        MToggle toggle = new MToggle("Locked", true);
        toggle.bounds(0, 0, 200, 24);
        toggle.onToggle(flips::incrementAndGet);
        toggle.setEnabled(false);

        toggle.toggle();

        assertTrue(toggle.isChecked());
        assertEquals(0, flips.get());
    }
}
