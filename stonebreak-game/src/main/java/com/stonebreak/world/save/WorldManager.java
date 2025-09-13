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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main coordination point for all world save/load operations.
 * Manages WorldSaver, WorldLoader, and provides high-level world management APIs.
 */
public class WorldManager {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_METADATA_FILE = "world.dat"; // Changed from world.json to world.dat for binary format
    private static final String PLAYER_DATA_FILE = "player.dat"; // Binary player data file
    private static final String REGIONS_DIRECTORY = "regions"; // Changed from chunks to regions directory
    private static final String CHUNKS_DIRECTORY = "chunks"; // Legacy chunks directory for backward compatibility
    
    // Singleton instance
    private static WorldManager instance;
    private static final Object instanceLock = new Object();
    
    // Jackson ObjectMapper for JSON serialization (kept for backward compatibility/migration)
    private final ObjectMapper objectMapper;
    
    // New Binary Components
    private RegionFileManager regionFileManager; // Non-final, initialized per world
    private final BinaryChunkCodec chunkCodec;
    
    // Legacy Components (for migration support)
    private final ChunkFileManager chunkFileManager;
    private final SaveFileValidator validator;
    private final CorruptionRecoveryManager recoveryManager;
    
    // Updated Components
    private final WorldSaver worldSaver;
    private final WorldLoader worldLoader;
    
    // Thread pool for async operations (separate from World's chunkBuildExecutor)
    private final ExecutorService saveLoadExecutor;
    
    // Session tracking
    private final AtomicLong sessionStartTime;
    private String currentWorldName;
    
    // Binary auto-save functionality
    private final ScheduledExecutorService binaryAutoSaveScheduler;
    private ScheduledFuture<?> binaryAutoSaveTask;
    private volatile World binaryAutoSaveWorld;
    private volatile Player binaryAutoSavePlayer;
    
