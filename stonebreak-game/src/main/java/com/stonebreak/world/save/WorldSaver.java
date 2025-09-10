package com.stonebreak.world.save;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.world.World;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.World.ChunkPosition;
import com.stonebreak.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles asynchronous world saving operations including auto-save functionality.
 * Uses the existing World's chunkBuildExecutor for async operations.
 */
public class WorldSaver {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_METADATA_FILE = "world.json";
    private static final int AUTO_SAVE_INTERVAL_SECONDS = 30;
    
    // Jackson ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper;
    
    // Chunk file manager for individual chunk operations
    private final ChunkFileManager chunkFileManager;
    
    // Auto-save scheduler
    private final ScheduledExecutorService autoSaveScheduler;
    private ScheduledFuture<?> autoSaveTask;
    private final AtomicBoolean autoSaveEnabled;
    
    // Current auto-save targets
    private volatile World currentWorld;
    private volatile Player currentPlayer;
    private volatile String currentWorldName;
    
    // Statistics
    private final AtomicInteger saveCount;
    
    /**
     * Creates a new WorldSaver.
     * 
     * @param objectMapper The Jackson ObjectMapper for JSON operations
     * @param chunkFileManager The ChunkFileManager for chunk operations
     */
    public WorldSaver(ObjectMapper objectMapper, ChunkFileManager chunkFileManager) {
        this.objectMapper = objectMapper;
        this.chunkFileManager = chunkFileManager;
        
        // Create auto-save scheduler
        this.autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WorldSaver-AutoSave");
            t.setDaemon(true);
            return t;
        });
        
        this.autoSaveEnabled = new AtomicBoolean(false);
        this.saveCount = new AtomicInteger(0);
        
        System.out.println("WorldSaver initialized");
    }
    
    /**
     * Saves a world asynchronously using the World's chunkBuildExecutor.
     * 
     * @param world The world to save
     * @param player The player to save with the world
     * @param worldName The name of the world
     * @return CompletableFuture that completes when save is finished
     */
    public CompletableFuture<Void> saveWorldAsync(World world, Player player, String worldName) {
        if (world == null || player == null || worldName == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World, player, and worldName cannot be null"));
        }
        
        // Use the World's existing chunkBuildExecutor for async operations
        return CompletableFuture.runAsync(() -> {
            try {
                saveWorldSync(world, player, worldName);
            } catch (Exception e) {
                throw new RuntimeException("Async save failed for world: " + worldName, e);
            }
        }, world.getChunkBuildExecutor());
    }
    
    /**
     * Saves a world synchronously (called by async methods).
     * 
     * @param world The world to save
     * @param player The player to save with the world
     * @param worldName The name of the world
     * @throws IOException if save operation fails
     */
    public void saveWorldSync(World world, Player player, String worldName) throws IOException {
        long startTime = System.currentTimeMillis();
        
        // Create world directory if it doesn't exist
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        Files.createDirectories(worldDir);
        
        // Save world metadata
        saveWorldMetadata(world, worldName);
        
        // Save player data
        savePlayerData(player, worldName);
        
        // Save modified chunks only
        List<ChunkPosition> savedChunks = saveDirtyChunks(world, worldName);
        
        // Update save statistics
        int saveNumber = saveCount.incrementAndGet();
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.println("Save #" + saveNumber + " completed for '" + worldName + "' in " + duration + "ms " +
                          "(saved " + savedChunks.size() + " chunks)");
    }
    
    /**
     * Starts auto-save for the specified world and player.
     * 
     * @param world The world to auto-save
     * @param player The player to auto-save
     * @param worldName The name of the world
     */
    public void startAutoSave(World world, Player player, String worldName) {
        if (world == null || player == null || worldName == null) {
            System.err.println("Cannot start auto-save: null parameters");
            return;
        }
        
        // Stop existing auto-save if running
        stopAutoSave();
        
        // Set current auto-save targets
        this.currentWorld = world;
        this.currentPlayer = player;
        this.currentWorldName = worldName;
        this.autoSaveEnabled.set(true);
        
        // Schedule auto-save task
        this.autoSaveTask = autoSaveScheduler.scheduleAtFixedRate(
            this::performAutoSave,
            AUTO_SAVE_INTERVAL_SECONDS,
            AUTO_SAVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        System.out.println("[AUTO-SAVE] Auto-save started for world '" + worldName + "' (interval: " + AUTO_SAVE_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Stops the current auto-save operation.
     */
    public void stopAutoSave() {
        autoSaveEnabled.set(false);
        
        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel(false);
            autoSaveTask = null;
        }
        
        // Clear current targets
        this.currentWorld = null;
        this.currentPlayer = null;
        this.currentWorldName = null;
        
        System.out.println("Auto-save stopped");
    }
    
    /**
     * Performs an auto-save operation (called by scheduler).
     */
    private void performAutoSave() {
        if (!autoSaveEnabled.get() || currentWorld == null || currentPlayer == null || currentWorldName == null) {
            System.out.println("[AUTO-SAVE] Auto-save skipped - enabled: " + autoSaveEnabled.get() + 
                             ", world: " + (currentWorld != null) + 
                             ", player: " + (currentPlayer != null) + 
                             ", worldName: " + currentWorldName);
            return;
        }
        
        try {
            // Check if there are any dirty chunks before saving
            List<Chunk> dirtyChunks = getDirtyChunks(currentWorld);
            
            if (dirtyChunks.isEmpty()) {
                // No changes to save, just update metadata timestamp
                System.out.println("[AUTO-SAVE] No dirty chunks found for '" + currentWorldName + "' - updating metadata timestamp only");
                updateWorldMetadataTimestamp(currentWorldName);
                return;
            }
            
            System.out.println("Auto-save starting for '" + currentWorldName + "' (" + dirtyChunks.size() + " dirty chunks)");
            
            // Perform asynchronous save (non-blocking for gameplay)
            saveWorldAsync(currentWorld, currentPlayer, currentWorldName)
                .thenRun(() -> {
                    System.out.println("Auto-save completed for '" + currentWorldName + "'");
                })
                .exceptionally(throwable -> {
                    System.err.println("Auto-save failed for '" + currentWorldName + "': " + throwable.getMessage());
                    return null;
                });
                
        } catch (Exception e) {
            System.err.println("Error during auto-save for '" + currentWorldName + "': " + e.getMessage());
        }
    }
    
    /**
     * Saves world metadata to the world.json file.
     */
    private void saveWorldMetadata(World world, String worldName) throws IOException {
        Path metadataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
        
        WorldSaveMetadata metadata;
        
        // Load existing metadata or create new
        if (Files.exists(metadataPath)) {
            try {
                metadata = objectMapper.readValue(metadataPath.toFile(), WorldSaveMetadata.class);
            } catch (IOException e) {
                System.err.println("Error reading existing metadata, creating new: " + e.getMessage());
                metadata = new WorldSaveMetadata(worldName, world.getSeed(), world.getSpawnPosition());
            }
        } else {
            metadata = new WorldSaveMetadata(worldName, world.getSeed(), world.getSpawnPosition());
        }
        
        // Update metadata
        metadata.setLastPlayed(LocalDateTime.now());
        metadata.setPreGeneratedChunks(world.getLoadedChunkCount());
        
        // Save metadata
        objectMapper.writeValue(metadataPath.toFile(), metadata);
    }
    
    /**
     * Updates only the timestamp in world metadata (for auto-save when no chunks changed).
     */
    private void updateWorldMetadataTimestamp(String worldName) throws IOException {
        Path metadataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
        
        if (Files.exists(metadataPath)) {
            WorldSaveMetadata metadata = objectMapper.readValue(metadataPath.toFile(), WorldSaveMetadata.class);
            metadata.setLastPlayed(LocalDateTime.now());
            objectMapper.writeValue(metadataPath.toFile(), metadata);
        }
    }
    
    /**
     * Saves player data to the world directory.
     */
    private void savePlayerData(Player player, String worldName) throws IOException {
        PlayerData playerData = new PlayerData(player);
        
        Path playerDataPath = Paths.get(WORLDS_DIRECTORY, worldName, "player.json");
        objectMapper.writeValue(playerDataPath.toFile(), playerData);
    }
    
    /**
     * Saves all dirty (modified) chunks to files.
     * 
     * @param world The world containing chunks to save
     * @param worldName The name of the world
     * @return List of saved chunk positions
     */
    private List<ChunkPosition> saveDirtyChunks(World world, String worldName) {
        List<ChunkPosition> savedChunks = new ArrayList<>();
        List<Chunk> dirtyChunks = getDirtyChunks(world);
        
        System.out.println("[SAVE-DEBUG] Found " + dirtyChunks.size() + " dirty chunks to save for world: " + worldName);
        
        for (Chunk chunk : dirtyChunks) {
            try {
                ChunkData chunkData = new ChunkData(chunk);
                chunkFileManager.saveChunk(chunkData, worldName, chunk.getChunkX(), chunk.getChunkZ());
                
                // Mark chunk as clean after successful save
                chunk.setDirty(false);
                
                savedChunks.add(new ChunkPosition(chunk.getChunkX(), chunk.getChunkZ()));
                
            } catch (IOException e) {
                System.err.println("Failed to save chunk (" + chunk.getChunkX() + "," + chunk.getChunkZ() + 
                                 ") for world '" + worldName + "': " + e.getMessage());
            }
        }
        
        return savedChunks;
    }
    
    /**
     * Gets a list of all dirty (modified) chunks from the world.
     * 
     * @param world The world to check for dirty chunks
     * @return List of dirty chunks
     */
    private List<Chunk> getDirtyChunks(World world) {
        List<Chunk> dirtyChunks = new ArrayList<>();
        
        // Get all loaded chunks and filter for dirty ones
        java.util.Collection<Chunk> allChunks = world.getAllLoadedChunks();
        System.out.println("[SAVE-DEBUG] Checking " + allChunks.size() + " loaded chunks for dirty status");
        
        int dirtyCount = 0;
        for (Chunk chunk : allChunks) {
            if (chunk.isDirty()) {
                dirtyChunks.add(chunk);
                dirtyCount++;
                System.out.println("[SAVE-DEBUG] Found dirty chunk: (" + chunk.getChunkX() + "," + chunk.getChunkZ() + ")");
            }
        }
        
        System.out.println("[SAVE-DEBUG] Total dirty chunks found: " + dirtyCount + " out of " + allChunks.size());
        return dirtyChunks;
    }
    
    /**
     * Performs a manual save operation (for save button/command).
     * 
     * @param world The world to save
     * @param player The player to save
     * @param worldName The name of the world
     * @return CompletableFuture that completes when save is finished
     */
    public CompletableFuture<Void> performManualSave(World world, Player player, String worldName) {
        System.out.println("Manual save requested for world: " + worldName);
        
        return saveWorldAsync(world, player, worldName)
            .thenRun(() -> {
                System.out.println("Manual save completed successfully for: " + worldName);
            })
            .exceptionally(throwable -> {
                System.err.println("Manual save failed for " + worldName + ": " + throwable.getMessage());
                return null;
            });
    }
    
    /**
     * Gets save statistics.
     */
    public int getSaveCount() {
        return saveCount.get();
    }
    
    /**
     * Checks if auto-save is currently enabled.
     */
    public boolean isAutoSaveEnabled() {
        return autoSaveEnabled.get();
    }
    
    /**
     * Gets the current auto-save world name.
     */
    public String getCurrentWorldName() {
        return currentWorldName;
    }
    
    /**
     * Shuts down the WorldSaver and all its resources.
     */
    public void shutdown() {
        try {
            // Stop auto-save
            stopAutoSave();
            
            // Shutdown the auto-save scheduler
            autoSaveScheduler.shutdown();
            try {
                if (!autoSaveScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    autoSaveScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                autoSaveScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            System.out.println("WorldSaver shutdown completed (saved " + saveCount.get() + " times total)");
            
        } catch (Exception e) {
            System.err.println("Error during WorldSaver shutdown: " + e.getMessage());
        }
    }
}