package com.stonebreak.blocks.meatrack;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeatRackStateTest {

    private static final BlockPos POS = new BlockPos(1, 64, 2);

    @Test
    void onlyFoodHangs() {
        MeatRackState rack = new MeatRackState(POS);
        ItemStack stick = new ItemStack(ItemType.STICK, 3);
        assertEquals(0, rack.hang(stick));
        assertEquals(0, rack.roomFor(stick));
        assertEquals(3, stick.getCount(), "a refused stack is left alone");
        assertTrue(rack.isEmpty());
    }

    @Test
    void foodTopsUpItsOwnHookBeforeTakingANewOne() {
        MeatRackState rack = new MeatRackState(POS);
        rack.hang(new ItemStack(ItemType.COOKED_BEEF, 10));
        rack.hang(new ItemStack(ItemType.BANANA, 4));
        rack.hang(new ItemStack(ItemType.COOKED_BEEF, 5));
        assertEquals(15, rack.getSlots()[0].getCount());
        assertEquals(ItemType.BANANA, rack.getSlots()[1].getItem());
        assertTrue(rack.getSlots()[2].isEmpty());
    }

    @Test
    void aFullRackHangsWhatFitsAndHandsBackTheRest() {
        MeatRackState rack = new MeatRackState(POS);
        int max = ItemType.RAW_MUTTON.getMaxStackSize();
        ItemStack big = new ItemStack(ItemType.RAW_MUTTON, max);
        for (int i = 0; i < MeatRackState.HOOKS; i++) {
            assertEquals(max, rack.hang(new ItemStack(ItemType.RAW_MUTTON, max)));
        }
        assertEquals(0, rack.roomFor(big));
        assertEquals(0, rack.hang(big));
        assertEquals(max, big.getCount());
    }

    @Test
    void roomForPredictsHangWithoutChangingTheHooks() {
        MeatRackState rack = new MeatRackState(POS);
        rack.hang(new ItemStack(ItemType.COOKED_GOOSE, 60));
        ItemStack more = new ItemStack(ItemType.COOKED_GOOSE, 20);
        int predicted = rack.roomFor(more);
        assertEquals(60, rack.getSlots()[0].getCount(), "roomFor changed nothing");
        assertEquals(predicted, rack.hang(more));
    }

    @Test
    void takingEmptiesTheLastFilledHook() {
        MeatRackState rack = new MeatRackState(POS);
        assertNull(rack.takeLast());
        assertEquals(-1, rack.lastFilledHook());
        rack.hang(new ItemStack(ItemType.COOKED_CHICKEN, 2));
        rack.hang(new ItemStack(ItemType.BLUEBERRY, 7));
        assertEquals(1, rack.lastFilledHook());
        ItemStack taken = rack.takeLast();
        assertEquals(ItemType.BLUEBERRY, taken.getItem());
        assertEquals(7, taken.getCount());
        assertEquals(0, rack.lastFilledHook());
    }

    @Test
    void hooksRoundTripThroughTheStateString() {
        MeatRackState rack = new MeatRackState(POS);
        rack.hang(new ItemStack(ItemType.COOKED_BEEF, 3));
        rack.hang(new ItemStack(ItemType.RAW_GOOSE, 1));
        String raw = rack.toStateString();
        assertTrue(MeatRackState.isMeatRackState(raw));
        MeatRackState back = new MeatRackState(POS);
        back.loadStateString(raw);
        assertEquals(3, back.getSlots()[0].getCount());
        assertEquals(ItemType.RAW_GOOSE, back.getSlots()[1].getItem());
        assertTrue(back.getSlots()[2].isEmpty());
    }

    @Test
    void hangingFoodNeverRemeshesTheChunk() {
        MeatRackState rack = new MeatRackState(POS);
        String bare = rack.toStateString();
        rack.hang(new ItemStack(ItemType.COOKED_BEEF, 1));
        assertNull(BlockRenderState.meshVariantKey(rack.toStateString()));
        assertFalse(BlockRenderState.affectsMesh(bare, rack.toStateString()));
    }
}
