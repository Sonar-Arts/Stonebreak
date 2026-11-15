package com.stonebreak.mobs.entities.ai.behavior;

import org.joml.Vector3f;

/**
 * Closing in on something to fight: walk toward it until within a working distance, then plant
 * feet and keep facing it while the attack behaviour cuts in. Shared by every chase — the player
 * ({@link PursuePlayerBehavior}) and a goblin's prey alike. One per behaviour: it remembers whether
 * it is holding, so the path is stopped once rather than every tick.
 */
public final class CloseIn {

    private static final float TURN_SPEED = 360.0f;

    private boolean holding;

    /**
     * @param holdDistance stop closing in once this near, and just face the target
     * @return whether the mob is holding (in position) rather than still closing in
     */
    public boolean toward(AiContext context, Vector3f point, float holdDistance, float speedMultiplier) {
        if (context.distanceTo(point) > holdDistance) {
            holding = false;
            context.nav().moveTo(point, Math.max(1.0f, holdDistance * 0.8f), speedMultiplier);
        } else {
            if (!holding) {
                context.nav().stop();
                holding = true;
            }
            context.steering().stopMoving();
            context.facePoint(point, TURN_SPEED);
        }
        return holding;
    }

    public boolean holding() {
        return holding;
    }

    public void reset() {
        holding = false;
    }
}
