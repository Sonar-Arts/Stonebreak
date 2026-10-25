package com.stonebreak.items.weapon;

import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeaponRules} gives each weapon its group, damage type and swing time,
 * keeps the damage numbers the game shipped with, and treats everything else as a fist.
 */
class WeaponRulesTest {

    @Test
    void swordIsAOneHandedSlashingWeapon() {
        WeaponStats sword = WeaponRules.statsFor(ItemType.SWORD);
        assertEquals(WeaponGroup.ONE_HANDED, sword.group());
        assertEquals(DamageType.SLASHING, sword.damageType());
        assertEquals(6.0f, sword.damage());
    }

    @Test
    void autoRegisteredDaggerResolvesByObjectId() {
        ItemType dagger = ItemType.getByObjectId("stonebreak:dagger");
        assertNotNull(dagger);
        assertEquals(DamageType.PIERCING, WeaponRules.statsFor(dagger).damageType());
    }

    @Test
    void twoHandedWeaponsSwingSlowerThanOneHanded() {
        float sword = WeaponRules.statsFor(ItemType.SWORD).swingSeconds();
        assertTrue(WeaponRules.statsFor(ItemType.WAR_AXE).swingSeconds() > sword);
        assertTrue(WeaponRules.statsFor(ItemType.PATTY_SMACKER).swingSeconds() > sword);
    }

    @Test
    void shippedDamageIsPreserved() {
        assertEquals(4.0f, ItemType.WAR_AXE.getDamage());
        assertEquals(4.0f, ItemType.WOODEN_AXE.getDamage());
        assertEquals(5.0f, ItemType.STONE_AXE.getDamage());
        assertEquals(2.0f, ItemType.STONE_PICKAXE.getDamage());
    }

    @Test
    void nonWeaponsStrikeLikeAFist() {
        assertSame(WeaponStats.UNARMED, WeaponRules.statsFor(null));
        assertSame(WeaponStats.UNARMED, WeaponRules.statsFor(ItemType.STICK));
        assertSame(WeaponStats.UNARMED, WeaponRules.statsFor(ItemType.BANANA));
    }
}
