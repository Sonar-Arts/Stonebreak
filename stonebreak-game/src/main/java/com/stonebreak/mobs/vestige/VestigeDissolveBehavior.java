package com.stonebreak.mobs.vestige;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;

/**
 * Coming apart into smoke, once the Vestige finds itself outside the Shade: it stops, plays the
 * dissolve, and is gone. Nothing outranks it.
 */
final class VestigeDissolveBehavior extends VestigeBehavior {

    private float elapsed;

    VestigeDissolveBehavior(Vestige vestige) {
        super(vestige);
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean canStart(AiContext context) {
        return vestige.isDissolving();
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
        if (elapsed >= Vestige.DISSOLVE_SECONDS) {
            vestige.vanish();
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
