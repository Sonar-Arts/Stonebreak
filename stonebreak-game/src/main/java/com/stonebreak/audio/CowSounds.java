package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Handles all sound-related functionality for cows.
 * This module manages walking sounds, mooing, and environmental audio interactions.
 */
public class CowSounds {
    // Walking sound system
    private float walkingSoundTimer;
    private boolean wasMovingLastFrame;
    private static final float WALKING_SOUND_INTERVAL = 0.4f;

    // Ambient mooing system
    // private float ambientMooTimer;
    // private static final float MIN_MOO_INTERVAL = 15.0f;
    // private static final float MAX_MOO_INTERVAL = 45.0f;
    // private float nextMooTime;

    private final World world;

    public CowSounds(World world) {
        this.world = world;
        this.walkingSoundTimer = 0.0f;
        this.wasMovingLastFrame = false;
        // this.ambientMooTimer = 0.0f;
        // this.nextMooTime = generateRandomMooTime();
    }

    /**
     * Updates cow sounds based on movement and ambient behavior.
     * Should be called every frame during cow update.
     */
    public void updateSounds(Vector3f position, Vector3f velocity, boolean onGround) {
        updateWalkingSounds(position, velocity, onGround);
        // updateAmbientSounds(position);
    }

    /**
     * Updates walking sounds based on cow movement.
     */
    private void updateWalkingSounds(Vector3f position, Vector3f velocity, boolean onGround) {
        // Calculate horizontal movement speed
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean isMoving = horizontalSpeed > 0.5f && onGround;

        if (isMoving) {
            // If cow just started moving, play sound immediately
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
     * Updates ambient mooing sounds.
     */
    // private void updateAmbientSounds(Vector3f position) {
    //     ambientMooTimer += Game.getDeltaTime();
    //
    //     if (ambientMooTimer >= nextMooTime) {
    //         playMooSound(position);
    //         ambientMooTimer = 0.0f;
    //         nextMooTime = generateRandomMooTime();
    //     }
    // }

    /**
     * Plays the appropriate walking sound based on the block type under the cow.
     */
    private void playWalkingSound(Vector3f position) {
        // Check what block type the cow is standing on
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - 0.1f); // Slightly below feet to get ground block
        int blockZ = (int) Math.floor(position.z);

        BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);

        // Play appropriate walking sound based on block type
        SoundSystem soundSystem = Game.getSoundSystem();
        if (soundSystem != null) {
            if (groundBlock == BlockType.GRASS) {
                soundSystem.playSoundWithVariation("cow_grasswalk", 0.2f);
            } else if (groundBlock == BlockType.SAND || groundBlock == BlockType.RED_SAND) {
                soundSystem.playSoundWithVariation("cow_sandwalk", 0.2f);
            } else {
                // Default walking sound for other block types
                soundSystem.playSoundWithVariation("cow_walk", 0.2f);
            }
        }
    }

    /**
     * Plays a moo sound with variation.
     */
    // private void playMooSound(Vector3f position) {
    //     SoundSystem soundSystem = Game.getSoundSystem();
    //     if (soundSystem != null) {
    //         soundSystem.playSoundWithVariation("cow_moo", 0.4f);
    //     }
    // }

    /**
     * Plays an interaction moo sound when the cow is petted or milked.
     */
    // public void playInteractionMoo(Vector3f position) {
    //     SoundSystem soundSystem = Game.getSoundSystem();
    //     if (soundSystem != null) {
    //         soundSystem.playSoundWithVariation("cow_moo_happy", 0.5f);
    //     }
    // }

    /**
     * Plays a hurt sound when the cow takes damage.
     */
    // public void playHurtSound(Vector3f position) {
    //     SoundSystem soundSystem = Game.getSoundSystem();
    //     if (soundSystem != null) {
    //         soundSystem.playSoundWithVariation("cow_hurt", 0.6f);
    //     }
    // }

    /**
     * Generates a random time for the next ambient moo.
     */
    // private float generateRandomMooTime() {
    //     return MIN_MOO_INTERVAL + (float) Math.random() * (MAX_MOO_INTERVAL - MIN_MOO_INTERVAL);
    // }

    /**
     * Resets the cow sound state. Call when cow is spawned or respawned.
     */
    public void reset() {
        walkingSoundTimer = 0.0f;
        wasMovingLastFrame = false;
        // ambientMooTimer = 0.0f;
        // nextMooTime = generateRandomMooTime();
    }
}