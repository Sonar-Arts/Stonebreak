package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Plays player footsteps when the body animation reports a foot contact.
 * The sample for the block
 * underfoot comes from that block's SBO {@code sounds[]} data via
 * {@link BlockSounds} — there is no hardcoded block→sample table.
 */
public class PlayerSounds {

    /** Gain scale applied to the ground block's authored step volume. */
    private static final float STEP_VOLUME = 0.3f;
    private final World world;

    public PlayerSounds(World world) {
        this.world = world;
    }

    /** Beyond this, another player's footstep is not worth the work (matches {@link MobSounds}). */
    private static final float MAX_AUDIBLE_DISTANCE = 30.0f;

    /**
     * Another player's footstep (issue #344): positional 3D at their feet, at the local player's
     * step volume. Played by their shadow on its own foot contacts, so it needs no packet.
     */
    public static void playStepAt(World world, Vector3f feet) {
        if (world == null) return;
        com.stonebreak.player.Player listener = com.stonebreak.core.Game.getPlayer();
        if (listener != null && listener.getPosition().distance(feet) > MAX_AUDIBLE_DISTANCE) return;
        BlockType groundBlock = world.getBlockAt(
                (int) Math.floor(feet.x), (int) Math.floor(feet.y - 0.1f), (int) Math.floor(feet.z));
        BlockSounds.playStepAt(groundBlock, feet, STEP_VOLUME);
    }

    /**
     * Plays the appropriate walking sound based on the block type under the player.
     */
    public void playFootstep(Vector3f position) {
        // Check what block type the player is standing on
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - 0.1f); // Slightly below feet to get ground block
        int blockZ = (int) Math.floor(position.z);

        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        // The block's SBO declares its own step sound (or none = silent).
        BlockSounds.playStepLocal(groundBlock, STEP_VOLUME);
    }

}
