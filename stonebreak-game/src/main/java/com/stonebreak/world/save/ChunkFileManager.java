package com.stonebreak.world.save;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Handles individual chunk file I/O operations with thread safety and compression support.
 * Manages the directory structure: worlds/[worldName]/chunks/chunk_x_z.json
 */
public class ChunkFileManager {
    
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String CHUNKS_DIRECTORY = "chunks";
    private static final String CHUNK_FILE_EXTENSION = ".json";
    private static final String CHUNK_FILE_PREFIX = "chunk_";
    private static final String TEMP_FILE_SUFFIX = ".tmp";
    
    // Jackson ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper;
    
    // Thread safety for concurrent file operations
    private final ReadWriteLock fileLock = new ReentrantReadWriteLock();
    
    // Compression threshold (chunks larger than this will be compressed)
    private static final double COMPRESSION_THRESHOLD = 0.3; // Save if compression ratio < 30%
    
    // ChunkFileInfo caching for performance
    private final Map<String, ChunkFileInfo> infoCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MS = 30000; // 30 seconds
    private static final int MAX_CACHE_SIZE = 1000;
    
    /**
     * Creates a new ChunkFileManager.
     * 
     * @param objectMapper The Jackson ObjectMapper for JSON operations
     */
    public ChunkFileManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        System.out.println("ChunkFileManager initialized");
    }
    
    /**
     * Saves a chunk to file with atomic write operations.
     * 
     * @param chunkData The chunk data to save
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @throws IOException if save operation fails
     */
    public void saveChunk(ChunkData chunkData, String worldName, int chunkX, int chunkZ) throws IOException {
        if (chunkData == null || worldName == null) {
            throw new IllegalArgumentException("ChunkData and worldName cannot be null");
        }
        
        // Validate chunk coordinates match
        if (chunkData.getChunkX() != chunkX || chunkData.getChunkZ() != chunkZ) {
            throw new IllegalArgumentException("Chunk coordinates mismatch: data has (" + 
                                             chunkData.getChunkX() + "," + chunkData.getChunkZ() + 
                                             ") but expected (" + chunkX + "," + chunkZ + ")");
        }
        
        fileLock.writeLock().lock();
        try {
            // Create chunks directory if it doesn't exist
            Path chunksDir = getChunksDirectory(worldName);
            Files.createDirectories(chunksDir);
            
            // Generate file paths
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            Path tempFile = Paths.get(chunkFile.toString() + TEMP_FILE_SUFFIX);
            
            // Write to temporary file first (atomic write)
            objectMapper.writeValue(tempFile.toFile(), chunkData);
            
            // Move temporary file to final location
            Files.move(tempFile, chunkFile, StandardCopyOption.REPLACE_EXISTING);
            
            // Log compression efficiency
            if (chunkData.isSparse()) {
                double compressionRatio = chunkData.getCompressionRatio();
                System.out.println("Saved chunk (" + chunkX + "," + chunkZ + ") with " + 
                                 String.format("%.1f%%", compressionRatio * 100) + " compression ratio");
            }
            
        } catch (IOException e) {
            System.err.println("Failed to save chunk (" + chunkX + "," + chunkZ + ") for world '" + worldName + "': " + e.getMessage());
            throw e;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    /**
     * Loads a chunk from file.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return ChunkData object, or null if chunk file doesn't exist
     * @throws IOException if load operation fails
     */
    public ChunkData loadChunk(String worldName, int chunkX, int chunkZ) throws IOException {
        if (worldName == null) {
            throw new IllegalArgumentException("WorldName cannot be null");
        }
        
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                return null; // Chunk doesn't exist
            }
            
            ChunkData chunkData;
            try {
                chunkData = objectMapper.readValue(chunkFile.toFile(), ChunkData.class);
                System.out.println("Successfully deserialized chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "'");
            } catch (com.fasterxml.jackson.core.JsonParseException e) {
                System.err.println("JSON parsing failed for chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
                System.err.println("File path: " + chunkFile);
                throw new IOException("Invalid JSON in chunk file", e);
            } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
                System.err.println("JSON mapping failed for chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
                System.err.println("This could indicate ChunkData class structure changes or missing default constructor");
                throw new IOException("Failed to map JSON to ChunkData object", e);
            }
            
            // Validate loaded data
            if (chunkData.getChunkX() != chunkX || chunkData.getChunkZ() != chunkZ) {
                System.err.println("Warning: Chunk coordinate mismatch in file (" + chunkX + "," + chunkZ + 
                                 ") - file contains (" + chunkData.getChunkX() + "," + chunkData.getChunkZ() + ")");
                // Fix coordinates
                chunkData.setChunkX(chunkX);
                chunkData.setChunkZ(chunkZ);
            }
            
            return chunkData;
            
        } catch (IOException e) {
            System.err.println("Failed to load chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            throw e;
        } finally {
            fileLock.readLock().unlock();
        }
    }
    
    /**
     * Checks if a chunk file exists.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return true if chunk file exists, false otherwise
     */
    public boolean chunkExists(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) {
            return false;
        }
        
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            return Files.exists(chunkFile) && Files.isRegularFile(chunkFile);
        } finally {
            fileLock.readLock().unlock();
        }
    }
    
    /**
     * Deletes a chunk file.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return true if chunk was deleted, false if it didn't exist
     * @throws IOException if delete operation fails
     */
    public boolean deleteChunk(String worldName, int chunkX, int chunkZ) throws IOException {
        if (worldName == null) {
            throw new IllegalArgumentException("WorldName cannot be null");
        }
        
        fileLock.writeLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                return false; // Chunk doesn't exist
            }
            
            Files.delete(chunkFile);
            System.out.println("Deleted chunk file (" + chunkX + "," + chunkZ + ") from world: " + worldName);
            return true;
            
        } catch (IOException e) {
            System.err.println("Failed to delete chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            throw e;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the size of a chunk file in bytes.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return File size in bytes, or -1 if file doesn't exist
     */
    public long getChunkFileSize(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) {
            return -1;
        }
        
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                return -1;
            }
            
            return Files.size(chunkFile);
            
        } catch (IOException e) {
            System.err.println("Failed to get size of chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            return -1;
        } finally {
            fileLock.readLock().unlock();
        }
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * The basic getChunkFileInfo(worldName, chunkX, chunkZ) method is still available.
     *
     * Gets comprehensive information about a chunk file including extended metadata.
     * Uses caching to improve performance for frequently accessed chunks.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @param loadExtendedMetadata Whether to load extended metadata from chunk data
     * @return ChunkFileInfo object, or null if file doesn't exist
     */
    /*
    public ChunkFileInfo getChunkFileInfo(String worldName, int chunkX, int chunkZ, boolean loadExtendedMetadata) {
        if (worldName == null) {
            return null;
        }
        
        String cacheKey = getCacheKey(worldName, chunkX, chunkZ);
        
        // Check cache first
        ChunkFileInfo cachedInfo = infoCache.get(cacheKey);
        if (cachedInfo != null && !cachedInfo.needsValidation(CACHE_EXPIRY_MS)) {
            // If we need extended metadata and cached info doesn't have it, load it
            if (loadExtendedMetadata && cachedInfo.getValidationStatus() == ChunkFileInfo.ValidationStatus.UNKNOWN) {
                populateExtendedMetadata(cachedInfo, worldName, chunkX, chunkZ);
                // Update cache with enhanced info
                infoCache.put(cacheKey, cachedInfo);
            }
            return cachedInfo;
        }
        
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                // Remove from cache if file doesn't exist
                infoCache.remove(cacheKey);
                return null;
            }
            
            long size = Files.size(chunkFile);
            long lastModified = Files.getLastModifiedTime(chunkFile).toMillis();
            
            // Check if cached info is still valid based on file modification time
            if (cachedInfo != null && cachedInfo.getLastModified() == lastModified) {
                // File hasn't changed, refresh cache timestamp
                cachedInfo.setLastValidated(System.currentTimeMillis());
                if (loadExtendedMetadata && cachedInfo.getValidationStatus() == ChunkFileInfo.ValidationStatus.UNKNOWN) {
                    populateExtendedMetadata(cachedInfo, worldName, chunkX, chunkZ);
                }
                infoCache.put(cacheKey, cachedInfo);
                return cachedInfo;
            }
            
            // Create new info
            ChunkFileInfo info = new ChunkFileInfo(chunkX, chunkZ, size, lastModified);
            
            if (loadExtendedMetadata) {
                populateExtendedMetadata(info, worldName, chunkX, chunkZ);
            }
            
            // Cache the info
            cacheChunkFileInfo(cacheKey, info);
            
            return info;
            
        } catch (IOException e) {
            System.err.println("Failed to get info for chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            return null;
        } finally {
            fileLock.readLock().unlock();
        }
    }
    */
    
    /**
     * Gets basic information about a chunk file (without extended metadata for performance).
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return ChunkFileInfo object, or null if file doesn't exist
     */
    public ChunkFileInfo getChunkFileInfo(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) {
            return null;
        }
        
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                return null;
            }
            
            long size = Files.size(chunkFile);
            long lastModified = Files.getLastModifiedTime(chunkFile).toMillis();
            
            return new ChunkFileInfo(chunkX, chunkZ, size, lastModified);
            
        } catch (IOException e) {
            System.err.println("Failed to get info for chunk (" + chunkX + "," + chunkZ + ") from world '" + worldName + "': " + e.getMessage());
            return null;
        } finally {
            fileLock.readLock().unlock();
        }
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * The basic validateChunkFile(worldName, chunkX, chunkZ) method is still available.
     *
     * Validates chunk file integrity and updates ChunkFileInfo with validation status.
     * 
     * @param info The ChunkFileInfo to update with validation results
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return true if chunk file is valid, false otherwise
     */
    /*
    public boolean validateChunkFile(ChunkFileInfo info, String worldName, int chunkX, int chunkZ) {
        long startTime = System.currentTimeMillis();
        try {
            ChunkData chunkData = loadChunk(worldName, chunkX, chunkZ);
            if (chunkData == null) {
                info.setValidationStatus(ChunkFileInfo.ValidationStatus.CORRUPT);
                info.setLastValidated(startTime);
                return false;
            }
            
            // Validate chunk data integrity
            boolean isValid = chunkData.getChunkX() == chunkX && 
                             chunkData.getChunkZ() == chunkZ &&
                             chunkData.getBlocks() != null;
            
            if (isValid) {
                info.setValidationStatus(ChunkFileInfo.ValidationStatus.VALID);
                // Update extended metadata from loaded chunk data
                info.setSparse(chunkData.isSparse());
                info.setCompressionRatio(chunkData.getCompressionRatio());
                info.setBlockCount(calculateTotalBlocks(chunkData));
                info.setPlayerModified(chunkData.isGeneratedByPlayer());
            } else {
                info.setValidationStatus(ChunkFileInfo.ValidationStatus.CORRUPT);
            }
            
            info.setLastValidated(startTime);
            return isValid;
                   
        } catch (Exception e) {
            System.err.println("Chunk file validation failed for (" + chunkX + "," + chunkZ + 
                             ") in world '" + worldName + "': " + e.getMessage());
            info.setValidationStatus(ChunkFileInfo.ValidationStatus.UNRECOVERABLE);
            info.setLastValidated(startTime);
            return false;
        }
    }
    */
    
    /**
     * Validates chunk file integrity by attempting to load and parse it (legacy method).
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @return true if chunk file is valid, false otherwise
     */
    public boolean validateChunkFile(String worldName, int chunkX, int chunkZ) {
        try {
            ChunkData chunkData = loadChunk(worldName, chunkX, chunkZ);
            if (chunkData == null) {
                return false;
            }
            
            // Validate chunk data integrity
            return chunkData.getChunkX() == chunkX && 
                   chunkData.getChunkZ() == chunkZ &&
                   chunkData.getBlocks() != null;
                   
        } catch (Exception e) {
            System.err.println("Chunk file validation failed for (" + chunkX + "," + chunkZ + 
                             ") in world '" + worldName + "': " + e.getMessage());
            return false;
        }
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * Backup operations are handled by CorruptionRecoveryManager instead.
     *
     * Creates backup of a chunk file.
     * 
     * @param worldName The name of the world
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @throws IOException if backup operation fails
     */
    /*
    public void backupChunk(String worldName, int chunkX, int chunkZ) throws IOException {
        fileLock.readLock().lock();
        try {
            Path chunkFile = getChunkFilePath(worldName, chunkX, chunkZ);
            
            if (!Files.exists(chunkFile)) {
                throw new IOException("Cannot backup non-existent chunk: (" + chunkX + "," + chunkZ + ")");
            }
            
            Path backupFile = Paths.get(chunkFile.toString() + ".backup");
            Files.copy(chunkFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            
            System.out.println("Created backup for chunk (" + chunkX + "," + chunkZ + ") in world: " + worldName);
            
        } finally {
            fileLock.readLock().unlock();
        }
    }
    */
    
    // Chunk Discovery Methods
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     * No external systems use the advanced chunk discovery functionality.
     *
     * Gets information about all chunks in a world.
     * 
     * @param worldName The name of the world
     * @param loadExtendedMetadata Whether to load extended metadata for each chunk
     * @return List of ChunkFileInfo objects, empty list if world has no chunks
     */
    /*
    public List<ChunkFileInfo> getAllChunkInfo(String worldName, boolean loadExtendedMetadata) {
        if (worldName == null) {
            return new ArrayList<>();
        }
        
        List<ChunkFileInfo> chunkInfos = new ArrayList<>();
        
        fileLock.readLock().lock();
        try {
            Path chunksDir = getChunksDirectory(worldName);
            
            if (!Files.exists(chunksDir) || !Files.isDirectory(chunksDir)) {
                return chunkInfos; // No chunks directory
            }
            
            Files.list(chunksDir)
                .filter(path -> path.toString().endsWith(CHUNK_FILE_EXTENSION))
                .forEach(path -> {
                    try {
                        String filename = path.getFileName().toString();
                        // Parse chunk coordinates from filename: chunk_x_z.json
                        if (filename.startsWith(CHUNK_FILE_PREFIX) && filename.endsWith(CHUNK_FILE_EXTENSION)) {
                            String coords = filename.substring(CHUNK_FILE_PREFIX.length(), 
                                                             filename.length() - CHUNK_FILE_EXTENSION.length());
                            String[] parts = coords.split("_");
                            if (parts.length == 2) {
                                int chunkX = Integer.parseInt(parts[0]);
                                int chunkZ = Integer.parseInt(parts[1]);
                                
                                ChunkFileInfo info = getChunkFileInfo(worldName, chunkX, chunkZ, loadExtendedMetadata);
                                if (info != null) {
                                    chunkInfos.add(info);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing chunk file " + path + ": " + e.getMessage());
                    }
                });
                
        } catch (IOException e) {
            System.err.println("Error listing chunks for world '" + worldName + "': " + e.getMessage());
        } finally {
            fileLock.readLock().unlock();
        }
        
        return chunkInfos;
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets information about all chunks in a world (basic metadata only for performance).
     * 
     * @param worldName The name of the world
     * @return List of ChunkFileInfo objects, empty list if world has no chunks
     */
    /*
    public List<ChunkFileInfo> getAllChunkInfo(String worldName) {
        return getAllChunkInfo(worldName, false);
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets chunks within a specific radius around a center point.
     * 
     * @param worldName The name of the world
     * @param centerX The center chunk X coordinate
     * @param centerZ The center chunk Z coordinate
     * @param radius The radius in chunk coordinates
     * @param loadExtendedMetadata Whether to load extended metadata
     * @return List of ChunkFileInfo objects within the radius, sorted by distance
     */
    /*
    public List<ChunkFileInfo> getChunkInfoInRadius(String worldName, int centerX, int centerZ, int radius, boolean loadExtendedMetadata) {
        List<ChunkFileInfo> allChunks = getAllChunkInfo(worldName, loadExtendedMetadata);
        
        return allChunks.stream()
            .filter(info -> {
                double distance = info.getDistanceToPlayer(centerX, centerZ);
                return distance <= radius;
            })
            .sorted(Comparator.comparingDouble(info -> info.getDistanceToPlayer(centerX, centerZ)))
            .collect(Collectors.toList());
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets chunks that need validation (unknown status or expired validation).
     * 
     * @param worldName The name of the world
     * @param maxValidationAge Maximum age in milliseconds for validation to be considered fresh
     * @return List of ChunkFileInfo objects that need validation
     */
    /*
    public List<ChunkFileInfo> getChunksNeedingValidation(String worldName, long maxValidationAge) {
        List<ChunkFileInfo> allChunks = getAllChunkInfo(worldName, true);
        
        return allChunks.stream()
            .filter(info -> info.needsValidation(maxValidationAge))
            .sorted(Comparator.comparingLong(ChunkFileInfo::getLastModified).reversed()) // Newest first
            .collect(Collectors.toList());
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets chunks that have been modified by players (useful for backups).
     * 
     * @param worldName The name of the world
     * @return List of ChunkFileInfo objects for player-modified chunks
     */
    /*
    public List<ChunkFileInfo> getPlayerModifiedChunks(String worldName) {
        List<ChunkFileInfo> allChunks = getAllChunkInfo(worldName, true);
        
        return allChunks.stream()
            .filter(ChunkFileInfo::isPlayerModified)
            .sorted(Comparator.comparingLong(ChunkFileInfo::getLastModified).reversed())
            .collect(Collectors.toList());
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets world statistics based on chunk file information.
     * 
     * @param worldName The name of the world
     * @return WorldStatistics object with comprehensive world info
     */
    /*
    public WorldStatistics getWorldStatistics(String worldName) {
        List<ChunkFileInfo> allChunks = getAllChunkInfo(worldName, true);
        
        if (allChunks.isEmpty()) {
            return new WorldStatistics(worldName, 0, 0, 0, 0, 0, 0);
        }
        
        long totalSize = allChunks.stream().mapToLong(ChunkFileInfo::getFileSize).sum();
        long totalBlocks = allChunks.stream().mapToLong(ChunkFileInfo::getBlockCount).sum();
        long playerModifiedCount = allChunks.stream().mapToLong(info -> info.isPlayerModified() ? 1 : 0).sum();
        long sparseChunkCount = allChunks.stream().mapToLong(info -> info.isSparse() ? 1 : 0).sum();
        
        double avgCompression = allChunks.stream()
            .filter(ChunkFileInfo::isSparse)
            .mapToDouble(ChunkFileInfo::getCompressionRatio)
            .average()
            .orElse(1.0);
            
        ChunkFileInfo newest = allChunks.stream()
            .max(Comparator.comparingLong(ChunkFileInfo::getLastModified))
            .orElse(null);
        long newestTimestamp = newest != null ? newest.getLastModified() : 0;
        
        return new WorldStatistics(worldName, allChunks.size(), totalSize, totalBlocks, 
                                 playerModifiedCount, sparseChunkCount, newestTimestamp);
    }
    */
    
    
    // Private helper methods
    
    /**
     * Generates a cache key for chunk file info.
     */
    private String getCacheKey(String worldName, int chunkX, int chunkZ) {
        return worldName + ":" + chunkX + ":" + chunkZ;
    }
    
    /**
     * Caches ChunkFileInfo with size limit management.
     */
    private void cacheChunkFileInfo(String cacheKey, ChunkFileInfo info) {
        // Remove oldest entries if cache is getting too large
        if (infoCache.size() >= MAX_CACHE_SIZE) {
            evictOldestCacheEntries();
        }
        infoCache.put(cacheKey, info);
    }
    
    /**
     * Evicts the oldest cache entries to make room for new ones.
     */
    private void evictOldestCacheEntries() {
        List<Map.Entry<String, ChunkFileInfo>> entries = new ArrayList<>(infoCache.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().getLastValidated()));
        
        // Remove oldest 20% of entries
        int toRemove = Math.max(1, MAX_CACHE_SIZE / 5);
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            infoCache.remove(entries.get(i).getKey());
        }
        
        System.out.println("Evicted " + toRemove + " old ChunkFileInfo cache entries");
    }
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Clears the cache for a specific world.
     */
    /*
    public void clearCache(String worldName) {
        infoCache.entrySet().removeIf(entry -> entry.getKey().startsWith(worldName + ":"));
        System.out.println("Cleared ChunkFileInfo cache for world: " + worldName);
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Clears the entire cache.
     */
    /*
    public void clearCache() {
        infoCache.clear();
        System.out.println("Cleared entire ChunkFileInfo cache");
    }
    */
    
    /*
     * UNUSED METHOD - Commented out during save system cleanup
     * This method was identified as unused in the save system cleanup analysis.
     *
     * Gets cache statistics.
     */
    /*
    public String getCacheStats() {
        return "ChunkFileInfo cache: " + infoCache.size() + "/" + MAX_CACHE_SIZE + " entries";
    }
    */
    
    /**
     * Calculates total block count from ChunkData.
     */
    private long calculateTotalBlocks(ChunkData chunkData) {
        if (chunkData == null || chunkData.getBlocks() == null) {
            return 0;
        }
        
        return chunkData.getBlocks().stream()
            .mapToLong(entry -> entry.getRunLength())
            .sum();
    }
    
    /**
     * Populates extended metadata for ChunkFileInfo by loading chunk data.
     */
    private void populateExtendedMetadata(ChunkFileInfo info, String worldName, int chunkX, int chunkZ) {
        try {
            ChunkData chunkData = loadChunk(worldName, chunkX, chunkZ);
            if (chunkData != null) {
                info.setSparse(chunkData.isSparse());
                info.setCompressionRatio(chunkData.getCompressionRatio());
                info.setBlockCount(calculateTotalBlocks(chunkData));
                info.setPlayerModified(chunkData.isGeneratedByPlayer());
                info.setValidationStatus(ChunkFileInfo.ValidationStatus.VALID);
                info.setLastValidated(System.currentTimeMillis());
            } else {
                info.setValidationStatus(ChunkFileInfo.ValidationStatus.CORRUPT);
                info.setLastValidated(System.currentTimeMillis());
            }
        } catch (Exception e) {
            info.setValidationStatus(ChunkFileInfo.ValidationStatus.UNRECOVERABLE);
            info.setLastValidated(System.currentTimeMillis());
        }
    }
    
    /**
     * Gets the path to the chunks directory for a world.
     */
    private Path getChunksDirectory(String worldName) {
        return Paths.get(WORLDS_DIRECTORY, worldName, CHUNKS_DIRECTORY);
    }
    
    /**
     * Gets the path to a specific chunk file.
     */
    private Path getChunkFilePath(String worldName, int chunkX, int chunkZ) {
        String filename = CHUNK_FILE_PREFIX + chunkX + "_" + chunkZ + CHUNK_FILE_EXTENSION;
        return getChunksDirectory(worldName).resolve(filename);
    }
    
}