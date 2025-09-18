package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Handles all sound-related functionality for the player.
 * This module manages walking sounds, timing, and block-based sound selection.
 */
public class PlayerSounds {
    // Walking sound system
    private float walkingSoundTimer;
    private boolean wasMovingLastFrame;
    private static final float WALKING_SOUND_INTERVAL = 0.3f;

    private final World world;

    public PlayerSounds(World world) {
        this.world = world;
        this.walkingSoundTimer = 0.0f;
        this.wasMovingLastFrame = false;
    }

    /**
     * Updates walking sounds based on player movement.
     * Should be called every frame during player update.
     */
    public void updateWalkingSounds(Vector3f position, Vector3f velocity, boolean onGround, boolean physicallyInWater) {
        // Calculate horizontal movement speed
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean isMoving = horizontalSpeed > 1.0f && onGround && !physicallyInWater;

        if (isMoving) {
            // If player just started moving, play sound immediately
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
            // Reset timer when not moving
            walkingSoundTimer = 0.0f;
        }

        wasMovingLastFrame = isMoving;
    }

    /**
     * Plays the appropriate walking sound based on the block type under the player.
     */
    private void playWalkingSound(Vector3f position) {
        // Check what block type the player is standing on
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - 0.1f); // Slightly below feet to get ground block
        int blockZ = (int) Math.floor(position.z);

        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        // Play appropriate walking sound based on block type
        SoundSystem soundSystem = Game.getSoundSystem();
        if (soundSystem != null) {
            if (groundBlock == BlockType.GRASS) {
                soundSystem.playSoundWithVariation("grasswalk", 0.3f);
            } else if (groundBlock == BlockType.SAND || groundBlock == BlockType.RED_SAND) {
                soundSystem.playSoundWithVariation("sandwalk", 0.3f);
            } else if (groundBlock == BlockType.WOOD || groundBlock == BlockType.WOOD_PLANKS ||
                       groundBlock == BlockType.PINE_WOOD_PLANKS || groundBlock == BlockType.ELM_WOOD_LOG ||
                       groundBlock == BlockType.ELM_WOOD_PLANKS) {
                soundSystem.playSoundWithVariation("woodwalk", 0.3f);
            }
        }
    }

    /**
     * Resets the walking sound state. Call when player is created or respawned.
     */
    public void reset() {
        walkingSoundTimer = 0.0f;
        wasMovingLastFrame = false;
    }
}