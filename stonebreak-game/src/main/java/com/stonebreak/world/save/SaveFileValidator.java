package com.stonebreak.world.save;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Comprehensive save file validation system with checksum verification,
 * schema validation, and integrity checking for the world save system.
 */
public class SaveFileValidator {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_METADATA_FILE = "world.json";
    private static final String CHUNKS_DIRECTORY = "chunks";
    private static final String PLAYER_DATA_FILE = "player.json";
    private static final String ENTITIES_FILE = "entities.json";
    private static final String VALIDATION_CACHE_FILE = ".validation_cache";
    
    // Schema version for backward compatibility
    private static final int CURRENT_SCHEMA_VERSION = 1;
    
    private final ObjectMapper objectMapper;
    private final ChunkFileManager chunkFileManager;
    private final ExecutorService validationExecutor;
    private final Map<String, ValidationCache> validationCache;
    
    /**
     * Creates a new SaveFileValidator.
     */
    public SaveFileValidator(ObjectMapper objectMapper, ChunkFileManager chunkFileManager) {
        this.objectMapper = objectMapper;
        this.chunkFileManager = chunkFileManager;
        this.validationExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SaveFileValidator");
            t.setDaemon(true);
            return t;
        });
        this.validationCache = new HashMap<>();
    }
    
    /**
     * Performs comprehensive validation of a world's save files.
     */
    public CompletableFuture<ValidationResult> validateWorld(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                ValidationResult.failure("World name cannot be null or empty")
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                ValidationResult.Builder builder = new ValidationResult.Builder();
                
                // Validate world directory structure
                validateWorldDirectory(worldName, builder);
                
                // Validate metadata file
                validateMetadataFile(worldName, builder);
                
                // Validate player data (if exists)
                validatePlayerData(worldName, builder);
                
                // Validate entities file (if exists)
                validateEntitiesFile(worldName, builder);
                
                // Validate chunks directory and files
                validateChunksDirectory(worldName, builder);
                
                // Check for orphaned or corrupt files
                validateFileIntegrity(worldName, builder);
                
                return builder.build();
                
            } catch (Exception e) {
                return ValidationResult.failure("Validation failed with exception: " + e.getMessage());
            }
        }, validationExecutor);
    }
    
    /**
     * Validates world directory structure exists and is accessible.
     */
    private void validateWorldDirectory(String worldName, ValidationResult.Builder builder) {
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        
        if (!Files.exists(worldDir)) {
            builder.addError(ValidationError.MISSING_WORLD_DIRECTORY, 
                "World directory does not exist: " + worldDir);
            return;
        }
        
        if (!Files.isDirectory(worldDir)) {
            builder.addError(ValidationError.INVALID_WORLD_DIRECTORY,
                "World path is not a directory: " + worldDir);
            return;
        }
        
        if (!Files.isReadable(worldDir)) {
            builder.addError(ValidationError.UNREADABLE_WORLD_DIRECTORY,
                "World directory is not readable: " + worldDir);
            return;
        }
        
        builder.addInfo("World directory structure is valid");
    }
    
    /**
     * Validates world metadata file integrity and schema compatibility.
     */
    private void validateMetadataFile(String worldName, ValidationResult.Builder builder) {
        Path metadataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_METADATA_FILE);
        
        if (!Files.exists(metadataPath)) {
            builder.addError(ValidationError.MISSING_METADATA,
                "World metadata file missing: " + metadataPath);
            return;
        }
        
        try {
            // Validate file integrity with checksum
            String checksum = calculateFileChecksum(metadataPath);
            ValidationCache cache = getValidationCache(worldName);
            
            if (cache != null && cache.metadataChecksum != null && 
                cache.metadataChecksum.equals(checksum) && 
                cache.isRecentlyValidated()) {
                builder.addInfo("Metadata file validation cached (valid)");
                return;
            }
            
            // Load and validate metadata structure
            WorldSaveMetadata metadata = objectMapper.readValue(metadataPath.toFile(), WorldSaveMetadata.class);
            
            // Schema version validation
            int schemaVersion = metadata.getSchemaVersion() != null ? metadata.getSchemaVersion() : 1;
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                builder.addWarning(ValidationError.FUTURE_SCHEMA_VERSION,
                    "Metadata uses future schema version " + schemaVersion + 
                    " (current: " + CURRENT_SCHEMA_VERSION + ")");
            }
            
            // Validate required fields
            if (metadata.getWorldName() == null || metadata.getWorldName().trim().isEmpty()) {
                builder.addError(ValidationError.INVALID_METADATA,
                    "Metadata missing world name");
            }
            
            // Note: seed is a primitive long, so cannot be null - just validate it's reasonable
            // A seed of 0 is valid, so we don't need to validate it further
            
            if (metadata.getCreationTime() == null) {
                builder.addWarning(ValidationError.INVALID_METADATA,
                    "Metadata missing creation time");
            }
            
            // Update validation cache
            updateValidationCache(worldName, checksum, null, null, null);
            
            builder.addInfo("Metadata file is valid");
            
        } catch (IOException e) {
            builder.addError(ValidationError.CORRUPT_METADATA,
                "Failed to read metadata file: " + e.getMessage());
        } catch (Exception e) {
            builder.addError(ValidationError.CORRUPT_METADATA,
                "Metadata file corruption detected: " + e.getMessage());
        }
    }
    
    /**
     * Validates player data file if it exists.
     */
    private void validatePlayerData(String worldName, ValidationResult.Builder builder) {
        Path playerDataPath = Paths.get(WORLDS_DIRECTORY, worldName, PLAYER_DATA_FILE);
        
        if (!Files.exists(playerDataPath)) {
            builder.addInfo("No player data file found (new world)");
            return;
        }
        
        try {
            String checksum = calculateFileChecksum(playerDataPath);
            ValidationCache cache = getValidationCache(worldName);
            
            if (cache != null && cache.playerDataChecksum != null && 
                cache.playerDataChecksum.equals(checksum) && 
                cache.isRecentlyValidated()) {
                builder.addInfo("Player data validation cached (valid)");
                return;
            }
            
            // Load and validate player data structure
            PlayerData playerData = objectMapper.readValue(playerDataPath.toFile(), PlayerData.class);
            
            // Validate player data fields
            if (playerData.getPosition() == null) {
                builder.addError(ValidationError.INVALID_PLAYER_DATA,
                    "Player data missing position");
            }
            
            if (playerData.getInventory() == null) {
                builder.addError(ValidationError.INVALID_PLAYER_DATA,
                    "Player data missing inventory");
            }
            
            // Update validation cache
            ValidationCache currentCache = getValidationCache(worldName);
            if (currentCache != null) {
                currentCache.playerDataChecksum = checksum;
                currentCache.lastValidated = Instant.now();
            }
            
            builder.addInfo("Player data file is valid");
            
        } catch (IOException e) {
            builder.addError(ValidationError.CORRUPT_PLAYER_DATA,
                "Failed to read player data file: " + e.getMessage());
        } catch (Exception e) {
            builder.addError(ValidationError.CORRUPT_PLAYER_DATA,
                "Player data file corruption detected: " + e.getMessage());
        }
    }
    
    /**
     * Validates entities file if it exists.
     */
    private void validateEntitiesFile(String worldName, ValidationResult.Builder builder) {
        Path entitiesPath = Paths.get(WORLDS_DIRECTORY, worldName, ENTITIES_FILE);
        
        if (!Files.exists(entitiesPath)) {
            builder.addInfo("No entities file found");
            return;
        }
        
        try {
            // Load and validate entities data structure
            List<EntityData> entities = objectMapper.readValue(entitiesPath.toFile(), 
                objectMapper.getTypeFactory().constructCollectionType(List.class, EntityData.class));
            
            // Validate entity data
            for (EntityData entity : entities) {
                if (entity.getPosition() == null) {
                    builder.addWarning(ValidationError.INVALID_ENTITY_DATA,
                        "Entity missing position data");
                }
                if (entity.getType() == null || entity.getType().trim().isEmpty()) {
                    builder.addWarning(ValidationError.INVALID_ENTITY_DATA,
                        "Entity missing type information");
                }
            }
            
            builder.addInfo("Entities file is valid (" + entities.size() + " entities)");
            
        } catch (IOException e) {
            builder.addError(ValidationError.CORRUPT_ENTITY_DATA,
                "Failed to read entities file: " + e.getMessage());
        } catch (Exception e) {
            builder.addError(ValidationError.CORRUPT_ENTITY_DATA,
                "Entities file corruption detected: " + e.getMessage());
        }
    }
    
    /**
     * Validates chunks directory and performs sampling of chunk files.
     */
    private void validateChunksDirectory(String worldName, ValidationResult.Builder builder) {
        Path chunksDir = Paths.get(WORLDS_DIRECTORY, worldName, CHUNKS_DIRECTORY);
        
        if (!Files.exists(chunksDir)) {
            builder.addInfo("No chunks directory found (new world)");
            return;
        }
        
        if (!Files.isDirectory(chunksDir)) {
            builder.addError(ValidationError.INVALID_CHUNKS_DIRECTORY,
                "Chunks path is not a directory: " + chunksDir);
            return;
        }
        
        try {
            List<Path> chunkFiles = Files.list(chunksDir)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> path.getFileName().toString().startsWith("chunk_"))
                .toList();
                
            if (chunkFiles.isEmpty()) {
                builder.addInfo("Chunks directory is empty (new world)");
                return;
            }
            
            // Sample validation of chunk files (validate up to 10% or at least 5)
            int sampleSize = Math.max(5, chunkFiles.size() / 10);
            List<Path> sampleFiles = chunkFiles.stream()
                .limit(sampleSize)
                .toList();
                
            int validChunks = 0;
            int corruptChunks = 0;
            
            for (Path chunkFile : sampleFiles) {
                String fileName = chunkFile.getFileName().toString();
                
                // Extract coordinates from filename
                try {
                    String[] parts = fileName.replace("chunk_", "").replace(".json", "").split("_");
                    if (parts.length != 2) {
                        builder.addWarning(ValidationError.INVALID_CHUNK_FILENAME,
                            "Invalid chunk filename format: " + fileName);
                        continue;
                    }
                    
                    int chunkX = Integer.parseInt(parts[0]);
                    int chunkZ = Integer.parseInt(parts[1]);
                    
                    // Use ChunkFileManager's validation
                    if (chunkFileManager.validateChunkFile(worldName, chunkX, chunkZ)) {
                        validChunks++;
                    } else {
                        corruptChunks++;
                        builder.addWarning(ValidationError.CORRUPT_CHUNK_FILE,
                            "Chunk file validation failed: " + fileName);
                    }
                    
                } catch (NumberFormatException e) {
                    builder.addWarning(ValidationError.INVALID_CHUNK_FILENAME,
                        "Cannot parse chunk coordinates from filename: " + fileName);
                }
            }
            
            builder.addInfo("Chunk validation complete: " + validChunks + " valid, " + 
                          corruptChunks + " corrupt (sampled " + sampleFiles.size() + 
                          " of " + chunkFiles.size() + " total)");
                          
            // If more than 10% of sampled chunks are corrupt, it's a serious issue
            if (corruptChunks > 0 && (double)corruptChunks / sampleFiles.size() > 0.1) {
                builder.addError(ValidationError.HIGH_CHUNK_CORRUPTION,
                    "High chunk corruption rate detected: " + 
                    String.format("%.1f%%", (double)corruptChunks / sampleFiles.size() * 100));
            }
            
        } catch (IOException e) {
            builder.addError(ValidationError.CHUNKS_DIRECTORY_ERROR,
                "Error accessing chunks directory: " + e.getMessage());
        }
    }
    
    /**
     * Validates overall file integrity and checks for orphaned files.
     */
    private void validateFileIntegrity(String worldName, ValidationResult.Builder builder) {
        Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
        
        try {
            // Check for unexpected files that might indicate corruption
            List<Path> allFiles = Files.walk(worldDir)
                .filter(Files::isRegularFile)
                .toList();
                
            Set<String> expectedExtensions = Set.of(".json", ".backup", ".tmp");
            Set<String> expectedFilenames = Set.of("world.json", "player.json", "entities.json", 
                ".validation_cache");
            
            for (Path file : allFiles) {
                String fileName = file.getFileName().toString();
                String extension = fileName.substring(fileName.lastIndexOf('.'));
                
                // Check for temporary files that might indicate interrupted operations
                if (fileName.endsWith(".tmp")) {
                    builder.addWarning(ValidationError.TEMPORARY_FILES_FOUND,
                        "Temporary file found (possible interrupted operation): " + fileName);
                }
                
                // Check for unexpected file types
                Path relativePath = worldDir.relativize(file);
                boolean isInChunksDir = relativePath.toString().startsWith(CHUNKS_DIRECTORY);
                boolean isExpectedFile = expectedFilenames.contains(fileName) || 
                    (isInChunksDir && fileName.startsWith("chunk_"));
                
                if (!isExpectedFile && !expectedExtensions.contains(extension)) {
                    builder.addWarning(ValidationError.UNEXPECTED_FILE,
                        "Unexpected file found: " + relativePath);
                }
            }
            
            builder.addInfo("File integrity check completed (" + allFiles.size() + " files examined)");
            
        } catch (IOException e) {
            builder.addError(ValidationError.FILE_INTEGRITY_ERROR,
                "Error during file integrity check: " + e.getMessage());
        }
    }
    
    /**
     * Calculates SHA-256 checksum of a file.
     */
    private String calculateFileChecksum(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
            
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Gets validation cache for a world.
     */
    private ValidationCache getValidationCache(String worldName) {
        return validationCache.computeIfAbsent(worldName, k -> loadValidationCache(worldName));
    }
    
    /**
     * Loads validation cache from file.
     */
    private ValidationCache loadValidationCache(String worldName) {
        Path cachePath = Paths.get(WORLDS_DIRECTORY, worldName, VALIDATION_CACHE_FILE);
        
        if (!Files.exists(cachePath)) {
            return new ValidationCache();
        }
        
        try {
            return objectMapper.readValue(cachePath.toFile(), ValidationCache.class);
        } catch (IOException e) {
            System.err.println("Failed to load validation cache for " + worldName + ": " + e.getMessage());
            return new ValidationCache();
        }
    }
    
    /**
     * Updates validation cache for a world.
     */
    private void updateValidationCache(String worldName, String metadataChecksum, 
                                     String playerDataChecksum, String entitiesChecksum, 
                                     String chunksChecksum) {
        ValidationCache cache = getValidationCache(worldName);
        
        if (metadataChecksum != null) cache.metadataChecksum = metadataChecksum;
        if (playerDataChecksum != null) cache.playerDataChecksum = playerDataChecksum;
        if (entitiesChecksum != null) cache.entitiesChecksum = entitiesChecksum;
        if (chunksChecksum != null) cache.chunksChecksum = chunksChecksum;
        cache.lastValidated = Instant.now();
        
        // Save cache to file
        try {
            Path cachePath = Paths.get(WORLDS_DIRECTORY, worldName, VALIDATION_CACHE_FILE);
            objectMapper.writeValue(cachePath.toFile(), cache);
        } catch (IOException e) {
            System.err.println("Failed to save validation cache for " + worldName + ": " + e.getMessage());
        }
    }
    
    /**
     * Shuts down the validator and its resources.
     */
    public void shutdown() {
        validationExecutor.shutdown();
        try {
            if (!validationExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                validationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            validationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Validation cache to avoid re-validating unchanged files.
     */
    public static class ValidationCache {
        public String metadataChecksum;
        public String playerDataChecksum;
        public String entitiesChecksum;
        public String chunksChecksum;
        public Instant lastValidated;
        
        public ValidationCache() {
            this.lastValidated = Instant.now();
        }
        
        public boolean isRecentlyValidated() {
            return lastValidated != null && 
                   Instant.now().minus(1, ChronoUnit.HOURS).isBefore(lastValidated);
        }
    }
    
    /**
     * Enumeration of validation error types.
     */
    public enum ValidationError {
        MISSING_WORLD_DIRECTORY,
        INVALID_WORLD_DIRECTORY,
        UNREADABLE_WORLD_DIRECTORY,
        MISSING_METADATA,
        CORRUPT_METADATA,
        INVALID_METADATA,
        FUTURE_SCHEMA_VERSION,
        CORRUPT_PLAYER_DATA,
        INVALID_PLAYER_DATA,
        CORRUPT_ENTITY_DATA,
        INVALID_ENTITY_DATA,
        INVALID_CHUNKS_DIRECTORY,
        CORRUPT_CHUNK_FILE,
        INVALID_CHUNK_FILENAME,
        HIGH_CHUNK_CORRUPTION,
        CHUNKS_DIRECTORY_ERROR,
        TEMPORARY_FILES_FOUND,
        UNEXPECTED_FILE,
        FILE_INTEGRITY_ERROR
    }
    
    /**
     * Validation result with detailed information about the validation process.
     */
    public static class ValidationResult {
        private final boolean isValid;
        private final List<ValidationMessage> errors;
        private final List<ValidationMessage> warnings;
        private final List<String> info;
        private final String summary;
        
        private ValidationResult(boolean isValid, List<ValidationMessage> errors, 
                               List<ValidationMessage> warnings, List<String> info, String summary) {
            this.isValid = isValid;
            this.errors = Collections.unmodifiableList(errors);
            this.warnings = Collections.unmodifiableList(warnings);
            this.info = Collections.unmodifiableList(info);
            this.summary = summary;
        }
        
        public boolean isValid() { return isValid; }
        public List<ValidationMessage> getErrors() { return errors; }
        public List<ValidationMessage> getWarnings() { return warnings; }
        public List<String> getInfo() { return info; }
        public String getSummary() { return summary; }
        
        public static ValidationResult failure(String message) {
            List<ValidationMessage> errors = List.of(new ValidationMessage(ValidationError.FILE_INTEGRITY_ERROR, message));
            return new ValidationResult(false, errors, Collections.emptyList(), 
                                      Collections.emptyList(), "Validation failed: " + message);
        }
        
        public static class Builder {
            private final List<ValidationMessage> errors = new ArrayList<>();
            private final List<ValidationMessage> warnings = new ArrayList<>();
            private final List<String> info = new ArrayList<>();
            
            public void addError(ValidationError type, String message) {
                errors.add(new ValidationMessage(type, message));
            }
            
            public void addWarning(ValidationError type, String message) {
                warnings.add(new ValidationMessage(type, message));
            }
            
            public void addInfo(String message) {
                info.add(message);
            }
            
            public ValidationResult build() {
                boolean isValid = errors.isEmpty();
                String summary = generateSummary(isValid, errors.size(), warnings.size());
                return new ValidationResult(isValid, errors, warnings, info, summary);
            }
            
            private String generateSummary(boolean isValid, int errorCount, int warningCount) {
                if (isValid && warningCount == 0) {
                    return "World validation successful - all files are valid";
                } else if (isValid) {
                    return "World validation successful with " + warningCount + " warning(s)";
                } else {
                    return "World validation failed with " + errorCount + " error(s)" + 
                           (warningCount > 0 ? " and " + warningCount + " warning(s)" : "");
                }
            }
        }
    }
    
    /**
     * Validation message with error type and description.
     */
    public static class ValidationMessage {
        private final ValidationError type;
        private final String message;
        
        public ValidationMessage(ValidationError type, String message) {
            this.type = type;
            this.message = message;
        }
        
        public ValidationError getType() { return type; }
        public String getMessage() { return message; }
        
        @Override
        public String toString() {
            return type.name() + ": " + message;
        }
    }
}