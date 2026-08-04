package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.BehaviorController;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.entities.ai.nav.Steering;

import java.util.List;
import java.util.Random;

/**
 * A mob's brain: a list of {@link Behavior}s, a {@link BehaviorController} choosing between them,
 * and a {@link PathAgent} carrying out whatever they decide.
 *
 * <p>It is also the stable face the rest of the game talks to. The renderer asks for an animation
 * state and a clip time, saves and network replication read and write that state by name, and
 * client-side shadow mobs have theirs pushed in from the server — none of which care that the
 * behaviour underneath became a set of small classes.
 *
 * <p>Concrete AI is composition now, not inheritance: a mob is assembled from behaviours instead of
 * subclassing this. That is why the class is final — a mob needing new behaviour writes a
 * {@link Behavior}, and every mob that shares it gets it too.
 */
public final class MobAI {

    private final LivingEntity entity;
    private final AiContext context;
    private final BehaviorController controller;
    private final PathAgent nav;

    private MobBehaviorState currentState = MobBehaviorState.IDLE;
    private float stateTimer;

    /** The usual case: reacts to the local player, seeded from the shared random. */
    public MobAI(LivingEntity entity, Steering steering, Behavior... behaviors) {
        this(entity, new PathAgent(entity, steering), PlayerLocator.LOCAL, new Random(),
                List.of(behaviors));
    }

    /** Full form, for tests and for mobs that need a specific navigation profile. */
    public MobAI(LivingEntity entity, PathAgent nav, PlayerLocator players, Random random,
                 List<Behavior> behaviors) {
        this.entity = entity;
        this.nav = nav;
        this.context = new AiContext(entity, nav, random, players);
        this.controller = new BehaviorController(behaviors);
    }

    /**
     * Runs one AI tick: behaviours decide, then navigation carries it out. The order matters —
     * a behaviour that sets a destination this tick starts moving toward it in the same tick.
     */
    public void update(float deltaTime) {
        if (!entity.isAlive()) {
            return;
        }
        stateTimer += deltaTime;
        controller.tick(context, deltaTime);
        nav.tick(deltaTime);
        setState(controller.animationState());
    }

    /**
     * Switches the animation state, resetting the state clock. Called by the tick from whatever
     * behaviour is running, and directly on clients when a replicated state arrives for a network
     * shadow mob.
     */
    public void setState(MobBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0.0f;
        }
    }

    public MobBehaviorState getCurrentState() {
        return currentState;
    }

    public float getStateTimer() {
        return stateTimer;
    }

    /**
     * Advances ONLY the state clock, running no behaviour. Client shadow mobs are otherwise frozen,
     * but their one-shot clips still need a clock to play through after a replicated state change.
     */
    public void advanceClientClock(float deltaTime) {
        stateTimer += deltaTime;
    }

    /**
     * The time the renderer should sample the current state's clip at. Looping states ride the
     * entity's continuous animation clock; one-shot states use state-relative time so the clip
     * plays through once and holds instead of restarting.
     */
    public float clipTime(float totalAnimationTime) {
        return currentState.isOneShot() ? stateTimer : totalAnimationTime;
    }

    /** Damage hook — reaches every behaviour, so the one that reacts need not be running. */
    public void onDamaged(float damage) {
        controller.onDamaged(context, damage);
    }

    /** This mob's navigation, for behaviours outside the controller and for the debug overlay. */
    public PathAgent nav() {
        return nav;
    }

    /** Name of the behaviour currently in charge, for the debug overlay. */
    public String activeBehaviorName() {
        return controller.activeName();
    }

    /** Releases AI resources when the entity is removed. */
    public void cleanup() {
        controller.stopAll(context);
        nav.cleanup();
    }
}