    /**
     * Private constructor for singleton pattern.
     */
    private WorldManager() {
        // Initialize Jackson ObjectMapper (kept for backward compatibility)
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        // Initialize binary components
        this.chunkCodec = new BinaryChunkCodec();
        
        // Initialize RegionFileManager (will be configured per world)
        this.regionFileManager = null; // Initialized per world in setCurrentWorld()
        
        // Initialize legacy components (for migration support)
        this.chunkFileManager = new ChunkFileManager(objectMapper);
        this.validator = new SaveFileValidator(objectMapper, chunkFileManager);
        this.recoveryManager = new CorruptionRecoveryManager(objectMapper, chunkFileManager, validator);
        
        // Initialize updated components (will be modified to support binary format)
        this.worldSaver = new WorldSaver(objectMapper, chunkFileManager);
        this.worldLoader = new WorldLoader(objectMapper, chunkFileManager);
        
        // Create dedicated thread pool for save/load operations
        this.saveLoadExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "WorldManager-SaveLoad");
            t.setDaemon(true);
            return t;
        });
        
        // Create binary auto-save scheduler
        this.binaryAutoSaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BinaryAutoSave");
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
     * Set up RegionFileManager for the current world.
     * @param worldName World name to set up region management for
     * @throws IOException if RegionFileManager creation fails
     */
    private void setupRegionFileManager(String worldName) throws IOException {
        if (regionFileManager != null) {
            regionFileManager.close();
        }
        
        String worldPath = Paths.get(WORLDS_DIRECTORY, worldName).toString();
        this.regionFileManager = new RegionFileManager(worldPath);
    }
    
    /**
     * Check if world uses binary format (has regions directory).
     * @param worldName World name to check
     * @return True if world uses binary format
     */
    private boolean usesBinaryFormat(String worldName) {
        Path regionsPath = Paths.get(WORLDS_DIRECTORY, worldName, REGIONS_DIRECTORY);
        Path binaryWorldFile = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
        return Files.exists(regionsPath) || Files.exists(binaryWorldFile);
    }
    
    /**
     * Save world metadata in binary format.
     * @param worldName World name
     * @param world World instance
     * @param player Player instance
     * @throws IOException if saving fails
     */
    private void saveBinaryMetadata(String worldName, World world, Player player) throws IOException {
        String worldPath = Paths.get(WORLDS_DIRECTORY, worldName).toString();
        
        // Save world metadata
        BinaryWorldMetadata worldMetadata = new BinaryWorldMetadata(worldName, world.getSeed());
        worldMetadata.setSpawnPosition(world.getSpawnPosition());
        worldMetadata.updatePlayTime(System.currentTimeMillis() - sessionStartTime.get());
        worldMetadata.saveToFile(worldPath);
        
        // Save player data
        BinaryPlayerData playerData = new BinaryPlayerData(player);
        playerData.saveToFile(worldPath);
    }
    
    /**
     * Load world metadata from binary format.
     * @param worldName World name
     * @param world World instance
     * @param player Player instance
     * @throws IOException if loading fails
     */
    private void loadBinaryMetadata(String worldName, World world, Player player) throws IOException {
        String worldPath = Paths.get(WORLDS_DIRECTORY, worldName).toString();
        
        // Load world metadata
        BinaryWorldMetadata worldMetadata = BinaryWorldMetadata.loadFromFile(worldPath);
        world.setSeed(worldMetadata.getSeed());
        world.setSpawnPosition(worldMetadata.getSpawnPosition());
        
        // Load player data if exists
        if (BinaryPlayerData.exists(worldPath)) {
            BinaryPlayerData playerData = BinaryPlayerData.loadFromFile(worldPath);
            playerData.applyToPlayer(player);
        }
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
                
                // Determine save format and perform the actual save
                if (usesBinaryFormat(currentWorldName)) {
                    System.out.println("Using binary save format for world: " + currentWorldName);
                    
                    // Set up region file manager
                    setupRegionFileManager(currentWorldName);
                    
                    // Save world and player metadata
                    saveBinaryMetadata(currentWorldName, world, player);
                    
                    // Save dirty chunks using binary format
                    saveBinaryChunks(world);
                    
                    System.out.println("Binary save completed for world: " + currentWorldName);
                } else {
                    System.out.println("Using legacy JSON save format for world: " + currentWorldName);
                    
                    // Use legacy save system
                    worldSaver.saveWorldSync(world, player, currentWorldName);
                    
                    // Validate saved data
                    SaveFileValidator.ValidationResult validation = validator.validateWorld(currentWorldName).join();
                    if (!validation.isValid()) {
                        System.err.println("Warning: Saved world failed validation: " + validation.getSummary());
                    }
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
     * Save dirty chunks using binary format.
     * @param world World instance to save chunks from
     * @throws IOException if chunk saving fails
     */
    private void saveBinaryChunks(World world) throws IOException {
        if (regionFileManager == null) {
            throw new IllegalStateException("RegionFileManager not initialized");
        }
        
        // Get all dirty chunks from the world
        var dirtyChunks = world.getDirtyChunks(); // This method would need to be added to World class
        
        System.out.println("Saving " + dirtyChunks.size() + " dirty chunks in binary format");
        
        // Save each dirty chunk
        for (var chunk : dirtyChunks) {
            try {
                regionFileManager.saveChunkSync(chunk);
                chunk.markClean(); // Mark chunk as clean after successful save
            } catch (IOException e) {
                System.err.println("Failed to save chunk [" + chunk.getX() + ", " + chunk.getZ() + "]: " + e.getMessage());
                throw e;
            }
        }
        
        // Sync all region files to disk
        regionFileManager.syncAllSync();
        
        System.out.println("Binary chunk save completed");
    }
    
    /**
     * Load chunks using binary format.
     * @param world World instance to load chunks into
     * @param centerX Center chunk X coordinate
     * @param centerZ Center chunk Z coordinate
     * @param radius Radius of chunks to load around center
     * @throws IOException if chunk loading fails
     */
    private void loadBinaryChunks(World world, int centerX, int centerZ, int radius) throws IOException {
        if (regionFileManager == null) {
            throw new IllegalStateException("RegionFileManager not initialized");
        }
        
        int chunksLoaded = 0;
        
        // Load chunks in a radius around the center
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                try {
                    var chunk = regionFileManager.loadChunkSync(x, z);
                    if (chunk != null) {
                        world.setChunk(x, z, chunk); // This method would need to be added to World class
                        chunksLoaded++;
                    }
                } catch (IOException e) {
                    System.err.println("Failed to load chunk [" + x + ", " + z + "]: " + e.getMessage());
                    // Continue loading other chunks
                }
            }
        }
        
        System.out.println("Loaded " + chunksLoaded + " chunks in binary format");
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
                
                // Determine load format and perform the actual load
                if (usesBinaryFormat(worldName)) {
                    System.out.println("Using binary load format for world: " + worldName);
                    
                    // Set up region file manager
                    setupRegionFileManager(worldName);
                    
                    // Load world and player metadata
                    loadBinaryMetadata(worldName, world, player);
                    
                    // Load initial chunks around spawn
                    var spawnPos = world.getSpawnPosition();
                    int spawnChunkX = (int) Math.floor(spawnPos.x / 16);
                    int spawnChunkZ = (int) Math.floor(spawnPos.z / 16);
                    loadBinaryChunks(world, spawnChunkX, spawnChunkZ, 8); // Load 8-chunk radius
                    
                    System.out.println("Binary load completed for world: " + worldName);
                } else {
                    System.out.println("Using legacy JSON load format for world: " + worldName);
                    
                    // Phase 1: Validate world files (legacy format)
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
                        
                        // Phase 2: Normal loading (legacy)
                        worldLoader.loadWorld(worldName, world, player);
                    }
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
     * Uses binary format for new worlds, legacy JSON for old worlds.
     * 
     * @param world The world to auto-save
     * @param player The player to auto-save
     */
    public void startAutoSave(World world, Player player) {
        if (currentWorldName == null) {
            System.err.println("Cannot start auto-save: no current world set");
            return;
        }
        
        if (usesBinaryFormat(currentWorldName)) {
            // For binary format worlds, start our own auto-save
            startBinaryAutoSave(world, player);
        } else {
            // For legacy JSON worlds, use the old WorldSaver
            worldSaver.startAutoSave(world, player, currentWorldName);
        }
        System.out.println("Auto-save started for world: " + currentWorldName + " (format: " + 
                          (usesBinaryFormat(currentWorldName) ? "binary" : "legacy JSON") + ")");
    }
    
    /**
     * Start binary auto-save for the current world.
     * @param world World to auto-save
     * @param player Player to auto-save
     */
    private void startBinaryAutoSave(World world, Player player) {
        // Stop existing binary auto-save if running
        stopBinaryAutoSave();
        
        // Store references for auto-save
        this.binaryAutoSaveWorld = world;
        this.binaryAutoSavePlayer = player;
        
        // Schedule auto-save every 30 seconds
        this.binaryAutoSaveTask = binaryAutoSaveScheduler.scheduleWithFixedDelay(() -> {
            try {
                System.out.println("Binary auto-save triggered for world: " + currentWorldName);
                
                // Set up region file manager if needed
                setupRegionFileManager(currentWorldName);
                
                // Save world and player metadata
                saveBinaryMetadata(currentWorldName, binaryAutoSaveWorld, binaryAutoSavePlayer);
                
                // Save dirty chunks
                saveBinaryChunks(binaryAutoSaveWorld);
                
                System.out.println("Binary auto-save completed for world: " + currentWorldName);
                
            } catch (Exception e) {
                System.err.println("Binary auto-save failed for world " + currentWorldName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        System.out.println("Binary auto-save scheduled for world: " + currentWorldName);
    }
    
    /**
     * Stop binary auto-save.
     */
    private void stopBinaryAutoSave() {
        if (binaryAutoSaveTask != null && !binaryAutoSaveTask.isCancelled()) {
            binaryAutoSaveTask.cancel(false);
            binaryAutoSaveTask = null;
            System.out.println("Binary auto-save stopped");
        }
        
        this.binaryAutoSaveWorld = null;
        this.binaryAutoSavePlayer = null;
    }
    
    /**
     * Stops auto-save for the current world.
     */
    public void stopAutoSave() {
        // Stop binary auto-save
        stopBinaryAutoSave();
        
        // Stop legacy auto-save
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
            
            // Close RegionFileManager if open
            if (regionFileManager != null) {
                try {
                    regionFileManager.close();
                } catch (IOException e) {
                    System.err.println("Error closing RegionFileManager: " + e.getMessage());
                }
            }
            
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