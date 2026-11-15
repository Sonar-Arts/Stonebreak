package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A camp saved in its fire comes back with the same goblins and the same stores. */
class CampCodecTest {

    private static final BlockPos FIRE = new BlockPos(10, 50, -4);

    @Test
    void aCampRoundTripsThroughItsFire() {
        GoblinCamp camp = new GoblinCamp(FIRE);
        Goblin archer = new Goblin(null, new Vector3f(10, 50, -3),
                new GoblinLoadout(MobWeapon.BOW, Headwear.PIRATE_HAT, null, true));
        camp.adopt(archer.pack());
        camp.addWaiting(new GoblinLoadout(MobWeapon.DAGGER, Headwear.BANDANA, "Red", false));
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 3));
        camp.stash().add(new ItemStack(ItemType.RAW_MUTTON, 1));

        String data = CampCodec.encode(camp);
        assertFalse(data.contains(";") || data.contains("="), "safe inside a state string: " + data);

        GoblinCamp back = CampCodec.decode(FIRE, data);
        assertEquals(2, back.population(), "everyone waits to be brought back");
        assertEquals(List.of(archer.getLoadout(), camp.waiting().get(0)), back.waiting());
        assertEquals(camp.stash().food(), back.stash().food());
        assertEquals(11, back.stash().food());
    }

    @Test
    void unreadableDataIsAnEmptyCampNotACrash() {
        GoblinCamp back = CampCodec.decode(FIRE, "%%% not base64 %%%");
        assertEquals(0, back.population());
        assertTrue(back.stash().isEmpty());
    }

    @Test
    void aRecalledGoblinRejoinsTheRosterAndItsMeatTheStash() {
        GoblinCamp camp = new GoblinCamp(FIRE);
        Goblin g = new Goblin(null, new Vector3f(), new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        camp.adopt(g.pack());
        g.inventory().add(new ItemStack(ItemType.RAW_BEEF, 2));
        g.pack().memberRemoved(g); // it wandered off into a chunk that unloaded
        assertEquals(1, camp.waiting().size());
        assertEquals(0, camp.members().size());
        assertEquals(6, camp.stash().food());
        assertEquals(1, camp.population());
    }
}
