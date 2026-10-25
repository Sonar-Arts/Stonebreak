package com.stonebreak.items.weapon;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

import java.util.Locale;

/**
 * Tooltip text for an item: its name, plus the weapon group, damage type and damage
 * when it is a weapon ("Sword (One-Handed, Slashing, 6 dmg)").
 */
public final class WeaponTooltip {

    private WeaponTooltip() {
    }

    public static String text(Item item) {
        String name = item.getName();
        if (!(item instanceof ItemType type)) {
            return name;
        }
        WeaponStats w = WeaponRules.statsFor(type);
        if (w == WeaponStats.UNARMED) {
            return name;
        }
        return String.format(Locale.ROOT, "%s (%s, %s, %s dmg)", name, w.group().getDisplayName(),
                w.damageType().getDisplayName(), number(w.damage()));
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
