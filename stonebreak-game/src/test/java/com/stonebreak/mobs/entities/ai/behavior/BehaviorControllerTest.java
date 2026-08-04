package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduler's rules, tested with stub behaviours so nothing here depends on a mob, a world or
 * navigation.
 *
 * <p>What matters is that urgency wins, that behaviours needing different things can share a tick,
 * that equal alternatives are chosen by weight, and that start/stop always pair up — a behaviour
 * that is interrupted without {@code stop()} leaks whatever it was holding.
 */
class BehaviorControllerTest {

    private final AiContext context = new AiContext(null, null, new Random(1), PlayerLocator.NONE);

    @Test
    void runsTheOnlyEligibleBehaviour() {
        StubBehavior idle = new StubBehavior("idle", 100);
        BehaviorController controller = new BehaviorController(List.of(idle));

        controller.tick(context, 0.05f);

        assertTrue(controller.isRunning(idle));
        assertEquals(1, idle.starts);
        assertEquals(1, idle.ticks);
    }

    @Test
    void urgentBehaviourInterruptsTheRunningOne() {
        StubBehavior ambient = new StubBehavior("ambient", 100);
        StubBehavior urgent = new StubBehavior("urgent", 10).startable(false);
        BehaviorController controller = new BehaviorController(List.of(ambient, urgent));

        controller.tick(context, 0.05f);
        assertTrue(controller.isRunning(ambient));

        urgent.startable(true);
        controller.tick(context, 0.05f);

        assertTrue(controller.isRunning(urgent));
        assertFalse(controller.isRunning(ambient), "the ambient behaviour must be displaced");
        assertEquals(1, ambient.stops, "and told it was displaced, so it can clean up");
    }

    @Test
    void aLessUrgentBehaviourWaitsItsTurn() {
        StubBehavior urgent = new StubBehavior("urgent", 10);
        StubBehavior ambient = new StubBehavior("ambient", 100);
        BehaviorController controller = new BehaviorController(List.of(ambient, urgent));

        controller.tick(context, 0.05f);

        assertTrue(controller.isRunning(urgent));
        assertFalse(controller.isRunning(ambient));
        assertEquals(0, ambient.starts);
    }

    @Test
    void behavioursNeedingDifferentThingsShareATick() {
        StubBehavior walking = new StubBehavior("walk", 50, EnumSet.of(Behavior.Flag.MOVE));
        StubBehavior looking = new StubBehavior("look", 50, EnumSet.of(Behavior.Flag.LOOK));
        BehaviorController controller = new BehaviorController(List.of(walking, looking));

        controller.tick(context, 0.05f);

        assertTrue(controller.isRunning(walking));
        assertTrue(controller.isRunning(looking));
        assertEquals(2, controller.runningBehaviors().size());
    }

    @Test
    void aFinishedBehaviourIsStoppedAndReplaced() {
        StubBehavior brief = new StubBehavior("brief", 50);
        StubBehavior ambient = new StubBehavior("ambient", 100);
        BehaviorController controller = new BehaviorController(List.of(brief, ambient));

        controller.tick(context, 0.05f);
        assertTrue(controller.isRunning(brief));

        brief.continuable(false).startable(false);
        controller.tick(context, 0.05f);

        assertEquals(1, brief.stops);
        assertTrue(controller.isRunning(ambient), "the next-best behaviour takes over the same tick");
    }

    @Test
    void equalPriorityAlternativesAreChosenByWeight() {
        // 9:1 odds over many independent choices: the common one must dominate, and the rare one
        // must still turn up — the point of weights is that neither starves.
        // One stream of randomness across the trials: a fresh Random per iteration would be seeded
        // from consecutive values, whose first draws are famously correlated.
        AiContext seeded = new AiContext(null, null, new Random(20260803L), PlayerLocator.NONE);
        int common = 0;
        int rare = 0;
        for (int trial = 0; trial < 200; trial++) {
            StubBehavior heavy = new StubBehavior("heavy", 100).withWeight(9.0f);
            StubBehavior light = new StubBehavior("light", 100).withWeight(1.0f);
            BehaviorController controller = new BehaviorController(List.of(heavy, light));

            controller.tick(seeded, 0.05f);
            if (controller.isRunning(heavy)) {
                common++;
            } else {
                rare++;
            }
        }
        assertTrue(common > rare * 3, "the heavier option should dominate, got " + common + ":" + rare);
        assertTrue(rare > 0, "the lighter option should still happen sometimes");
    }

