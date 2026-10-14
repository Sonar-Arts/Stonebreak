package com.stonebreak.items.weapon;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Guards the SBO 1.10 weapon data across every shipped item: each named weapon
 * has a {@code weapon} section, and every authored group and damage type is
 * one the game knows (an unknown one would silently fall back to a fist).
 */
class WeaponDataCoverageTest {

    private static final Set<String> WEAPONS = Set.of(
            "stonebreak:sword", "stonebreak:dagger", "stonebreak:war_axe",
            "stonebreak:patty_smacker", "stonebreak:staff");

    @Test
    void everyNamedWeaponHasWeaponData() {
        List<String> missing = new ArrayList<>();
        for (String id : WEAPONS) {
            boolean has = ItemRegistry.getInstance().get(id)
                    .map(e -> e.sboData().manifest().weapon() != null).orElse(false);
            if (!has) missing.add(id);
        }
        assertEquals(List.of(), missing, "weapon SBOs without a weapon section");
    }

    @Test
    void everyAuthoredWeaponResolvesToGameStats() {
        List<String> unknown = new ArrayList<>();
        for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
            SBOFormat.WeaponData w = e.sboData().manifest().weapon();
            if (w == null) continue;
            if (!names(WeaponGroup.values()).contains(w.group())
                    || !names(DamageType.values()).contains(w.damageType())) {
                unknown.add(e.objectId() + " → " + w.group() + "/" + w.damageType());
                continue;
            }
            ItemType type = ItemType.getByObjectId(e.objectId());
            assertNotNull(type, e.objectId() + " has weapon data but no ItemType");
            assertNotSame(WeaponStats.UNARMED, WeaponRules.statsFor(type), e.objectId());
        }
        assertEquals(List.of(), unknown, "weapon groups / damage types the game does not know");
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> v : values) out.add(v.name().toLowerCase(Locale.ROOT));
        return out;
    }
}
