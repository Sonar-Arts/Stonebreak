package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmeltingRecipeTest {

    @Test
    void constructorThrowsForNullId() {
        assertThrows(NullPointerException.class, () ->
                new SmeltingRecipe(null, new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1)));
    }

    @Test
    void constructorThrowsForNullInput() {
        assertThrows(NullPointerException.class, () ->
                new SmeltingRecipe("test:dir2sto", null, new ItemStack(BlockType.STONE, 1)));
    }

    @Test
    void constructorThrowsForNullOutput() {
        assertThrows(NullPointerException.class, () ->
                new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), null));
    }

    @Test
    void matchesReturnsTrueForSameItemRegardlessOfCount() {
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        assertTrue(recipe.matches(new ItemStack(BlockType.DIRT, 1)), "count 1 should match");
        assertTrue(recipe.matches(new ItemStack(BlockType.DIRT, 5)), "count 5 should match");
    }

    @Test
    void matchesReturnsFalseForDifferentItem() {
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        assertFalse(recipe.matches(new ItemStack(BlockType.STONE, 1)));
    }

    @Test
    void matchesReturnsFalseForNull() {
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        assertFalse(recipe.matches(null));
    }

    @Test
    void matchesReturnsFalseForEmptyStack() {
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        assertFalse(recipe.matches(new ItemStack(BlockType.DIRT, 0)));
    }

    @Test
    void equalsAndHashCodeUseIdOnly() {
        SmeltingRecipe a = new SmeltingRecipe("same:id", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));
        SmeltingRecipe b = new SmeltingRecipe("same:id", new ItemStack(ItemType.STICK, 1), new ItemStack(ItemType.WOODEN_PICKAXE, 1));

        assertEquals(a, b, "same id should be equal regardless of input/output");
        assertEquals(a.hashCode(), b.hashCode(), "same id should have equal hash codes");
    }

    @Test
    void differentIdsAreNotEqual() {
        SmeltingRecipe a = new SmeltingRecipe("id:one", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));
        SmeltingRecipe b = new SmeltingRecipe("id:two", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        assertNotEquals(a, b);
    }
}