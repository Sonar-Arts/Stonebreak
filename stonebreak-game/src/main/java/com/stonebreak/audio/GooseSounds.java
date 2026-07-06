package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

/**
 * Handles walking/footstep audio for geese.
 *
 * <p>Mirrors {@link CowSounds}: footstep sounds are played at intervals while the goose
 * moves on the ground, with the clip chosen from the block underfoot and positioned in 3D.
 * Geese are lighter and quicker than cows, so the footstep cadence is a little faster.
 */
public class GooseSounds {
    // Walking sound system
    private float walkingSoundTimer;
    private boolean wasMovingLastFrame;
    private static final float WALKING_SOUND_INTERVAL = 0.7f; // Quicker, lighter steps than the cow
    private static final float MAX_SOUND_DISTANCE = 30.0f; // Maximum distance for goose sounds

    private final World world;

    public GooseSounds(World world) {
        this.world = world;
        this.walkingSoundTimer = 0.0f;
        this.wasMovingLastFrame = false;
    }

    /**
     * Updates goose sounds based on movement and ambient behavior.
     * Should be called every frame during goose update.
     */
    public void updateSounds(Vector3f position, Vector3f velocity, boolean onGround) {
        updateWalkingSounds(position, velocity, onGround);
    }

    /**
     * Updates walking sounds based on goose movement.
     */
    private void updateWalkingSounds(Vector3f position, Vector3f velocity, boolean onGround) {
        // Calculate horizontal movement speed
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        // Footsteps only make sense on the ground; airborne geese glide silently.
        boolean isMoving = onGround && horizontalSpeed > 0.5f;

        if (isMoving) {
            // If goose just started moving, play sound immediately
            if (!wasMovingLastFrame) {
                playWalkingSound(position);
                walkingSoundTimer = 0.0f;
            } else {
                walkingSoundTimer += Game.getDeltaTime();

                // Play walking sound at intervals
                if (walkingSoundTimer >= WALKING_SOUND_INTERVAL) {
                    playWalkingSound(position);
                    walkingSoundTimer = 0.0f;
                }
            }
        } else {
            // Only reset timer when actually stopped (not just brief off-ground moments)
            if (horizontalSpeed <= 0.1f) {
                walkingSoundTimer = 0.0f;
            }
        }

        wasMovingLastFrame = isMoving;
    }

    /**
     * Plays the appropriate walking sound based on the block type under the goose.
     */
    private void playWalkingSound(Vector3f position) {
        // Check distance to player first
        Player player = Game.getPlayer();
        if (player == null) {
            return; // No player to hear the sound
        }

        Vector3f playerPos = player.getPosition();
        float distance = position.distance(playerPos);

        // Only play sound if within hearing range
        if (distance > MAX_SOUND_DISTANCE) {
            return;
        }

        // Check what block type the goose is standing on
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - 1.0f); // Check one block below goose's feet
        int blockZ = (int) Math.floor(position.z);

        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        // Play appropriate walking sound based on block type using positional audio
        SoundSystem soundSystem = Game.getSoundSystem();
        if (soundSystem != null) {
            if (groundBlock == BlockType.GRASS) {
                soundSystem.playSoundAt3DWithVariation("grasswalk", 0.25f, position);
            } else if (groundBlock == BlockType.SAND || groundBlock == BlockType.RED_SAND) {
                soundSystem.playSoundAt3DWithVariation("sandwalk", 0.25f, position);
            } else if (groundBlock == BlockType.WOOD || groundBlock == BlockType.WOOD_PLANKS ||
                       groundBlock == BlockType.PINE_WOOD_PLANKS || groundBlock == BlockType.ELM_WOOD_LOG ||
                       groundBlock == BlockType.ELM_WOOD_PLANKS) {
                soundSystem.playSoundAt3DWithVariation("woodwalk", 0.25f, position);
            }
        }
    }

    /**
     * Resets the goose sound state. Call when the goose is spawned or respawned.
     */
    public void reset() {
        walkingSoundTimer = 0.0f;
        wasMovingLastFrame = false;
    }
}
