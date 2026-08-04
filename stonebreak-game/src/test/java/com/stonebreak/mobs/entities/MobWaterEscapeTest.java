package com.stonebreak.mobs.entities;

import com.stonebreak.audio.MobSounds;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every mob, in ponds of every depth, ticked through the real physics.
 *
 * <p>Run across all four mobs on purpose. They differ in exactly the properties water cares about —
 * a cow is 1.02 tall on 0.62 legs with a 1.3-long body, a chicken is 0.7 tall on no legs at all —
 * and the bugs found here have all been shape-dependent: probes measured from the wrong part of the
 * body, clearances that fit one mob and not another, climbs sized against the wrong jump. A single
 * mob passing proves very little, which is why these loop rather than pick a representative.
 */
class MobWaterEscapeTest {

    /** Every mob that walks. */
    private static final EntityType[] MOBS = {
            EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN, EntityType.GOOSE
    };

    /** Topmost water block; the shore's standable surface is one above it. */
    private static final int WATER_TOP = 13;
    private static final float LAND_SURFACE = WATER_TOP + 1.0f;
    private static final float WATER_SURFACE = WATER_TOP + 0.875f; // source-block height

    private static final int POND_MIN = 4;
    private static final int POND_MAX = 11;

    private static final float TICK = 0.05f; // the server's 20 Hz step
    private static final int SETTLE_TICKS = 60;
    private static final int ESCAPE_TICKS = 600;

    // ── The thing players actually see ───────────────────────────────────────

