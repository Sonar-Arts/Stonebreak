package com.stonebreak.player.animation;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerCharacter;
import org.joml.Vector3dc;

/**
 * What a player body's clip notifies do in the world (#457), the same for the local player, remote players and
 * decoys: {@link PlayerAnimationSet#FOOTSTEP} is the caller's step sound (the sample comes from the block underfoot);
 * every other notify name plays the player asset's FX and sound event of that name ({@code splash}, {@code ripple},
 * ... - the SBE's own {@code fx[]} / sound bindings), at the body's feet.
 */
public final class PlayerAnimEvents {

    private PlayerAnimEvents() {
    }

    /** Plays the player asset's FX and sound bound to {@code event} at {@code feet}. */
    public static void assetEvent(String event, Vector3dc feet) {
        com.stonebreak.fx.bindings.EntityFx.play(PlayerCharacter.ASSET_ID, event, feet.x(), feet.y(), feet.z(), 1f);
        com.stonebreak.audio.EntitySounds.playAt(EntityType.REMOTE_PLAYER, event, feet.x(), feet.y(), feet.z());
    }
}
