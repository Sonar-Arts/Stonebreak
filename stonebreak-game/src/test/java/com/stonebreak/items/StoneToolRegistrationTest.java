package com.stonebreak.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stone axe / stone pickaxe SBOs are wired up as first-class ItemTypes
 * (like the existing wooden / stone tool set): correct registry identity,
 * tool category, and damage step above their wooden counterparts.
 */
class StoneToolRegistrationTest {

    @Test
    void stonePickaxeIsRegisteredAsATool() {
        assertSame(ItemType.STONE_PICKAXE, ItemType.getByObjectId("stonebreak:stone_pickaxe"));
        assertEquals("stonebreak:stone_pickaxe", ItemType.objectIdFor(ItemType.STONE_PICKAXE));
        assertEquals(1029, ItemType.STONE_PICKAXE.getId());
        assertEquals("Stone Pickaxe", ItemType.STONE_PICKAXE.getName());
        assertTrue(ItemType.STONE_PICKAXE.isTool());
        assertEquals(ItemCategory.TOOLS, ItemType.STONE_PICKAXE.getCategory());
        assertEquals(1, ItemType.STONE_PICKAXE.getMaxStackSize());
    }

    @Test
    void stoneAxeIsRegisteredAsATool() {
        assertSame(ItemType.STONE_AXE, ItemType.getByObjectId("stonebreak:stone_axe"));
        assertEquals("stonebreak:stone_axe", ItemType.objectIdFor(ItemType.STONE_AXE));
        assertEquals(1030, ItemType.STONE_AXE.getId());
        assertEquals("Stone Axe", ItemType.STONE_AXE.getName());
        assertTrue(ItemType.STONE_AXE.isTool());
        assertEquals(ItemCategory.TOOLS, ItemType.STONE_AXE.getCategory());
        assertEquals(1, ItemType.STONE_AXE.getMaxStackSize());
    }

    @Test
    void stoneToolsArePresentInTheGlobalRegistry() {
        assertNotNull(ItemType.valueOf("STONE_PICKAXE"));
        assertNotNull(ItemType.valueOf("STONE_AXE"));
        boolean sawPick = false;
        boolean sawAxe = false;
        for (ItemType t : ItemType.values()) {
            if (t == ItemType.STONE_PICKAXE) sawPick = true;
            if (t == ItemType.STONE_AXE) sawAxe = true;
        }
        assertTrue(sawPick && sawAxe, "STONE_PICKAXE/STONE_AXE must be reachable via ItemType.values()");
    }

    @Test
    void stoneToolsOutdamageTheirWoodenCounterparts() {
        assertTrue(ItemType.STONE_AXE.getDamage() > ItemType.WOODEN_AXE.getDamage());
        assertTrue(ItemType.STONE_PICKAXE.getDamage() > ItemType.WOODEN_PICKAXE.getDamage());
    }
}
