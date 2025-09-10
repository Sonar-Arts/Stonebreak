package com.stonebreak.world.save;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.world.World;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.ChunkPosition;
import com.stonebreak.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Handles world loading operations with progress reporting and data integrity validation.
 * Integrates with existing chunk generation and loading systems.
 */
public class WorldLoader {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_METADATA_FILE = "world.json";
    private static final String PLAYER_DATA_FILE = "player.json";
    private static final String CHUNKS_DIRECTORY = "chunks";
    
    // Jackson ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper;
    
    // Chunk file manager for individual chunk operations
    private final ChunkFileManager chunkFileManager;
    
    // Save file validator for integrity checking
    private final SaveFileValidator saveFileValidator;
    
    // Progress reporting
    private Consumer<String> progressReporter;
    private DetailedProgressReporter detailedProgressReporter;
    private AtomicInteger loadedChunkCount;
    private long loadStartTime;
    
    /**
     * Interface for detailed progress reporting with phase-specific callbacks.
     */
    public interface DetailedProgressReporter {
        void onPhaseStart(LoadingPhase phase, String description);
        void onPhaseProgress(LoadingPhase phase, int current, int total, String details);
        void onPhaseComplete(LoadingPhase phase, long durationMs);
        void onSubStageUpdate(String subStage, int progress, int total);
        void onTimeEstimate(String estimate);
        void onError(String error, Exception exception);
    }
    
    /**
     * Enumeration of loading phases for detailed progress tracking.
     */
    public enum LoadingPhase {
        VALIDATION("Validating World Data"),
        METADATA("Loading World Metadata"),
        PLAYER_DATA("Loading Player Data"),
        CHUNK_DISCOVERY("Discovering Saved Chunks"),
        CHUNK_LOADING("Loading Chunks"),
        FINALIZATION("Finalizing World Setup");
        
        private final String description;
        
