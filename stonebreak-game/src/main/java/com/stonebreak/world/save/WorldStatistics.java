package com.stonebreak.world.save;

/**
 * Container for comprehensive world statistics based on chunk file information.
 * Provides insights into world size, compression efficiency, player activity, and storage usage.
 */
public class WorldStatistics {
    private final String worldName;
    private final int totalChunks;
    private final long totalSize;
    private final long totalBlocks;
    private final long playerModifiedChunks;
    private final long sparseChunks;
    private final long lastModified;
    
    /**
     * Creates a new WorldStatistics instance.
     * 
     * @param worldName The name of the world
     * @param totalChunks Total number of chunk files
     * @param totalSize Total size of all chunk files in bytes
     * @param totalBlocks Total number of blocks across all chunks
     * @param playerModifiedChunks Number of chunks modified by players
     * @param sparseChunks Number of chunks using sparse compression
     * @param lastModified Timestamp of the most recently modified chunk
     */
    public WorldStatistics(String worldName, int totalChunks, long totalSize, long totalBlocks,
                         long playerModifiedChunks, long sparseChunks, long lastModified) {
        this.worldName = worldName;
        this.totalChunks = totalChunks;
        this.totalSize = totalSize;
        this.totalBlocks = totalBlocks;
        this.playerModifiedChunks = playerModifiedChunks;
        this.sparseChunks = sparseChunks;
        this.lastModified = lastModified;
    }
    
    // Basic getters
    public String getWorldName() { return worldName; }
    public int getTotalChunks() { return totalChunks; }
    public long getTotalSize() { return totalSize; }
    public long getTotalBlocks() { return totalBlocks; }
    public long getPlayerModifiedChunks() { return playerModifiedChunks; }
    public long getSparseChunks() { return sparseChunks; }
    public long getLastModified() { return lastModified; }
    
    /**
     * Returns a human-readable representation of the total world size.
     * 
     * @return Formatted size string (e.g., "1.2 KB", "5.7 MB")
     */
    public String getFormattedSize() {
        if (totalSize < 1024) return totalSize + " bytes";
        if (totalSize < 1024 * 1024) return String.format("%.1f KB", totalSize / 1024.0);
        return String.format("%.1f MB", totalSize / (1024.0 * 1024.0));
    }
    
    /**
     * Calculates the average chunk file size in bytes.
     * 
     * @return Average chunk size, or 0 if no chunks exist
     */
    public double getAverageChunkSize() {
        return totalChunks > 0 ? (double) totalSize / totalChunks : 0;
    }
    
    /**
     * Calculates the ratio of sparse chunks to total chunks.
     * 
     * @return Sparse ratio (0.0 to 1.0), or 0 if no chunks exist
     */
    public double getSparseRatio() {
        return totalChunks > 0 ? (double) sparseChunks / totalChunks : 0;
    }
    
    /**
     * Calculates the ratio of player-modified chunks to total chunks.
     * 
     * @return Player modification ratio (0.0 to 1.0), or 0 if no chunks exist
     */
    public double getPlayerModificationRatio() {
        return totalChunks > 0 ? (double) playerModifiedChunks / totalChunks : 0;
    }
    
    /**
     * Gets the average number of blocks per chunk.
     * 
     * @return Average blocks per chunk, or 0 if no chunks exist
     */
    public double getAverageBlocksPerChunk() {
        return totalChunks > 0 ? (double) totalBlocks / totalChunks : 0;
    }
    
    /**
     * Formats the player modification ratio as a percentage.
     * 
     * @return Percentage string (e.g., "45.2%")
     */
    public String getPlayerModificationPercentage() {
        return String.format("%.1f%%", getPlayerModificationRatio() * 100);
    }
    
    /**
     * Formats the sparse ratio as a percentage.
     * 
     * @return Percentage string (e.g., "78.3%")
     */
    public String getSparsePercentage() {
        return String.format("%.1f%%", getSparseRatio() * 100);
    }
    
    /**
     * Estimates the memory footprint if all chunks were loaded.
     * 
     * @return Estimated memory usage string
     */
    public String getEstimatedMemoryFootprint() {
        // Rough estimate: each chunk takes ~50KB in memory when loaded
        long estimatedBytes = totalChunks * 50 * 1024;
        if (estimatedBytes < 1024 * 1024) {
            return String.format("%.1f KB", estimatedBytes / 1024.0);
        } else {
            return String.format("%.1f MB", estimatedBytes / (1024.0 * 1024.0));
        }
    }
    
    @Override
    public String toString() {
        return "WorldStatistics{" +
               "world='" + worldName + "'" +
               ", chunks=" + totalChunks +
               ", size=" + getFormattedSize() +
               ", blocks=" + totalBlocks +
               ", playerModified=" + playerModifiedChunks + " (" + getPlayerModificationPercentage() + ")" +
               ", sparse=" + sparseChunks + " (" + getSparsePercentage() + ")" +
               ", lastModified=" + new java.util.Date(lastModified) +
               ", avgChunkSize=" + String.format("%.1f KB", getAverageChunkSize() / 1024.0) +
               ", estimatedMemory=" + getEstimatedMemoryFootprint() +
               '}';
    }
}