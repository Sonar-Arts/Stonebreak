package com.stonebreak.mobs.entities.ai;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

import java.util.List;

/**
 * The shared behaviour implementation for passive ground mobs (cow, sheep,
 * chicken, and any future grazer): weighted random switching between IDLE /
 * WANDERING / GRAZING, an optional one-shot WING_FLAP gesture while idle, and
 * a configurable damage response (flee or startle). All movement goes through
 * the shared {@link MobNavigator}.
 *
 * <p>Per-mob personality is pure data — a {@link Config} passed at
 * construction. A state with zero weight/chance is simply never entered, so
 * chickens (grazeWeight 0) never graze and cows (wingFlapChancePerSecond 0)
 * never flap.
 */
public class PassiveMobAI extends MobAI {

    /** How the mob reacts to taking damage. */
    public enum DamageResponse {
        /** Run ~10 blocks directly away from the player for a few seconds. */
        FLEE,
        /** Freeze in place briefly (startled). */
        STARTLE
    }

    /**
     * Per-mob behaviour tuning. Weights are relative (they need not sum to 1);
     * zero disables the state entirely.
     */
    public record Config(
            float minStateDurationSeconds,
            float maxStateDurationSeconds,
            float wanderMinDistance,
            float wanderMaxDistance,
            float moveSpeedMultiplier,
            float rotationSpeedDegPerSec,
            float idleWeight,
            float wanderWeight,
            float grazeWeight,
            float wingFlapChancePerSecond,
            float wingFlapDurationSeconds,
            float hopBoostSpeed,
            float hopDurationSeconds,
            DamageResponse damageResponse) {
    }

    private static final float TARGET_REACHED_RADIUS = 1.5f;
    private static final float FLEE_DISTANCE = 10.0f;
    private static final float FLEE_DURATION_SECONDS = 4.0f;
    private static final float STARTLE_DURATION_SECONDS = 2.0f;

    private final Config config;
    private final MobNavigator navigator;

    public PassiveMobAI(com.stonebreak.mobs.entities.LivingEntity entity, Config config) {
        super(entity);
        this.config = config;
        this.navigator = new MobNavigator(entity, config.rotationSpeedDegPerSec(),
                config.moveSpeedMultiplier(), config.hopBoostSpeed(), config.hopDurationSeconds());
        this.stateChangeTimer = randomStateDuration();
    }

    public MobNavigator getNavigator() { return navigator; }

    @Override
    public void update(float deltaTime) {
        if (!entity.isAlive()) return;

        stateTimer += deltaTime;
        navigator.tick(deltaTime);

        if (currentState == MobBehaviorState.WING_FLAP) {
            // Hold the flap for the clip's length, then settle back to idle.
            if (stateTimer >= config.wingFlapDurationSeconds()) {
                setState(MobBehaviorState.IDLE);
                stateChangeTimer = randomStateDuration();
            }
        } else {
            stateChangeTimer -= deltaTime;
            if (stateChangeTimer <= 0) {
                changeToRandomState();
                stateChangeTimer = randomStateDuration();
            }
            // Occasionally play the one-shot gesture while standing idle.
            if (currentState == MobBehaviorState.IDLE
                    && Math.random() < config.wingFlapChancePerSecond() * deltaTime) {
                setState(MobBehaviorState.WING_FLAP);
            }
        }

        switch (currentState) {
            case IDLE, WING_FLAP, GRAZING -> navigator.stopMoving();
            case WANDERING -> handleWanderBehavior(deltaTime);
        }

        navigator.updatePathTrail(currentState == MobBehaviorState.WANDERING,
                currentState == MobBehaviorState.IDLE ? stateTimer : 0.0f);
    }

    private void handleWanderBehavior(float deltaTime) {
        if (!navigator.hasTarget() || navigator.reachedTarget(TARGET_REACHED_RADIUS)) {
            navigator.pickWanderTarget(config.wanderMinDistance(), config.wanderMaxDistance());
        }
        navigator.moveTowardTarget(deltaTime);
    }

    /**
     * Picks the next state by the configured weights, with a 50% chance to
     * re-roll among the other enabled states when the pick repeats the current
     * one (so mobs don't sit in one state forever).
     */
    private void changeToRandomState() {
        MobBehaviorState newState = weightedPick();
        if (newState == currentState && Math.random() < 0.5) {
            MobBehaviorState rerolled;
            int guard = 0;
            do {
                rerolled = weightedPick();
            } while (rerolled == currentState && ++guard < 10);
            newState = rerolled;
        }
        setState(newState);
    }

    private MobBehaviorState weightedPick() {
        float total = config.idleWeight() + config.wanderWeight() + config.grazeWeight();
        if (total <= 0) return MobBehaviorState.IDLE;
        float roll = (float) (Math.random() * total);
        if (roll < config.idleWeight()) return MobBehaviorState.IDLE;
        if (roll < config.idleWeight() + config.wanderWeight()) return MobBehaviorState.WANDERING;
        return MobBehaviorState.GRAZING;
    }

    private float randomStateDuration() {
        return config.minStateDurationSeconds()
                + (float) (Math.random()
                        * (config.maxStateDurationSeconds() - config.minStateDurationSeconds()));
    }

    @Override
    protected void onStateEntered(MobBehaviorState newState) {
        if (newState == MobBehaviorState.WANDERING) {
            navigator.clearTarget();
        }
    }

    @Override
    public float clipTime(float totalAnimationTime) {
        // One-shot gesture: sample state-relative time so the clip plays once.
        return currentState == MobBehaviorState.WING_FLAP ? stateTimer : totalAnimationTime;
    }

    @Override
    public void onDamaged(float damage) {
        switch (config.damageResponse()) {
            case FLEE -> {
                setState(MobBehaviorState.WANDERING);
                stateChangeTimer = FLEE_DURATION_SECONDS;
                Player player = Game.getPlayer();
                if (player != null) {
                    navigator.pickFleeTarget(player.getPosition(), FLEE_DISTANCE);
                }
            }
            case STARTLE -> {
                setState(MobBehaviorState.IDLE);
                stateChangeTimer = STARTLE_DURATION_SECONDS;
            }
        }
    }

    @Override
    public List<Vector3f> getPathPoints() {
        return navigator.getPathPoints();
    }

    @Override
    public void clearDebugPaths() {
        navigator.clearDebugPaths();
    }

    @Override
    public void cleanup() {
        navigator.cleanup();
    }
}
