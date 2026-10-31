package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;

/**
 * Coming apart into smoke, once the Wisp finds itself outside the Shade: it stops, plays the
 * dissolve, and is gone. Nothing outranks it.
 */
final class WispDissolveBehavior extends WispBehavior {

    private float elapsed;

    WispDissolveBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean canStart(AiContext context) {
        return wisp.isDissolving();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return true;
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        context.steering().stopMoving();
        elapsed += deltaTime;
        if (elapsed >= Wisp.DISSOLVE_SECONDS) {
            wisp.vanish();
        }
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.DISSOLVE;
    }

    @Override
    public String debugName() {
        return "Dissolve";
    }
}
