package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A camp grows on food it can spare, never past twelve, and runs its goblins in bands of four. */
class CampGrowthTest {

    private static Goblin goblin() {
        return new Goblin(null, new Vector3f(), new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
    }

    private static GoblinCamp campOf(int goblins) {
        GoblinCamp camp = new GoblinCamp(new BlockPos(0, 64, 0));
        Goblin first = goblin();
        camp.adopt(first.pack());
        for (int i = 1; i < goblins; i++) {
            camp.join(goblin());
        }
        return camp;
    }

    @Test
    void growthNeedsADaysFoodLeftAfterPaying() {
        assertFalse(CampGrowth.canGrow(3, 6), "3 to eat + 4 to pay is more than 6");
        assertTrue(CampGrowth.canGrow(3, 7));
        assertFalse(CampGrowth.canGrow(CampGrowth.MAX_POPULATION, 1000), "full");
        assertFalse(CampGrowth.canGrow(0, 1000), "an empty camp grows no one");
    }

    @Test
    void aWellFedCampPaysFourMealsForAnotherGoblin() {
        GoblinCamp camp = campOf(2);
        camp.stash().add(new ItemStack(ItemType.COOKED_BEEF, 3)); // 9 meals
        camp.tick(CampGrowth.INTERVAL_SECONDS + 0.1f, new Random(1));
        assertEquals(3, camp.population(), "a new goblin waits to come in at the fire");
        assertEquals(1, camp.waiting().size());
        assertTrue(camp.stash().food() <= 9 - CampGrowth.COST, "it was paid for: " + camp.stash().food());
    }

    @Test
    void goblinsJoinPacksOfFourAndOverfullPacksSplit() {
        GoblinCamp camp = campOf(6);
        assertEquals(2, camp.packs().size());
        assertEquals(4, camp.packs().get(0).size());
        assertEquals(2, camp.packs().get(1).size());

        // A loner joins a full pack (as a campless goblin may): the camp splits it at its next tick.
        GoblinPack first = camp.packs().get(0);
        first.recruit(goblin());
        assertEquals(5, first.size());
        camp.tick(0.05f, new Random(1));
        for (GoblinPack p : camp.packs()) {
            assertTrue(p.size() >= 2 && p.size() <= CampGrowth.PACK_SIZE, "pack of " + p.size());
        }
        assertEquals(7, camp.population());
    }

    @Test
    void aCampsFifthGoblinIsNotLeftOnItsOwn() {
        GoblinCamp camp = campOf(5);
        assertEquals(1, camp.packs().get(1).size(), "it came in to a full pack");
        camp.tick(0.05f, new Random(1));
        java.util.List<Integer> sizes = camp.packs().stream().map(GoblinPack::size).filter(n -> n > 0).sorted().toList();
        assertEquals(java.util.List.of(2, 3), sizes, "three and two, not four and one");
        assertEquals(5, camp.population());
    }

    @Test
    void aPackOutHuntingIsNotReshuffled() {
        GoblinCamp camp = campOf(5);
        GoblinPack hunters = camp.packs().get(1);
        camp.huntStarted(hunters);
        camp.tick(0.05f, new Random(1));
        assertEquals(1, hunters.size(), "the lone hunter keeps its own pack until it is home");
    }

    @Test
    void onlyOnePackHuntsForTheCampAtATime() {
        GoblinCamp camp = campOf(8);
        GoblinPack a = camp.packs().get(0);
        GoblinPack b = camp.packs().get(1);
        assertNotSame(a, b);
        assertTrue(camp.mayHunt(a));
        camp.huntStarted(a);
        assertFalse(camp.mayHunt(b), "the other pack stays home");
        camp.huntEnded(a);
        assertTrue(camp.mayHunt(b));
    }
}
