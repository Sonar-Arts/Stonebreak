package com.stonebreak.world.save;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main coordination point for all world save/load operations.
 * Manages WorldSaver, WorldLoader, and provides high-level world management APIs.
 */
public class WorldManager {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_METADATA_FILE = "world.json";
    private static final String CHUNKS_DIRECTORY = "chunks";
    
    // Singleton instance
    private static WorldManager instance;
    private static final Object instanceLock = new Object();
    
    // Jackson ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper;
    
    // Components
    private final WorldSaver worldSaver;
    private final WorldLoader worldLoader;
    private final ChunkFileManager chunkFileManager;
    private final SaveFileValidator validator;
    private final CorruptionRecoveryManager recoveryManager;
    
    // Thread pool for async operations (separate from World's chunkBuildExecutor)
    private final ExecutorService saveLoadExecutor;
    
    // Session tracking
    private final AtomicLong sessionStartTime;
    private String currentWorldName;
    
    /**
     * Private constructor for singleton pattern.
     */
    private WorldManager() {
        // Initialize Jackson ObjectMapper
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        // Initialize components
        this.chunkFileManager = new ChunkFileManager(objectMapper);
        this.validator = new SaveFileValidator(objectMapper, chunkFileManager);
        this.worldSaver = new WorldSaver(objectMapper, chunkFileManager);
        this.worldLoader = new WorldLoader(objectMapper, chunkFileManager);
        this.recoveryManager = new CorruptionRecoveryManager(objectMapper, chunkFileManager, validator);
        
        // Create dedicated thread pool for save/load operations
        this.saveLoadExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "WorldManager-SaveLoad");
            t.setDaemon(true);
            return t;
        });
        
        // Session tracking
        this.sessionStartTime = new AtomicLong(System.currentTimeMillis());
        this.currentWorldName = null;
        
        // Ensure worlds directory exists
        createWorldsDirectory();
        
        System.out.println("WorldManager initialized successfully");
    }
    
    /**
     * Gets the singleton instance of WorldManager.
     */
    public static WorldManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new WorldManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Saves a world asynchronously with automatic backup creation.
     * 
     * @param world The world to save
     * @param player The player to save with the world
     * @return CompletableFuture that completes when save is finished
     */
    public CompletableFuture<Void> saveWorld(World world, Player player) {
        if (world == null || player == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World and player cannot be null"));
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                System.out.println("Starting enhanced world save for: " + currentWorldName);
                
                // Create backup before saving (for corruption recovery)
                if (currentWorldName != null) {
                    CorruptionRecoveryManager.BackupResult backup = 
                        recoveryManager.createWorldBackup(currentWorldName).join();
                    
                    if (backup.isSuccessful()) {
                        System.out.println("Pre-save backup created: " + backup.getBackupName());
                    } else {
                        System.err.println("Backup creation failed: " + backup.getMessage());
                    }
                }
                
                // Perform the actual save
                worldSaver.saveWorldSync(world, player, currentWorldName);
                
                // Validate saved data
                SaveFileValidator.ValidationResult validation = validator.validateWorld(currentWorldName).join();
                if (!validation.isValid()) {
                    System.err.println("Warning: Saved world failed validation: " + validation.getSummary());
                }
                
                long duration = System.currentTimeMillis() - startTime;
                System.out.println("Enhanced world save completed in " + duration + "ms for: " + currentWorldName);
                
            } catch (Exception e) {
                System.err.println("Error saving world " + currentWorldName + ": " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to save world: " + currentWorldName, e);
            }
        }, saveLoadExecutor);
    }
    
    /**
     * Loads a world asynchronously with enhanced validation and recovery.
     * 
     * @param worldName The name of the world to load
     * @param world The World instance to load data into
     * @param player The Player instance to load data into
     * @return CompletableFuture that completes when load is finished
     */
    public CompletableFuture<Void> loadWorld(String worldName, World world, Player player) {
        if (worldName == null || world == null || player == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World name, world, and player cannot be null"));
        }
        
        if (!worldExists(worldName)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World does not exist: " + worldName));
        }
        
        this.currentWorldName = worldName;
        this.sessionStartTime.set(System.currentTimeMillis());
        
        return CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                System.out.println("Starting enhanced world load for: " + worldName);
                
                // Phase 1: Validate world files
                SaveFileValidator.ValidationResult validation = validator.validateWorld(worldName).join();
                
                if (!validation.isValid()) {
                    System.err.println("World validation failed for " + worldName + ": " + validation.getSummary());
                    
                    // Attempt automatic recovery
                    System.out.println("Attempting automatic recovery for world: " + worldName);
                    CorruptionRecoveryManager.RecoveryResult recovery = 
                        recoveryManager.recoverCorruptedWorld(worldName, world, player).join();
                    
                    if (!recovery.isSuccessful()) {
                        throw new RuntimeException("World recovery failed: " + recovery.getMessage());
                    }
                    
                    System.out.println("Recovery successful: " + recovery.getMessage());
                } else {
                    System.out.println("World validation passed for: " + worldName);
                    
                    // Phase 2: Normal loading
                    worldLoader.loadWorld(worldName, world, player);
                }
                
                long duration = System.currentTimeMillis() - startTime;
                System.out.println("Enhanced world load completed in " + duration + "ms for: " + worldName);
                
            } catch (Exception e) {
                System.err.println("Error loading world " + worldName + ": " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to load world: " + worldName, e);
            }
        }, saveLoadExecutor);
    }
    
    /**
     * Starts auto-save for the current world.
     * 
     * @param world The world to auto-save
     * @param player The player to auto-save
     */
    public void startAutoSave(World world, Player player) {
        if (currentWorldName == null) {
            System.err.println("Cannot start auto-save: no current world set");
            return;
        }
        
        worldSaver.startAutoSave(world, player, currentWorldName);
        System.out.println("Auto-save started for world: " + currentWorldName);
    }
    
    /**
     * Stops auto-save for the current world.
     */
    public void stopAutoSave() {
        worldSaver.stopAutoSave();
        System.out.println("Auto-save stopped for world: " + currentWorldName);
    }
    
    /**
     * Checks if a world exists.
     * 
     * @param worldName The name of the world to check
     * @return true if the world exists, false otherwise
     */
    public boolean worldExists(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return false;
        }
        
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        Path metadataFile = worldDir.resolve(WORLD_METADATA_FILE);
        
        return Files.exists(worldDir) && Files.isDirectory(worldDir) && Files.exists(metadataFile);
    }
    
    /**
     * Creates a new world with the given metadata.
     * 
     * @param metadata The world metadata to save
     * @return CompletableFuture that completes when world creation is finished
     */
    public CompletableFuture<Void> createWorld(WorldSaveMetadata metadata) {
        if (metadata == null || metadata.getWorldName() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Metadata and world name cannot be null"));
        }
        
        String worldName = metadata.getWorldName();
        
        if (worldExists(worldName)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World already exists: " + worldName));
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Create world directory structure
                Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
                Path chunksDir = worldDir.resolve(CHUNKS_DIRECTORY);
                
                Files.createDirectories(worldDir);
                Files.createDirectories(chunksDir);
                
                // Save metadata
                Path metadataPath = worldDir.resolve(WORLD_METADATA_FILE);
                objectMapper.writeValue(metadataPath.toFile(), metadata);
                
                System.out.println("Created new world: " + worldName + " with seed: " + metadata.getSeed());
                
            } catch (IOException e) {
                System.err.println("Error creating world " + worldName + ": " + e.getMessage());
                throw new RuntimeException("Failed to create world: " + worldName, e);
            }
        }, saveLoadExecutor);
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * No UI integration found for world deletion functionality.
     *
     * Deletes a world permanently.
     * 
     * @param worldName The name of the world to delete
     * @return CompletableFuture that completes when deletion is finished
     */
    /*
    public CompletableFuture<Void> deleteWorld(String worldName) {
        if (!worldExists(worldName)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World does not exist: " + worldName));
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
                deleteDirectoryRecursively(worldDir);
                System.out.println("Deleted world: " + worldName);
                
            } catch (IOException e) {
                System.err.println("Error deleting world " + worldName + ": " + e.getMessage());
                throw new RuntimeException("Failed to delete world: " + worldName, e);
            }
        }, saveLoadExecutor);
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * World discovery is handled by WorldSelectScreen directly.
     *
     * Lists all available worlds.
     * 
     * @return List of world names
     */
    /*
    public List<String> listWorlds() {
        List<String> worldNames = new ArrayList<>();
        
        try {
            Path worldsDir = Paths.get(WORLDS_DIRECTORY);
            if (!Files.exists(worldsDir)) {
                return worldNames;
            }
            
            Files.list(worldsDir)
                 .filter(Files::isDirectory)
                 .map(path -> path.getFileName().toString())
                 .filter(this::worldExists)
                 .forEach(worldNames::add);
                 
        } catch (IOException e) {
            System.err.println("Error listing worlds: " + e.getMessage());
        }
        
        return worldNames;
    }
    */
    
    /**
     * Gets metadata for a world.
     * 
     * @param worldName The name of the world
     * @return WorldSaveMetadata or null if not found
     */
    public WorldSaveMetadata getWorldMetadata(String worldName) {
        if (!worldExists(worldName)) {
            return null;
        }
        
        try {
            Path metadataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
            return objectMapper.readValue(metadataPath.toFile(), WorldSaveMetadata.class);
        } catch (IOException e) {
            System.err.println("Error reading world metadata for " + worldName + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Updates play time for the current world session.
     */
    public CompletableFuture<Void> updateCurrentWorldPlayTime() {
        if (currentWorldName == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                WorldSaveMetadata metadata = getWorldMetadata(currentWorldName);
                if (metadata != null) {
                    long sessionTime = System.currentTimeMillis() - sessionStartTime.get();
                    metadata.updatePlayTime(sessionTime);
                    
                    Path metadataPath = Paths.get(WORLDS_DIRECTORY, currentWorldName, WORLD_METADATA_FILE);
                    objectMapper.writeValue(metadataPath.toFile(), metadata);
                    
                    // Reset session timer
                    sessionStartTime.set(System.currentTimeMillis());
                }
            } catch (IOException e) {
                System.err.println("Error updating play time for " + currentWorldName + ": " + e.getMessage());
            }
        }, saveLoadExecutor);
    }
    
    /**
     * Sets the current world name (for session tracking).
     */
    public void setCurrentWorldName(String worldName) {
        this.currentWorldName = worldName;
        this.sessionStartTime.set(System.currentTimeMillis());
    }
    
    /**
     * Gets the current world name.
     */
    public String getCurrentWorldName() {
        return currentWorldName;
    }
    
    /**
     * Validates a world's save files for corruption or errors.
     * 
     * @param worldName The name of the world to validate
     * @return CompletableFuture containing the validation result
     */
    public CompletableFuture<SaveFileValidator.ValidationResult> validateWorld(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                SaveFileValidator.ValidationResult.failure("World name cannot be null or empty")
            );
        }
        
        return validator.validateWorld(worldName);
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * Backup operations are handled internally by the save system.
     *
     * Creates a backup of the specified world.
     * 
     * @param worldName The name of the world to backup
     * @return CompletableFuture containing the backup result
     */
    /*
    public CompletableFuture<CorruptionRecoveryManager.BackupResult> createBackup(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                CorruptionRecoveryManager.BackupResult.failure("World name cannot be null or empty")
            );
        }
        
        return recoveryManager.createWorldBackup(worldName);
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * No UI integration found for backup listing functionality.
     *
     * Lists available backups for a world.
     * 
     * @param worldName The name of the world
     * @return List of available backup information
     */
    /*
    public List<CorruptionRecoveryManager.BackupInfo> listBackups(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        return recoveryManager.listAvailableBackups(worldName);
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * No UI integration found for manual backup restoration.
     *
     * Restores a world from a specific backup.
     * 
     * @param worldName The name of the world to restore
     * @param backupName The name of the backup to restore from
     * @param world The World instance to load data into
     * @param player The Player instance to load data into
     * @return CompletableFuture containing the recovery result
     */
    /*
    public CompletableFuture<CorruptionRecoveryManager.RecoveryResult> restoreFromBackup(
            String worldName, String backupName, World world, Player player) {
        
        if (worldName == null || backupName == null || world == null || player == null) {
            return CompletableFuture.completedFuture(
                CorruptionRecoveryManager.RecoveryResult.failure("Invalid parameters for backup restoration")
            );
        }
        
        return recoveryManager.restoreFromBackup(worldName, backupName, world, player);
    }
    */
    
    /**
     * Sets a detailed progress reporter for the world loader.
     * 
     * @param progressReporter The detailed progress reporter
     */
    public void setDetailedProgressReporter(WorldLoader.DetailedProgressReporter progressReporter) {
        worldLoader.setDetailedProgressReporter(progressReporter);
    }
    
    /**
     * Gets access to the save file validator for advanced operations.
     */
    public SaveFileValidator getValidator() {
        return validator;
    }
    
    /**
     * Gets access to the corruption recovery manager for advanced operations.
     */
    public CorruptionRecoveryManager getRecoveryManager() {
        return recoveryManager;
    }
    
    /**
     * Shuts down the WorldManager and all its components.
     */
    public void shutdown() {
        try {
            // Stop auto-save first
            stopAutoSave();
            
            // Update play time for current session
            updateCurrentWorldPlayTime().join();
            
            // Shutdown components
            worldSaver.shutdown();
            validator.shutdown();
            recoveryManager.shutdown();
            
            // Shutdown executor
            saveLoadExecutor.shutdown();
            try {
                if (!saveLoadExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    saveLoadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                saveLoadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            System.out.println("WorldManager shutdown completed");
            
        } catch (Exception e) {
            System.err.println("Error during WorldManager shutdown: " + e.getMessage());
        }
    }
    
    /**
     * Creates the worlds directory if it doesn't exist.
     */
    private void createWorldsDirectory() {
        try {
            Path worldsDir = Paths.get(WORLDS_DIRECTORY);
            if (!Files.exists(worldsDir)) {
                Files.createDirectories(worldsDir);
                System.out.println("Created worlds directory: " + worldsDir.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Failed to create worlds directory: " + e.getMessage());
        }
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(child -> {
                try {
                    deleteDirectoryRecursively(child);
                } catch (IOException e) {
                    System.err.println("Error deleting " + child + ": " + e.getMessage());
                }
            });
        }
        Files.deleteIfExists(path);
    }
}