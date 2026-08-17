package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render-free interaction contract for {@link MTabBar}: hit-testing maps
 * clicks to the right slot, the selection callback fires exactly when the
 * selection moves, and out-of-range input never corrupts the index.
 */
class MTabBarInteractionTest {

    private static MTabBar bar() {
        // Three tabs across x ∈ [100, 400) → slots of 100px each.
        return new MTabBar("Items", "Feats", "Looks").bounds(100, 50, 300, 30);
    }

    @Test
    void tabAtMapsSlotsAndEdges() {
        MTabBar bar = bar();
        assertEquals(0, bar.tabAt(101, 60));
        assertEquals(1, bar.tabAt(250, 60));
        assertEquals(2, bar.tabAt(399, 60), "the far edge belongs to the last tab");
        assertEquals(-1, bar.tabAt(99, 60), "left of the bar is nothing");
        assertEquals(-1, bar.tabAt(250, 90), "below the bar is nothing");
    }

    @Test
    void clickingSelectsAndFiresOnceOnChange() {
        List<Integer> fired = new ArrayList<>();
        MTabBar bar = bar().onSelect(fired::add);

        assertTrue(bar.handleClick(250, 60), "a hit is consumed");
        assertEquals(1, bar.selectedIndex());
        assertEquals(List.of(1), fired);

        assertTrue(bar.handleClick(250, 60), "re-clicking the live tab is still consumed");
        assertEquals(List.of(1), fired, "but must not re-fire the callback");

        assertFalse(bar.handleClick(50, 60), "a miss is not consumed");
        assertEquals(1, bar.selectedIndex(), "and moves nothing");
        assertEquals(List.of(1), fired);
    }

    @Test
    void hoverTracksTheSlotUnderTheCursor() {
        MTabBar bar = bar();
        assertTrue(bar.updateHover(350, 60));
        assertEquals(2, bar.hoveredIndex());
        assertFalse(bar.updateHover(0, 0));
        assertEquals(-1, bar.hoveredIndex(), "leaving the bar clears the hover");
    }

    @Test
    void selectionIsClampedAndSilentWhenSetFluently() {
        List<Integer> fired = new ArrayList<>();
        MTabBar bar = bar().onSelect(fired::add);

        bar.selected(99);
        assertEquals(2, bar.selectedIndex(), "out-of-range selection clamps to the last tab");
        bar.selected(-5);
        assertEquals(0, bar.selectedIndex(), "and to the first");
        assertTrue(fired.isEmpty(), "the fluent setter is for initial state — it never fires");
    }

    @Test
    void anEmptyBarNeverHitsAnything() {
        MTabBar bar = new MTabBar().bounds(0, 0, 100, 20);
        assertEquals(-1, bar.tabAt(50, 10));
        assertFalse(bar.handleClick(50, 10));
        assertEquals(0, bar.selectedIndex());
    }
}
