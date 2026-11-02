package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Dictionaries, {@code #} ingredients, and the shipped dictionary data. */
class ItemDictionaryTest {

    @BeforeAll
    static void registries() {
        BlockType.values();
        ItemType.values();
    }

    private static ItemDictionary planks() {
        return new ItemDictionary.Builder()
                .add("test:planks", BlockType.WOOD_PLANKS)
                .add("test:planks", BlockType.DRY_OAK_PLANKS)
                .add("test:planks", BlockType.WOOD_PLANKS) // duplicate ignored
                .build();
    }

    @Test
    void builderKeepsOrderAndDropsDuplicates() {
        assertEquals(List.of(BlockType.WOOD_PLANKS, BlockType.DRY_OAK_PLANKS), planks().members("test:planks"));
        assertEquals(List.of(), planks().members("test:unknown"));
    }

    @Test
    void parseDistinguishesDictionariesFromSpecificItems() {
        ItemDictionary dict = planks();
        Ingredient tag = Ingredient.parse("#test:planks", dict);
        assertInstanceOf(Ingredient.Dictionary.class, tag);
        assertTrue(tag.test(BlockType.DRY_OAK_PLANKS));
        assertFalse(tag.test(BlockType.STONE));

        Ingredient single = Ingredient.parse("stonebreak:wood_planks", dict);
        assertInstanceOf(Ingredient.Single.class, single);
        assertFalse(single.test(BlockType.DRY_OAK_PLANKS));

        assertNull(Ingredient.parse("#test:unknown", dict), "unknown dictionary");
        assertNull(Ingredient.parse("stonebreak:nope", dict), "unknown item");
        assertNull(Ingredient.parse(" ", dict));
    }

    @Test
    void dictionaryRecipeAcceptsAnyMemberAndRepresentsTheFirst() {
        Ingredient tag = Ingredient.parse("#test:planks", planks());
        Recipe r = Recipe.ofIngredients("t", List.of(List.of(tag, tag)), new ItemStack(ItemType.STICK, 4));
        CraftingManager m = new CraftingManager();
        m.registerRecipe(r);
        assertNotNull(m.craftItem(List.of(List.of(
                new ItemStack(BlockType.WOOD_PLANKS, 1), new ItemStack(BlockType.DRY_OAK_PLANKS, 1)))));
        assertNull(m.craftItem(List.of(List.of(
                new ItemStack(BlockType.WOOD_PLANKS, 1), new ItemStack(BlockType.STONE, 1)))));
        assertEquals(BlockType.WOOD_PLANKS, r.getPattern()[0][1].getItem());
    }

    @Test
    void displayNameIsTitleCasedPath() {
        assertEquals("Wooden Planks", ItemDictionary.displayName("stonebreak:wooden_planks"));
        assertEquals("Logs", ItemDictionary.displayName("logs"));
    }

    // ── shipped data ─────────────────────────────────────────────────────────

    @Test
    void shippedDictionariesHaveTheirMembers() {
        ItemDictionary dict = ItemDictionary.fromRegistries();
        assertEquals(5, dict.members("stonebreak:wooden_planks").size());
        assertTrue(dict.members("stonebreak:logs").contains(BlockType.DRY_OAK_LOG));
        assertEquals(List.of(BlockType.COBBLESTONE), dict.members("stonebreak:cobblestone"),
                "cobbled sandstones are not cobblestone: they make no stone tools");
        assertTrue(dict.members("stonebreak:leaves").size() >= 2);
        assertTrue(dict.members("stonebreak:sand").contains(BlockType.getByObjectId("stonebreak:red_sand")));
    }

    @Test
    void onlyCobblestoneMakesAStonePickaxe() {
        CraftingManager m = new CraftingManager();
        RecipeLoader.loadFromSBOs(m);
        assertEquals(ItemType.getByObjectId("stonebreak:stone_pickaxe"),
                m.craftItem(pickaxe(BlockType.COBBLESTONE)).getItem());
        for (BlockType cobbled : new BlockType[]{BlockType.COBBLED_SANDSTONE, BlockType.COBBLED_RED_SANDSTONE}) {
            ItemStack out = m.craftItem(pickaxe(cobbled));
            assertTrue(out == null || out.isEmpty(), cobbled.getName() + " made " + out);
        }
    }

    private static List<List<ItemStack>> pickaxe(BlockType head) {
        ItemStack h = new ItemStack(head, 1);
        ItemStack stick = new ItemStack(ItemType.STICK, 1);
        return List.of(
                List.of(h, h, h),
                Arrays.asList(null, stick, null),
                Arrays.asList(null, stick, null));
    }

    @Test
    void smeltingAndFuelUseDictionaries() {
        SmeltingManager s = new SmeltingManager();
        SmeltingRecipeLoader.loadFromSBOs(s);
        // #stonebreak:sand → glass, for both sands
        assertEquals(BlockType.getByObjectId("stonebreak:glass"),
                s.getRecipe(new ItemStack(BlockType.getByObjectId("stonebreak:red_sand"), 1)).getOutput().getItem());
        assertEquals(BlockType.getByObjectId("stonebreak:glass"),
                s.getRecipe(new ItemStack(BlockType.getByObjectId("stonebreak:sand"), 1)).getOutput().getItem());
        // the furnace's fuel table covers whole dictionaries...
        assertEquals(300, s.getBurnTimePerUnit(BlockType.DRY_OAK_LOG));
        assertEquals(300, s.getBurnTimePerUnit(BlockType.WOOD_PLANKS));
        assertEquals(100, s.getBurnTimePerUnit(ItemType.STICK));
        // ...but an item's own fuel declaration wins
        assertEquals(800, s.getBurnTimePerUnit(ItemType.getByObjectId("stonebreak:coal_chunk")));
        assertEquals(0, s.getBurnTimePerUnit(BlockType.STONE));
    }

    @Test
    void fuelIfAbsentNeverOverrides() {
        SmeltingManager s = new SmeltingManager();
        s.registerFuel(BlockType.WOOD, 50);
        assertFalse(s.registerFuelIfAbsent(BlockType.WOOD, 300));
        assertEquals(50, s.getBurnTimePerUnit(BlockType.WOOD));
        assertTrue(s.registerFuelIfAbsent(BlockType.WOOD_PLANKS, 300));
    }
}
