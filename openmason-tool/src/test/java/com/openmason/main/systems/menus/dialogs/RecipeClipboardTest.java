package com.openmason.main.systems.menus.dialogs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeClipboardTest {

    @BeforeEach
    @AfterEach
    void resetClipboard() {
        RecipeClipboard.clear();
    }

    @Test
    void ingredientCopyAndClear() {
        assertFalse(RecipeClipboard.hasIngredient());
        RecipeClipboard.copyIngredient("stonebreak:stick");
        assertTrue(RecipeClipboard.hasIngredient());
        assertEquals("stonebreak:stick", RecipeClipboard.ingredient());

        RecipeClipboard.copyIngredient("");
        assertFalse(RecipeClipboard.hasIngredient());
        assertNull(RecipeClipboard.ingredient());
    }

    @Test
    void patternClipRequiresNineSlots() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeClipboard.PatternClip(3, 3, List.of("a", "b"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeClipboard.PatternClip(3, 3, null, 1));
    }

    @Test
    void patternClipNormalizes() {
        List<String> slots = new ArrayList<>(Collections.nCopies(9, (String) null));
        slots.set(0, "stonebreak:wood_planks");
        RecipeClipboard.PatternClip clip = new RecipeClipboard.PatternClip(7, 0, slots, -3);

        assertEquals(3, clip.width(), "width clamps to 1..3");
        assertEquals(1, clip.height(), "height clamps to 1..3");
        assertEquals(1, clip.outputCount(), "output count floors at 1");
        assertEquals("stonebreak:wood_planks", clip.slots().get(0));
        for (int i = 1; i < 9; i++) {
            assertEquals("", clip.slots().get(i), "null slots normalize to empty strings");
        }
    }

    @Test
    void patternRoundTrip() {
        List<String> slots = Arrays.asList("a", "b", "", "", "c", "", "", "", "");
        RecipeClipboard.copyPattern(new RecipeClipboard.PatternClip(2, 2, slots, 4));

        assertTrue(RecipeClipboard.hasPattern());
        RecipeClipboard.PatternClip clip = RecipeClipboard.pattern();
        assertEquals(2, clip.width());
        assertEquals(2, clip.height());
        assertEquals(4, clip.outputCount());
        assertEquals(slots, clip.slots());
    }
}