    @Test
    void damageReachesEveryBehaviourIncludingIdleOnes() {
        StubBehavior running = new StubBehavior("running", 100);
        StubBehavior dormant = new StubBehavior("dormant", 20).startable(false);
        BehaviorController controller = new BehaviorController(List.of(running, dormant));
        controller.tick(context, 0.05f);

        controller.onDamaged(context, 3.0f);

        assertEquals(3.0f, running.lastDamage, 0.001f);
        assertEquals(3.0f, dormant.lastDamage, 0.001f,
                "the behaviour that reacts to a hit is never the one already running");
    }

    @Test
    void animationStateComesFromTheMostUrgentRunningBehaviour() {
        StubBehavior ambient = new StubBehavior("ambient", 100).withAnimation(MobBehaviorState.GRAZING);
        StubBehavior urgent = new StubBehavior("urgent", 10, EnumSet.of(Behavior.Flag.LOOK))
                .withAnimation(MobBehaviorState.WANDERING);
        BehaviorController controller = new BehaviorController(List.of(ambient, urgent));

        controller.tick(context, 0.05f);

        assertTrue(controller.isRunning(ambient), "both run — they need different things");
        assertEquals(MobBehaviorState.WANDERING, controller.animationState());
        assertEquals("urgent", controller.activeName());
    }

    @Test
    void nothingRunningReadsAsIdle() {
        BehaviorController controller = new BehaviorController(List.of(
                new StubBehavior("never", 100).startable(false)));

        controller.tick(context, 0.05f);

        assertEquals(MobBehaviorState.IDLE, controller.animationState());
        assertEquals("none", controller.activeName());
    }

    @Test
    void stopAllStopsEverythingItStarted() {
        StubBehavior a = new StubBehavior("a", 50, EnumSet.of(Behavior.Flag.MOVE));
        StubBehavior b = new StubBehavior("b", 50, EnumSet.of(Behavior.Flag.LOOK));
        BehaviorController controller = new BehaviorController(List.of(a, b));
        controller.tick(context, 0.05f);

        controller.stopAll(context);

        assertEquals(1, a.stops);
        assertEquals(1, b.stops);
        assertTrue(controller.runningBehaviors().isEmpty());
    }

    /** A behaviour whose eligibility and lifecycle a test drives directly. */
    private static final class StubBehavior implements Behavior {
        private final String name;
        private final int priority;
        private final EnumSet<Flag> flags;

        private boolean startable = true;
        private boolean continuable = true;
        private float weight = 1.0f;
        private MobBehaviorState animation = MobBehaviorState.IDLE;

        int starts;
        int stops;
        int ticks;
        float lastDamage;

        StubBehavior(String name, int priority) {
            this(name, priority, EnumSet.of(Flag.MOVE));
        }

        StubBehavior(String name, int priority, EnumSet<Flag> flags) {
            this.name = name;
            this.priority = priority;
            this.flags = flags;
        }

        StubBehavior startable(boolean value) {
            this.startable = value;
            return this;
        }

        StubBehavior continuable(boolean value) {
            this.continuable = value;
            return this;
        }

        StubBehavior withWeight(float value) {
            this.weight = value;
            return this;
        }

        StubBehavior withAnimation(MobBehaviorState value) {
            this.animation = value;
            return this;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public float weight() {
            return weight;
        }

        @Override
        public EnumSet<Flag> flags() {
            return flags;
        }

        @Override
        public boolean canStart(AiContext context) {
            return startable;
        }

        @Override
        public boolean shouldContinue(AiContext context) {
            return continuable;
        }

        @Override
        public void start(AiContext context) {
            starts++;
        }

        @Override
        public void tick(AiContext context, float deltaTime) {
            ticks++;
        }

        @Override
        public void stop(AiContext context) {
            stops++;
        }

        @Override
        public void onDamaged(AiContext context, float damage) {
            lastDamage = damage;
        }

        @Override
        public MobBehaviorState animationState() {
            return animation;
        }

        @Override
        public String debugName() {
            return name;
        }
    }
}
