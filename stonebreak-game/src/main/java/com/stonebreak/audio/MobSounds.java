package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Footstep audio for mobs. Replaces the per-mob sound classes that each
 * carried an identical hardcoded block→sample switch: the sample now comes
 * from the ground block's SBO {@code sounds[]} data ({@link BlockSounds}),
 * while the per-mob <em>personality</em> — step cadence, loudness, whether
 * airborne moments pause the steps — stays a constructor parameter.
 */
public class MobSounds {

    private static final float MAX_SOUND_DISTANCE = 30.0f;

    private final World world;
    private final float stepInterval;
    private final float stepVolume;
    private final boolean requireGround;

    /**
     * Seconds since the last played step. Accumulates continuously (moving or
     * not) and gates every play at {@link #stepInterval}, so on/off flicker in
     * the moving state (hop-prone mobs briefly leaving the ground each waddle)
     * can never fire steps faster than the mob's cadence. Primed to the
     * interval so a mob that starts moving after a real pause steps at once.
     */
    private float timeSinceLastStep;

    /**
     * @param stepInterval  seconds between footsteps while moving
     * @param stepVolume    gain scale applied to the block's authored step volume
     * @param requireGround true to silence steps while airborne (light,
     *                      hop-prone mobs); false to tolerate brief off-ground
     *                      moments (heavy, plodding mobs)
     */
    public MobSounds(World world, float stepInterval, float stepVolume, boolean requireGround) {
        this.world = world;
        this.stepInterval = stepInterval;
        this.stepVolume = stepVolume;
        this.requireGround = requireGround;
        this.timeSinceLastStep = stepInterval;
    }

    /**
     * Updates footstep sounds based on movement. Call every frame during the
     * mob's update.
     */
    public void updateSounds(Vector3f position, Vector3f velocity, boolean onGround) {
        timeSinceLastStep += Game.getDeltaTime();

        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean isMoving = horizontalSpeed > 0.5f && (!requireGround || onGround);

        if (isMoving && timeSinceLastStep >= stepInterval) {
            playWalkingSound(position);
            timeSinceLastStep = 0.0f;
        }
    }

    private void playWalkingSound(Vector3f position) {
        Player player = Game.getPlayer();
        if (player == null) return;
        if (position.distance(player.getPosition()) > MAX_SOUND_DISTANCE) return;

        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - 1.0f); // block below the mob's feet
        int blockZ = (int) Math.floor(position.z);
        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        BlockSounds.playStepAt(groundBlock, position, stepVolume);
    }

    /** Resets the footstep state. Call when the mob is spawned or respawned. */
    public void reset() {
        timeSinceLastStep = stepInterval;
    }
}
