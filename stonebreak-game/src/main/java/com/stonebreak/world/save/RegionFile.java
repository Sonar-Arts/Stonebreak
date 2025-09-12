package com.stonebreak.world.save;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Handles individual region files (.mcr) in the binary save system.
 * Each region file contains 32x32 chunks with an 8KB header containing offset and length tables.
 * 
 * Region File Structure:
 * - Header (8KB total)
 *   - Chunk Offset Table (4KB): 1024 int32 values (chunk file offsets)
 *   - Chunk Length Table (4KB): 1024 int32 values (chunk data lengths)
 * - Chunk Data Section (variable size)
 *   - Individual compressed/uncompressed chunk data blocks
 */
public class RegionFile implements AutoCloseable {
    
    /** Size of the region file header in bytes (8KB) */
    public static final int HEADER_SIZE = 8192;
    
    /** Number of chunks per region (32x32) */
    public static final int CHUNKS_PER_REGION = 1024;
    
    /** Size of each offset/length table (4KB each) */
    public static final int TABLE_SIZE = 4096;
    
    private final RandomAccessFile file;
    private final Path filePath;
    private final int[] chunkOffsets;
    private final int[] chunkLengths;
    private final ReentrantReadWriteLock lock;
    private volatile boolean closed = false;
    
    /**
     * Open or create a region file.
     * @param regionPath Path to the region file
     * @throws IOException if file operations fail
     */
    public RegionFile(Path regionPath) throws IOException {
        this.filePath = regionPath;
        this.file = new RandomAccessFile(regionPath.toFile(), "rw");
        this.chunkOffsets = new int[CHUNKS_PER_REGION];
        this.chunkLengths = new int[CHUNKS_PER_REGION];
        this.lock = new ReentrantReadWriteLock();
        
        initializeFile();
        loadHeader();
    }
    
