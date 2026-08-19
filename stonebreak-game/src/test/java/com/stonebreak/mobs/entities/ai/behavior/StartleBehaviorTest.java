package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The freeze-on-hit personality — {@link FleeBehavior}'s sibling at the same priority, so which
 * of the two a mob is given is the whole of its damage reaction. The contract: dormant until
 * damaged, armed for exactly its duration, planted in place while it runs (a startled mob that
 * keeps sliding reads as a physics bug), and re-armed cleanly by the next hit.
 */
class StartleBehaviorTest {

    private final StubMob mob = new StubMob(EntityType.CHICKEN, new Vector3f(0, 64, 0));
    private final AiContext context = new AiContext(
            mob, new PathAgent(mob, new Steering(mob, 360f, 0f, 0f)), new Random(1), PlayerLocator.NONE);

    @Test
    void dormantUntilDamaged() {
        StartleBehavior startle = new StartleBehavior(1.5f);

        assertFalse(startle.canStart(context));

        startle.onDamaged(context, 1f);
        assertTrue(startle.canStart(context));
    }

    @Test
    void theFreezeLastsExactlyItsDuration() {
        StartleBehavior startle = new StartleBehavior(1.0f);
        startle.onDamaged(context, 1f);
        startle.start(context);

        for (int i = 0; i < 19; i++) {
            startle.tick(context, 0.05f);
        }
        assertTrue(startle.shouldContinue(context), "one tick short of the duration");

        startle.tick(context, 0.05f);
        assertFalse(startle.shouldContinue(context), "and released the tick it runs out");
    }

    @Test
    void aFrozenMobIsPlantedEveryTick() {
        StartleBehavior startle = new StartleBehavior(1.0f);
        startle.onDamaged(context, 1f);
        startle.start(context);

        mob.setVelocity(new Vector3f(3f, 0f, -2f)); // knockback keeps pushing
        startle.tick(context, 0.05f);

        Vector3f velocity = mob.getVelocity();
        assertEquals(0f, velocity.x, 1e-5f, "the freeze must cancel horizontal push every tick");
        assertEquals(0f, velocity.z, 1e-5f);
    }

    @Test
    void aSecondHitReArmsTheFullFreeze() {
        StartleBehavior startle = new StartleBehavior(1.0f);
        startle.onDamaged(context, 1f);
        startle.start(context);
        startle.tick(context, 0.9f);

        startle.onDamaged(context, 1f); // hit again just before release
        startle.tick(context, 0.9f);

        assertTrue(startle.shouldContinue(context), "the timer restarts from the fresh hit");
    }

    @Test
    void aStartledMobAnimatesAsIdle() {
        assertEquals(MobBehaviorState.IDLE, new StartleBehavior(1f).animationState(),
                "freezing looks like standing, not walking");
    }
}
