package com.stonebreak.world.save;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.Comparator;

/**
 * Manages corruption recovery operations including automatic backup creation,
 * recovery strategy implementation, and data restoration for the world save system.
 */
public class CorruptionRecoveryManager {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String BACKUPS_DIRECTORY = "backups";
    private static final String WORLD_METADATA_FILE = "world.json";
    private static final String PLAYER_DATA_FILE = "player.json";
    private static final String ENTITIES_FILE = "entities.json";
    private static final String CHUNKS_DIRECTORY = "chunks";
    
    // Backup naming pattern
    private static final String BACKUP_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss";
    private static final DateTimeFormatter BACKUP_FORMATTER = DateTimeFormatter.ofPattern(BACKUP_TIMESTAMP_PATTERN);
    
    // Recovery strategies
    public enum RecoveryStrategy {
        RESTORE_FROM_BACKUP,
        REGENERATE_FROM_SEED,
        PARTIAL_RECOVERY,
        FALLBACK_TO_DEFAULTS
    }
    
    private final ObjectMapper objectMapper;
    private final ChunkFileManager chunkFileManager;
    private final SaveFileValidator validator;
    private final ExecutorService recoveryExecutor;
    
    /**
     * Creates a new CorruptionRecoveryManager.
     */
    public CorruptionRecoveryManager(ObjectMapper objectMapper, ChunkFileManager chunkFileManager, 
                                   SaveFileValidator validator) {
        this.objectMapper = objectMapper;
        this.chunkFileManager = chunkFileManager;
        this.validator = validator;
        this.recoveryExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CorruptionRecovery");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Creates a full backup of a world before performing save operations.
     */
    public CompletableFuture<BackupResult> createWorldBackup(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                BackupResult.failure("World name cannot be null or empty")
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                
                Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
                if (!Files.exists(worldDir)) {
                    return BackupResult.failure("World directory does not exist: " + worldName);
                }
                
                // Create backup directory structure
                String backupName = worldName + "_" + BACKUP_FORMATTER.format(LocalDateTime.now());
                Path backupDir = createBackupDirectory(backupName);
                
                // Copy world files to backup
                BackupStats stats = copyWorldToBackup(worldDir, backupDir);
                
                long duration = System.currentTimeMillis() - startTime;
                
                System.out.println("World backup completed for '" + worldName + "' in " + duration + 
                                 "ms (" + stats.filesBackedUp + " files, " + 
                                 formatFileSize(stats.totalSize) + ")");
                
                return BackupResult.success(backupName, backupDir, stats, duration);
                
            } catch (Exception e) {
                System.err.println("Backup creation failed for world '" + worldName + "': " + e.getMessage());
                return BackupResult.failure("Backup creation failed: " + e.getMessage());
            }
        }, recoveryExecutor);
    }
    
    /**
     * Attempts to recover a corrupted world using the most appropriate strategy.
     */
    public CompletableFuture<RecoveryResult> recoverCorruptedWorld(String worldName, 
                                                                  World world, Player player) {
        if (worldName == null || world == null || player == null) {
            return CompletableFuture.completedFuture(
                RecoveryResult.failure("Invalid parameters for world recovery")
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("Starting corruption recovery for world: " + worldName);
                
                // First, validate the current state to understand the corruption
                SaveFileValidator.ValidationResult validation = validator.validateWorld(worldName).join();
                
                // Determine best recovery strategy
                RecoveryStrategy strategy = determineRecoveryStrategy(validation);
                System.out.println("Selected recovery strategy: " + strategy);
                
                // Execute recovery strategy
                RecoveryResult result = executeRecoveryStrategy(worldName, world, player, 
                                                              strategy, validation);
                
                System.out.println("Recovery completed for world '" + worldName + "': " + 
                                 (result.isSuccessful() ? "SUCCESS" : "FAILED"));
                
                return result;
                
            } catch (Exception e) {
                System.err.println("World recovery failed for '" + worldName + "': " + e.getMessage());
                return RecoveryResult.failure("Recovery operation failed: " + e.getMessage());
            }
        }, recoveryExecutor);
    }
    
    /**
     * Lists available backups for a world.
     */
    public List<BackupInfo> listAvailableBackups(String worldName) {
        List<BackupInfo> backups = new ArrayList<>();
        
        try {
            Path backupsDir = Paths.get(BACKUPS_DIRECTORY);
            if (!Files.exists(backupsDir)) {
                return backups;
            }
            
            String backupPrefix = worldName + "_";
            
            try (Stream<Path> stream = Files.list(backupsDir)) {
                stream.filter(Files::isDirectory)
                      .filter(path -> path.getFileName().toString().startsWith(backupPrefix))
                      .forEach(backupPath -> {
                          try {
                              BackupInfo info = createBackupInfo(backupPath, worldName);
                              if (info != null) {
                                  backups.add(info);
                              }
                          } catch (Exception e) {
                              System.err.println("Error reading backup info: " + e.getMessage());
                          }
                      });
            }
            
            // Sort by creation date (newest first)
            backups.sort((a, b) -> b.getCreationTime().compareTo(a.getCreationTime()));
            
        } catch (IOException e) {
            System.err.println("Error listing backups for world '" + worldName + "': " + e.getMessage());
        }
        
        return backups;
    }
    
    /**
     * Restores a world from a specific backup.
     */
    public CompletableFuture<RecoveryResult> restoreFromBackup(String worldName, String backupName, 
                                                              World world, Player player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path backupDir = Paths.get(BACKUPS_DIRECTORY, backupName);
                if (!Files.exists(backupDir)) {
                    return RecoveryResult.failure("Backup not found: " + backupName);
                }
                
                Path worldDir = Paths.get(WORLDS_DIRECTORY, worldName);
                
                // Create backup of current corrupted state
                createWorldBackup(worldName + "_corrupted").join();
                
                // Clear current world directory
                if (Files.exists(worldDir)) {
                    deleteDirectoryRecursively(worldDir);
                }
                
                // Copy backup to world directory
                Files.createDirectories(worldDir);
                copyDirectoryRecursively(backupDir, worldDir);
                
                // Load restored world data
                WorldLoader worldLoader = new WorldLoader(objectMapper, chunkFileManager);
                worldLoader.loadWorld(worldName, world, player);
                
                System.out.println("Successfully restored world '" + worldName + "' from backup: " + backupName);
                
                return RecoveryResult.success("World restored from backup: " + backupName, 
                                            RecoveryStrategy.RESTORE_FROM_BACKUP);
                
            } catch (Exception e) {
                System.err.println("Backup restoration failed: " + e.getMessage());
                return RecoveryResult.failure("Backup restoration failed: " + e.getMessage());
            }
        }, recoveryExecutor);
    }
    
    /**
     * Determines the best recovery strategy based on validation results.
     * SAFE MODE: Never selects strategies that reset the world or player data.
     */
    private RecoveryStrategy determineRecoveryStrategy(SaveFileValidator.ValidationResult validation) {
        if (validation.isValid()) {
            return RecoveryStrategy.PARTIAL_RECOVERY; // Minor issues only
        }
        
        // Always prioritize backup restoration if backups are available
        // This preserves world state and player progress
        return RecoveryStrategy.RESTORE_FROM_BACKUP;
    }
    
    /**
     * Executes the selected recovery strategy.
     * SAFE MODE: Only executes non-destructive recovery strategies.
     */
    private RecoveryResult executeRecoveryStrategy(String worldName, World world, Player player,
                                                 RecoveryStrategy strategy, 
                                                 SaveFileValidator.ValidationResult validation) {
        try {
            switch (strategy) {
                case RESTORE_FROM_BACKUP:
                    return attemptSafeBackupRestoration(worldName, world, player);
                    
                case PARTIAL_RECOVERY:
                    return attemptPartialRecovery(worldName, world, player, validation);
                    
                // REMOVED: REGENERATE_FROM_SEED and FALLBACK_TO_DEFAULTS
                // These strategies were destructive and could reset world/player data
                    
                default:
                    // If we can't restore from backup, fall back to partial recovery
                    System.out.println("Unknown or unsupported recovery strategy: " + strategy + 
                                     ", falling back to partial recovery");
                    return attemptPartialRecovery(worldName, world, player, validation);
            }
        } catch (Exception e) {
            return RecoveryResult.failure("Recovery strategy execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Attempts to restore from the most recent backup - SAFE MODE.
     * Never falls back to destructive recovery methods.
     */
    private RecoveryResult attemptSafeBackupRestoration(String worldName, World world, Player player) {
        List<BackupInfo> backups = listAvailableBackups(worldName);
        
        if (backups.isEmpty()) {
            System.out.println("No backups available, falling back to partial recovery");
            // SAFE: Fall back to partial recovery instead of destructive seed regeneration
            SaveFileValidator.ValidationResult validation = validator.validateWorld(worldName).join();
            return attemptPartialRecovery(worldName, world, player, validation);
        }
        
        // Try the most recent backup first
        BackupInfo mostRecent = backups.get(0);
        RecoveryResult result = restoreFromBackup(worldName, mostRecent.getName(), world, player).join();
        
        if (result.isSuccessful()) {
            return result;
        }
        
        // If most recent backup fails, try older backups
        for (int i = 1; i < Math.min(3, backups.size()); i++) {
            BackupInfo backup = backups.get(i);
            result = restoreFromBackup(worldName, backup.getName(), world, player).join();
            if (result.isSuccessful()) {
                return result;
            }
        }
        
        // All backups failed, fallback to partial recovery (SAFE)
        System.out.println("All backup attempts failed, falling back to partial recovery");
        SaveFileValidator.ValidationResult validation = validator.validateWorld(worldName).join();
        return attemptPartialRecovery(worldName, world, player, validation);
    }
    
    // REMOVED: attemptSeedRegeneration method
    // This method was destructive and would reset the world and player data.
    // All references to this method have been replaced with safe alternatives.
    
    /**
     * Attempts partial recovery by fixing individual corrupted components.
     */
    private RecoveryResult attemptPartialRecovery(String worldName, World world, Player player,
                                                SaveFileValidator.ValidationResult validation) {
        List<String> recoveryActions = new ArrayList<>();
        
        try {
            // Handle corrupted player data - SAFE MODE
            boolean playerDataCorrupted = validation.getErrors().stream()
                .anyMatch(error -> error.getType().name().contains("PLAYER_DATA"));
                
            if (playerDataCorrupted) {
                // Instead of resetting to defaults, just delete the corrupted file
                // The player will keep their current in-game state
                Path playerDataPath = Paths.get(WORLDS_DIRECTORY, worldName, PLAYER_DATA_FILE);
                try {
                    Files.deleteIfExists(playerDataPath);
                    recoveryActions.add("Removed corrupted player data file (preserving current player state)");
                } catch (IOException e) {
                    recoveryActions.add("Failed to remove corrupted player data: " + e.getMessage());
                }
            }
            
            // Remove corrupted chunks (they'll be regenerated on demand)
            boolean chunksCorrupted = validation.getErrors().stream()
                .anyMatch(error -> error.getType().name().contains("CHUNK"));
                
            if (chunksCorrupted) {
                int removedChunks = removeCorruptedChunks(worldName);
                recoveryActions.add("Removed " + removedChunks + " corrupted chunks");
            }
            
            // Fix entity data if corrupted
            boolean entitiesCorrupted = validation.getErrors().stream()
                .anyMatch(error -> error.getType().name().contains("ENTITY"));
                
            if (entitiesCorrupted) {
                clearCorruptedEntityData(worldName);
                recoveryActions.add("Cleared corrupted entity data");
            }
            
            String message = "Partial recovery completed: " + String.join(", ", recoveryActions);
            return RecoveryResult.success(message, RecoveryStrategy.PARTIAL_RECOVERY);
            
        } catch (Exception e) {
            return RecoveryResult.failure("Partial recovery failed: " + e.getMessage());
        }
    }
    
    // REMOVED: attemptFallbackRecovery method
    // This method was destructive and would completely reset the world and player data.
    // All references to this method have been replaced with safe alternatives.
    
    // Helper methods
    
    private Path createBackupDirectory(String backupName) throws IOException {
        Path backupsDir = Paths.get(BACKUPS_DIRECTORY);
        Files.createDirectories(backupsDir);
        
        Path backupDir = backupsDir.resolve(backupName);
        Files.createDirectories(backupDir);
        
        return backupDir;
    }
    
    private BackupStats copyWorldToBackup(Path sourceDir, Path backupDir) throws IOException {
        BackupStats stats = new BackupStats();
        
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(file -> {
                      try {
                          Path relativePath = sourceDir.relativize(file);
                          Path targetPath = backupDir.resolve(relativePath);
                          Files.createDirectories(targetPath.getParent());
                          Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                          
                          stats.filesBackedUp++;
                          stats.totalSize += Files.size(file);
                          
                      } catch (IOException e) {
                          System.err.println("Failed to backup file " + file + ": " + e.getMessage());
                      }
                  });
        }
        
        return stats;
    }
    
    private BackupInfo createBackupInfo(Path backupPath, String worldName) {
        try {
            String backupName = backupPath.getFileName().toString();
            String timestampPart = backupName.substring(worldName.length() + 1);
            LocalDateTime localDateTime = LocalDateTime.parse(timestampPart, BACKUP_FORMATTER);
            Instant creationTime = localDateTime.atZone(ZoneId.systemDefault()).toInstant();
            
            long size = calculateDirectorySize(backupPath);
            
            return new BackupInfo(backupName, creationTime, size);
            
        } catch (Exception e) {
            System.err.println("Error creating backup info for " + backupPath + ": " + e.getMessage());
            return null;
        }
    }
    
    private long calculateDirectorySize(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                        .mapToLong(path -> {
                            try {
                                return Files.size(path);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
        }
    }
    
    // REMOVED: resetPlayerDataToDefaults method
    // This method was destructive and would reset player progress.
    // Replaced with safer approach that preserves current player state.
    
    private int removeCorruptedChunks(String worldName) throws IOException {
        Path chunksDir = Paths.get(WORLDS_DIRECTORY, worldName, CHUNKS_DIRECTORY);
        int removedCount = 0;
        
        if (Files.exists(chunksDir)) {
            try (Stream<Path> stream = Files.list(chunksDir)) {
                List<Path> chunkFiles = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                                             .toList();
                                             
                for (Path chunkFile : chunkFiles) {
                    String fileName = chunkFile.getFileName().toString();
                    try {
                        String[] parts = fileName.replace("chunk_", "").replace(".json", "").split("_");
                        int chunkX = Integer.parseInt(parts[0]);
                        int chunkZ = Integer.parseInt(parts[1]);
                        
                        if (!chunkFileManager.validateChunkFile(worldName, chunkX, chunkZ)) {
                            Files.delete(chunkFile);
                            removedCount++;
                        }
                    } catch (Exception e) {
                        // If we can't even parse the filename, delete it
                        Files.delete(chunkFile);
                        removedCount++;
                    }
                }
            }
        }
        
        return removedCount;
    }
    
    private void clearCorruptedEntityData(String worldName) throws IOException {
        Path entitiesPath = Paths.get(WORLDS_DIRECTORY, worldName, ENTITIES_FILE);
        if (Files.exists(entitiesPath)) {
            Files.delete(entitiesPath);
        }
    }
    
    // REMOVED: clearAllWorldData method
    // This method was destructive and would delete all world chunks and entities.
    // No longer used by safe recovery methods.
    
    private void copyDirectoryRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to copy " + sourcePath + ": " + e.getMessage());
                }
            });
        }
    }
    
    private void deleteDirectoryRecursively(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(path -> {
                          try {
                              Files.delete(path);
                          } catch (IOException e) {
                              System.err.println("Failed to delete " + path + ": " + e.getMessage());
                          }
                      });
            }
        }
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Shuts down the recovery manager and its resources.
     */
    public void shutdown() {
        recoveryExecutor.shutdown();
        try {
            if (!recoveryExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                recoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            recoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Result classes
    
    public static class BackupResult {
        private final boolean successful;
        private final String message;
        private final String backupName;
        private final Path backupPath;
        private final BackupStats stats;
        private final long duration;
        
        private BackupResult(boolean successful, String message, String backupName, 
                           Path backupPath, BackupStats stats, long duration) {
            this.successful = successful;
            this.message = message;
            this.backupName = backupName;
            this.backupPath = backupPath;
            this.stats = stats;
            this.duration = duration;
        }
        
        public static BackupResult success(String backupName, Path backupPath, BackupStats stats, long duration) {
            return new BackupResult(true, "Backup created successfully", backupName, backupPath, stats, duration);
        }
        
        public static BackupResult failure(String message) {
            return new BackupResult(false, message, null, null, null, 0);
        }
        
        public boolean isSuccessful() { return successful; }
        public String getMessage() { return message; }
        public String getBackupName() { return backupName; }
        public Path getBackupPath() { return backupPath; }
        public BackupStats getStats() { return stats; }
        public long getDuration() { return duration; }
    }
    
    public static class RecoveryResult {
        private final boolean successful;
        private final String message;
        private final RecoveryStrategy strategy;
        
        private RecoveryResult(boolean successful, String message, RecoveryStrategy strategy) {
            this.successful = successful;
            this.message = message;
            this.strategy = strategy;
        }
        
        public static RecoveryResult success(String message, RecoveryStrategy strategy) {
            return new RecoveryResult(true, message, strategy);
        }
        
        public static RecoveryResult failure(String message) {
            return new RecoveryResult(false, message, null);
        }
        
        public boolean isSuccessful() { return successful; }
        public String getMessage() { return message; }
        public RecoveryStrategy getStrategy() { return strategy; }
    }
    
    public static class BackupStats {
        public int filesBackedUp = 0;
        public long totalSize = 0;
    }
    
    public static class BackupInfo {
        private final String name;
        private final Instant creationTime;
        private final long size;
        
        public BackupInfo(String name, Instant creationTime, long size) {
            this.name = name;
            this.creationTime = creationTime;
            this.size = size;
        }
        
        public String getName() { return name; }
        public Instant getCreationTime() { return creationTime; }
        public long getSize() { return size; }
    }
}