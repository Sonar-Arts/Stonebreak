package com.stonebreak.mobs.entities.ai.behavior;

import com.openmason.engine.entity.BehaviorHost;
import com.openmason.engine.entity.BehaviorScheduler;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Decides which of a mob's {@link Behavior}s are running, and ticks them.
 *
 * <p>The deciding is the engine's behaviour scheduler (Open Mason {@code cb_*}, OpenMason #115): stop
 * anything that no longer applies, start the most urgent things that do and whose
 * {@link Behavior.Flag}s are free, then tick what is running. Two rules give it all the
 * expressiveness the mobs need:
 *
 * <ul>
 *   <li><b>Preemption.</b> A behaviour may take a flag from a less urgent one that holds it, which
 *       is how being hit interrupts grazing without either behaviour knowing about the other.</li>
 *   <li><b>Weighted choice among equals.</b> When several behaviours of the same priority could
 *       start, one is picked by {@link Behavior#weight()} — so a mob's ambient personality ("mostly
 *       idle, sometimes wander") is three numbers rather than a scheduler of its own.</li>
 * </ul>
 *
 * <p>This class is the game's side of it: it describes each behaviour to the scheduler (priority,
 * weight, flags — all fixed for a behaviour's life) and answers the scheduler's questions by asking
 * the behaviours, with the mob's {@link AiContext}. The weighted pick draws from the context's
 * {@code Random}, so a seeded mob makes the same choices it always did.
 */
public final class BehaviorController implements BehaviorHost {

    /** In the order given; the scheduler names behaviours by their index here. */
    private final List<Behavior> behaviors;
    /** By priority (stable): the order damage reaches them. */
    private final List<Behavior> byPriority;
    private final BehaviorScheduler scheduler;

    /** The context of the scheduler call in progress. */
    private AiContext context;

    public BehaviorController(List<Behavior> behaviors) {
        this.behaviors = List.copyOf(behaviors);
        List<Behavior> sorted = new ArrayList<>(behaviors);
        sorted.sort(Comparator.comparingInt(Behavior::priority));
        this.byPriority = List.copyOf(sorted);
        List<BehaviorScheduler.Desc> descs = new ArrayList<>(behaviors.size());
        for (Behavior behavior : behaviors) {
            descs.add(new BehaviorScheduler.Desc(behavior.priority(), behavior.weight(), bits(behavior.flags())));
        }
        this.scheduler = BehaviorScheduler.create(descs);
    }

    private static int bits(EnumSet<Behavior.Flag> flags) {
        int bits = 0;
        for (Behavior.Flag flag : flags) {
            bits |= 1 << flag.ordinal();
        }
        return bits;
    }

    public void tick(AiContext context, float deltaTime) {
        context.setDeltaTime(deltaTime);
        AiContext outer = this.context;
        this.context = context;
        try {
            scheduler.tick(this, deltaTime);
        } finally {
            this.context = outer;
        }
    }

    /** Delivers a damage event to every behaviour, running or not, most urgent first. */
    public void onDamaged(AiContext context, float damage) {
        for (Behavior behavior : byPriority) {
            behavior.onDamaged(context, damage);
        }
    }

    /** Stops everything, e.g. when the mob dies or is removed. */
    public void stopAll(AiContext context) {
        AiContext outer = this.context;
        this.context = context;
        try {
            scheduler.stopAll(this);
        } finally {
            this.context = outer;
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
        int index = indexOf(behavior);
        return index >= 0 && scheduler.isRunning(index);
    }

    /** The running behaviours, in the order they started. */
    public List<Behavior> runningBehaviors() {
        int[] running = scheduler.running();
        List<Behavior> out = new ArrayList<>(running.length);
        for (int index : running) {
            out.add(behaviors.get(index));
        }
        return List.copyOf(out);
    }

    private Behavior leader() {
        int index = scheduler.leader();
        return index < 0 ? null : behaviors.get(index);
    }

    private int indexOf(Behavior behavior) {
        for (int i = 0; i < behaviors.size(); i++) {
            if (behaviors.get(i) == behavior) {
                return i;
            }
        }
        return -1;
    }

    // ---- the scheduler's questions (BehaviorHost) --------------------------------------------------

    @Override
    public boolean shouldContinue(int index) {
        return behaviors.get(index).shouldContinue(context);
    }

    @Override
    public boolean canStart(int index) {
        return behaviors.get(index).canStart(context);
    }

    @Override
    public void start(int index) {
        behaviors.get(index).start(context);
    }

    @Override
    public void stop(int index) {
        behaviors.get(index).stop(context);
    }

    @Override
    public void tick(int index, float deltaTime) {
        behaviors.get(index).tick(context, deltaTime);
    }

    @Override
    public float random() {
        return context.random().nextFloat();
    }
}
