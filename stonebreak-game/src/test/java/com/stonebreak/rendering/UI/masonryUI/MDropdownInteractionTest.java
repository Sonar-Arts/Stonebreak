package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dropdown's open/select/close protocol, headless. The rules that keep menus feeling right:
 * the header toggles the list, a click in the open list commits exactly the item under the mouse
 * (list geometry starts flush under the header), any outside click closes AND is consumed (so it
 * cannot fall through to a widget underneath), and the change callback fires only when the
 * selection actually changes.
 */
class MDropdownInteractionTest {

    private static final float ITEM_H = 20f;

    private MDropdown dropdown() {
        return new MDropdown("Quality", new String[] {"Low", "Medium", "High"})
                .itemHeight(ITEM_H).bounds(10, 10, 120, 24);
    }

    @Test
    void theHeaderTogglesTheList() {
        MDropdown dropdown = dropdown();
        assertFalse(dropdown.isOpen());

        assertTrue(dropdown.handleClick(70, 22));
        assertTrue(dropdown.isOpen());
        assertTrue(dropdown.handleClick(70, 22));
        assertFalse(dropdown.isOpen());
    }

    @Test
    void theListStartsFlushUnderTheHeader() {
        MDropdown dropdown = dropdown();
        dropdown.open();

        // Header ends at y=34; items stack from there in ITEM_H rows.
        assertEquals(0, dropdown.itemUnderMouse(70, 35));
        assertEquals(1, dropdown.itemUnderMouse(70, 34 + ITEM_H + 1));
        assertEquals(2, dropdown.itemUnderMouse(70, 34 + 2 * ITEM_H + 1));
        assertEquals(-1, dropdown.itemUnderMouse(70, 34 + 3 * ITEM_H + 1), "past the last row");
        assertEquals(-1, dropdown.itemUnderMouse(200, 40), "beside the list");
    }

    @Test
    void aClosedListHasNothingUnderTheMouse() {
        assertEquals(-1, dropdown().itemUnderMouse(70, 40));
    }

    @Test
    void clickingAnItemCommitsItAndCloses() {
        AtomicInteger changes = new AtomicInteger();
        MDropdown dropdown = dropdown().onSelect(changes::incrementAndGet);
        dropdown.open();

        assertTrue(dropdown.handleClick(70, 34 + ITEM_H + 5)); // row 1

        assertEquals(1, dropdown.selectedIndex());
        assertEquals("Medium", dropdown.selectedItem());
        assertFalse(dropdown.isOpen());
        assertEquals(1, changes.get());
    }

    @Test
    void reSelectingTheCurrentItemClosesWithoutFiring() {
        AtomicInteger changes = new AtomicInteger();
        MDropdown dropdown = dropdown().onSelect(changes::incrementAndGet);
        dropdown.open();

        dropdown.handleClick(70, 35); // row 0, already selected

        assertFalse(dropdown.isOpen());
        assertEquals(0, changes.get(), "no change means no settings write");
    }

    @Test
    void anOutsideClickClosesAndIsConsumed() {
        MDropdown dropdown = dropdown();
        dropdown.open();

        assertTrue(dropdown.handleClick(400, 400),
                "the dismissing click must not fall through to whatever is underneath");
        assertFalse(dropdown.isOpen());
    }

    @Test
    void aClickMissingAClosedDropdownIsIgnored() {
        assertFalse(dropdown().handleClick(400, 400));
    }

    @Test
    void keyboardNudgesClampAtTheEndsAndFirePerStep() {
        AtomicInteger changes = new AtomicInteger();
        MDropdown dropdown = dropdown().onSelect(changes::incrementAndGet);

        dropdown.adjustSelection(-1);
        assertEquals(0, dropdown.selectedIndex(), "already at the top — no wrap");
        assertEquals(0, changes.get());

        dropdown.adjustSelection(1);
        dropdown.adjustSelection(1);
        dropdown.adjustSelection(1);
        assertEquals(2, dropdown.selectedIndex(), "clamped at the bottom");
        assertEquals(2, changes.get(), "two real moves, two callbacks");
    }
}
