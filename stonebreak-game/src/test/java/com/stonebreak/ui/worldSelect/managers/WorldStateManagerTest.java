package com.stonebreak.ui.worldSelect.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the WorldStateManager selection and scroll semantics.
 *
 * <p>Tests verify that selection never leaves {@code [0, size-1]}, that {@code getSelectedWorld()}
 * on an empty list safely returns null, that scroll offset stays within bounds, and that
 * {@code adjustScrollToSelection()} implicitly called during navigation always keeps the selected
 * index visible within the viewport window of {@code ITEMS_PER_PAGE} {@code (8)}. items.
 */
class WorldStateManagerTest {

    private static final int ITEMS_PER_PAGE = 8;

    private WorldStateManager state;

    @BeforeEach
    void setUp() {
        state = new WorldStateManager();
    }

    // ===== EMPTY LIST =====

    @Test
    void emptyListReturnsNullSelectedWorld() {
        assertNull(state.getSelectedWorld(), "empty list must return null for selected world");
    }

    @Test
    void emptyListHasNoWorlds() {
        assertFalse(state.hasWorlds(), "hasWorlds must be false for an empty list");
    }

    @Test
    void emptyListSelectionIndexStaysAtZero() {
        assertEquals(0, state.getSelectedIndex());
    }

    // ===== THREE ITEM LIST (shorter than one page) =====

    @Test
    void selectionWrapsAtBoundariesForShortList() {
        state.setWorldList(List.of("A", "B", "C"));

        // Start at index 0
        assertEquals("A", state.getSelectedWorld());

        // Move up from index 0 stays at 0
        state.moveSelectionUp();
        assertEquals("A", state.getSelectedWorld(), "moving up from first item must stay at index 0");

        // Move down to index 2
        state.moveSelectionDown();
        state.moveSelectionDown();
        assertEquals("C", state.getSelectedWorld());

        // Move down from index 2 stays at 2
        state.moveSelectionDown();
        assertEquals("C", state.getSelectedWorld(), "moving down from last item must stay at index 2");
    }

    @Test
    void setWorldListResetsSelectionToValidIndex() {
        state.setWorldList(List.of("X", "Y", "Z"));
        assertEquals(0, state.getSelectedIndex());
        assertEquals("X", state.getSelectedWorld());
    }

    @Test
    void scrollOffsetStaysAtZeroForShortList() {
        state.setWorldList(List.of("A", "B", "C"));
        assertEquals(0, state.getScrollOffset(), "scroll must be 0 for a list shorter than one page");

        // Visible window should cover indices 0..2
        assertEquals(0, state.getVisibleStartIndex());
        assertEquals(3, state.getVisibleEndIndex(), "end index should equal list size for short list");
    }

    // ===== EXACTLY EIGHT ITEMS (exactly one page) =====

