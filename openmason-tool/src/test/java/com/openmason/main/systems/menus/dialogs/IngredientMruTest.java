package com.openmason.main.systems.menus.dialogs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientMruTest {

    @Test
    void touchOrdersMostRecentFirst() {
        IngredientMru mru = new IngredientMru(5);
        mru.touch("a");
        mru.touch("b");
        mru.touch("c");
        assertEquals(List.of("c", "b", "a"), mru.list());
    }

    @Test
    void touchingExistingEntryMovesItToFront() {
        IngredientMru mru = new IngredientMru(5);
        mru.touch("a");
        mru.touch("b");
        mru.touch("c");
        mru.touch("a");
        assertEquals(List.of("a", "c", "b"), mru.list());
        assertEquals(3, mru.list().size(), "re-touch must not duplicate");
    }

    @Test
    void capacityEvictsOldest() {
        IngredientMru mru = new IngredientMru(3);
        mru.touch("a");
        mru.touch("b");
        mru.touch("c");
        mru.touch("d");
        assertEquals(List.of("d", "c", "b"), mru.list());
    }

    @Test
    void nullAndEmptyIdsAreIgnored() {
        IngredientMru mru = new IngredientMru(3);
        mru.touch(null);
        mru.touch("");
        assertTrue(mru.isEmpty());
    }

    @Test
    void clearEmpties() {
        IngredientMru mru = new IngredientMru(3);
        mru.touch("a");
        mru.clear();
        assertTrue(mru.isEmpty());
    }
}
