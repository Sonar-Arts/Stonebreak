package com.stonebreak.mobs.vestige;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Invisibility: out of the victim's sight, the Vestige draws its veil and moves — usually round
 * behind them, setting up a Woosh. Only its eyes still faintly glow. It takes the chance at once
 * after a Peek-a-boo has the victim staring at the wrong tree, and now and then otherwise. If the
 * victim is tracking it, there is no Woosh to set up: it flanks them from well off instead.
 */
final class VestigeVeilBehavior extends VestigeBehavior {

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

    VestigeVeilBehavior(Vestige vestige) {
        super(vestige);
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public boolean canStart(AiContext context) {
        VestigeSenses senses = senses();
        if (mind().mode() != VestigeMind.Mode.STALK || !mind().veilReady() || vestige.isVeiled()
                || !senses.hasVictim() || senses.inView() || senses.distance() > MAX_RANGE) {
            return false;
        }
        return mind().perfectMoment()
                || context.random().nextFloat() < IDLE_CHANCE_PER_SECOND * context.deltaTime();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return vestige.isVeiled() && mind().mode() == VestigeMind.Mode.STALK && senses().hasVictim()
                && (goal == null || elapsed < 1f || !tripOver(context));
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        vestige.veil(tuning().veilSeconds());
        mind().frighten(tuning().veilGain());
        mind().usedVeil();
        VestigeSenses senses = senses();
        goal = tracked() ? flankSpot(SEARCH_RADIUS)
                : vestige.cover().find(vestige.getPosition(), SEARCH_RADIUS, senses.victimEye(), senses.victimLook(),
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
