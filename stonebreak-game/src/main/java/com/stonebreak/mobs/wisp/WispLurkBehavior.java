package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import org.joml.Vector3f;

/**
 * What a stalking Wisp does between tricks: drifts silently from tree to tree in the thick of the
 * forest, keeping out of its victim's sight at a middling distance, watching them. If it is seen it
 * moves on to other cover. When its victim leaves the Shade it waits at the treeline, watching. While
 * they are tracking it, it keeps well off and on their flank.
 */
final class WispLurkBehavior extends WispBehavior {

    static final float SEARCH_RADIUS = 24f;
    static final float MOVE_SPEED = 0.9f;
    /** Least time between choosing new cover. */
    static final float REPLAN_SECONDS = 1.5f;
    /** Longest it stays at one tree, unseen and content, before drifting to another. */
    static final float SETTLE_SECONDS = 12f;

    private Vector3f goal;
    private boolean moving;
    private float replanAt;
    private float settledAt;

    WispLurkBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 7;
    }

    @Override
    public boolean canStart(AiContext context) {
        return mind().mode() == WispMind.Mode.STALK && !wisp.isDissolving();
    }

    @Override
    public void start(AiContext context) {
        goal = null;
        moving = false;
        replanAt = 0f;
        settledAt = wisp.getAge();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        WispSenses senses = senses();
        if (!senses.hasVictim()) {
            context.nav().stop();
            moving = false;
            return;
        }
        float age = wisp.getAge();
        boolean tracked = tracked();
        float bandMin = tracked ? tuning().trackedBandMin() : tuning().lurkBandMin();
        float bandMax = tracked ? tuning().trackedBandMax() : tuning().lurkBandMax();
        boolean outOfBand = senses.distance() < bandMin || senses.distance() > bandMax;
        boolean restless = !moving && age - settledAt >= SETTLE_SECONDS;
        if (age >= replanAt && (goal == null || senses.inView() || (!moving && outOfBand) || restless)) {
            replanAt = age + REPLAN_SECONDS;
            Vector3f next = tracked ? flankSpot(SEARCH_RADIUS)
                    : wisp.cover().find(wisp.getPosition(), SEARCH_RADIUS, senses.victimEye(),
                            senses.victimLook(), bandMin, bandMax, CoverFinder.Preference.FOREST);
            if (next != null && (goal == null || next.distance(goal) > 1.5f)) {
                goal = next;
                moving = true;
            }
        }
        if (moving && goal != null) {
            goTo(context, goal, 0.6f, MOVE_SPEED);
            if (tripOver(context) && context.nav().status() != PathAgent.Status.IDLE) {
                moving = false;
                settledAt = age;
                context.nav().stop();
            }
        } else {
            holdAndWatch(context);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
        moving = false;
    }

    @Override
    public MobBehaviorState animationState() {
        return moving ? MobBehaviorState.WANDERING : MobBehaviorState.IDLE;
    }

    @Override
    public String debugName() {
        return "Lurk";
    }
}
