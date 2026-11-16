package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EntityManager} on the engine's native entity model (OpenMason #114): the lifecycle the game
 * relied on (deferred add/remove, listener order, removal while iterating), the spatial queries
 * against the Java scans they replaced, and clip clocks moving in and out of the native world.
 */
class EntityManagerNativeTest {

    private World world;
    private EntityManager em;
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        world = mock(World.class); // getBlockAt -> null: open air
        em = new EntityManager(world);
        when(world.getEntityManager()).thenReturn(em);
        em.addListener(new EntityManager.Listener() {
            @Override public void onEntityAdded(Entity e) { events.add("+" + e.getType()); }
            @Override public void onEntityRemoved(Entity e) { events.add("-" + e.getType()); }
        });
    }

    @AfterEach
    void tearDown() {
        em.cleanup();
    }

    private StubMob mob(EntityType type, float x, float z) {
        return new StubMob(type, new Vector3f(x, 64f, z), world);
    }

    @Test
    void additionsWaitForTheNextUpdateAndKeepTheirOrder() {
        StubMob cow = mob(EntityType.COW, 0, 0);
        StubMob goose = mob(EntityType.GOOSE, 1, 0);
        em.addEntity(cow);
        em.addEntity(goose);
        em.addEntity(cow); // twice: listed once
        assertTrue(em.getAllEntities().isEmpty(), "queued entities are not in the world yet");
        assertEquals(List.of(cow, goose), em.getAllEntitiesIncludingPending());
        assertNotEquals(0L, cow.getEntityHandle());

        em.update(0.05f);
        assertEquals(List.of(cow, goose), em.getAllEntities());
        assertEquals(List.of("+COW", "+GOOSE"), events);
        assertEquals(2, em.getEntityCount());
    }

    @Test
    void removalIsDeferredSoTheListCanBeWalked() {
        StubMob a = mob(EntityType.COW, 0, 0);
        StubMob b = mob(EntityType.SHEEP, 2, 0);
        StubMob c = mob(EntityType.CHICKEN, 4, 0);
        em.addEntity(a);
        em.addEntity(b);
        em.addEntity(c);
        em.update(0.05f);
        events.clear();

        for (Entity e : em.getAllEntities()) {
            if (e == b) em.removeEntity(e);
        }
        assertEquals(3, em.getAllEntities().size(), "still in the world until the update ends");
        em.update(0.05f);
        assertEquals(List.of(a, c), em.getAllEntities(), "order of the rest kept");
        assertEquals(List.of("-SHEEP"), events);
        assertEquals(0L, b.getEntityHandle(), "a removed entity holds no handle");

        // Dead entities leave in the same update.
        c.setAlive(false);
        em.update(0.05f);
        assertEquals(List.of(a), em.getAllEntities());
        assertEquals(List.of("-SHEEP", "-CHICKEN"), events);

        // A removed entity can come back.
        em.addEntity(b);
        em.update(0.05f);
        assertEquals(List.of(a, b), em.getAllEntities());
    }

    @Test
    void removingAQueuedEntityCancelsIt() {
        StubMob cow = mob(EntityType.COW, 0, 0);
        em.addEntity(cow);
        em.removeEntity(cow);
        em.update(0.05f);
        assertTrue(em.getAllEntities().isEmpty());
        assertTrue(events.isEmpty(), "never added, so never removed either");
        assertEquals(0L, cow.getEntityHandle());
    }

    @Test
    void queriesMatchTheJavaScansTheyReplaced() {
        Random random = new Random(7);
        EntityType[] types = {EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN, EntityType.GOOSE};
        for (int i = 0; i < 120; i++) {
            StubMob m = mob(types[i % types.length], random.nextFloat() * 80 - 40, random.nextFloat() * 80 - 40);
            if (i % 9 == 0) m.setAlive(false); // dead this tick: still listed, never answered
            em.addEntity(m);
        }
        em.update(0.05f); // physics moves them; queries read positions as they are now
        for (Entity e : em.getAllEntities()) {
            if (random.nextInt(9) == 0) e.setAlive(false);
            e.position.y += random.nextFloat(); // moved since the last update: queries must see it
        }

        List<Entity> all = em.getAllEntities();
        for (int trial = 0; trial < 50; trial++) {
            Vector3f c = new Vector3f(random.nextFloat() * 80 - 40, 60 + random.nextFloat() * 8, random.nextFloat() * 80 - 40);
            float r = random.nextFloat() * 25;
            List<Entity> expected = new ArrayList<>();
            for (Entity e : all) {
                if (e.isAlive() && e.getPosition().distanceSquared(c) <= r * r) expected.add(e);
            }
            assertEquals(expected, em.getEntitiesInRange(c, r), "range " + trial);

            EntityType type = types[trial % types.length];
            Entity nearest = null;
            float best = Float.MAX_VALUE;
            for (Entity e : all) {
                if (e.isAlive() && e.getType() == type) {
                    float d = e.getPosition().distance(c);
                    if (d < best) {
                        best = d;
                        nearest = e;
                    }
                }
            }
            assertSame(nearest, em.getNearestEntity(c, type), "nearest " + trial);
        }

        EntityType type = EntityType.SHEEP;
        assertEquals(all.stream().filter(e -> e.isAlive() && e.getType() == type).toList(), em.getEntitiesByType(type));
        assertEquals(all.stream().filter(Entity::isAlive).toList(), em.getLivingEntities());
    }

    @Test
    void chunkUnloadRemovesOnlyThatColumnsLocalEntities() {
        StubMob inside = mob(EntityType.COW, 3.5f, 15.99f);
        StubMob edge = mob(EntityType.COW, 16f, 4f);   // x == 16 belongs to the next column
        StubMob shadow = mob(EntityType.COW, 5f, 5f);
        shadow.setNetworkShadow(true);
        StubMob player = new StubMob(EntityType.REMOTE_PLAYER, new Vector3f(6f, 64f, 6f), world);
        for (Entity e : List.of(inside, edge, shadow, player)) em.addEntity(e);
        em.update(0.05f);
        events.clear();

        em.removeEntitiesInChunk(0, 0);
        em.update(0.05f);
        assertEquals(List.of(edge, shadow, player), em.getAllEntities());
        assertEquals(List.of("-COW"), events);
    }

    @Test
    void clipClocksMoveIntoTheNativeWorldAndBackUnchanged() {
        StubMob attached = mob(EntityType.COW, 0, 0);
        StubMob detached = mob(EntityType.COW, 0, 0);
        EntityClipClock in = attached.getClipClock();
        EntityClipClock out = detached.getClipClock();
        for (EntityClipClock clock : List.of(in, out)) {
            clock.setClip(1, false);
            clock.advance(0.3f, 0.3f);
        }
        em.addEntity(attached);
        em.update(0.05f); // attaches, then the cow's own update advances the continuous clock
        assertTrue(in.isAttached());
        out.advance(0.05f, 0.0f);

        // Same calls on both: one in the native world, one in plain fields.
        float[] dts = {0.016f, 0.05f, 0.033f, 0.1f, 0.007f};
        for (int i = 0; i < 40; i++) {
            float dt = dts[i % dts.length];
            if (i == 11) {
                in.setClip(4, true);
                out.setClip(4, true);
            }
            if (i == 23) {
                in.setClip(4, false);
                out.setClip(4, false);
            }
            in.advance(dt, i % 3 == 0 ? 0.0f : dt);
            out.advance(dt, i % 3 == 0 ? 0.0f : dt);
            assertEquals(out.clipTime(), in.clipTime(), 0.0f, "step " + i);
        }
        assertEquals(out.snapshot(), in.snapshot());

        // Removal is deferred: the cow still runs its update in the tick it leaves.
        em.removeEntity(attached);
        em.update(0.05f);
        out.advance(0.05f, 0.0f);
        assertFalse(in.isAttached());
        assertEquals(out.snapshot(), in.snapshot(), "detaching copies the clock back out");
    }

    @Test
    void cleanupDropsQueuedAndDeadEntitiesQuietly() {
        StubMob alive = mob(EntityType.COW, 0, 0);
        StubMob dead = mob(EntityType.COW, 1, 0);
        em.addEntity(alive);
        em.addEntity(dead);
        em.update(0.05f);
        dead.setAlive(false);
        StubMob queued = mob(EntityType.GOOSE, 2, 0);
        em.addEntity(queued);
        events.clear();

        em.cleanup();
        assertEquals(List.of(alive), em.getAllEntitiesIncludingPending());
        assertTrue(events.isEmpty());
        assertNull(em.getNearestEntity(new Vector3f(), EntityType.GOOSE));
    }
}
