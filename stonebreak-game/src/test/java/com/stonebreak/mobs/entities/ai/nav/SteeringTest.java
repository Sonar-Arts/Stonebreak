package com.stonebreak.mobs.entities.ai.nav;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steering and facing are shared by every mob, so the rules that used to be copied per mob are
 * pinned here: speed scales by the behaviour multiplier and by status effects, hops can be
 * suppressed, and facing always goes through the entity type's model yaw offset.
 *
 * <p>These paths never touch the world — an airborne entity skips the block probe — so steering can
 * be exercised with no world at all.
 */
class SteeringTest {

    private static final float EPS = 1e-4f;

    private static Steering steeringFor(StubMob mob, float rotationSpeed) {
        return new Steering(mob, rotationSpeed, 0.0f, 0.0f);
    }

    @Test
    void steersAlongTheGivenHeadingAtTheRequestedFractionOfMoveSpeed() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);

        // Airborne, so the obstacle probe is skipped and no world lookup happens.
        mob.setOnGround(false);
        steering.steerAlong(new Vector3f(1, 0, 0), 0.4f, 1.0f, false);

        assertEquals(mob.getMoveSpeed() * 0.4f, mob.getVelocity().x, EPS);
        assertEquals(0.0f, mob.getVelocity().z, EPS);
    }

    @Test
    void steeringLeavesVerticalVelocityAlone() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setOnGround(false);
        mob.setVelocity(new Vector3f(0, 2.5f, 0));

        steering.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);

        assertEquals(2.5f, mob.getVelocity().y, EPS, "vertical motion is the caller's business");
    }

    @Test
    void stopMovingKillsHorizontalDriftOnly() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        mob.setVelocity(new Vector3f(3.0f, -1.0f, 2.0f));

        steeringFor(mob, 200.0f).stopMoving();

        assertEquals(0.0f, mob.getVelocity().x, EPS);
        assertEquals(0.0f, mob.getVelocity().z, EPS);
        assertEquals(-1.0f, mob.getVelocity().y, EPS, "gravity is not steering's business");
    }

    /** Cow and sheep models are authored facing −Z; everything else faces +Z. */
    @Test
    void facingAppliesTheModelYawOffset() {
        StubMob goose = new StubMob(EntityType.GOOSE);
        StubMob cow = new StubMob(EntityType.COW);

        // A full second at 3600 deg/s is far more than needed, so both land exactly on target.
        steeringFor(goose, 200.0f).faceDirection(new Vector3f(0, 0, 1), 3600.0f, 1.0f);
        steeringFor(cow, 200.0f).faceDirection(new Vector3f(0, 0, 1), 3600.0f, 1.0f);

        assertEquals(0.0f, normalizeDegrees(goose.getRotation().y), 1e-3f);
        assertEquals(180.0f, Math.abs(normalizeDegrees(cow.getRotation().y)), 1e-3f);
        assertEquals(EntityType.GOOSE.getModelYawOffsetDegrees(), 0.0f, EPS);
    }

    @Test
    void facingTurnsNoFasterThanTheGivenRate() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);

        // Target is 90 degrees away but only 10 degrees of turn are allowed this tick.
        steering.faceDirection(new Vector3f(1, 0, 0), 100.0f, 0.1f);

        assertEquals(10.0f, normalizeDegrees(mob.getRotation().y), 1e-3f);
    }

    @Test
    void facingTakesTheShortestArc() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setRotation(new Vector3f(0, 170.0f, 0));

        // Target yaw is -170; the short way round is +20, not -340.
        steering.faceDirection(new Vector3f(-0.17365f, 0, -0.98481f), 3600.0f, 1.0f);

        assertEquals(-170.0f, normalizeDegrees(mob.getRotation().y), 0.1f);
    }

    /**
     * The signal a behaviour waits on before committing to a heading — a goose finishing its turn
     * before it leaves the ground. It must agree with {@code faceDirection} about where "facing
     * that way" is, offset included, or the wait either never ends or ends pointing wrong.
     */
    @Test
    void yawErrorReportsHowFarIsLeftToTurn() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);

        assertEquals(90.0f, steering.yawErrorTo(new Vector3f(1, 0, 0)), 1e-3f,
                "a goose facing +Z is a quarter turn from +X");

        steering.faceDirection(new Vector3f(1, 0, 0), 3600.0f, 1.0f);

        assertEquals(0.0f, steering.yawErrorTo(new Vector3f(1, 0, 0)), 1e-3f,
                "once turned, nothing is left to turn");
    }

    @Test
    void yawErrorIsTheShortestArcAndNeverNegative() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setRotation(new Vector3f(0, 170.0f, 0));

        // Target yaw is -170: twenty degrees the short way, three hundred and forty the long way.
        assertEquals(20.0f, steering.yawErrorTo(new Vector3f(-0.17365f, 0, -0.98481f)), 0.1f);
    }

    /** The offset lives in one place; the error query has to read it from there too. */
    @Test
    void yawErrorAccountsForTheModelYawOffset() {
        StubMob cow = new StubMob(EntityType.COW);
        Steering steering = steeringFor(cow, 200.0f);

        // A cow model is authored facing −Z, so a cow at yaw 0 is already pointing along −Z.
        assertEquals(0.0f, steering.yawErrorTo(new Vector3f(0, 0, -1)), 1e-3f);
        assertEquals(180.0f, steering.yawErrorTo(new Vector3f(0, 0, 1)), 1e-3f);
    }

    /** A crippled mob steers slower without every behaviour having to remember to ask. */
    @Test
    void statusEffectsScaleSteeringSpeed() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setOnGround(false);

        steering.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);
        float unhindered = mob.getVelocity().x;

        mob.applyStatusEffect(
                com.stonebreak.mobs.entities.status.StatusEffectType.CRIPPLE, 5.0f, 0.5f);
        steering.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);

        assertTrue(mob.getVelocity().x < unhindered,
                "CRIPPLE should slow steering: " + mob.getVelocity().x + " vs " + unhindered);
        assertEquals(unhindered * mob.getMoveSpeedMultiplier(), mob.getVelocity().x, EPS);
    }

    /** An airborne mob has nothing to push off, so a route-driven jump request is refused. */
    @Test
    void jumpsAreRefusedInMidAir() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setOnGround(false);

        assertTrue(!steering.requestJump());
        assertEquals(0.0f, mob.getVelocity().y, EPS);
    }

    /** One jump per cooldown, however many times a route and the obstacle probe both ask. */
    @Test
    void jumpsAreRateLimited() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        Steering steering = steeringFor(mob, 200.0f);
        mob.setOnGround(true);

        assertTrue(steering.requestJump());
        assertTrue(mob.getVelocity().y > 0.0f);

        mob.setOnGround(true); // physics would land it eventually; the cooldown still applies
        assertTrue(!steering.requestJump(), "a second hop must wait for the cooldown");

        steering.tick(1.5f);
        assertTrue(steering.requestJump(), "and is allowed once it expires");
    }

    /** Folds a yaw into (-180, 180] so comparisons don't trip over full turns. */
    private static float normalizeDegrees(float degrees) {
        float result = degrees % 360.0f;
        if (result > 180.0f) {
            result -= 360.0f;
        } else if (result <= -180.0f) {
            result += 360.0f;
        }
        return result;
    }
}