    /**
     * Initialize the file if it's new (create empty header).
     * @throws IOException if file operations fail
     */
    private void initializeFile() throws IOException {
        if (file.length() < HEADER_SIZE) {
            // New file - create empty header
            file.setLength(HEADER_SIZE);
            file.seek(0);
            
            // Write empty offset table
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                file.writeInt(0);
            }
            
            // Write empty length table
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                file.writeInt(0);
            }
        }
    }
    
    /**
     * Load the header (offset and length tables) from the file.
     * @throws IOException if file operations fail
     */
    private void loadHeader() throws IOException {
        lock.writeLock().lock();
        try {
            file.seek(0);
            
            // Read offset table
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                chunkOffsets[i] = file.readInt();
            }
            
            // Read length table
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                chunkLengths[i] = file.readInt();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Read chunk data from the region file.
     * @param localX Local chunk X coordinate (0-31)
     * @param localZ Local chunk Z coordinate (0-31)
     * @return Chunk data bytes, or null if chunk doesn't exist
     * @throws IOException if file operations fail
     */
    public byte[] readChunk(int localX, int localZ) throws IOException {
        validateCoordinates(localX, localZ);
        
        if (closed) {
            throw new IOException("Region file is closed");
        }
        
        int index = getChunkIndex(localX, localZ);
        
        lock.readLock().lock();
        try {
            int offset = chunkOffsets[index];
            int length = chunkLengths[index];
            
            if (offset == 0 || length == 0) {
                return null; // Chunk not saved
            }
            
            // Validate offset and length
            if (offset < HEADER_SIZE || offset + length > file.length()) {
                throw new IOException(String.format(
                    "Invalid chunk data at [%d,%d]: offset=%d, length=%d, fileSize=%d", 
                    localX, localZ, offset, length, file.length()));
            }
            
            file.seek(offset);
            byte[] data = new byte[length];
            file.readFully(data);
            
            return data;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Write chunk data to the region file.
     * @param localX Local chunk X coordinate (0-31)
     * @param localZ Local chunk Z coordinate (0-31)
     * @param data Chunk data to write
     * @throws IOException if file operations fail
     */
    public void writeChunk(int localX, int localZ, byte[] data) throws IOException {
        validateCoordinates(localX, localZ);
        
        if (closed) {
            throw new IOException("Region file is closed");
        }
        
        if (data == null || data.length == 0) {
            // Remove chunk
            removeChunk(localX, localZ);
            return;
        }
        
        int index = getChunkIndex(localX, localZ);
        
        lock.writeLock().lock();
        try {
            // Find space at end of file for new data
            long newOffset = file.length();
            
            // Write chunk data at end of file
            file.seek(newOffset);
            file.write(data);
            
            // Update header tables
            chunkOffsets[index] = (int) newOffset;
            chunkLengths[index] = data.length;
            
            // Write updated header entries
            updateHeaderEntry(index);
            
            // Force data to disk
            file.getFD().sync();
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove a chunk from the region file.
     * @param localX Local chunk X coordinate (0-31)
     * @param localZ Local chunk Z coordinate (0-31)
     * @throws IOException if file operations fail
     */
    public void removeChunk(int localX, int localZ) throws IOException {
        validateCoordinates(localX, localZ);
        
        if (closed) {
            throw new IOException("Region file is closed");
        }
        
        int index = getChunkIndex(localX, localZ);
        
        lock.writeLock().lock();
        try {
            chunkOffsets[index] = 0;
            chunkLengths[index] = 0;
            updateHeaderEntry(index);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Check if a chunk exists in this region file.
     * @param localX Local chunk X coordinate (0-31)
     * @param localZ Local chunk Z coordinate (0-31)
     * @return True if chunk exists
     */
    public boolean hasChunk(int localX, int localZ) {
        validateCoordinates(localX, localZ);
        
        if (closed) {
            return false;
        }
        
        int index = getChunkIndex(localX, localZ);
        
        lock.readLock().lock();
        try {
            return chunkOffsets[index] != 0 && chunkLengths[index] > 0;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Update a single header entry (offset and length for one chunk).
     * @param chunkIndex Chunk index (0-1023)
     * @throws IOException if file operations fail
     */
    private void updateHeaderEntry(int chunkIndex) throws IOException {
        // Update offset entry
        file.seek(chunkIndex * 4L);
        file.writeInt(chunkOffsets[chunkIndex]);
        
        // Update length entry
        file.seek(TABLE_SIZE + (chunkIndex * 4L));
        file.writeInt(chunkLengths[chunkIndex]);
    }
    
    /**
     * Get the chunk index for local coordinates.
     * @param localX Local X coordinate (0-31)
     * @param localZ Local Z coordinate (0-31)
     * @return Chunk index (0-1023)
     */
    private int getChunkIndex(int localX, int localZ) {
        return localX + localZ * 32;
    }
    
    /**
     * Validate local coordinates.
     * @param localX Local X coordinate
     * @param localZ Local Z coordinate
     * @throws IllegalArgumentException if coordinates are invalid
     */
    private void validateCoordinates(int localX, int localZ) {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            throw new IllegalArgumentException(
                String.format("Invalid local coordinates: [%d, %d]. Must be 0-31.", localX, localZ));
        }
    }
    
    /**
     * Get region file statistics.
     * @return Region file statistics
     */
    public RegionStats getStats() {
        lock.readLock().lock();
        try {
            int chunksStored = 0;
            long totalDataSize = 0;
            
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                if (chunkOffsets[i] != 0 && chunkLengths[i] > 0) {
                    chunksStored++;
                    totalDataSize += chunkLengths[i];
                }
            }
            
            try {
                long fileSize = file.length();
                return new RegionStats(chunksStored, totalDataSize, fileSize);
            } catch (IOException e) {
                return new RegionStats(chunksStored, totalDataSize, -1);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Force all buffered data to disk.
     * @throws IOException if sync fails
     */
    public void sync() throws IOException {
        lock.readLock().lock();
        try {
            if (!closed) {
                file.getFD().sync();
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the file path for this region file.
     * @return File path
     */
    public Path getFilePath() {
        return filePath;
    }
    
    /**
     * Check if this region file is closed.
     * @return True if closed
     */
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            if (!closed) {
                file.close();
                closed = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Statistics for a region file.
     */
    public static class RegionStats {
        public final int chunksStored;
        public final long totalDataSize;
        public final long fileSize;
        public final double efficiency;
        
        public RegionStats(int chunksStored, long totalDataSize, long fileSize) {
            this.chunksStored = chunksStored;
            this.totalDataSize = totalDataSize;
            this.fileSize = fileSize;
            this.efficiency = fileSize > 0 ? (double) totalDataSize / fileSize * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("Region[%d chunks, %d bytes data, %d bytes file, %.1f%% efficient]",
                    chunksStored, totalDataSize, fileSize, efficiency);
        }
    }
}