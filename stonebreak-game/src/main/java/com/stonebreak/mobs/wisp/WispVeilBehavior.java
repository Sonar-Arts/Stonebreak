package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Invisibility: out of the victim's sight, the Wisp draws its veil and moves — usually round
 * behind them, setting up a Woosh. Only its eyes still faintly glow. It takes the chance at once
 * after a Peek-a-boo has the victim staring at the wrong tree, and now and then otherwise. If the
 * victim is tracking it, there is no Woosh to set up: it flanks them from well off instead.
 */
final class WispVeilBehavior extends WispBehavior {

    /** Chance per second of veiling without a perfect moment, while everything else allows it. */
    static final float IDLE_CHANCE_PER_SECOND = 0.04f;
    static final float MAX_RANGE = 45f;
    static final float MOVE_SPEED = 1.4f;
    static final float SEARCH_RADIUS = 16f;
    /** How near behind the victim it tries to end up. */
    static final float BAND_MIN = 7f;
    static final float BAND_MAX = 13f;

    private Vector3f goal;
    private float elapsed;

    WispVeilBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public boolean canStart(AiContext context) {
        WispSenses senses = senses();
        if (mind().mode() != WispMind.Mode.STALK || !mind().veilReady() || wisp.isVeiled()
                || !senses.hasVictim() || senses.inView() || senses.distance() > MAX_RANGE) {
            return false;
        }
        return mind().perfectMoment()
                || context.random().nextFloat() < IDLE_CHANCE_PER_SECOND * context.deltaTime();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return wisp.isVeiled() && mind().mode() == WispMind.Mode.STALK && senses().hasVictim()
                && (goal == null || elapsed < 1f || !tripOver(context));
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        wisp.veil(tuning().veilSeconds());
        mind().frighten(tuning().veilGain());
        mind().usedVeil();
        WispSenses senses = senses();
        goal = tracked() ? flankSpot(SEARCH_RADIUS)
                : wisp.cover().find(wisp.getPosition(), SEARCH_RADIUS, senses.victimEye(), senses.victimLook(),
                        BAND_MIN, BAND_MAX, CoverFinder.Preference.BEHIND_VICTIM);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        if (goal != null) {
            goTo(context, goal, 1.0f, MOVE_SPEED);
        } else {
            holdAndWatch(context);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return goal != null ? MobBehaviorState.WANDERING : MobBehaviorState.IDLE;
    }

    @Override
    public String debugName() {
        return "Veil";
    }
}
