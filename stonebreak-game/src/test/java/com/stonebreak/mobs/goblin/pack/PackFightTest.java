package com.stonebreak.mobs.goblin.pack;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.mobs.goblin.camp.RaidPlanner;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goblins of different camps fight on sight; a side that has lost half its number and is clearly
 * outmatched gives up and goes over to the winners; camps raid when hungry or much stronger.
 */
class PackFightTest {

    private final List<Entity> world = new ArrayList<>();
    private final NearbyEntities nearby = (center, radius) -> world.stream()
            .filter(e -> e.isAlive() && e.getPosition().distance(center) <= radius).toList();

    private Goblin goblin(float x) {
        Goblin g = new Goblin(null, new Vector3f(x, 64, 0), new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        world.add(g);
        return g;
    }

    private GoblinCamp camp(int x, int goblins) {
        GoblinCamp camp = new GoblinCamp(new BlockPos(x, 64, 0));
        camp.adopt(goblin(x).pack());
        for (int i = 1; i < goblins; i++) {
            camp.join(goblin(x + i * 0.5f));
        }
        return camp;
    }

    @Test
    void surrenderNeedsHeavyLossesAndBeingOutmatched() {
        assertFalse(SurrenderRule.surrenders(4, 3, 4), "barely scratched");
        assertFalse(SurrenderRule.surrenders(4, 2, 2), "half gone, but an even fight");
        assertTrue(SurrenderRule.surrenders(4, 2, 4));
        assertFalse(SurrenderRule.surrenders(4, 0, 4), "nobody left to surrender");
    }

    @Test
    void campsRaidWhenHungryOrMuchStronger() {
        assertTrue(RaidPlanner.shouldRaid(6, 0.5f, 6), "starving");
        assertTrue(RaidPlanner.shouldRaid(6, 10, 4), "half as strong again");
        assertFalse(RaidPlanner.shouldRaid(6, 10, 5));
        assertFalse(RaidPlanner.shouldRaid(1, 0, 1), "one goblin raids no one");
    }

    @Test
    void goblinsOfOneCampAreAlliesAndOfTwoAreRivals() {
        GoblinCamp a = camp(0, 3);
        GoblinCamp b = camp(6, 3);
        Goblin a1 = a.members().get(0);
        Goblin a2 = a.members().get(2);
        Goblin b1 = b.members().get(0);
        assertTrue(a1.isAllyOf(a2));
        assertFalse(a1.isAllyOf(b1));
    }

    @Test
    void rivalCampsFightAndTheBeatenSideJoinsTheWinners() {
        GoblinCamp strong = camp(0, 4);
        GoblinCamp weak = camp(8, 4);
        GoblinPack strongPack = strong.packs().get(0);
        GoblinPack weakPack = weak.packs().get(0);

        strongPack.tick(1.1f, nearby);
        assertSame(weakPack, strongPack.enemy(), "they saw each other");
        assertSame(strongPack, weakPack.enemy());

        // The weak side loses two of its four.
        weakPack.members().get(3).setAlive(false);
        weakPack.members().get(2).setAlive(false);
        weakPack.tick(1.1f, nearby);

        assertEquals(0, weakPack.size(), "it gave up");
        assertEquals(6, strong.population(), "its two survivors joined the winners' camp");
        assertNull(strongPack.enemy(), "the fight is over");
        for (Goblin g : strong.members()) {
            assertSame(strong, g.pack().camp());
        }
    }

    @Test
    void aRaidIsSentWithTheBiggestPackThatIsNotHunting() {
        GoblinCamp camp = camp(0, 6);
        assertTrue(camp.sendRaid(new BlockPos(50, 64, 0)));
        assertTrue(camp.raiding());
        GoblinPack raiders = camp.packs().stream().filter(p -> p.raidTarget() != null).findFirst().orElseThrow();
        assertEquals(4, raiders.size());
        raiders.endRaid();
        assertFalse(camp.raiding());
    }

    @Test
    void aSecondPackJoiningInDoesNotResetTheFirstFight() {
        GoblinCamp raiders = camp(0, 4);
        GoblinCamp defenders = camp(8, 8); // two packs of four
        GoblinPack r = raiders.packs().get(0);
        GoblinPack d1 = defenders.packs().get(0);
        GoblinPack d2 = defenders.packs().get(1);

        r.engage(d1);
        r.members().get(3).setAlive(false);
        r.members().get(2).setAlive(false);
        d2.engage(r); // the other defenders pile in

        assertSame(d1, r.enemy(), "the raiders are still in their first fight");
        assertSame(r, d2.enemy());
        r.tick(1.1f, nearby);
        assertEquals(0, r.size(), "half of the four it went in with are down: it gives up");
    }
}
