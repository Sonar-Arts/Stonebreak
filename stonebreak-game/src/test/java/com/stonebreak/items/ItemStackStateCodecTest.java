package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stack token every item-holding block (furnace, crafting table) persists in its chunk
 * block state. A token that loses the item, its count or its state destroys items on reload.
 */
class ItemStackStateCodecTest {

    @Test
    void blockAndItemStacksRoundTrip() {
        ItemStack dirt = ItemStackStateCodec.decode(ItemStackStateCodec.encode(new ItemStack(BlockType.DIRT, 12)));
        assertSame(BlockType.DIRT, dirt.getItem());
        assertEquals(12, dirt.getCount());

        ItemStack sticks = ItemStackStateCodec.decode(ItemStackStateCodec.encode(new ItemStack(ItemType.STICK, 3)));
        assertSame(ItemType.STICK, sticks.getItem());
        assertEquals(3, sticks.getCount());
    }

    @Test
    void theItemStateSurvives() {
        // A filled bucket must come back filled, not as an empty one.
        ItemStack water = new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER);
        ItemStack back = ItemStackStateCodec.decode(ItemStackStateCodec.encode(water));
        assertSame(ItemType.WOODEN_BUCKET, back.getItem());
        assertEquals(ItemType.BUCKET_STATE_WATER, back.getState());
    }

    @Test
    void aStateThatCouldBreakTheEnclosingStringIsNotWritten() {
        String token = ItemStackStateCodec.encode(new ItemStack(ItemType.STICK, 1, "bad|state;x=y"));
        assertEquals("I:" + ItemType.STICK.getId() + ":1", token);
        assertNull(ItemStackStateCodec.decode(token).getState());
    }

    @Test
    void emptyAndMalformedTokensDecodeToEmpty() {
        assertEquals(ItemStackStateCodec.EMPTY_TOKEN, ItemStackStateCodec.encode(null));
        assertEquals(ItemStackStateCodec.EMPTY_TOKEN, ItemStackStateCodec.encode(new ItemStack(0, 0)));
        assertTrue(ItemStackStateCodec.decode(null).isEmpty());
        assertTrue(ItemStackStateCodec.decode("").isEmpty());
        assertTrue(ItemStackStateCodec.decode("garbage").isEmpty());
        assertTrue(ItemStackStateCodec.decode("B:x:3").isEmpty());
        assertTrue(ItemStackStateCodec.decode("B:" + BlockType.DIRT.getId() + ":0").isEmpty());
    }

    @Test
    void clampLimitsAnOversizedStack() {
        ItemStack huge = new ItemStack(BlockType.DIRT, 1000);
        assertEquals(huge.getMaxStackSize(), ItemStackStateCodec.clamp(huge).getCount());
        assertTrue(ItemStackStateCodec.clamp(null).isEmpty());
    }
}
