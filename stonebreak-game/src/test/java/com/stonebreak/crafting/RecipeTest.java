package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Recipe} constructor validation, dimension reporting,
 * {@code matches()} behaviour, and identity-based {@code equals}/hashCode.
 */
class RecipeTest {

    private static List<ItemStack> row(ItemStack... slots) {
        return Arrays.asList(slots);
    }

    private static List<List<ItemStack>> pattern(List<ItemStack>... rows) {
        return Arrays.asList(rows);
    }

    private static List<List<ItemStack>> grid(List<ItemStack>... rows) {
        return Arrays.asList(rows);
    }

    private static ItemStack s(BlockType b, int count) {
        return new ItemStack(b, count);
    }

    // ── Constructor ──────────────────────────────────────────────────────

    @Test
    void constructorThrowsNullPointerExceptionForNullIdOrNullPatternOrNullOutput() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.STONE, 1)));
        ItemStack out = s(BlockType.STONE, 1);

        assertThrows(NullPointerException.class, () -> new Recipe(null, pat, out));
        assertThrows(NullPointerException.class, () -> new Recipe("x", null, out));
        assertThrows(NullPointerException.class, () -> new Recipe("x", pat, null));
    }

    @Test
    void constructorThrowsIllegalArgumentExceptionWhenRowsHaveDifferentLengths() {
        List<List<ItemStack>> ragged = pattern(row(s(BlockType.STONE, 1), s(BlockType.DIRT, 1)),
                                              row(s(BlockType.STONE, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Recipe("ragged", ragged, s(BlockType.STONE, 1)));
    }

    @Test
    void widthAndHeightDeriveFromPattern() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.STONE, 1), s(BlockType.DIRT, 1), s(BlockType.STONE, 1)),
                                            row(s(BlockType.DIRT, 1), s(BlockType.STONE, 1), s(BlockType.DIRT, 1)));
        Recipe recipe = new Recipe("2x3", pat, s(BlockType.STONE, 1));

        assertEquals(3, recipe.getRecipeWidth());
        assertEquals(2, recipe.getRecipeHeight());
    }

    // ── matches() ────────────────────────────────────────────────────────

    @Test
    void matchesSucceedsForExactSameItemsGrid() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.STONE, 1)));
        Recipe recipe = new Recipe("stone1", pat, s(BlockType.STONE, 1));

        assertTrue(recipe.matches(grid(row(s(BlockType.STONE, 1))), 0, 0));
    }

    @Test
    void matchesSucceedsWhenGridStackHasLargerCountAndFailsWhenSmaller() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.STONE, 2)));
        Recipe recipe = new Recipe("stone2", pat, s(BlockType.STONE, 1));

        assertTrue(recipe.matches(grid(row(s(BlockType.STONE, 3))), 0, 0));
        assertFalse(recipe.matches(grid(row(s(BlockType.STONE, 1))), 0, 0));
    }

    @Test
    void matchesFailsWhenGridHasDifferentItemInOneSlot() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.DIRT, 1)));
        Recipe recipe = new Recipe("dirt1", pat, s(BlockType.STONE, 1));

        assertFalse(recipe.matches(grid(row(s(BlockType.STONE, 1))), 0, 0));
    }

    @Test
    void matchesTreatsEmptyGridStackTheSameAsEmptySlotWhenIngredientIsNull() {
        List<List<ItemStack>> pat = pattern(row((ItemStack) null));
        Recipe recipe = new Recipe("empty1", pat, s(BlockType.STONE, 1));

        assertTrue(recipe.matches(grid(row(new ItemStack(BlockType.STONE, 0))), 0, 0));
    }

    @Test
    void matchesFailsWhenIngredientIsNullButGridHasNonEmptyStack() {
        List<List<ItemStack>> pat = pattern(row((ItemStack) null));
        Recipe recipe = new Recipe("empty2", pat, s(BlockType.STONE, 1));

        assertFalse(recipe.matches(grid(row(s(BlockType.STONE, 1))), 0, 0));
    }

    @Test
    void matchesReturnsFalseWhenRecipeDoesNotFitAtTheOffset() {
        List<List<ItemStack>> pat = pattern(row(s(BlockType.STONE, 1), s(BlockType.DIRT, 1)));
        Recipe recipe = new Recipe("1x2", pat, s(BlockType.STONE, 1));

        assertFalse(recipe.matches(grid(row(s(BlockType.STONE, 1))), 0, 0));
    }

    // ── equals / hashCode ────────────────────────────────────────────────

    @Test
    void equalsAndHashCodeUseOnlyId() {
        Recipe a = new Recipe("same", pattern(row(s(BlockType.STONE, 1))), s(BlockType.STONE, 1));
        Recipe b = new Recipe("same", pattern(row(s(BlockType.DIRT, 1))), s(BlockType.DIRT, 1));
        Recipe c = new Recipe("different", pattern(row(s(BlockType.STONE, 1))), s(BlockType.STONE, 1));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}