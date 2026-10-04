package com.stonebreak.mobs.goblin;

import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.mobs.sbe.Clothing;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A goblin's loadout is saved and replicated as one string, so it must survive the round trip
 * exactly — otherwise a goblin changes weapon or hat across a reload or between players.
 */
class GoblinLoadoutTest {

    @Test
    void everyLoadoutSurvivesEncodeAndDecode() {
        for (MobWeapon weapon : MobWeapon.values()) {
            for (Headwear headwear : Headwear.values()) {
                for (boolean earring : new boolean[] {false, true}) {
                    GoblinLoadout loadout = new GoblinLoadout(weapon, headwear,
                            headwear == Headwear.BANDANA ? "Green" : null, earring);
                    assertEquals(loadout, GoblinLoadout.decode(loadout.encode()), loadout.encode());
                }
            }
        }
    }

    @Test
    void garbageDecodesToAPlainDaggerGoblin() {
        GoblinLoadout plain = new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false);
        assertEquals(plain, GoblinLoadout.decode(null));
        assertEquals(plain, GoblinLoadout.decode(""));
        assertEquals(plain, GoblinLoadout.decode("SPEAR;CROWN"));
    }

    @Test
    void onlyABandanaCarriesAColourAndItIsAlwaysARealOne() {
        assertNull(new GoblinLoadout(MobWeapon.BOW, Headwear.PIRATE_HAT, "Red", false).bandanaColor());
        assertTrue(Clothing.BANDANA_COLORS.contains(
                new GoblinLoadout(MobWeapon.BOW, Headwear.BANDANA, "Plaid", false).bandanaColor()));
    }

    @Test
    void rollsGiveEveryWeaponAndARarePirateHat() {
        Random random = new Random(42);
        int samples = 20_000;
        Map<MobWeapon, Integer> weapons = new EnumMap<>(MobWeapon.class);
        int pirateHats = 0;
        for (int i = 0; i < samples; i++) {
            GoblinLoadout loadout = GoblinLoadout.roll(random);
            weapons.merge(loadout.weapon(), 1, Integer::sum);
            if (loadout.headwear() == Headwear.PIRATE_HAT) pirateHats++;
        }
        for (MobWeapon weapon : MobWeapon.values()) {
            assertTrue(weapons.getOrDefault(weapon, 0) > samples / 10, weapon + " is rolled");
        }
        float pirateRate = pirateHats / (float) samples;
        assertEquals(GoblinLoadout.PIRATE_HAT_CHANCE, pirateRate, 0.01f, "the pirate hat stays rare");
    }
}
