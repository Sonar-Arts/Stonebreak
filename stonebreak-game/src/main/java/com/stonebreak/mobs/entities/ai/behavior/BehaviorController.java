package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Decides which of a mob's {@link Behavior}s are running, and ticks them.
 *
 * <p>The whole scheduler is: stop anything that no longer applies, start the most urgent things
 * that do and whose {@link Behavior.Flag}s are free, then tick what is running. Two rules give it
 * all the expressiveness the mobs need:
 *
 * <ul>
 *   <li><b>Preemption.</b> A behaviour may take a flag from a less urgent one that holds it, which
 *       is how being hit interrupts grazing without either behaviour knowing about the other.</li>
 *   <li><b>Weighted choice among equals.</b> When several behaviours of the same priority could
 *       start, one is picked by {@link Behavior#weight()} — so a mob's ambient personality ("mostly
 *       idle, sometimes wander") is three numbers rather than a scheduler of its own.</li>
 * </ul>
 *
 * <p>Behaviours whose flags do not overlap run together, so a look-at behaviour can share a tick
 * with a walking one.
 */
public final class BehaviorController {

    private final List<Behavior> behaviors;
    private final List<Behavior> running = new ArrayList<>(2);
    private final List<Behavior> candidates = new ArrayList<>(4);

    public BehaviorController(List<Behavior> behaviors) {
        this.behaviors = new ArrayList<>(behaviors);
        this.behaviors.sort(Comparator.comparingInt(Behavior::priority));
    }

    public void tick(AiContext context, float deltaTime) {
        context.setDeltaTime(deltaTime);
        stopFinished(context);
        startEligible(context);
        // Indexed, not enhanced-for: a behaviour's tick can end up stopping another one.
        for (int i = 0; i < running.size(); i++) {
            running.get(i).tick(context, deltaTime);
        }
    }

    private void stopFinished(AiContext context) {
        for (int i = running.size() - 1; i >= 0; i--) {
            Behavior behavior = running.get(i);
            if (!behavior.shouldContinue(context)) {
                running.remove(i);
                behavior.stop(context);
            }
        }
    }

    private void startEligible(AiContext context) {
        int index = 0;
        while (index < behaviors.size()) {
            int priority = behaviors.get(index).priority();
            int groupEnd = index;
            while (groupEnd < behaviors.size() && behaviors.get(groupEnd).priority() == priority) {
                groupEnd++;
            }
            startFromGroup(context, index, groupEnd);
            index = groupEnd;
        }
    }

    /** Starts as many behaviours from one priority group as will fit alongside each other. */
    private void startFromGroup(AiContext context, int from, int to) {
        while (true) {
            candidates.clear();
            for (int i = from; i < to; i++) {
                Behavior behavior = behaviors.get(i);
                if (!running.contains(behavior) && fits(behavior) && behavior.canStart(context)) {
                    candidates.add(behavior);
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
            Behavior chosen = pickByWeight(context, candidates);
            preempt(context, chosen);
            running.add(chosen);
            chosen.start(context);
        }
    }

    /** Whether nothing at least as urgent already holds the flags this behaviour needs. */
    private boolean fits(Behavior candidate) {
        EnumSet<Behavior.Flag> needed = candidate.flags();
        for (Behavior active : running) {
            if (active.priority() <= candidate.priority() && intersects(active.flags(), needed)) {
                return false;
            }
        }
        return true;
    }

    /** Stops the less urgent behaviours holding the flags this one is about to take. */
    private void preempt(AiContext context, Behavior starting) {
        EnumSet<Behavior.Flag> needed = starting.flags();
        for (int i = running.size() - 1; i >= 0; i--) {
            Behavior active = running.get(i);
            if (intersects(active.flags(), needed)) {
                running.remove(i);
                active.stop(context);
            }
        }
    }

    private static Behavior pickByWeight(AiContext context, List<Behavior> options) {
        if (options.size() == 1) {
            return options.get(0);
        }
        float total = 0.0f;
        for (Behavior option : options) {
            total += Math.max(0.0f, option.weight());
        }
        if (total <= 0.0f) {
            return options.get(0);
        }
        float roll = context.random().nextFloat() * total;
        for (Behavior option : options) {
            roll -= Math.max(0.0f, option.weight());
            if (roll <= 0.0f) {
                return option;
            }
        }
        return options.get(options.size() - 1);
    }

    private static boolean intersects(EnumSet<Behavior.Flag> a, EnumSet<Behavior.Flag> b) {
        for (Behavior.Flag flag : b) {
            if (a.contains(flag)) {
                return true;
            }
        }
        return false;
    }

    /** Delivers a damage event to every behaviour, running or not. */
    public void onDamaged(AiContext context, float damage) {
        for (Behavior behavior : behaviors) {
            behavior.onDamaged(context, damage);
        }
    }

    /** Stops everything, e.g. when the mob dies or is removed. */
    public void stopAll(AiContext context) {
        for (int i = running.size() - 1; i >= 0; i--) {
            Behavior behavior = running.remove(i);
            behavior.stop(context);
        }
    }

    /**
     * The animation state of the most urgent running behaviour, or {@link MobBehaviorState#IDLE}
     * when nothing is running.
     */
    public MobBehaviorState animationState() {
        Behavior leader = leader();
        return leader == null ? MobBehaviorState.IDLE : leader.animationState();
    }

    /** Name of the most urgent running behaviour, for the debug overlay. */
    public String activeName() {
        Behavior leader = leader();
        return leader == null ? "none" : leader.debugName();
    }

    /** Whether {@code behavior} is currently running. Test seam and debug aid. */
    public boolean isRunning(Behavior behavior) {
        return running.contains(behavior);
    }

    public List<Behavior> runningBehaviors() {
        return List.copyOf(running);
    }

    private Behavior leader() {
        Behavior leader = null;
        for (Behavior active : running) {
            if (leader == null || active.priority() < leader.priority()) {
                leader = active;
            }
        }
        return leader;
    }
}
