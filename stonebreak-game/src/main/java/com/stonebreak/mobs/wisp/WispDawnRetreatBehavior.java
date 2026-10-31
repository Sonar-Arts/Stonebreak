package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Dawn: the fog lifts and the Wisp goes, whatever it was doing — veiled, away from its victim, and
 * out of the world as soon as nobody is looking at where it was.
 */
final class WispDawnRetreatBehavior extends WispBehavior {

    /** It stays veiled at least this long before it may slip away, so it is seen to leave. */
    static final float MIN_RETREAT_SECONDS = 2f;
    /** Gone by now whether anyone is looking or not. */
    static final float MAX_RETREAT_SECONDS = 20f;
    private static final float RETREAT_SPEED = 1.5f;

    private float elapsed;
    private Vector3f goal;

    WispDawnRetreatBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public boolean canStart(AiContext context) {
        World world = context.world();
        return !wisp.isDissolving() && world != null && world.getNightFog() < tuning().dawnFog();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return !wisp.isDissolving();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        wisp.veil(MAX_RETREAT_SECONDS);
        goal = senses().hasVictim() ? escapeSpot(20f, 40f) : null;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        if (!senses().hasVictim()
                || elapsed >= MAX_RETREAT_SECONDS
                || (elapsed >= MIN_RETREAT_SECONDS && !senses().inView() && !senses().watched())) {
            wisp.vanish();
            return;
        }
        if (goal != null) {
            goTo(context, goal, 1.5f, RETREAT_SPEED);
        } else {
            context.steering().stopMoving();
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "DawnRetreat";
    }
}
