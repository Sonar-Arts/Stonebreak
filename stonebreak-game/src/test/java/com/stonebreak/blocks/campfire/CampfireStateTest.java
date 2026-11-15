package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipeLoader;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cooking on a campfire, and the state string that carries it (and a camp) with the chunk. */
class CampfireStateTest {

    private static final SmeltingManager SMELTING = new SmeltingManager();
    private static final BlockPos POS = new BlockPos(3, 64, -7);

    @BeforeAll
    static void loadRecipes() {
        SmeltingRecipeLoader.loadFromSBOs(SMELTING);
    }

    @Test
    void rawMeatComesOffCookedAfterTheCookTime() {
        CampfireState fire = new CampfireState(POS);
        assertTrue(fire.put(SMELTING, ItemType.RAW_BEEF));
        assertTrue(fire.tick(SMELTING, CampfireState.COOK_SECONDS - 0.5f).isEmpty(), "not done yet");
        List<ItemStack> done = fire.tick(SMELTING, 1.0f);
        assertEquals(1, done.size());
        assertSame(ItemType.COOKED_BEEF, done.get(0).getItem());
        assertTrue(fire.contents().isEmpty(), "the spot is free again");
    }

    @Test
    void onlyCookableThingsGoOnAndOnlyFourAtATime() {
        CampfireState fire = new CampfireState(POS);
        assertFalse(fire.put(SMELTING, ItemType.STICK), "a stick does not cook");
        assertFalse(fire.put(SMELTING, ItemType.COOKED_MUTTON), "cooked meat does not cook again");
        for (int i = 0; i < CampfireState.SLOTS; i++) {
            assertTrue(fire.put(SMELTING, ItemType.RAW_CHICKEN));
        }
        assertFalse(fire.hasFreeSpot());
        assertFalse(fire.put(SMELTING, ItemType.RAW_CHICKEN));
    }

    @Test
    void theStateStringKeepsTheCookingAndTheCampAndDrawsAsLit() {
        CampfireState fire = new CampfireState(POS);
        fire.put(SMELTING, ItemType.RAW_GOOSE);
        fire.tick(SMELTING, 3.0f);
        fire.setCampData("abc-DEF_123");
        String raw = fire.toStateString();

        assertEquals(CampfireState.LIT, BlockRenderState.meshVariantKey(raw), "payload never changes the clip");
        CampfireState back = CampfireState.parse(POS, raw);
        assertEquals("abc-DEF_123", back.campData());
        assertEquals(1, back.contents().size());
        assertTrue(back.tick(SMELTING, CampfireState.COOK_SECONDS - 3.0f + 0.1f).size() == 1,
                "the cooking carried on from where it was");
    }

    @Test
    void aPlainFireCarriesNoCamp() {
        assertNull(CampfireState.parse(POS, new CampfireState(POS).toStateString()).campData());
        assertNull(CampfireState.parse(POS, null).campData());
    }
}
