package com.stonebreak.world.save;

/**
 * Enhanced information about a chunk file with extended metadata and validation status.
 * This class provides comprehensive details about saved chunk files including
 * file size, modification times, compression info, and validation status.
 */
public class ChunkFileInfo {
    private final int chunkX;
    private final int chunkZ;
    private final long fileSize;
    private final long lastModified;
    
    // Extended metadata
    private boolean isSparse;
    private double compressionRatio;
    private long blockCount;
    private boolean isPlayerModified;
    private ValidationStatus validationStatus;
    private long lastValidated;
    private String checksum;
    
    /**
     * Creates a new ChunkFileInfo with basic file information.
     * 
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @param fileSize The size of the chunk file in bytes
     * @param lastModified The timestamp when the file was last modified
     */
    public ChunkFileInfo(int chunkX, int chunkZ, long fileSize, long lastModified) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.fileSize = fileSize;
        this.lastModified = lastModified;
        this.isSparse = false;
        this.compressionRatio = 1.0;
        this.blockCount = 0;
        this.isPlayerModified = false;
        this.validationStatus = ValidationStatus.UNKNOWN;
        this.lastValidated = 0;
        this.checksum = null;
    }
    
    // Basic getters
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public long getFileSize() { return fileSize; }
    public long getLastModified() { return lastModified; }
    
    // Extended metadata getters/setters
    public boolean isSparse() { return isSparse; }
    public void setSparse(boolean sparse) { this.isSparse = sparse; }
    
    public double getCompressionRatio() { return compressionRatio; }
    public void setCompressionRatio(double compressionRatio) { this.compressionRatio = compressionRatio; }
    
    public long getBlockCount() { return blockCount; }
    public void setBlockCount(long blockCount) { this.blockCount = blockCount; }
    
    public boolean isPlayerModified() { return isPlayerModified; }
    public void setPlayerModified(boolean playerModified) { this.isPlayerModified = playerModified; }
    
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public void setValidationStatus(ValidationStatus validationStatus) { this.validationStatus = validationStatus; }
    
    public long getLastValidated() { return lastValidated; }
    public void setLastValidated(long lastValidated) { this.lastValidated = lastValidated; }
    
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    
    // Utility methods
    
    /**
     * Returns a human-readable representation of the file size.
     * 
     * @return Formatted file size string (e.g., "1.2 KB", "5.7 MB")
     */
    public String getFileSizeFormatted() {
        if (fileSize < 1024) {
            return fileSize + " bytes";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.1f KB", fileSize / 1024.0);
        } else {
            return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        }
    }
    
    /**
     * Calculates the distance from this chunk to a player's chunk position.
     * 
     * @param playerChunkX The player's chunk X coordinate
     * @param playerChunkZ The player's chunk Z coordinate
     * @return The Euclidean distance between chunk positions
     */
    public double getDistanceToPlayer(int playerChunkX, int playerChunkZ) {
        double dx = chunkX - playerChunkX;
        double dz = chunkZ - playerChunkZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
    
    /**
     * Determines if this chunk file needs validation based on age.
     * 
     * @param maxAgeMs Maximum age in milliseconds for validation to be considered fresh
     * @return true if validation is needed, false if validation is still fresh
     */
    public boolean needsValidation(long maxAgeMs) {
        return validationStatus == ValidationStatus.UNKNOWN || 
               (System.currentTimeMillis() - lastValidated) > maxAgeMs;
    }
    
    /**
     * Returns a human-readable compression information string.
     * 
     * @return Compression info (e.g., "No compression", "75.3% compression")
     */
    public String getCompressionInfo() {
        if (!isSparse) {
            return "No compression";
        }
        return String.format("%.1f%% compression", (1.0 - compressionRatio) * 100);
    }
    
    @Override
    public String toString() {
        return "ChunkFileInfo{" +
               "chunk=(" + chunkX + "," + chunkZ + ")" +
               ", size=" + getFileSizeFormatted() +
               ", lastModified=" + new java.util.Date(lastModified) +
               ", sparse=" + isSparse +
               ", compression=" + getCompressionInfo() +
               ", playerModified=" + isPlayerModified +
               ", validationStatus=" + validationStatus +
               '}';
    }
    
    /**
     * Validation status for chunk files.
     * Used to track the integrity and recoverability of saved chunk files.
     */
    public enum ValidationStatus {
        /** File has not yet been validated */
        UNKNOWN,
        
        /** File is valid and can be loaded successfully */
        VALID,
        
        /** File is corrupted but may be recoverable through repair strategies */
        CORRUPT,
        
        /** File is beyond recovery and needs regeneration */
        UNRECOVERABLE
    }
}