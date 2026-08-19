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
 * The ambient idle/graze behaviour — one class configured twice, per its own design note. The
 * rules pinned: a bout lasts a duration drawn from its configured window, velocity is re-zeroed
 * every tick (water flow and knockback keep pushing a mob that has stopped deciding to move),
 * and a mob afloat in deep water refuses to idle at all — that refusal is what leaves wandering
 * as its only choice and sends it looking for a bank instead of treading water forever.
 */
class StandStillBehaviorTest {

    private final StubMob mob = new StubMob(EntityType.COW, new Vector3f(0, 64, 0));
    private final AiContext context = new AiContext(
            mob, new PathAgent(mob, new Steering(mob, 360f, 0f, 0f)), new Random(7), PlayerLocator.NONE);

    @Test
    void aBoutLastsSomewhereInItsConfiguredWindow() {
        StandStillBehavior idle = StandStillBehavior.idle(1f, 2f, 4f);
        idle.start(context);

        idle.tick(context, 1.9f);
        assertTrue(idle.shouldContinue(context), "no bout may end before the minimum");

        idle.tick(context, 2.2f); // past the maximum in total
        assertFalse(idle.shouldContinue(context), "no bout may outlive the maximum");
    }

    @Test
    void anIdlingMobIsReplantedEveryTick() {
        StandStillBehavior idle = StandStillBehavior.idle(1f, 2f, 4f);
        idle.start(context);

        mob.setVelocity(new Vector3f(1.5f, 0f, 2.5f)); // current keeps pushing
        idle.tick(context, 0.05f);

        Vector3f velocity = mob.getVelocity();
        assertEquals(0f, velocity.x, 1e-5f);
        assertEquals(0f, velocity.z, 1e-5f);
    }

    @Test
    void aMobAfloatInDeepWaterRefusesToIdle() {
        mob.setOnGround(false);
        mob.setSubmersion(0.8f); // deep enough that isInWater() follows

        assertFalse(StandStillBehavior.idle(1f, 2f, 4f).canStart(context),
                "treading water forever is exactly what this refusal prevents");
    }

    @Test
    void wadingInTheShallowsStillCountsAsStanding() {
        mob.setOnGround(true);
        mob.setSubmersion(0.3f);

        assertTrue(StandStillBehavior.idle(1f, 2f, 4f).canStart(context));
    }

    @Test
    void zeroWeightMeansNeverChosen() {
        assertFalse(StandStillBehavior.idle(0f, 2f, 4f).canStart(context));
    }

    @Test
    void idleAndGrazeAreTheSameBehaviourWithDifferentFaces() {
        StandStillBehavior idle = StandStillBehavior.idle(1f, 2f, 4f);
        StandStillBehavior graze = StandStillBehavior.graze(1f, 2f, 4f);

        assertEquals(MobBehaviorState.IDLE, idle.animationState());
        assertEquals(MobBehaviorState.GRAZING, graze.animationState());
        assertEquals("Idle", idle.debugName());
        assertEquals("Graze", graze.debugName());
        assertEquals(idle.priority(), graze.priority(), "ambient behaviours compete on weight, not priority");
    }
}
