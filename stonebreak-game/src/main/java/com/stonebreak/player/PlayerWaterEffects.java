package com.stonebreak.player;

import com.stonebreak.core.Game;
import com.stonebreak.fx.bindings.SwimFx;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.state.PhysicsState;
import org.joml.Vector3f;

/**
 * The local player's water entry splash and surface ripples (the player SBE's {@code splash}/{@code ripple} FX,
 * Stonebreak#441), advanced once per tick by {@link PlayerUpdatePipeline}.
 */
final class PlayerWaterEffects {

    private final SwimFx swimFx = new SwimFx();

    /**
     * @param eyesSubmerged true when the player's eyes are underwater (swimming state);
     *                      ripples are a surface effect and are suppressed while submerged.
     */
    void update(float dt, PhysicsState state, boolean eyesSubmerged) {
        org.joml.Vector3d feet = state.getPosition();
        if (state.justEnteredWaterThisFrame()) {
            swimFx.enteredWater(com.stonebreak.player.PlayerCharacter.ASSET_ID, PlayerConstants.PLAYER_HEIGHT, Game.getWorld(), feet,
                    Math.max(0f, -state.getVelocity().y));
        }
        Vector3f vel = state.getVelocity();
        float horizSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        swimFx.update(dt, com.stonebreak.player.PlayerCharacter.ASSET_ID, PlayerConstants.PLAYER_HEIGHT, Game.getWorld(), feet,
                state.isPhysicallyInWater(), eyesSubmerged, horizSpeed);
    }
}