    /** A shore level with the water: the mob steps out over a lip of a fraction of a block. */
    @Test
    void everyMobCanGetOutOfWaterAtEveryDepth() {
        List<String> failures = new ArrayList<>();
        for (EntityType type : MOBS) {
            for (int depth = 1; depth <= 3; depth++) {
                String failure = tryEscape(type, depth, 0);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }

        assertTrue(failures.isEmpty(), "mobs stuck in water:\n  " + String.join("\n  ", failures));
    }

    /**
     * A shore a block above the water — the common shape where a lake has eaten into the terrain
     * rather than lapping up to it. Every mob has to be able to haul itself over that lip, because
     * from the mob's point of view it is the same one-block ledge it hops on land all day.
     */
    @Test
    void everyMobCanClimbAShoreOneBlockAboveTheWater() {
        List<String> failures = new ArrayList<>();
        for (EntityType type : MOBS) {
            for (int depth = 1; depth <= 3; depth++) {
                String failure = tryEscape(type, depth, 1);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }

        assertTrue(failures.isEmpty(), "mobs stuck below a raised shore:\n  "
                + String.join("\n  ", failures));
    }

    @Test
    void everyMobFloatsToTheSurfaceRatherThanWalkingTheBottom() {
        List<String> failures = new ArrayList<>();
        for (EntityType type : MOBS) {
            for (int depth = 2; depth <= 3; depth++) {
                TestWorld world = pond(depth, 0);
                StubMob mob = dropIn(world, type, depth);
                tick(world, mob, 200);

                float feet = feetY(mob);
                if (feet < WATER_SURFACE - standingHeight(mob)) {
                    failures.add(type + " at depth " + depth + " sank: feet " + feet
                            + ", surface " + WATER_SURFACE);
                }
            }
        }

        assertTrue(failures.isEmpty(), "mobs sank:\n  " + String.join("\n  ", failures));
    }

    @Test
    void everyMobSettlesAtTheSurfaceInsteadOfBobbing() {
        List<String> failures = new ArrayList<>();
        for (EntityType type : MOBS) {
            TestWorld world = pond(3, 0);
            StubMob mob = dropIn(world, type, 3);
            tick(world, mob, 200);
            float settled = feetY(mob);
            tick(world, mob, 100);

            if (Math.abs(feetY(mob) - settled) > 0.5f) {
                failures.add(type + " oscillates: " + settled + " then " + feetY(mob));
            }
            if (feetY(mob) > WATER_SURFACE + 0.2f) {
                failures.add(type + " levitates out of the water: " + feetY(mob));
            }
        }

        assertTrue(failures.isEmpty(), "unstable floating:\n  " + String.join("\n  ", failures));
    }

    /** Ground contact flickers as a mob walks; every mob must still be heard over it. */
    @Test
    void everyMobMakesFootstepsWhileWalking() {
        List<String> failures = new ArrayList<>();
        for (EntityType type : MOBS) {
            TestWorld world = pond(2, 0);
            StubMob mob = standingOnLand(world, type);
            Steering steering = new Steering(mob, 360.0f, 0.0f, 0.0f);
            MobSounds sounds = MobSounds.forEntity(world, mob);

            int steps = 0;
            for (int i = 0; i < 300; i++) {
                steering.tick(TICK);
                steering.steerAlong(new Vector3f(0, 0, 1), 1.0f, TICK, false);
                collision(world).applyLivingEntityPhysics(mob, TICK);
                if (sounds.updateSounds(mob)) {
                    steps++;
                }
            }

            if (steps == 0) {
                failures.add(type + " walked to z=" + mob.getPosition().z + " in silence");
            }
        }

        assertTrue(failures.isEmpty(), "silent mobs:\n  " + String.join("\n  ", failures));
    }

    @Test
    void aMobOnDryLandIsUnaffectedByTheWaterNearby() {
        TestWorld world = pond(3, 0);
        StubMob cow = standingOnLand(world, EntityType.COW);
        tick(world, cow, 60);

        assertTrue(cow.getSubmersion() == 0.0f, "no water here");
        assertTrue(Math.abs(feetY(cow) - LAND_SURFACE) < 0.2f,
                "it should just stand there, got " + feetY(cow));
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     * Attempts an escape and returns a description of the failure, or null on success.
     *
     * <p>Steering points at the bank the whole time, standing in for the route an agent would
     * follow: what is under test is whether the mob's body and physics can execute that route.
     */
    private String tryEscape(EntityType type, int depth, int bankRise) {
        TestWorld world = pond(depth, bankRise);
        StubMob mob = dropIn(world, type, depth);
        tick(world, mob, SETTLE_TICKS); // surface first, as it would after falling in

        Steering steering = new Steering(mob, 360.0f, 0.0f, 0.0f);
        Vector3f towardBank = new Vector3f(-1, 0, 0); // land lies at x < POND_MIN
        EntityCollision collision = collision(world);

        float highestReached = feetY(mob);
        for (int i = 0; i < ESCAPE_TICKS; i++) {
            steering.tick(TICK);
            steering.steerAlong(towardBank, 1.0f, TICK, true);
            collision.applyLivingEntityPhysics(mob, TICK);
            highestReached = Math.max(highestReached, feetY(mob));
            if (!mob.isInWater() && mob.isOnGround() && feetY(mob) >= landSurface(bankRise) - 0.1f) {
                return null;
            }
        }

        return type + " at depth " + depth + ", bank +" + bankRise + ": stuck at x="
                + mob.getPosition().x + " feet=" + feetY(mob) + " (peaked at " + highestReached
                + ", needs " + landSurface(bankRise) + "), submersion " + mob.getSubmersion();
    }

    /**
     * A world with a pond of the given depth: water always tops out at {@link #WATER_TOP}, and the
     * floor beneath it drops with depth, so the shore is identical in every case and only the depth
     * varies.
     */
    private TestWorld pond(int depth, int bankRise) {
        TestWorld world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        Chunk chunk = new Chunk(0, 0);
        int pondFloorTop = WATER_TOP - depth;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean inPond = x >= POND_MIN && x <= POND_MAX && z >= POND_MIN && z <= POND_MAX;
                int columnTop = inPond ? WATER_TOP : WATER_TOP + bankRise;
                for (int y = 0; y <= columnTop; y++) {
                    BlockType block;
                    if (!inPond) {
                        block = BlockType.STONE;              // land, standable one above its top
                    } else if (y <= pondFloorTop) {
                        block = BlockType.STONE;              // pond floor
                    } else {
                        block = BlockType.WATER;
                    }
                    chunk.setBlock(x, y, z, block);
                }
            }
        }
        world.setChunk(0, 0, chunk);
        return world;
    }

    /** Places a mob standing on the pond floor at its centre, as if it had just fallen in. */
    private StubMob dropIn(TestWorld world, EntityType type, int depth) {
        return mobAtFeet(world, type, 8.5f, WATER_TOP - depth + 1, 8.5f);
    }

    private StubMob standingOnLand(TestWorld world, EntityType type) {
        return mobAtFeet(world, type, 1.5f, LAND_SURFACE, 1.5f);
    }

    /** The standable surface of the shore for a given bank rise. */
    private static float landSurface(int bankRise) {
        return LAND_SURFACE + bankRise;
    }

    private StubMob mobAtFeet(TestWorld world, EntityType type, float x, float feetY, float z) {
        StubMob mob = new StubMob(type, new Vector3f(x, 0, z), world);
        mob.setPosition(new Vector3f(x, feetY + mob.getLegHeight(), z));
        return mob;
    }

    private EntityCollision collision(TestWorld world) {
        return new EntityCollision(world);
    }

    private void tick(TestWorld world, StubMob mob, int ticks) {
        EntityCollision collision = collision(world);
        for (int i = 0; i < ticks; i++) {
            collision.applyLivingEntityPhysics(mob, TICK);
        }
    }

    private static float feetY(StubMob mob) {
        return mob.getPosition().y - mob.getLegHeight();
    }

    private static float standingHeight(StubMob mob) {
        return mob.getLegHeight() + mob.getHeight();
    }
}
