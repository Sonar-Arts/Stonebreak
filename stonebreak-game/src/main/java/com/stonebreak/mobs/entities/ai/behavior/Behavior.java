package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.EnumSet;

/**
 * One thing a mob can be doing. Wandering, grazing, fleeing, floating on water — each is a small
 * class that knows when it applies, what it does per tick, and which animation it looks like.
 *
 * <p>A mob's personality is then a list of these plus their tuning, rather than a state machine
 * that grows a branch per mob. Adding a behaviour to one mob cannot change another's, and a new mob
 * is assembled from behaviours that already exist.
 *
 * <p><b>Priority is lowest-first</b>: 0 is the most urgent thing a mob could be doing. A behaviour
 * only starts if the {@link Flag}s it needs are free, or if it outranks whatever holds them — which
 * is what lets fleeing cut straight through a grazing routine while a lower-priority idle waits its
 * turn.
 *
 * <p>Behaviours of equal priority compete by {@link #weight()}, so "mostly idle, sometimes wander,
 * occasionally graze" stays a matter of three numbers on one mob.
 */
public interface Behavior {

    /** What a running behaviour occupies, so compatible behaviours can share a tick. */
    enum Flag {
        /** Drives the mob's position. Only one behaviour may at a time. */
        MOVE,
        /** Drives where the mob is looking, without moving it. */
        LOOK
    }

    /** Lower runs first. See the class note. */
    int priority();

    /** Relative likelihood of being chosen among equal-priority alternatives. */
    default float weight() {
        return 1.0f;
    }

    /** What this behaviour needs exclusive use of while it runs. */
    default EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    /** Whether conditions are right to begin. Called only while the behaviour is not running. */
    boolean canStart(AiContext context);

    /**
     * Whether to keep going. Called every tick while running; defaults to the same test used to
     * start, which suits behaviours whose trigger simply persists.
     */
    default boolean shouldContinue(AiContext context) {
        return canStart(context);
    }

    /** Called once as the behaviour begins. */
    default void start(AiContext context) {
    }

    /** Called every tick while running. */
    void tick(AiContext context, float deltaTime);

    /** Called once as the behaviour ends, whether it finished or was interrupted. */
    default void stop(AiContext context) {
    }

    /**
     * The animation this behaviour looks like. Deliberately separate from the behaviour's identity:
     * fleeing and wandering are different behaviours that both render as walking.
     */
    MobBehaviorState animationState();

    /**
     * Damage hook, delivered to every behaviour whether running or not — the behaviour that reacts
     * to being hit is by definition not the one that was running when it happened.
     */
    default void onDamaged(AiContext context, float damage) {
    }

    /** Short name for the debug overlay. */
    default String debugName() {
        return getClass().getSimpleName().replace("Behavior", "");
    }
}