        LoadingPhase(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Creates a new WorldLoader.
     * 
     * @param objectMapper The Jackson ObjectMapper for JSON operations
     * @param chunkFileManager The ChunkFileManager for chunk operations
     */
    public WorldLoader(ObjectMapper objectMapper, ChunkFileManager chunkFileManager) {
        this.objectMapper = objectMapper;
        this.chunkFileManager = chunkFileManager;
        this.saveFileValidator = new SaveFileValidator(objectMapper, chunkFileManager);
        this.loadedChunkCount = new AtomicInteger(0);
        
        System.out.println("WorldLoader initialized with SaveFileValidator");
    }
    
    /**
     * Loads a world including metadata, player data, and chunks.
     * 
     * @param worldName The name of the world to load
     * @param world The World instance to load data into
     * @param player The Player instance to load data into
     * @throws IOException if loading fails
     */
    public void loadWorld(String worldName, World world, Player player) throws IOException {
        if (worldName == null || world == null || player == null) {
            throw new IllegalArgumentException("World name, world, and player cannot be null");
        }
        
        loadedChunkCount.set(0);
        loadStartTime = System.currentTimeMillis();
        reportProgress("Starting world load for: " + worldName);
        
        try {
            // Phase 1: Validation
            executePhase(LoadingPhase.VALIDATION, () -> {
                validateWorldWithSaveValidator(worldName);
                updateTimeEstimate(1, 6);
                return null;
            });
            
            // Phase 2: Load world metadata
            WorldSaveMetadata metadata = executePhase(LoadingPhase.METADATA, () -> {
                WorldSaveMetadata meta = loadWorldMetadata(worldName);
                reportDetailedProgress(LoadingPhase.METADATA, 1, 1, "Seed: " + meta.getSeed());
                updateTimeEstimate(2, 6);
                return meta;
            });
            
            // Apply metadata to world
            applyMetadataToWorld(metadata, world);
            
            // Phase 3: Load player data
            executePhase(LoadingPhase.PLAYER_DATA, () -> {
                loadPlayerData(worldName, player);
                reportDetailedProgress(LoadingPhase.PLAYER_DATA, 1, 1, "Player state restored");
                updateTimeEstimate(3, 6);
                return null;
            });
            
            // Phase 4: Discover chunks
            List<ChunkPosition> availableChunks = executePhase(LoadingPhase.CHUNK_DISCOVERY, () -> {
                List<ChunkPosition> chunks = findAvailableChunks(worldName);
                reportDetailedProgress(LoadingPhase.CHUNK_DISCOVERY, chunks.size(), chunks.size(), 
                                     chunks.size() + " chunk files found");
                updateTimeEstimate(4, 6);
                return chunks;
            });
            
            // Phase 5: Load nearby chunks
            executePhase(LoadingPhase.CHUNK_LOADING, () -> {
                loadNearbyChunksWithProgress(worldName, world, player, availableChunks);
                updateTimeEstimate(5, 6);
                return null;
            });
            
            // Phase 6: Finalization
            executePhase(LoadingPhase.FINALIZATION, () -> {
                finalizeWorldLoading(world, player);
                updateTimeEstimate(6, 6);
                return null;
            });
            
            long totalTime = System.currentTimeMillis() - loadStartTime;
            reportProgress("World load completed: " + worldName + " (" + loadedChunkCount.get() + 
                         " chunks loaded in " + totalTime + "ms)");
            System.out.println("Successfully loaded world '" + worldName + "' with " + loadedChunkCount.get() + " chunks");
            
        } catch (Exception e) {
            if (detailedProgressReporter != null) {
                detailedProgressReporter.onError("World loading failed: " + e.getMessage(), e);
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            } else {
                throw new IOException("World loading failed: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Loads a specific chunk from file if it exists.
     * 
     * @param worldName The name of the world
     * @param world The World instance
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return The loaded chunk, or null if not found
     */
    public Chunk loadChunk(String worldName, World world, int chunkX, int chunkZ) {
        try {
            if (!chunkFileManager.chunkExists(worldName, chunkX, chunkZ)) {
                return null;
            }
            
            ChunkData chunkData = chunkFileManager.loadChunk(worldName, chunkX, chunkZ);
            if (chunkData == null) {
                return null;
            }
            
            // Create new chunk and apply loaded data
            Chunk chunk = new Chunk(chunkX, chunkZ);
            chunkData.applyToChunk(chunk);
            
            loadedChunkCount.incrementAndGet();
            return chunk;
            
        } catch (IOException e) {
            System.err.println("Failed to load chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Loads world metadata from the world.json file.
     * 
     * @param worldName The name of the world
     * @return WorldSaveMetadata object
     * @throws IOException if metadata cannot be loaded
     */
    public WorldSaveMetadata loadWorldMetadata(String worldName) throws IOException {
        Path metadataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
        
        if (!Files.exists(metadataPath)) {
            throw new IOException("World metadata file not found: " + metadataPath);
        }
        
        try {
            WorldSaveMetadata metadata = objectMapper.readValue(metadataPath.toFile(), WorldSaveMetadata.class);
            
            // Validate metadata
            if (metadata.getWorldName() == null || !metadata.getWorldName().equals(worldName)) {
                System.err.println("Warning: World name mismatch in metadata (expected: " + worldName + 
                                 ", found: " + metadata.getWorldName() + ")");
                metadata.setWorldName(worldName);
            }
            
            return metadata;
            
        } catch (IOException e) {
            throw new IOException("Failed to parse world metadata for '" + worldName + "': " + e.getMessage(), e);
        }
    }
    
    /**
     * Validates that world data exists and is accessible.
     * 
     * @param worldName The name of the world to validate
     * @throws IOException if world validation fails
     */
    public void validateWorldData(String worldName) throws IOException {
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        Path metadataFile = worldDir.resolve(WORLD_METADATA_FILE);
        Path chunksDir = worldDir.resolve(CHUNKS_DIRECTORY);
        
        if (!Files.exists(worldDir) || !Files.isDirectory(worldDir)) {
            throw new IOException("World directory does not exist: " + worldDir);
        }
        
        if (!Files.exists(metadataFile)) {
            throw new IOException("World metadata file missing: " + metadataFile);
        }
        
        if (!Files.exists(chunksDir)) {
            System.out.println("Creating chunks directory for world: " + worldName);
            Files.createDirectories(chunksDir);
        }
        
        // Try to load metadata to verify it's valid
        try {
            loadWorldMetadata(worldName);
        } catch (IOException e) {
            throw new IOException("World metadata is corrupted for '" + worldName + "': " + e.getMessage(), e);
        }
        
        System.out.println("World data validation passed for: " + worldName);
    }
    
    /**
     * Checks if a specific chunk can be loaded from the world.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return true if chunk can be loaded, false otherwise
     */
    public boolean canLoadChunk(String worldName, int chunkX, int chunkZ) {
        return chunkFileManager.chunkExists(worldName, chunkX, chunkZ);
    }
    
    /**
     * Sets a progress reporter for loading operations.
     * 
     * @param progressReporter Consumer that receives progress messages
     */
    public void setProgressReporter(Consumer<String> progressReporter) {
        this.progressReporter = progressReporter;
    }
    
    /**
     * Sets a detailed progress reporter for enhanced progress tracking.
     * 
     * @param detailedProgressReporter DetailedProgressReporter that receives phase-specific callbacks
     */
    public void setDetailedProgressReporter(DetailedProgressReporter detailedProgressReporter) {
        this.detailedProgressReporter = detailedProgressReporter;
    }
    
    /**
     * Gets the count of chunks loaded in the current session.
     */
    public int getLoadedChunkCount() {
        return loadedChunkCount.get();
    }
    
    /**
     * Resets the loaded chunk counter.
     */
    public void resetLoadedChunkCount() {
        loadedChunkCount.set(0);
    }
    
    // Private helper methods
    
    /**
     * Validates that the world exists and is accessible.
     */
    private void validateWorldExists(String worldName) throws IOException {
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        if (!Files.exists(worldDir) || !Files.isDirectory(worldDir)) {
            throw new IOException("World does not exist: " + worldName);
        }
    }
    
    /**
     * Comprehensive world validation using SaveFileValidator.
     * Reports validation progress and handles errors gracefully.
     */
    private void validateWorldWithSaveValidator(String worldName) throws IOException {
        // First do basic existence check
        validateWorldExists(worldName);
        
        reportDetailedProgress(LoadingPhase.VALIDATION, 0, 3, "Checking world directory structure");
        updateSubStage("Validating world files", 1, 3);
        
        try {
            // Perform comprehensive validation
            CompletableFuture<SaveFileValidator.ValidationResult> validationFuture = 
                saveFileValidator.validateWorld(worldName);
                
            SaveFileValidator.ValidationResult result = validationFuture.join();
            
            reportDetailedProgress(LoadingPhase.VALIDATION, 2, 3, "Processing validation results");
            
            // Process validation results
            if (result.isValid()) {
                reportDetailedProgress(LoadingPhase.VALIDATION, 3, 3, 
                    "Validation passed: " + result.getInfo().size() + " checks completed");
                    
                // Log any warnings but continue
                if (!result.getWarnings().isEmpty()) {
                    System.out.println("World validation warnings for '" + worldName + "':");
                    for (SaveFileValidator.ValidationMessage warning : result.getWarnings()) {
                        System.out.println("  - " + warning.getMessage());
                    }
                }
                
                System.out.println("World validation successful: " + result.getSummary());
                
            } else {
                // Validation failed - log errors and throw exception
                System.err.println("World validation failed for '" + worldName + "': " + result.getSummary());
                
                StringBuilder errorMsg = new StringBuilder("World validation failed:\n");
                for (SaveFileValidator.ValidationMessage error : result.getErrors()) {
                    errorMsg.append("  - ").append(error.getMessage()).append("\n");
                    System.err.println("  ERROR: " + error.getMessage());
                }
                
                // Also log warnings
                for (SaveFileValidator.ValidationMessage warning : result.getWarnings()) {
                    System.err.println("  WARNING: " + warning.getMessage());
                }
                
                if (detailedProgressReporter != null) {
                    detailedProgressReporter.onError("Validation failed: " + result.getSummary(), 
                        new IOException(errorMsg.toString()));
                }
                
                throw new IOException(errorMsg.toString().trim());
            }
            
        } catch (Exception e) {
            String errorMsg = "Failed to validate world files: " + e.getMessage();
            System.err.println(errorMsg);
            
            if (detailedProgressReporter != null) {
                detailedProgressReporter.onError(errorMsg, e);
            }
            
            if (e instanceof IOException) {
                throw (IOException) e;
            } else {
                throw new IOException(errorMsg, e);
            }
        }
    }
    
    /**
     * Applies metadata to the World instance.
     */
    private void applyMetadataToWorld(WorldSaveMetadata metadata, World world) {
        // Set world seed (critical for consistent generation)
        world.setSeed(metadata.getSeed());
        
        // Set spawn position
        world.setSpawnPosition(metadata.getSpawnPosition());
        
        System.out.println("Applied world metadata: seed=" + metadata.getSeed() + 
                          ", spawn=" + metadata.getSpawnPosition());
    }
    
    /**
     * Loads player data and applies it to the Player instance.
     */
    private void loadPlayerData(String worldName, Player player) throws IOException {
        Path playerDataPath = Paths.get(WORLDS_DIRECTORY, worldName, PLAYER_DATA_FILE);
        
        if (!Files.exists(playerDataPath)) {
            System.out.println("No player data found for world '" + worldName + "', using defaults");
            return;
        }
        
        try {
            PlayerData playerData = objectMapper.readValue(playerDataPath.toFile(), PlayerData.class);
            playerData.applyToPlayer(player);
            
            System.out.println("Loaded player data for world: " + worldName);
            
        } catch (IOException e) {
            System.err.println("Failed to load player data for '" + worldName + "': " + e.getMessage() + 
                             " (using defaults)");
            // Continue with defaults rather than failing
        }
    }
    
    /**
     * Finds all available chunk files in the world.
     */
    private List<ChunkPosition> findAvailableChunks(String worldName) throws IOException {
        List<ChunkPosition> chunks = new ArrayList<>();
        Path chunksDir = Paths.get(WORLDS_DIRECTORY, worldName, CHUNKS_DIRECTORY);
        
        if (!Files.exists(chunksDir)) {
            return chunks; // Empty list if no chunks directory
        }
        
        try {
            Files.list(chunksDir)
                 .filter(Files::isRegularFile)
                 .filter(path -> path.getFileName().toString().endsWith(".json"))
                 .forEach(path -> {
                     ChunkPosition pos = parseChunkPosition(path.getFileName().toString());
                     if (pos != null) {
                         chunks.add(pos);
                     }
                 });
        } catch (IOException e) {
            System.err.println("Error scanning chunks directory for " + worldName + ": " + e.getMessage());
        }
        
        return chunks;
    }
    
    /**
     * Parses chunk position from filename (e.g., "chunk_5_-3.json" -> ChunkPosition(5, -3)).
     */
    private ChunkPosition parseChunkPosition(String filename) {
        try {
            if (!filename.startsWith("chunk_") || !filename.endsWith(".json")) {
                return null;
            }
            
            String coordinates = filename.substring(6, filename.length() - 5); // Remove "chunk_" and ".json"
            String[] parts = coordinates.split("_");
            
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                return new ChunkPosition(x, z);
            }
            
        } catch (NumberFormatException e) {
            System.err.println("Invalid chunk filename format: " + filename);
        }
        
        return null;
    }
    
    /**
     * Loads chunks that are near the player's position.
     */
    private void loadNearbyChunks(String worldName, World world, Player player, List<ChunkPosition> availableChunks) {
        if (availableChunks.isEmpty()) {
            reportProgress("No saved chunks to load");
            return;
        }
        
        // Get player's chunk position
        org.joml.Vector3f playerPos = player.getPosition();
        int playerChunkX = (int) Math.floor(playerPos.x / World.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(playerPos.z / World.CHUNK_SIZE);
        
        // Determine render distance (use a reasonable default)
        int renderDistance = 8; // This should match World.RENDER_DISTANCE if accessible
        
        // Load chunks within render distance
        int loadedCount = 0;
        for (ChunkPosition chunkPos : availableChunks) {
            int deltaX = Math.abs(chunkPos.getX() - playerChunkX);
            int deltaZ = Math.abs(chunkPos.getZ() - playerChunkZ);
            
            if (deltaX <= renderDistance && deltaZ <= renderDistance) {
                Chunk loadedChunk = loadChunk(worldName, world, chunkPos.getX(), chunkPos.getZ());
                if (loadedChunk != null) {
                    // Register chunk with world (this should integrate with existing chunk management)
                    try {
                        world.setChunk(chunkPos.getX(), chunkPos.getZ(), loadedChunk);
                        loadedCount++;
                    } catch (Exception e) {
                        System.err.println("Failed to register loaded chunk (" + chunkPos.getX() + 
                                         "," + chunkPos.getZ() + "): " + e.getMessage());
                    }
                }
            }
        }
        
        reportProgress("Loaded " + loadedCount + " chunks near player");
    }
    
    /**
     * Reports progress to the configured progress reporter.
     */
    private void reportProgress(String message) {
        if (progressReporter != null) {
            progressReporter.accept(message);
        }
        System.out.println("[WorldLoader] " + message);
    }
    
    /**
     * Executes a loading phase with detailed progress tracking.
     */
    private <T> T executePhase(LoadingPhase phase, PhaseExecutor<T> executor) throws Exception {
        long phaseStart = System.currentTimeMillis();
        
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onPhaseStart(phase, phase.getDescription());
        }
        reportProgress(phase.getDescription());
        
        try {
            T result = executor.execute();
            
            long phaseDuration = System.currentTimeMillis() - phaseStart;
            if (detailedProgressReporter != null) {
                detailedProgressReporter.onPhaseComplete(phase, phaseDuration);
            }
            
            return result;
            
        } catch (Exception e) {
            if (detailedProgressReporter != null) {
                detailedProgressReporter.onError("Phase " + phase + " failed: " + e.getMessage(), e);
            }
            throw e;
        }
    }
    
    /**
     * Reports detailed progress for a phase.
     */
    private void reportDetailedProgress(LoadingPhase phase, int current, int total, String details) {
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onPhaseProgress(phase, current, total, details);
        }
    }
    
    /**
     * Updates time estimate based on current progress.
     */
    private void updateTimeEstimate(int completedPhases, int totalPhases) {
        if (detailedProgressReporter != null) {
            long elapsed = System.currentTimeMillis() - loadStartTime;
            double progress = (double) completedPhases / totalPhases;
            
            if (progress > 0) {
                long estimatedTotal = (long) (elapsed / progress);
                long remaining = estimatedTotal - elapsed;
                
                String estimate;
                if (remaining < 1000) {
                    estimate = "< 1 second";
                } else if (remaining < 60000) {
                    estimate = (remaining / 1000) + " seconds";
                } else {
                    estimate = (remaining / 60000) + " minutes";
                }
                
                detailedProgressReporter.onTimeEstimate(estimate);
            }
        }
    }
    
    /**
     * Updates the sub-stage progress for detailed progress reporting.
     */
    private void updateSubStage(String subStage, int progress, int total) {
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onSubStageUpdate(subStage, progress, total);
        }
    }
    
    /**
     * Enhanced chunk loading with progress tracking.
     */
    private void loadNearbyChunksWithProgress(String worldName, World world, Player player, 
                                            List<ChunkPosition> availableChunks) {
        if (availableChunks.isEmpty()) {
            reportDetailedProgress(LoadingPhase.CHUNK_LOADING, 0, 0, "No saved chunks to load");
            return;
        }
        
        // Get player's chunk position
        org.joml.Vector3f playerPos = player.getPosition();
        int playerChunkX = (int) Math.floor(playerPos.x / World.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(playerPos.z / World.CHUNK_SIZE);
        
        // Determine render distance
        int renderDistance = 8;
        
        // Find chunks within render distance
        List<ChunkPosition> nearbyChunks = new ArrayList<>();
        for (ChunkPosition chunkPos : availableChunks) {
            int deltaX = Math.abs(chunkPos.getX() - playerChunkX);
            int deltaZ = Math.abs(chunkPos.getZ() - playerChunkZ);
            
            if (deltaX <= renderDistance && deltaZ <= renderDistance) {
                nearbyChunks.add(chunkPos);
            }
        }
        
        reportDetailedProgress(LoadingPhase.CHUNK_LOADING, 0, nearbyChunks.size(), 
                             "Loading " + nearbyChunks.size() + " chunks near player");
        
        // Load chunks with progress updates
        int loadedCount = 0;
        for (int i = 0; i < nearbyChunks.size(); i++) {
            ChunkPosition chunkPos = nearbyChunks.get(i);
            
            if (detailedProgressReporter != null) {
                detailedProgressReporter.onSubStageUpdate("Loading chunk (" + chunkPos.getX() + 
                    "," + chunkPos.getZ() + ")", i, nearbyChunks.size());
            }
            
            Chunk loadedChunk = loadChunk(worldName, world, chunkPos.getX(), chunkPos.getZ());
            if (loadedChunk != null) {
                try {
                    world.setChunk(chunkPos.getX(), chunkPos.getZ(), loadedChunk);
                    loadedCount++;
                } catch (Exception e) {
                    System.err.println("Failed to register loaded chunk (" + chunkPos.getX() + 
                                     "," + chunkPos.getZ() + "): " + e.getMessage());
                }
            }
            
            // Report progress every 10 chunks or at the end
            if ((i + 1) % 10 == 0 || i == nearbyChunks.size() - 1) {
                reportDetailedProgress(LoadingPhase.CHUNK_LOADING, i + 1, nearbyChunks.size(), 
                                     loadedCount + " chunks loaded successfully");
            }
        }
        
        reportProgress("Loaded " + loadedCount + " chunks near player");
    }
    
    /**
     * Finalizes world loading setup.
     */
    private void finalizeWorldLoading(World world, Player player) {
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onSubStageUpdate("Validating world state", 1, 3);
        }
        
        // Validate world state
        if (world.getSeed() == 0) {
            System.err.println("Warning: World seed not properly set during loading");
        }
        
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onSubStageUpdate("Updating player state", 2, 3);
        }
        
        // Ensure player is in a valid state
        org.joml.Vector3f playerPos = player.getPosition();
        if (playerPos.y < 0) {
            System.err.println("Warning: Player position below world bottom, adjusting");
            playerPos.y = 64;
        }
        
        if (detailedProgressReporter != null) {
            detailedProgressReporter.onSubStageUpdate("Loading complete", 3, 3);
        }
        
        reportDetailedProgress(LoadingPhase.FINALIZATION, 1, 1, 
                             "World ready with " + loadedChunkCount.get() + " chunks");
    }
    
    /**
     * Shuts down the WorldLoader and releases resources.
     * This should be called when the WorldLoader is no longer needed.
     */
    public void shutdown() {
        if (saveFileValidator != null) {
            saveFileValidator.shutdown();
            System.out.println("WorldLoader shutdown complete");
        }
    }
    
    /**
     * Functional interface for phase execution.
     */
    @FunctionalInterface
    private interface PhaseExecutor<T> {
        T execute() throws Exception;
    }
}