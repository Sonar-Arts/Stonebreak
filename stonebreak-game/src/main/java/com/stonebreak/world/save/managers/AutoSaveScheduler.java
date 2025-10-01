package com.stonebreak.world.save.managers;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.core.PlayerState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector2f;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles automatic saving every 30 seconds with dirty chunk detection.
 * Single responsibility: schedule and manage auto-save operations.
 * Uses dependency inversion - depends on SaveOperations interface.
 */
public class AutoSaveScheduler implements AutoCloseable {

    private static final int AUTO_SAVE_INTERVAL_SECONDS = 30;

    private final WorldSaveManager saveManager;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> autoSaveTask;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicLong lastSaveTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong totalSaves = new AtomicLong(0);

    // References to game state (injected for dependency inversion)
    private volatile World world;
    private volatile Player player;
    private volatile WorldMetadata worldMetadata;

    public AutoSaveScheduler(WorldSaveManager saveManager) {
        this.saveManager = saveManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoSave-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the auto-save scheduler with game state references.
     */
    public void startAutoSave(World world, Player player, WorldMetadata worldMetadata) {
        if (isRunning.get()) {
            System.out.println("Auto-save is already running");
            return;
        }

        this.world = world;
        this.player = player;
        this.worldMetadata = worldMetadata;

        autoSaveTask = scheduler.scheduleAtFixedRate(
            this::performAutoSave,
            AUTO_SAVE_INTERVAL_SECONDS,
            AUTO_SAVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        isRunning.set(true);
        System.out.println("Auto-save started - will save every " + AUTO_SAVE_INTERVAL_SECONDS + " seconds");
    }

    /**
     * Stops the auto-save scheduler.
     */
    public void stopAutoSave() {
        if (!isRunning.get()) {
            return;
        }

        if (autoSaveTask != null) {
            autoSaveTask.cancel(false);
            autoSaveTask = null;
        }

        isRunning.set(false);
        System.out.println("Auto-save stopped");
    }

    /**
     * Performs a manual save immediately.
     */
    public void saveNow() {
        if (world == null || player == null || worldMetadata == null) {
            System.err.println("Cannot perform manual save - game state not initialized");
            return;
        }

        System.out.println("Performing manual save...");
        performAutoSave();
    }

    /**
     * Performs the actual auto-save operation.
     */
    private void performAutoSave() {
        try {
            if (world == null || player == null || worldMetadata == null) {
                return;
            }

            long startTime = System.currentTimeMillis();

            // Get dirty chunks from the world
            Collection<Chunk> dirtyChunks = getDirtyChunks(world);

            if (dirtyChunks.isEmpty()) {
                System.out.println("Auto-save skipped - no dirty chunks found");
                return;
            }

            // Create current player state
            PlayerState playerState = createPlayerState(player);

            // Update world metadata with current play time
            updateWorldMetadata(worldMetadata, startTime);

            // Save dirty chunks only (selective saving)
            saveManager.saveDirtyChunks(dirtyChunks)
                .thenCompose(v -> saveManager.savePlayerState(playerState))
                .thenCompose(v -> saveManager.saveWorldMetadata(worldMetadata))
                .thenRun(() -> {
                    // Mark saved chunks as clean to allow them to be unloaded
                    markChunksAsClean(dirtyChunks);

                    long duration = System.currentTimeMillis() - startTime;
                    totalSaves.incrementAndGet();
                    lastSaveTime.set(System.currentTimeMillis());

                    System.out.printf("Auto-save completed: %d chunks saved and marked clean in %dms%n",
                        dirtyChunks.size(), duration);
                })
                .exceptionally(ex -> {
                    System.err.println("Auto-save failed: " + ex.getMessage());
                    return null;
                });

        } catch (Exception e) {
            System.err.println("Auto-save error: " + e.getMessage());
        }
    }

    /**
     * Gets dirty chunks from the world that need to be saved.
     */
    private Collection<Chunk> getDirtyChunks(World world) {
        return world.getDirtyChunks();
    }

    /**
     * Creates a PlayerState from the current player.
     */
    private PlayerState createPlayerState(Player player) {
        PlayerState state = new PlayerState();

        // Copy position and rotation
        state.setPosition(new Vector3f(player.getPosition()));
        state.setRotation(new Vector2f(player.getCamera().getYaw(), player.getCamera().getPitch()));

        // Copy player state
        state.setHealth(player.getHealth());
        state.setFlying(player.isFlying());

        // Default to creative mode since there's no gameMode field in Player
        state.setGameMode(1); // Creative mode

        // Copy inventory - combine hotbar and main inventory
        ItemStack[] combinedInventory = new ItemStack[36]; // 9 hotbar + 27 main
        Inventory inventory = player.getInventory();

        // Copy hotbar items (slots 0-8)
        for (int i = 0; i < 9; i++) {
            combinedInventory[i] = inventory.getHotbarItem(i);
        }

        // Copy main inventory items (slots 9-35)
        for (int i = 0; i < 27; i++) {
            combinedInventory[i + 9] = inventory.getMainInventoryItem(i);
        }

        state.setInventory(combinedInventory);
        state.setSelectedHotbarSlot(inventory.getSelectedSlot());

        return state;
    }

    /**
     * Updates world metadata with current play time.
     */
    private void updateWorldMetadata(WorldMetadata metadata, long currentTime) {
        if (lastSaveTime.get() > 0) {
            long sessionTime = currentTime - lastSaveTime.get();
            metadata.addPlayTime(sessionTime);
        }
    }

    /**
     * Marks chunks as clean after successful save to allow unloading.
     * This is critical for the dirty chunk protection system.
     */
    private void markChunksAsClean(Collection<Chunk> savedChunks) {
        int cleanedCount = 0;
        StringBuilder chunkList = new StringBuilder();

        for (Chunk chunk : savedChunks) {
            if (chunk.isDirty()) {
                chunk.markClean();
                cleanedCount++;
                if (chunkList.length() > 0) chunkList.append(", ");
                chunkList.append("(").append(chunk.getX()).append(",").append(chunk.getZ()).append(")");
            }
        }

        if (cleanedCount > 0) {
            System.out.println("[AUTO-SAVE] Marked " + cleanedCount + " chunks as CLEAN - now eligible for unloading: " + chunkList.toString());
        }
    }

    /**
     * Gets auto-save statistics.
     */
    public AutoSaveStats getStats() {
        AutoSaveStats stats = new AutoSaveStats();
        stats.isRunning = isRunning.get();
        stats.totalSaves = totalSaves.get();
        stats.lastSaveTime = lastSaveTime.get();
        stats.nextSaveIn = getTimeUntilNextSave();
        return stats;
    }

    /**
     * Gets seconds until the next auto-save.
     */
    public long getTimeUntilNextSave() {
        if (!isRunning.get()) {
            return -1;
        }

        long timeSinceLastSave = (System.currentTimeMillis() - lastSaveTime.get()) / 1000;
        return Math.max(0, AUTO_SAVE_INTERVAL_SECONDS - timeSinceLastSave);
    }

    /**
     * Checks if auto-save is currently running.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Gets the total number of auto-saves performed.
     */
    public long getTotalSaves() {
        return totalSaves.get();
    }

    /**
     * Gets the timestamp of the last save operation.
     */
    public long getLastSaveTime() {
        return lastSaveTime.get();
    }

    @Override
    public void close() {
        stopAutoSave();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("AutoSaveScheduler closed successfully");
    }

    /**
     * Statistics about auto-save operations.
     */
    public static class AutoSaveStats {
        public boolean isRunning;
        public long totalSaves;
        public long lastSaveTime;
        public long nextSaveIn; // seconds until next save

        @Override
        public String toString() {
            if (!isRunning) {
                return "Auto-save: STOPPED (Total saves: " + totalSaves + ")";
            }

            return String.format("Auto-save: RUNNING (Total: %d, Next in: %ds)",
                totalSaves, nextSaveIn);
        }
    }
}