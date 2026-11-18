package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3dc;
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
    public static void playStepAt(World world, Vector3dc feet) {
        playStepAt(world, feet.x(), feet.y(), feet.z());
    }

    /** {@link #playStepAt(World, Vector3dc)} at a world position. */
    public static void playStepAt(World world, double x, double y, double z) {
        if (world == null) return;
        com.stonebreak.player.Player listener = com.stonebreak.core.Game.getPlayer();
        if (listener != null && listener.getPosition().distance(x, y, z) > MAX_AUDIBLE_DISTANCE) return;
        BlockType groundBlock = world.getBlockAt(
                (long) Math.floor(x), (long) Math.floor(y - 0.1), (long) Math.floor(z));
        BlockSounds.playStepAt(groundBlock, x, y, z, STEP_VOLUME);
        com.stonebreak.fx.bindings.BlockFx.stepAt(groundBlock, x, y, z);
    }

    /**
     * Plays the appropriate walking sound based on the block type under the player.
     */
    public void playFootstep(Vector3dc position) {
        // Check what block type the player is standing on
        long blockX = (long) Math.floor(position.x());
        long blockY = (long) Math.floor(position.y() - 0.1); // Slightly below feet to get ground block
        long blockZ = (long) Math.floor(position.z());

        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        // The block's SBO declares its own step sound (or none = silent).
        BlockSounds.playStepLocal(groundBlock, STEP_VOLUME);
        com.stonebreak.fx.bindings.BlockFx.stepAt(groundBlock, position.x(), position.y(), position.z());
    }

}
