package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;

/**
 * Game-side debug utilities for verifying 3D positional audio relative to the player.
 *
 * <p>These live in the game module (not the engine {@link SoundSystem}) because they resolve
 * positions from the player/world. They are driven by the {@code /test_3d_*} chat commands.
 */
public final class Audio3DTest {

    private Audio3DTest() {
    }

    /**
     * Plays the "blockpickup" sound at several distances from the player to verify distance
     * attenuation. Blocks between sounds, so call from a background thread.
     */
    public static void test3DAudio() {
        SoundSystem soundSystem = Game.getSoundSystem();
        if (soundSystem == null || !soundSystem.isSoundLoaded("blockpickup")) {
            System.err.println("Cannot test 3D audio - blockpickup sound not loaded");
            return;
        }

        Player player = Game.getPlayer();
        if (player == null) {
            System.err.println("Cannot test 3D audio - player not found");
            return;
        }

        org.joml.Vector3f playerPos = player.getPosition();
        System.out.println("🧪 3D AUDIO TEST: Player position: (" + playerPos.x + ", " + playerPos.y + ", " + playerPos.z + ")");

        // Test sounds at various distances
        float[][] testPositions = {
            {playerPos.x + 2.0f, playerPos.y, playerPos.z},     // 2 blocks away (should be loud)
            {playerPos.x + 10.0f, playerPos.y, playerPos.z},    // 10 blocks away (should be quieter)
            {playerPos.x + 25.0f, playerPos.y, playerPos.z},    // 25 blocks away (should be very quiet)
            {playerPos.x + 60.0f, playerPos.y, playerPos.z}     // 60 blocks away (should be silent)
        };

        String[] descriptions = {
            "2 blocks (should be loud)",
            "10 blocks (should be quieter)",
            "25 blocks (should be very quiet)",
            "60 blocks (should be silent)"
        };

        for (int i = 0; i < testPositions.length; i++) {
            float[] pos = testPositions[i];
            System.out.println("🧪 3D AUDIO TEST: Playing sound at " + descriptions[i] + " - position (" + pos[0] + ", " + pos[1] + ", " + pos[2] + ")");

            // Calculate distance for reference
            float distance = (float) Math.sqrt(
                Math.pow(pos[0] - playerPos.x, 2) +
                Math.pow(pos[1] - playerPos.y, 2) +
                Math.pow(pos[2] - playerPos.z, 2)
            );
            System.out.println("🧪 3D AUDIO TEST: Calculated distance: " + distance + " blocks");

            soundSystem.playSoundAt3D("blockpickup", 0.8f, pos[0], pos[1], pos[2]);

            // Wait a bit between sounds
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("🧪 3D AUDIO TEST: Test completed. If 3D audio is working, you should have heard:");
        System.out.println("  - Loud sound at 2 blocks");
        System.out.println("  - Quieter sound at 10 blocks");
        System.out.println("  - Very quiet sound at 25 blocks");
        System.out.println("  - Silent or barely audible sound at 60 blocks");
    }

    /**
     * Simple immediate test of 3D audio at one distance from the player.
     */
    public static void testSingle3DAudio(float distance) {
        SoundSystem soundSystem = Game.getSoundSystem();
        if (soundSystem == null || !soundSystem.isSoundLoaded("blockpickup")) {
            System.err.println("Cannot test 3D audio - blockpickup sound not loaded");
            return;
        }

        Player player = Game.getPlayer();
        if (player == null) {
            System.err.println("Cannot test 3D audio - player not found");
            return;
        }

        org.joml.Vector3f playerPos = player.getPosition();
        float testX = playerPos.x + distance;
        float testY = playerPos.y;
        float testZ = playerPos.z;

        System.out.println("🧪 SINGLE 3D TEST: Player at (" + playerPos.x + ", " + playerPos.y + ", " + playerPos.z + ")");
        System.out.println("🧪 SINGLE 3D TEST: Playing sound " + distance + " blocks away at (" + testX + ", " + testY + ", " + testZ + ")");

        soundSystem.playSoundAt3D("blockpickup", 0.8f, testX, testY, testZ);
    }
}
