package com.stonebreak.input;

import com.openmason.engine.input.KeyEdges;

import org.joml.Vector3f;

import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.core.render.Screenshots;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.diagnostics.SaveSystemDiagnostics;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;

/**
 * Function keys: F1 hide HUD, F2 screenshot, F3 debug overlay, F4 memory-leak analysis,
 * F5 perspective toggle (Shift+F5 memory profiling), F6 test-cow spawn,
 * F7 manual save, F8 save-system diagnostic.
 */
final class DebugKeyHandler {

    private static final float[] CHAT_YELLOW = {1.0f, 1.0f, 0.0f, 1.0f};
    private static final float[] CHAT_GREEN = {0.0f, 1.0f, 0.0f, 1.0f};
    private static final float[] CHAT_RED = {1.0f, 0.0f, 0.0f, 1.0f};

    private final KeyEdges keys;

    DebugKeyHandler(KeyEdges keys) {
        this.keys = keys;
    }

    void poll() {
        if (keys.pressedOnce(GLFW_KEY_F1)) {
            Game.toggleHudHidden();
        }

        if (keys.pressedOnce(GLFW_KEY_F2)) {
            Screenshots.request();
        }

        if (keys.pressedOnce(GLFW_KEY_F3)) {
            Game.toggleDebugOverlay();
        }

        if (keys.pressedOnce(GLFW_KEY_F4)) {
            System.out.println("[DEBUG] Manual memory leak analysis triggered by F4 key...");
            Game.triggerMemoryLeakAnalysis();
        }

        if (keys.pressedOnce(GLFW_KEY_F5)) {
            handlePerspectiveOrProfiling();
        }

        if (keys.pressedOnce(GLFW_KEY_F6)) {
            spawnTestCow();
        }

        if (keys.pressedOnce(GLFW_KEY_F7)) {
            triggerManualSave();
        }

        if (keys.pressedOnce(GLFW_KEY_F8)) {
            runSaveDiagnostics();
        }
    }

    private void handlePerspectiveOrProfiling() {
        boolean shiftHeld = keys.isDown(GLFW_KEY_LEFT_SHIFT) || keys.isDown(GLFW_KEY_RIGHT_SHIFT);
        if (shiftHeld) {
            System.out.println("[DEBUG] Detailed memory profiling triggered by Shift+F5...");
            MemoryProfiler profiler = MemoryProfiler.getInstance();
            profiler.takeSnapshot("manual_f5_" + System.currentTimeMillis());
            profiler.reportDetailedMemoryStats();
            Game.forceGCAndReport("Shift+F5 Manual GC");
        } else {
            Player player = Game.getPlayer();
            if (player != null) {
                player.togglePerspective();
            }
        }
    }

    private void spawnTestCow() {
        Player player = Game.getPlayer();
        EntityManager entityManager = Game.getEntityManager();
        if (player == null || entityManager == null) {
            return;
        }

        // Spawn 5 blocks in front of the player, snapped to ground level.
        org.joml.Vector3d playerPos = player.getPosition();
        Vector3f playerDir = player.getCamera().getFront();
        org.joml.Vector3d spawnPos = new org.joml.Vector3d(
            playerPos.x + playerDir.x * 5.0,
            playerPos.y,
            playerPos.z + playerDir.z * 5.0
        );

        long feetY = (long) Math.floor(playerPos.y);
        long groundY = feetY;
        long sx = (long) Math.floor(spawnPos.x), sz = (long) Math.floor(spawnPos.z);
        for (long y = feetY + 10; y >= feetY - 10; y--) {
            BlockType block = Game.getWorld().getBlockAt(sx, y, sz);
            if (block != null && block != BlockType.AIR) {
                groundY = y + 1;
                break;
            }
        }
        spawnPos.y = groundY;

        Entity cow = entityManager.spawnCowWithVariant(spawnPos, "angus");
        if (cow != null) {
            System.out.println("[DEBUG] Spawned test Angus cow with new cute face at " + spawnPos);
        } else {
            System.out.println("[DEBUG] Failed to spawn test Angus cow");
        }
    }

    private void triggerManualSave() {
        Game game = Game.getInstance();
        if (game == null) {
            System.err.println("[MANUAL-SAVE] Game instance is null");
            return;
        }

        SaveService saveService = game.getSaveService();
        ChatSystem chatSystem = game.getChatSystem();

        if (saveService == null) {
            System.err.println("[MANUAL-SAVE] Save system not available - cannot save");
            if (chatSystem != null) {
                chatSystem.addMessage("Save system not available", CHAT_RED);
            }
            return;
        }

        try {
            System.out.println("[MANUAL-SAVE] Starting manual save...");
            if (chatSystem != null) {
                chatSystem.addMessage("Saving world...", CHAT_YELLOW);
            }

            saveService.saveAll()
                .thenRun(() -> {
                    System.out.println("[MANUAL-SAVE] Manual save completed successfully");
                    if (chatSystem != null) {
                        chatSystem.addMessage("World saved successfully!", CHAT_GREEN);
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[MANUAL-SAVE] Manual save failed: " + throwable.getMessage());
                    if (chatSystem != null) {
                        chatSystem.addMessage("Save failed: " + throwable.getMessage(), CHAT_RED);
                    }
                    return null;
                });
        } catch (Exception e) {
            System.err.println("[MANUAL-SAVE] Error during manual save: " + e.getMessage());
            e.printStackTrace();
            if (chatSystem != null) {
                chatSystem.addMessage("Save error: " + e.getMessage(), CHAT_RED);
            }
        }
    }

    private void runSaveDiagnostics() {
        Game game = Game.getInstance();
        if (game == null) {
            return;
        }

        Player player = game.getPlayer();
        ChatSystem chatSystem = game.getChatSystem();
        if (player == null) {
            System.out.println("[F8-DIAGNOSTIC] Player is null");
            if (chatSystem != null) {
                chatSystem.addMessage("Diagnostic failed - no player", CHAT_RED);
            }
            return;
        }

        org.joml.Vector3d pos = player.getPosition();
        long worldX = (long) Math.floor(pos.x);
        long worldY = (long) Math.floor(pos.y);
        long worldZ = (long) Math.floor(pos.z);
        long chunkX = worldX >> 5;
        long chunkZ = worldZ >> 5;

        System.out.println("\n[F8-DIAGNOSTIC] Running save system diagnostic at player position...");
        System.out.println("[F8-DIAGNOSTIC] Player world pos: (" + worldX + ", " + worldY + ", " + worldZ + ")");
        System.out.println("[F8-DIAGNOSTIC] Cube column: (" + chunkX + ", " + chunkZ + "), cube y " + (worldY >> 5));

        if (chatSystem != null) {
            chatSystem.addMessage("Running save diagnostic...", CHAT_YELLOW);
        }

        SaveSystemDiagnostics.printDiagnostics();

        String worldName = "unknown";
        SaveService saveService = game.getSaveService();
        if (saveService != null) {
            String worldPath = saveService.getWorldPath();
            if (worldPath != null && !worldPath.isBlank()) {
                worldName = java.nio.file.Paths.get(worldPath).getFileName().toString();
            }
        }

        SaveSystemDiagnostics.diagnoseCubeColumn(worldName, worldX, worldZ);

        if (chatSystem != null) {
            chatSystem.addMessage("Diagnostic complete - check console", CHAT_GREEN);
        }
    }
}
