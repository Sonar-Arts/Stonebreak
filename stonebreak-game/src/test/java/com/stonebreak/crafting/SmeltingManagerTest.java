package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmeltingManagerTest {

    @Test
    void registerRecipeIgnoresNullAndDuplicates() {
        SmeltingManager mgr = new SmeltingManager();
        SmeltingRecipe recipe = new SmeltingRecipe("test:dup", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));

        mgr.registerRecipe(null);
        mgr.registerRecipe(recipe);
        mgr.registerRecipe(recipe); // same id — should be ignored

        assertEquals(1, mgr.getRecipeCount());
    }

    @Test
    void getRecipeReturnsMatchingRecipeForRegisteredInput() {
        SmeltingManager mgr = new SmeltingManager();
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));
        mgr.registerRecipe(recipe);

        assertSame(recipe, mgr.getRecipe(new ItemStack(BlockType.DIRT, 1)));
    }

    @Test
    void getRecipeReturnsNullForUnregisteredInput() {
        SmeltingManager mgr = new SmeltingManager();
        SmeltingRecipe recipe = new SmeltingRecipe("test:dir2sto", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));
        mgr.registerRecipe(recipe);

        assertNull(mgr.getRecipe(new ItemStack(ItemType.STICK, 1)));
    }

    @Test
    void getRecipeReturnsNullForNullInput() {
        SmeltingManager mgr = new SmeltingManager();

        assertNull(mgr.getRecipe(null));
    }

    @Test
    void getRecipeReturnsNullForEmptyInput() {
        SmeltingManager mgr = new SmeltingManager();

        assertNull(mgr.getRecipe(new ItemStack(BlockType.STONE, 0)));
    }

    @Test
    void getRecipeReturnsFirstMatchingRecipe() {
        SmeltingManager mgr = new SmeltingManager();
        SmeltingRecipe first = new SmeltingRecipe("test:first", new ItemStack(BlockType.DIRT, 1), new ItemStack(BlockType.STONE, 1));
        SmeltingRecipe second = new SmeltingRecipe("test:second", new ItemStack(BlockType.DIRT, 1), new ItemStack(ItemType.STICK, 1));
        mgr.registerRecipe(first);
        mgr.registerRecipe(second);

        assertSame(first, mgr.getRecipe(new ItemStack(BlockType.DIRT, 1)), "should return the first matching recipe");
    }

    @Test
    void getBurnTimeReturnsPerUnitTicksTimesCount() {
        SmeltingManager mgr = new SmeltingManager();
        mgr.registerFuel(ItemType.STICK, 100);

        assertEquals(300, mgr.getBurnTime(new ItemStack(ItemType.STICK, 3)));
    }

    @Test
    void getBurnTimeReturnsZeroForNonFuel() {
        SmeltingManager mgr = new SmeltingManager();
        mgr.registerFuel(ItemType.STICK, 100);

        assertEquals(0, mgr.getBurnTime(new ItemStack(BlockType.STONE, 1)));
    }

    @Test
    void getBurnTimeReturnsZeroForNullStack() {
        SmeltingManager mgr = new SmeltingManager();

        assertEquals(0, mgr.getBurnTime(null));
    }

    @Test
    void getBurnTimeReturnsZeroForEmptyStack() {
        SmeltingManager mgr = new SmeltingManager();

        assertEquals(0, mgr.getBurnTime(new ItemStack(ItemType.STICK, 0)));
    }

    @Test
    void registerFuelIgnoresZeroAndNegativeBurnTicks() {
        SmeltingManager mgr = new SmeltingManager();

        mgr.registerFuel(ItemType.STICK, 0);
        assertEquals(0, mgr.getBurnTimePerUnit(ItemType.STICK), "burnTicks=0 should be ignored");

        mgr.registerFuel(ItemType.WOODEN_PICKAXE, -5);
        assertEquals(0, mgr.getBurnTimePerUnit(ItemType.WOODEN_PICKAXE), "burnTicks=-5 should be ignored");
    }

    @Test
    void getBurnTimePerUnitReturnsRegisteredValueForFuel() {
        SmeltingManager mgr = new SmeltingManager();
        mgr.registerFuel(ItemType.STICK, 100);

        assertEquals(100, mgr.getBurnTimePerUnit(ItemType.STICK));
    }

    @Test
    void getBurnTimePerUnitReturnsZeroForNonFuel() {
        SmeltingManager mgr = new SmeltingManager();

        assertEquals(0, mgr.getBurnTimePerUnit(BlockType.STONE));
    }
}