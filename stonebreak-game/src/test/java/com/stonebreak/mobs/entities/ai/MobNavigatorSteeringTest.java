package com.stonebreak.mobs.entities.ai;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steering and facing are shared by every mob, so the rules that used to be copied per mob are
 * pinned here: speed scales by the behaviour multiplier and by status effects, hops can be
 * suppressed, and facing always goes through the entity type's model yaw offset.
 *
 * <p>These paths never touch the world — {@code allowJump=false} or an airborne entity skips the
 * block probe — so the navigator can be exercised with no world at all.</p>
 */
class MobNavigatorSteeringTest {

    private static final float EPS = 1e-4f;

    /** Minimal concrete mob: the navigator only needs position, velocity, rotation and type. */
    private static final class StubMob extends LivingEntity {
        private final EntityType type;

        StubMob(EntityType type) {
            super(null, new Vector3f(0, 64, 0), type);
            this.type = type;
        }

        @Override
        public EntityType getType() {
            return type;
        }

        @Override
        public void render(Renderer renderer) {
        }

        @Override
        public void onInteract(Player player) {
        }

        @Override
        public void onDamage(float damage, LivingEntity.DamageSource source) {
        }

        @Override
        protected void onDeath() {
        }

        @Override
        public ItemStack[] getDrops() {
            return new ItemStack[0];
        }
    }

    private static MobNavigator navigatorFor(StubMob mob, float rotationSpeed, float moveMultiplier) {
        return new MobNavigator(mob, rotationSpeed, moveMultiplier, 0.0f, 0.0f);
    }

    @Test
    void steersAlongTheGivenHeadingAtTheRequestedFractionOfMoveSpeed() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 0.7f);

        // Airborne, so the obstacle probe is skipped and no world lookup happens.
        mob.setOnGround(false);
        navigator.steerAlong(new Vector3f(1, 0, 0), 0.4f, 1.0f, false);

        assertEquals(mob.getMoveSpeed() * 0.4f, mob.getVelocity().x, EPS);
        assertEquals(0.0f, mob.getVelocity().z, EPS);
    }

    /** The multiplier passed per call wins over the one the navigator was built with. */
    @Test
    void perCallMultiplierOverridesTheConstructionDefault() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 0.7f);
        mob.setOnGround(false);

        navigator.steerAlong(new Vector3f(0, 0, 1), 1.0f, 1.0f, false);

        assertEquals(mob.getMoveSpeed(), mob.getVelocity().z, EPS);
    }

    @Test
    void steeringLeavesVerticalVelocityAlone() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 1.0f);
        mob.setOnGround(false);
        mob.setVelocity(new Vector3f(0, 2.5f, 0));

        navigator.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);

        assertEquals(2.5f, mob.getVelocity().y, EPS, "vertical motion is the caller's business");
    }

    /** Cow and sheep models are authored facing −Z; everything else faces +Z. */
    @Test
    void facingAppliesTheModelYawOffset() {
        StubMob goose = new StubMob(EntityType.GOOSE);
        StubMob cow = new StubMob(EntityType.COW);

        // A full second at 3600 deg/s is far more than needed, so both land exactly on target.
        navigatorFor(goose, 200.0f, 1.0f).faceDirection(new Vector3f(0, 0, 1), 3600.0f, 1.0f);
        navigatorFor(cow, 200.0f, 1.0f).faceDirection(new Vector3f(0, 0, 1), 3600.0f, 1.0f);

        assertEquals(0.0f, normalizeDegrees(goose.getRotation().y), 1e-3f);
        assertEquals(180.0f, Math.abs(normalizeDegrees(cow.getRotation().y)), 1e-3f);
        assertEquals(EntityType.GOOSE.getModelYawOffsetDegrees(), 0.0f, EPS);
    }

    @Test
    void facingTurnsNoFasterThanTheGivenRate() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 1.0f);

        // Target is 90 degrees away but only 10 degrees of turn are allowed this tick.
        navigator.faceDirection(new Vector3f(1, 0, 0), 100.0f, 0.1f);

        assertEquals(10.0f, normalizeDegrees(mob.getRotation().y), 1e-3f);
    }

    @Test
    void facingTakesTheShortestArc() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 1.0f);
        mob.setRotation(new Vector3f(0, 170.0f, 0));

        // Target yaw is -170; the short way round is +20, not -340.
        navigator.faceDirection(new Vector3f(-0.17365f, 0, -0.98481f), 3600.0f, 1.0f);

        assertEquals(-170.0f, normalizeDegrees(mob.getRotation().y), 0.1f);
    }

    /** A crippled mob steers slower without every behaviour having to remember to ask. */
    @Test
    void statusEffectsScaleSteeringSpeed() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 1.0f);
        mob.setOnGround(false);

        navigator.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);
        float unhindered = mob.getVelocity().x;

        mob.applyStatusEffect(
                com.stonebreak.mobs.entities.status.StatusEffectType.CRIPPLE, 5.0f, 0.5f);
        navigator.steerAlong(new Vector3f(1, 0, 0), 1.0f, 0.1f, false);

        assertTrue(mob.getVelocity().x < unhindered,
                "CRIPPLE should slow steering: " + mob.getVelocity().x + " vs " + unhindered);
        assertEquals(unhindered * mob.getMoveSpeedMultiplier(), mob.getVelocity().x, EPS);
    }

    @Test
    void targetBookkeepingTracksDistance() {
        StubMob mob = new StubMob(EntityType.GOOSE);
        MobNavigator navigator = navigatorFor(mob, 200.0f, 1.0f);

        assertTrue(!navigator.hasTarget());
        navigator.setTarget(new Vector3f(0, 64, 1));
        assertTrue(navigator.hasTarget());
        assertTrue(navigator.reachedTarget(1.5f));
        assertTrue(!navigator.reachedTarget(0.5f));

        navigator.clearTarget();
        assertTrue(!navigator.hasTarget());
        assertTrue(!navigator.reachedTarget(100.0f), "no target is never 'reached'");
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
