package com.stonebreak.ui.worldSelect.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the sticky hover-card state machine in {@link WorldStateManager}.
 *
 * <p>The card carries clickable buttons, so the rules that matter are: it only opens after
 * the cursor has rested on a row, it stays open while the cursor is over the row <em>or</em>
 * the card itself (that is what makes the buttons reachable), and leaving both only closes it
 * after a grace period. Anything that moves rows around on screen must close it outright,
 * because its position is derived from where its anchor row is drawn.
 */
class WorldInfoCardStateTest {

    private static final long OPEN = WorldStateManager.CARD_OPEN_DELAY_MS;
    private static final long CLOSE = WorldStateManager.CARD_CLOSE_DELAY_MS;

    private WorldStateManager state;

    @BeforeEach
    void setUp() {
        state = new WorldStateManager();
        state.setWorldList(worlds(12));
    }

    private static List<String> worlds(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add("World " + i);
        }
        return list;
    }

    // ===== OPENING =====

    @Test
    void cardIsClosedInitially() {
        assertFalse(state.isCardOpen(), "no card before the cursor has hovered anything");
        assertNull(state.getCardWorld());
        assertEquals(-1, state.getCardIndex());
    }

    @Test
    void hoveringARowDoesNotOpenTheCardImmediately() {
        state.updateCardHover(3, false, 1_000L);
        state.tickCard(1_000L);
        assertFalse(state.isCardOpen(), "card must wait out the hover delay");
    }

    @Test
    void cardOpensOnceTheHoverDelayElapses() {
        state.updateCardHover(3, false, 1_000L);
        state.tickCard(1_000L + OPEN);
        assertTrue(state.isCardOpen());
        assertEquals(3, state.getCardIndex());
        assertEquals("World 3", state.getCardWorld());
    }

    @Test
    void cardOpensWithoutFurtherMouseMoveEvents() {
        // A resting cursor produces no move events, so only the per-frame tick can open it
        state.updateCardHover(5, false, 0L);
        state.tickCard(OPEN);
        assertEquals(5, state.getCardIndex(), "tick alone must be able to open the card");
    }

    @Test
    void leavingBeforeTheDelayCancelsTheOpen() {
        state.updateCardHover(3, false, 0L);
        state.updateCardHover(-1, false, 100L);
        state.tickCard(1_000L);
        assertFalse(state.isCardOpen(), "a cursor that passed through must not open a card");
    }

    @Test
    void movingBetweenRowsRestartsTheDelay() {
        state.updateCardHover(3, false, 0L);
        state.updateCardHover(4, false, OPEN - 10L);
        state.tickCard(OPEN);
        assertFalse(state.isCardOpen(), "the new row gets its own full delay");
        state.tickCard(OPEN - 10L + OPEN);
        assertEquals(4, state.getCardIndex());
    }

    // ===== STICKINESS =====

    private void openCardOn(int row) {
        state.updateCardHover(row, false, 0L);
        state.tickCard(OPEN);
        assertTrue(state.isCardOpen(), "precondition: card open on row " + row);
    }

    @Test
    void cardStaysOpenWhileCursorIsOverIt() {
        openCardOn(2);
        // Cursor moved off the row and onto the card: this is the case that makes the
        // buttons inside the card clickable at all.
        state.updateCardHover(-1, true, OPEN);
        state.tickCard(OPEN + CLOSE * 10);
        assertTrue(state.isCardOpen(), "card must not close while the cursor is inside it");
        assertEquals(2, state.getCardIndex());
    }

    @Test
    void cardStaysOpenWhileCursorIsOnItsAnchorRow() {
        openCardOn(2);
        state.updateCardHover(2, false, OPEN);
        state.tickCard(OPEN + CLOSE * 10);
        assertTrue(state.isCardOpen());
    }

    @Test
    void returningToTheCardCancelsTheCloseGrace() {
        openCardOn(2);
        state.updateCardHover(-1, false, OPEN);          // crossing the seam
        state.updateCardHover(-1, true, OPEN + 50L);     // landed on the card
        state.tickCard(OPEN + CLOSE * 5);
        assertTrue(state.isCardOpen(), "crossing the seam must not close the card");
    }

    // ===== CLOSING =====

    @Test
    void leavingBothClosesAfterTheGracePeriod() {
        openCardOn(2);
        state.updateCardHover(-1, false, OPEN);
        state.tickCard(OPEN + CLOSE - 1L);
        assertTrue(state.isCardOpen(), "still inside the grace period");
        state.tickCard(OPEN + CLOSE);
        assertFalse(state.isCardOpen());
    }

    @Test
    void hoveringAnotherRowClosesTheOldCardAtOnce() {
        openCardOn(2);
        state.updateCardHover(6, false, OPEN);
        assertFalse(state.isCardOpen(), "the old card is no longer what the cursor is asking about");
    }

    @Test
    void scrollingClosesTheCard() {
        openCardOn(2);
        state.scrollDown();
        assertFalse(state.isCardOpen(), "the card is anchored to a row's screen position");
    }

    @Test
    void scrollingThatChangesNothingKeepsTheCard() {
        openCardOn(2);
        state.scrollUp(); // already at offset 0
        assertTrue(state.isCardOpen(), "a clamped, no-op scroll must not disturb the card");
    }

    @Test
    void selectingAVisibleRowKeepsTheCard() {
        openCardOn(2);
        state.setSelectedIndex(3);
        assertTrue(state.isCardOpen(), "selection inside the page does not move any row");
    }

    @Test
    void selectingAnOffscreenRowClosesTheCard() {
        openCardOn(2);
        state.setSelectedIndex(11); // forces a scroll
        assertFalse(state.isCardOpen());
    }

    @Test
    void openingADialogClosesTheCard() {
        openCardOn(2);
        state.openDeleteDialog("World 2");
        assertFalse(state.isCardOpen(), "the card must not float over a modal dialog");
    }

    @Test
    void refreshingTheWorldListClosesTheCard() {
        openCardOn(2);
        state.setWorldList(worlds(3));
        assertFalse(state.isCardOpen(), "row 2 may be a different world now");
    }

    @Test
    void resetClosesTheCard() {
        openCardOn(2);
        state.reset();
        assertFalse(state.isCardOpen());
    }

    @Test
    void cardForARowThatNoLongerExistsReportsNoWorld() {
        state.updateCardHover(10, false, 0L);
        state.setWorldList(worlds(2)); // shrinks the list, cancelling the pending open
        state.tickCard(OPEN);
        assertNull(state.getCardWorld(), "a card must never name a row past the end of the list");
    }

    // ===== BUTTON HOVER =====

    @Test
    void cardButtonHoverIsClearedWhenTheCardCloses() {
        openCardOn(2);
        state.setHoveredCardButton("card-backup");
        assertEquals("card-backup", state.getHoveredCardButton());
        state.closeCard();
        assertNull(state.getHoveredCardButton(), "a closed card has no hovered button");
    }
}
