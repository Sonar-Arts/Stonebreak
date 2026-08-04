package com.stonebreak.mobs.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What water does to a body vertically.
 *
 * <p>The property that matters, and the one the old physics got wrong, is that <b>a submerged mob
 * ends up moving upward</b>. Reducing gravity is not buoyancy — it still points down, so anything
 * that fell in a lake sank to the bottom and stayed there with no way back to the surface, which is
 * where its route out begins.
 */
class EntityWaterPhysicsTest {

    private static final float TICK = 0.05f; // the server's 20 Hz step
    private static final float EPS = 1e-3f;

    @Test
    void aSubmergedBodyRisesInsteadOfSinking() {
        float velocityY = 0.0f;
        for (int tick = 0; tick < 40; tick++) {
            velocityY = EntityWaterPhysics.verticalVelocityAfter(velocityY, 1.0f, TICK);
        }

        assertTrue(velocityY > 0.0f, "fully submerged, the mob must end up rising, got " + velocityY);
    }

    @Test
    void aSinkingBodyIsArrestedAndTurnedAround() {
        // Falls in at speed, as it would after walking off a bank.
        float velocityY = -12.0f;
        boolean turnedAround = false;
        for (int tick = 0; tick < 60 && !turnedAround; tick++) {
            velocityY = EntityWaterPhysics.verticalVelocityAfter(velocityY, 1.0f, TICK);
            turnedAround = velocityY > 0.0f;
        }

        assertTrue(turnedAround, "a mob that falls in should stop sinking within a few seconds");
    }

    @Test
    void aFloatingBodySettlesRatherThanLaunchingOut() {
        // At the natural floating depth there is no lift left, so it cannot climb out on buoyancy.
        float velocityY = 0.0f;
        for (int tick = 0; tick < 40; tick++) {
            velocityY = EntityWaterPhysics.verticalVelocityAfter(velocityY, 0.5f, TICK);
        }

        assertTrue(velocityY <= 0.0f, "half-submerged should not keep pushing upward, got " + velocityY);
    }

    @Test
    void wadingKeepsMostOfItsWeight() {
        // Ankle-deep: the mob should still fall essentially normally, or it would bob on puddles.
        float shallow = EntityWaterPhysics.verticalVelocityAfter(0.0f, 0.1f, TICK);
        float dry = Entity.GRAVITY * TICK;

        assertTrue(shallow < 0.0f, "a wading mob still falls");
        assertTrue(shallow < dry * 0.7f,
                "and keeps most of its weight: " + shallow + " vs dry " + dry);
    }

    @Test
    void dragDoesNotDependOnTickRate() {
        // One 0.2s step against four 0.05s steps: exponential damping must agree.
        float coarse = EntityWaterPhysics.verticalVelocityAfter(-5.0f, 1.0f, 0.2f);

        float fine = -5.0f;
        for (int i = 0; i < 4; i++) {
            fine = EntityWaterPhysics.verticalVelocityAfter(fine, 1.0f, 0.05f);
        }

        assertEquals(coarse, fine, 0.35f,
                "a mob must not swim differently at 20 Hz than at 60: " + coarse + " vs " + fine);
    }

    @Test
    void buoyancyIsCappedSoMobsDoNotRocketOut() {
        float velocityY = 0.0f;
        for (int tick = 0; tick < 200; tick++) {
            velocityY = EntityWaterPhysics.verticalVelocityAfter(velocityY, 1.0f, TICK);
        }

        assertTrue(velocityY < 2.0f, "rise should be a drift, not a launch, got " + velocityY);
    }

    @Test
    void waterSlowsHorizontalMovementInProportionToDepth() {
        assertEquals(1.0f, EntityWaterPhysics.speedFactor(0.0f), EPS, "dry mobs are unaffected");
        assertTrue(EntityWaterPhysics.speedFactor(1.0f) < EntityWaterPhysics.speedFactor(0.5f),
                "deeper is slower");
        assertTrue(EntityWaterPhysics.speedFactor(1.0f) > 0.0f,
                "but a swimming mob still makes headway, or it could never reach a bank");
    }

    @Test
    void speedFactorToleratesOutOfRangeSubmersion() {
        assertEquals(1.0f, EntityWaterPhysics.speedFactor(-0.5f), EPS);
        assertEquals(EntityWaterPhysics.speedFactor(1.0f), EntityWaterPhysics.speedFactor(2.0f), EPS);
    }

    @Test
    void submersionOfAWorldlessMobIsZero() {
        // Guards every caller that runs before a mob has a world (spawn, tests, headless tools).
        assertEquals(0.0f, EntityWaterPhysics.submersion(null, new StubMob(EntityType.COW)), EPS);
    }

    /**
     * A stroke has to clearly beat the passive drift, or it would achieve nothing the mob was not
     * already doing — and it is what has to lift a mob over a bank against near-full gravity.
     */
    @Test
    void aSwimStrokeIsFarStrongerThanDrifting() {
        float drift = 0.0f;
        for (int tick = 0; tick < 100; tick++) {
            drift = EntityWaterPhysics.verticalVelocityAfter(drift, 1.0f, TICK);
        }

        StubMob mob = new StubMob(EntityType.COW);
        mob.setSubmersion(1.0f);
        mob.swimUp();
        float stroke = mob.getVelocity().y;

        assertTrue(stroke > drift * 4.0f,
                "a deliberate stroke should dwarf passive float: " + stroke + " vs " + drift);
        assertTrue(stroke >= mob.getJumpApexHeight(),
                "and be of jump strength, so a bank it could hop on land is one it can leave water over");
    }

    @Test
    void aStrokeDoesNothingOutOfWater() {
        StubMob mob = new StubMob(EntityType.COW);
        mob.swimUp();

        assertEquals(0.0f, mob.getVelocity().y, EPS, "there is nothing to push against");
    }
}
