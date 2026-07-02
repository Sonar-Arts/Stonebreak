package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Vector3f;

import java.util.List;

/**
 * Base class for all mob AI controllers — the single source of truth for the
 * state-machine plumbing every mob shares: the current {@link MobBehaviorState},
 * state/state-change timers, and the hooks the rest of the engine talks to
 * (renderer clip timing, save/load, network shadows, debug overlay).
 *
 * <p>Concrete behaviour lives in subclasses ({@link PassiveMobAI} for wandering
 * passive mobs; future hostile AIs extend this too). The owning
 * {@link LivingEntity} drives {@link #update} from its own update loop and
 * exposes the AI generically via {@code LivingEntity.getAI()}.
 */
public abstract class MobAI {

    protected final LivingEntity entity;

    protected MobBehaviorState currentState = MobBehaviorState.IDLE;
    protected float stateTimer;
    protected float stateChangeTimer;

    protected MobAI(LivingEntity entity) {
        this.entity = entity;
    }

    /** Runs one AI tick. Called by the owning entity while alive and not stunned. */
    public abstract void update(float deltaTime);

    /**
     * Switches behaviour state, resetting the state timer. Also applied on
     * clients when a replicated state arrives for a network-shadow mob.
     */
    public void setState(MobBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0.0f;
            onStateEntered(newState);
        }
    }

    /** Hook invoked when {@link #setState} actually changes state. */
    protected void onStateEntered(MobBehaviorState newState) {
    }

    public MobBehaviorState getCurrentState() { return currentState; }

    public float getStateTimer() { return stateTimer; }

    /**
     * Advances ONLY the state-timer clock, without running any AI decisions.
     * Used on a client network shadow (whose AI is otherwise frozen) so
     * one-shot clips sampled from {@link #getStateTimer()} play through after
     * a replicated state change resets the timer via {@link #setState}.
     */
    public void advanceClientClock(float deltaTime) {
        stateTimer += deltaTime;
    }

    /**
     * The clip time the renderer should sample the current state's animation
     * at. Looping states use the entity's continuously advancing animation
     * clock; one-shot states (wing flap) override this to return state-relative
     * time so the clip plays through once instead of freezing on its last frame.
     */
    public float clipTime(float totalAnimationTime) {
        return totalAnimationTime;
    }

    /** Damage response hook; default is no reaction. */
    public void onDamaged(float damage) {
    }

    /** Debug path trail for the overlay; empty unless the AI navigates. */
    public List<Vector3f> getPathPoints() {
        return List.of();
    }

    /** Clears debug path visualization data. */
    public void clearDebugPaths() {
    }

    /** Releases AI resources when the entity is removed. */
    public void cleanup() {
    }
}
