package com.stonebreak.items.weapon;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Weapons show their group, damage type and damage after the name; everything else shows just the name. */
class WeaponTooltipTest {

    @Test
    void weaponsListTheirStats() {
        assertEquals("Sword (One-Handed, Slashing, 6 dmg)", WeaponTooltip.text(ItemType.SWORD));
    }

    @Test
    void nonWeaponsShowOnlyTheirName() {
        assertEquals(ItemType.STICK.getName(), WeaponTooltip.text(ItemType.STICK));
        assertEquals(BlockType.DIRT.getName(), WeaponTooltip.text(BlockType.DIRT));
    }
}
