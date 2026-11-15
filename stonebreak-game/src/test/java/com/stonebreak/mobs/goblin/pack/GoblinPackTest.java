package com.stonebreak.mobs.goblin.pack;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.mobs.goblin.food.FoodLedger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How goblins band up and hunt: a spawn band is one pack, a loner finds one, a hungry pack picks
 * the nearest animal, and a pack with a meal for everyone stops hunting and rests.
 */
class GoblinPackTest {

    private static final float DT = 0.05f;

    /** Everything in the "world", found by distance like the real entity manager. */
    private final List<Entity> world = new ArrayList<>();
    private final NearbyEntities nearby = (center, radius) -> world.stream()
            .filter(e -> e.isAlive() && e.getPosition().distance(center) <= radius).toList();

    private Goblin goblin(float x, float z) {
        Goblin g = new Goblin(null, new Vector3f(x, 64, z),
                new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        world.add(g);
        return g;
    }

    private StubMob animal(EntityType type, float x, float z) {
        StubMob mob = new StubMob(type, new Vector3f(x, 64, z));
        world.add(mob);
        return mob;
    }

    private static GoblinPack band(Goblin... goblins) {
        GoblinPack.formBand(List.of(goblins));
        return goblins[0].pack();
    }

    private static void run(GoblinPack pack, NearbyEntities nearby, float seconds) {
        for (float t = 0; t < seconds; t += DT) {
            pack.tick(DT, nearby);
        }
    }

    @Test
    void aSpawnBandIsOnePackLedByItsFirstGoblin() {
        Goblin a = goblin(0, 0), b = goblin(1, 0), c = goblin(2, 0);
        GoblinPack pack = band(a, b, c);
        assertEquals(3, pack.size());
        assertSame(pack, b.pack());
        assertSame(pack, c.pack());
        assertTrue(pack.isLeader(a));
    }

    @Test
    void whenTheLeaderFallsTheNextGoblinLeads() {
        Goblin a = goblin(0, 0), b = goblin(1, 0);
        GoblinPack pack = band(a, b);
        a.setAlive(false);
        run(pack, nearby, DT);
        assertEquals(1, pack.size());
        assertTrue(pack.isLeader(b));
    }

    @Test
    void aLoneGoblinJoinsTheNearestPackWithinReach() {
        Goblin a = goblin(0, 0), b = goblin(1, 0);
        GoblinPack pack = band(a, b);
        Goblin loner = goblin(GoblinPack.JOIN_RADIUS - 2, 0);
        Goblin farAway = goblin(GoblinPack.JOIN_RADIUS * 4, 0);

        run(loner.pack(), nearby, DT);
        run(farAway.pack(), nearby, DT);

        assertSame(pack, loner.pack());
        assertEquals(3, pack.size());
        assertEquals(1, farAway.pack().size(), "nobody near enough to join");
    }

    @Test
    void twoBandsDoNotMerge() {
        GoblinPack first = band(goblin(0, 0), goblin(1, 0));
        GoblinPack second = band(goblin(3, 0), goblin(4, 0));
        run(first, nearby, 3);
        run(second, nearby, 3);
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertNotSame(first, second);
    }

    @Test
    void aHungryPackHuntsTheNearestAnimal() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        animal(EntityType.COW, 20, 0);
        StubMob sheep = animal(EntityType.SHEEP, 8, 0);
        goblin(5, 5); // another goblin is not prey
        animal(EntityType.SHEEP, GoblinPack.PREY_RADIUS + 5, 0); // too far to notice

        run(pack, nearby, DT);

        assertEquals(GoblinPack.Task.HUNT, pack.task());
        assertSame(sheep, pack.quarry());
    }

    @Test
    void killedQuarryIsReplacedByTheNextNearest() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        StubMob sheep = animal(EntityType.SHEEP, 5, 0);
        StubMob cow = animal(EntityType.COW, 10, 0);
        run(pack, nearby, DT);
        sheep.setAlive(false);
        assertNull(pack.quarry(), "a dead quarry is not hunted");
        run(pack, nearby, GoblinPack.PREY_SCAN_SECONDS + DT);
        assertSame(cow, pack.quarry());
    }

    @Test
    void aPackWithAMealForEveryGoblinStopsHuntingAndRests() {
        Goblin a = goblin(0, 0), b = goblin(1, 0), c = goblin(2, 0);
        GoblinPack pack = band(a, b, c);
        animal(EntityType.COW, 6, 0);
        run(pack, nearby, DT);
        assertEquals(GoblinPack.Task.HUNT, pack.task());

        // The cow is down and its beef is in b's pockets: three meals for three goblins.
        b.inventory().add(new ItemStack(ItemType.RAW_BEEF, 1));
        run(pack, nearby, DT);

        assertEquals(GoblinPack.Task.ROAM, pack.task());
        assertNull(pack.quarry());
        assertEquals(FoodLedger.MIN_COOLDOWN_SECONDS, pack.ledger().cooldown(), 1.0f);
    }

    @Test
    void aHuntThatFindsNothingIsCalledOff() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        run(pack, nearby, DT);
        assertEquals(GoblinPack.Task.HUNT, pack.task());
        run(pack, nearby, GoblinPack.FRUITLESS_HUNT_SECONDS + 1);
        assertEquals(GoblinPack.Task.ROAM, pack.task());
    }

    @Test
    void quarryThatOutrunsTheLeashIsGivenUp() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        StubMob sheep = animal(EntityType.SHEEP, 10, 0);
        run(pack, nearby, DT);
        assertSame(sheep, pack.quarry());
        sheep.setPosition(new Vector3f(GoblinPack.PREY_LEASH + 5, 64, 0));
        run(pack, nearby, DT);
        assertNull(pack.quarry());
    }

    @Test
    void quarryThePackCannotGetCloserToIsLetGoForAnother() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        StubMob stuck = animal(EntityType.SHEEP, 6, 0);   // on a ledge the goblins never reach
        StubMob other = animal(EntityType.COW, 20, 0);
        run(pack, nearby, DT);
        assertSame(stuck, pack.quarry());
        run(pack, nearby, GoblinPack.QUARRY_STALL_SECONDS + GoblinPack.PREY_SCAN_SECONDS + 0.5f);
        assertSame(other, pack.quarry(), "it gave up on the sheep and went for the cow");
    }

    @Test
    void aHuntThatCatchesNothingEndsEvenWhileChasing() {
        GoblinPack pack = band(goblin(0, 0), goblin(1, 0));
        StubMob sheep = animal(EntityType.SHEEP, 6, 0);
        run(pack, nearby, DT);
        // The goblins keep closing in, but never land a blow.
        for (float t = 0; t < GoblinPack.HUNT_DEADLINE_SECONDS + 1 && pack.task() == GoblinPack.Task.HUNT; t += 1f) {
            sheep.setPosition(new Vector3f(6 - (t % 5), 64, 0));
            run(pack, nearby, 1f);
        }
        assertEquals(GoblinPack.Task.ROAM, pack.task());
    }
}
