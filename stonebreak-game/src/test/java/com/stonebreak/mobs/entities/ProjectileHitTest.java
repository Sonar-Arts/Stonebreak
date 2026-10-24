package com.stonebreak.mobs.entities;

import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Arrows and fire bolts hit a creature anywhere on its body, at any draw strength, on the
 * server's 20 Hz tick.
 *
 * <p>Both used to test a radius around the entity's position — its feet — so a goblin's chest
 * and every chicken's head were unhittable, and a full-draw arrow (2.25 blocks per tick)
 * skipped clean past most mobs between ticks.
 */
class ProjectileHitTest {

    private static final float TICK = 0.05f; // the server's 20 Hz step
    private static final float[] BODY_HEIGHTS = {0.1f, 0.5f, 0.9f}; // fraction up the body box
    private static final float[] ARROW_SPEEDS = {15f, 30f, 45f};    // min, half, full draw

    private record Range(World world, StubMob mob) {}

    /** Hitboxes are measured from the models. */
    @BeforeAll
    static void loadModels() {
        SbeEntityRegistry.scanAndLoad();
    }

    /** A mob standing in open air at the origin, the only living entity in its world. */
    private static Range range(EntityType type) {
        World world = mock(World.class); // getBlockAt → null: nothing solid
        StubMob mob = new StubMob(type, new Vector3f(0f, 64f + type.getLegHeight(), 0f), world);
        EntityManager em = mock(EntityManager.class);
        when(em.getLivingEntities()).thenReturn(List.of(mob));
        when(world.getEntityManager()).thenReturn(em);
        return new Range(world, mob);
    }

    private static float bodyHeight(StubMob mob, float fraction) {
        Entity.BoundingBox box = mob.getHitBox();
        return box.minY + (box.maxY - box.minY) * fraction;
    }

    /** Flies a level arrow along +X from {@code startX}, standing in for the external physics step. */
    private static boolean arrowHits(Range r, float startX, float y, float speed) {
        Arrow arrow = new Arrow(r.world(), new Vector3f(startX, y, 0f), new Vector3f(speed, 0f, 0f));
        float before = r.mob().getHealth();
        for (int i = 0; i < 100 && arrow.isAlive() && arrow.position.x < 6f; i++) {
            arrow.update(TICK);
            arrow.velocity.y = 0f; // level flight: aim height is what is under test, not drop
            arrow.position.add(new Vector3f(arrow.velocity).mul(TICK));
        }
        return r.mob().getHealth() < before;
    }

    private static boolean boltHits(Range r, float startX, float y) {
        FireBolt bolt = new FireBolt(r.world(), new Vector3f(startX, y, 0f), new Vector3f(1f, 0f, 0f));
        float before = r.mob().getHealth();
        for (int i = 0; i < 40 && !bolt.isImpacted(); i++) {
            bolt.update(TICK);
        }
        return r.mob().getHealth() < before;
    }

    @Test
    void arrowsHitEveryPartOfEveryCreatureAtEveryDraw() {
        List<String> misses = new ArrayList<>();
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            for (float fraction : BODY_HEIGHTS) {
                for (float speed : ARROW_SPEEDS) {
                    // Sweep the start over one tick of travel so every phase of the 20 Hz step is tried.
                    for (float offset = 0f; offset < speed * TICK; offset += 0.1f) {
                        Range r = range(type);
                        if (!arrowHits(r, -6f + offset, bodyHeight(r.mob(), fraction), speed)) {
                            misses.add(type + " at " + fraction + " of its height, speed " + speed
                                    + ", start offset " + offset);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), misses);
    }

    @Test
    void fireBoltsHitEveryPartOfEveryCreature() {
        List<String> misses = new ArrayList<>();
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            for (float fraction : BODY_HEIGHTS) {
                Range r = range(type);
                if (!boltHits(r, -4f, bodyHeight(r.mob(), fraction))) {
                    misses.add(type + " at " + fraction + " of its height");
                }
            }
        }
        assertEquals(List.of(), misses);
    }

    /**
     * Through the real tick: physics, then the entity-entity push. The push used to shove an
     * arrow that ended its tick inside a goblin back out the way it came, so no sweep ever
     * crossed the body — slow or close-range shots never landed.
     */
    @Test
    void anArrowEndingItsTickInsideAGoblinIsNotPushedOut() {
        World world = mock(World.class); // getBlockAt → null: nothing solid
        EntityManager em = new EntityManager(world);
        when(world.getEntityManager()).thenReturn(em);
        StubMob goblin = new StubMob(EntityType.GOBLIN, new Vector3f(0f, 64f, 0f), world);
        em.addEntity(goblin);
        em.update(TICK);
        float before = goblin.getHealth();

        // 0.75 blocks per tick from 1 block out: the first step ends inside the 0.83-wide body.
        em.spawnArrow(new Vector3f(-1f, bodyHeight(goblin, 0.5f), 0f), new Vector3f(15f, 0f, 0f));
        for (int i = 0; i < 5; i++) {
            em.update(TICK);
        }
        em.cleanup();

        assertTrue(goblin.getHealth() < before, "arrow was shoved out of the goblin instead of hitting it");
    }

    @Test
    void projectilesPassingOverACreatureMissIt() {
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            Range r = range(type);
            float overhead = r.mob().getHitBox().maxY + 0.5f;
            assertTrue(!arrowHits(r, -6f, overhead, 45f), type + " hit by an arrow passing overhead");
            assertTrue(!boltHits(r, -4f, overhead), type + " hit by a bolt passing overhead");
        }
    }
}
