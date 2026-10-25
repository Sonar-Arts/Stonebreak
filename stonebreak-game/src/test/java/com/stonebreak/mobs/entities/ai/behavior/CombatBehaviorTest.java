package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.mobs.entities.ai.perception.LightProbe;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.ai.perception.ShoutRelay;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.entities.combat.WeaponProfile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fighting rules for hostile mobs: when a pursuit starts and holds, when a swing lands, and
 * when a bow shot leaves. Damage must land on the clip's impact beat — not on the first frame — so
 * what the player sees and what they feel agree.
 */
class CombatBehaviorTest {

    private static final float DT = 1.0f / 60.0f;

    /** A player wherever the test puts one, recording the blows it takes. */
    private static final class FakePlayers implements PlayerLocator {
        Vector3f position;
        boolean visible = true;
        final List<Float> hits = new ArrayList<>();
        float lastKnockback;

        @Override
        public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
            return position == null ? null : out.set(position);
        }

        @Override
        public boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
            return position != null && visible;
        }

        @Override
        public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
            if (position == null) return false;
            hits.add(amount);
            lastKnockback = knockback;
            return true;
        }
    }

    private final FakePlayers players = new FakePlayers();
    private final StubMob mob = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0));
    private final AiContext context = new AiContext(
            mob, new PathAgent(mob, new Steering(mob, 360f, 0f, 0f)), new Random(3), players);

    /** Goblins face -Z at yaw 0, so a player straight down -Z is already in front of it. */
    private void playerAhead(float distance) {
        players.position = new Vector3f(0, 64, -distance);
    }

    /** Runs a behaviour like the controller does, ageing the mob with it, until it ends. */
    private float runToEnd(Behavior behavior, float maxSeconds) {
        behavior.start(context);
        float t = 0;
        while (behavior.shouldContinue(context) && t < maxSeconds) {
            step(behavior);
            t += DT;
        }
        behavior.stop(context);
        return t;
    }

    private void step(Behavior behavior) {
        context.setDeltaTime(DT);
        mob.setAge(mob.getAge() + DT);
        behavior.tick(context, DT);
    }

    private static WeaponProfile.Melee dagger() {
        return (WeaponProfile.Melee) MobWeapon.DAGGER.profile();
    }

    private static WeaponProfile.Ranged bow() {
        return (WeaponProfile.Ranged) MobWeapon.BOW.profile();
    }

    // ── Pursue ──────────────────────────────────────────────────────────────

    /** Awareness on the stub goblin, in whatever state the test puts it. */
    private AwarenessController aware(AwarenessController.AwarenessState state) {
        AwarenessController awareness = new AwarenessController(mob, PerceptionProfile.GOBLIN, players,
                LightProbe.FULL, ShoutRelay.NONE, new NoiseBus());
        awareness.applyReplicatedState(state);
        mob.setAwareness(awareness);
        return awareness;
    }

    @Test
    void onlyAnAlertedGoblinClosesInOnThePlayer() {
        AwarenessController awareness = aware(AwarenessController.AwarenessState.UNAWARE);
        PursuePlayerBehavior pursue = new PursuePlayerBehavior(awareness, 1.5f, 0.9f, 1.3f);
        playerAhead(5f);
        assertFalse(pursue.canStart(context), "it has not noticed anyone");
        awareness.applyReplicatedState(AwarenessController.AwarenessState.ALERTED);
        assertTrue(pursue.canStart(context));
        awareness.applyReplicatedState(AwarenessController.AwarenessState.SEARCHING);
        assertFalse(pursue.canStart(context), "searching is the search behaviour's business");
    }

    @Test
    void aPursuerAtItsHoldDistanceStandsAndLooksIdle() {
        AwarenessController awareness = aware(AwarenessController.AwarenessState.ALERTED);
        PursuePlayerBehavior pursue = new PursuePlayerBehavior(awareness, 2f, 0.9f, 1.3f);
        playerAhead(1f);
        pursue.start(context);
        step(pursue);
        assertEquals(MobBehaviorState.IDLE, pursue.animationState());
        playerAhead(10f);
        step(pursue);
        assertEquals(MobBehaviorState.WANDERING, pursue.animationState());
    }

    @Test
    void anUnawareGoblinNeitherSwingsNorShootsAtAPlayerInReach() {
        AwarenessController awareness = aware(AwarenessController.AwarenessState.UNAWARE);
        mob.setAge(10f);
        MeleeAttackBehavior swing = new MeleeAttackBehavior(dagger());
        RangedAttackBehavior shot = new RangedAttackBehavior(bow(), recordShots);
        playerAhead(1f);
        assertFalse(swing.canStart(context));
        playerAhead(bow().minRange() + 1f);
        assertFalse(shot.canStart(context));

        awareness.applyReplicatedState(AwarenessController.AwarenessState.ALERTED);
        playerAhead(1f);
        assertTrue(swing.canStart(context));
    }

    // ── Melee ───────────────────────────────────────────────────────────────

    @Test
    void aSwingOnlyStartsWithThePlayerInReach() {
        MeleeAttackBehavior melee = new MeleeAttackBehavior(dagger());
        playerAhead(dagger().reach() + 0.5f);
        assertFalse(melee.canStart(context));
        playerAhead(dagger().reach() - 0.2f);
        assertTrue(melee.canStart(context));
    }

    @Test
    void theBlowLandsOnceAtTheImpactBeatNotOnTheFirstFrame() {
        MeleeAttackBehavior melee = new MeleeAttackBehavior(dagger());
        playerAhead(1f);
        melee.start(context);
        float t = 0;
        while (t + DT < dagger().impactSeconds()) {
            step(melee);
            t += DT;
        }
        assertTrue(players.hits.isEmpty(), "nothing lands during the wind-up");

        while (melee.shouldContinue(context)) {
            step(melee);
        }
        assertEquals(List.of(dagger().damage()), players.hits, "exactly one hit per swing");
        assertEquals(dagger().knockback(), players.lastKnockback);
    }

    @Test
    void aPlayerWhoGetsClearBeforeTheImpactIsMissed() {
        MeleeAttackBehavior melee = new MeleeAttackBehavior(dagger());
        playerAhead(1f);
        melee.start(context);
        playerAhead(dagger().reach() * 3f);
        while (melee.shouldContinue(context)) {
            step(melee);
        }
        assertTrue(players.hits.isEmpty());
    }

    @Test
    void theSwingIsCommittedForItsClipAndThenCoolsDown() {
        MeleeAttackBehavior melee = new MeleeAttackBehavior(dagger());
        playerAhead(1f);
        float length = runToEnd(melee, 10f);
        assertEquals(dagger().clipSeconds(), length, 2 * DT);

        assertFalse(melee.canStart(context), "cooling down right after a swing");
        mob.setAge(mob.getAge() + dagger().cooldown());
        assertTrue(melee.canStart(context), "ready again once the cooldown has passed");
    }

    @Test
    void theSmackerShovesFarHarderThanTheDagger() {
        WeaponProfile.Melee smacker = (WeaponProfile.Melee) MobWeapon.PATTY_SMACKER.profile();
        assertTrue(smacker.knockback() > dagger().knockback() * 3f);
        assertEquals(MobBehaviorState.SMASH, smacker.animation());
        assertEquals(MobBehaviorState.STAB, dagger().animation());
    }

    // ── Ranged ──────────────────────────────────────────────────────────────

    private record Shot(Vector3f origin, Vector3f velocity, float damage) {}

    private final List<Shot> shots = new ArrayList<>();
    private final ArrowLauncher recordShots =
            (LivingEntity shooter, Vector3f origin, Vector3f velocity, float damage) ->
                    shots.add(new Shot(origin, velocity, damage));

    @Test
    void aDrawNeedsThePlayerInRangeAndInSight() {
        RangedAttackBehavior ranged = new RangedAttackBehavior(bow(), recordShots);
        playerAhead(bow().minRange() - 1f);
        assertFalse(ranged.canStart(context), "too close to draw");
        playerAhead(bow().maxRange() + 1f);
        assertFalse(ranged.canStart(context), "out of range");
        playerAhead((bow().minRange() + bow().maxRange()) / 2f);
        assertTrue(ranged.canStart(context));
        players.visible = false;
        assertFalse(ranged.canStart(context), "no shooting through walls");
    }

    @Test
    void theArrowLeavesAtTheEndOfTheDrawAimedAtThePlayer() {
        RangedAttackBehavior ranged = new RangedAttackBehavior(bow(), recordShots);
        playerAhead(10f);
        ranged.start(context);
        assertEquals(MobBehaviorState.DRAW_BOW, ranged.animationState());
        float t = 0;
        while (t + DT < bow().drawSeconds()) {
            step(ranged);
            t += DT;
        }
        assertTrue(shots.isEmpty(), "nothing leaves mid-draw");

        step(ranged);
        step(ranged);
        assertEquals(1, shots.size());
        assertEquals(MobBehaviorState.RELEASE_BOW, ranged.animationState());

        Shot shot = shots.getFirst();
        assertEquals(bow().damage(), shot.damage());
        assertEquals(bow().arrowSpeed(), shot.velocity().length(), 1e-3f);
        assertTrue(shot.velocity().z < 0, "flies toward the player");
        assertTrue(shot.velocity().y > 0, "lifted to cancel the drop over the flight");
    }

    @Test
    void aDrawIsAbandonedWhenThePlayerGetsOutOfRange() {
        RangedAttackBehavior ranged = new RangedAttackBehavior(bow(), recordShots);
        playerAhead(10f);
        ranged.start(context);
        step(ranged);
        playerAhead(bow().maxRange() + 5f);
        runToEnd(ranged, 5f);
        assertTrue(shots.isEmpty());
    }
}