    @Test
    void scrollOffsetStaysAtZeroForExactlyOnePage() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7"));
        assertEquals(0, state.getScrollOffset());
        assertEquals(0, state.getVisibleStartIndex());
        assertEquals(ITEMS_PER_PAGE, state.getVisibleEndIndex());
    }

    @Test
    void isIndexVisibleAgreesWithWindowForOnePage() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7"));

        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            assertTrue(state.isIndexVisible(i), "index " + i + " must be visible when scroll is 0");
        }
    }

    // ===== TEN ITEMS (longer than one page by 2) =====

    @Test
    void selectionNavigationBoundsForLongList() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        state.setSelectedIndex(0);
        assertEquals("0", state.getSelectedWorld());

        state.moveSelectionDown();
        assertEquals("1", state.getSelectedWorld());

        // Jump directly to last index
        state.setSelectedIndex(9);
        assertEquals("9", state.getSelectedWorld());

        // Try to move past end - wraps to 9
        state.moveSelectionDown();
        assertEquals("9", state.getSelectedWorld(), "moving down past last index must wrap to end");

        // Try to move below 0
        state.setSelectedIndex(0);
        state.moveSelectionUp();
        assertEquals("0", state.getSelectedWorld(), "moving up past first index must wrap to 0");
    }

    @Test
    void scrollAutoAdjustsToKeepSelectionVisible() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        // Initially scroll is 0, selection 0 is visible
        assertEquals(0, state.getScrollOffset());

        // Select index 5 - still visible at scroll 0, indices 0..7 visible
        state.setSelectedIndex(5);
        assertEquals(0, state.getScrollOffset(), "index 5 is visible at scroll 0");

        // Select index 7 - last visible item at scroll 0
        state.setSelectedIndex(7);
        assertEquals(0, state.getScrollOffset());

        // Select index 8 - must scroll down, indices 1..8 visible
        state.setSelectedIndex(8);
        assertEquals(1, state.getScrollOffset(), "selecting index 8 must scroll so it is visible");

        // Select index 9 - must scroll down, indices 2..9 visible
        state.setSelectedIndex(9);
        assertEquals(2, state.getScrollOffset(), "selecting index 9 must scroll so it is visible");
    }

    @Test
    void isIndexVisibleAgreesWithScrollWindowForLongList() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        // At scroll offset 0, indices 0..7 are visible
        state.setScrollOffset(0);
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            assertTrue(state.isIndexVisible(i), "index " + i + " must be visible at scroll 0");
        }
        assertFalse(state.isIndexVisible(8), "index 8 must not be visible at scroll 0");
        assertFalse(state.isIndexVisible(9), "index 9 must not be visible at scroll 0");

        // At scroll offset 2, indices 2..9 are visible
        state.setScrollOffset(2);
        assertFalse(state.isIndexVisible(0), "index 0 must not be visible at scroll 2");
        assertFalse(state.isIndexVisible(1), "index 1 must not be visible at scroll 2");
        for (int i = 2; i < 10; i++) {
            assertTrue(state.isIndexVisible(i), "index " + i + " must be visible at scroll 2");
        }
    }

    @Test
    void setScrollOffsetClampsToValidBounds() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        // Try to set negative scroll
        state.setScrollOffset(-5);
        assertEquals(0, state.getScrollOffset(), "negative scroll must clamp to 0");

        // Max scroll for 10 items with 8 per page is 2
        state.setScrollOffset(100);
        assertEquals(2, state.getScrollOffset(), "scroll must clamp to max of 2 for 10 items");

        // Exactly at max
        state.setScrollOffset(2);
        assertEquals(2, state.getScrollOffset());
    }

    @Test
    void scrollUpAndDownClampCorrectly() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        state.setScrollOffset(0);
        state.scrollUp();
        assertEquals(0, state.getScrollOffset(), "scrolling up from 0 must stay at 0");

        state.setScrollOffset(2); // max
        state.scrollDown();
        assertEquals(2, state.getScrollOffset(), "scrolling down at max must stay at max");
    }

    // ===== SET SELECTION INDEX DIRECTLY =====

    @Test
    void setSelectedIndexClampsForEmptyList() {
        state.setSelectedIndex(50);
        assertEquals(0, state.getSelectedIndex(), "index must clamp to 0 for empty list");
    }

    @Test
    void setSelectedIndexClampsForNonEmptyList() {
        state.setWorldList(List.of("A", "B", "C"));

        state.setSelectedIndex(-10);
        assertEquals(0, state.getSelectedIndex(), "index must clamp to 0 for negative");

        state.setSelectedIndex(100);
        assertEquals(2, state.getSelectedIndex(), "index must clamp to size-1 for out-of-bounds");
    }

    // ===== HOVER MANAGEMENT =====

    @Test
    void hoverIndexClampForValidList() {
        state.setWorldList(List.of("A", "B", "C"));

        state.setHoveredIndex(0);
        assertEquals(0, state.getHoveredIndex());

        state.setHoveredIndex(2);
        assertEquals(2, state.getHoveredIndex());

        // Invalid hover
        state.setHoveredIndex(-1);
        assertEquals(-1, state.getHoveredIndex(), "hoveredIndex must be -1 for invalid index");

        state.setHoveredIndex(100);
        assertEquals(-1, state.getHoveredIndex(), "hoveredIndex must be -1 for out-of-bounds");
    }

    @Test
    void clearHoverSetsIndexToNegativeOne() {
        state.setWorldList(List.of("A", "B", "C"));
        state.setHoveredIndex(1);
        state.clearHover();
        assertEquals(-1, state.getHoveredIndex());
    }

    // ===== DIALOG FLAGS =====

    @Test
    void openAndCloseCreateDialog() {
        assertFalse(state.isShowCreateDialog());

        state.openCreateDialog();
        assertTrue(state.isShowCreateDialog(), "create dialog must be open");

        state.closeCreateDialog();
        assertFalse(state.isShowCreateDialog(), "create dialog must close");
    }

    @Test
    void openAndCloseDeleteDialog() {
        assertFalse(state.isShowDeleteDialog());

        state.openDeleteDialog("WorldA");
        assertTrue(state.isShowDeleteDialog(), "delete dialog must be open");
        assertEquals("WorldA", state.getWorldPendingDelete());

        state.closeDeleteDialog();
        assertFalse(state.isShowDeleteDialog(), "delete dialog must close");
        assertNull(state.getWorldPendingDelete(), "pending delete must be null after close");
    }

    @Test
    void anyDialogOpenReturnsTrueWhenEitherIsOpen() {
        assertTrue(state.isAnyDialogOpen(), "no dialog open initially");
        // Actually initially neither is open
        assertFalse(state.isAnyDialogOpen(), "no dialog open initially");

        state.openCreateDialog();
        assertTrue(state.isAnyDialogOpen(), "create dialog open");

        state.closeCreateDialog();
        state.openDeleteDialog("WorldA");
        assertTrue(state.isAnyDialogOpen(), "delete dialog open");
    }

    // ===== SCROLL DELTA =====

    @Test
    void scrollDeltaPositiveMovesUp() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
        state.setScrollOffset(2);
        state.scroll(1.0); // positive delta scrolls up
        assertEquals(1, state.getScrollOffset());
    }

    @Test
    void scrollDeltaNegativeMovesDown() {
        state.setWorldList(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
        state.setScrollOffset(0);
        state.scroll(-1.0); // negative delta scrolls down
        assertEquals(1, state.getScrollOffset());
    }

    @Test
    void scrollDeltaZeroDoesNothing() {
        state.setWorldList(List.of("0", "1", "2"));
        state.setScrollOffset(0);
        state.scroll(0.0);
        assertEquals(0, state.getScrollOffset(), "zero delta must not change scroll");
    }
}