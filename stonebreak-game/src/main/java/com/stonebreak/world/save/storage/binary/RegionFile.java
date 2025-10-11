package com.stonebreak.world.save.storage.binary;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Handles individual region files (.mcr) containing 32×32 chunks each.
 * Single responsibility: manage one region file's storage operations.
 * Thread-safe with read/write locks for concurrent access.
 * OPTIMIZATION: Batched fsync to reduce I/O overhead
 */
public class RegionFile implements AutoCloseable {

    private static final int HEADER_SIZE = 8192; // 8KB header (4KB offsets + 4KB lengths)
    private static final int CHUNKS_PER_REGION = 1024; // 32×32 = 1024 chunks
    private static final int SYNC_BATCH_SIZE = 10; // Sync to disk after N chunk writes

    private final RandomAccessFile file;
    private final int[] chunkOffsets = new int[CHUNKS_PER_REGION];
    private final int[] chunkLengths = new int[CHUNKS_PER_REGION];
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path regionPath;

    // Batched fsync tracking
    private int unsyncedWrites = 0;
    private boolean needsSync = false;

    public RegionFile(Path regionPath) throws IOException {
        this.regionPath = regionPath;
        this.file = new RandomAccessFile(regionPath.toFile(), "rw");
        loadHeader();
    }

    /**
     * Reads chunk data from the region file.
     * Returns null if chunk doesn't exist.
     */
    public byte[] readChunk(int localX, int localZ) throws IOException {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            throw new IllegalArgumentException("Local coordinates must be 0-31");
        }

        int index = getChunkIndex(localX, localZ);

        lock.readLock().lock();
        try {
            int offset = chunkOffsets[index];
            int length = chunkLengths[index];

            if (offset == 0 || length == 0) {
                return null; // Chunk not saved
            }

            // CRITICAL VALIDATION: Detect obviously corrupted header entries
            // Valid chunk data must be after header and have reasonable size
            long fileLength = file.length();
            boolean isCorrupted = false;

            if (offset < HEADER_SIZE) {
                System.err.println("[REGION-ERROR] CORRUPTION DETECTED: offset " + offset + " < HEADER_SIZE " + HEADER_SIZE);
                isCorrupted = true;
            } else if (length < 0 || length > 10_000_000) { // 10MB max per chunk
                System.err.println("[REGION-ERROR] CORRUPTION DETECTED: unreasonable chunk length " + length);
                isCorrupted = true;
            } else if (offset + length > fileLength) {
                System.err.println("[REGION-ERROR] CORRUPTION DETECTED: offset + length exceeds file size");
                isCorrupted = true;
            }

            if (isCorrupted) {
                System.err.println("[REGION-ERROR] Corrupted chunk header at (" + localX + "," + localZ +
                    ") in region " + regionPath.getFileName() +
                    " - offset: " + offset + ", length: " + length + ", file size: " + fileLength);
                System.err.println("[REGION-ERROR] Marking chunk as corrupted and returning null - will be regenerated");
                // Mark chunk as invalid to prevent future read attempts
                chunkOffsets[index] = 0;
                chunkLengths[index] = 0;
                // Update header on disk to prevent reading corrupted data again
                try {
                    updateHeaderEntry(index);
                    file.getFD().sync();
                } catch (IOException e) {
                    System.err.println("[REGION-ERROR] Failed to update header after corruption detection: " + e.getMessage());
                }
                return null;
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
     * Writes chunk data to the region file.
     * Uses atomic write strategy with immediate sync to prevent corruption.
     * CRITICAL: Must sync after EVERY write to prevent data loss on crash.
     * Previous batched fsync optimization caused LZ4 decompression errors.
     */
    public void writeChunk(int localX, int localZ, byte[] data) throws IOException {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            throw new IllegalArgumentException("Local coordinates must be 0-31");
        }

        int index = getChunkIndex(localX, localZ);

        lock.writeLock().lock();
        try {
            // Find space at end of file for new chunk data
            long offset = file.length();

            // Write chunk data at end of file
            file.seek(offset);
            file.write(data);

            // CRITICAL: Sync chunk data to disk BEFORE updating header
            // This ensures the data is physically on disk before the header points to it
            file.getFD().sync();

            // Update in-memory tables
            chunkOffsets[index] = (int) offset;
            chunkLengths[index] = data.length;

            // Write updated header entries
            updateHeaderEntry(index);

            // CRITICAL: Sync header to disk immediately
            // Without this, a crash could leave the header pointing to unwritten data
            file.getFD().sync();

            // Reset tracking variables
            unsyncedWrites = 0;
            needsSync = false;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a chunk exists in this region.
     */
    public boolean hasChunk(int localX, int localZ) {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            return false;
        }

        int index = getChunkIndex(localX, localZ);

        lock.readLock().lock();
        try {
            boolean exists = chunkOffsets[index] != 0 && chunkLengths[index] != 0;
            if (!exists) {
                System.out.println("[REGION-DEBUG] Chunk (" + localX + "," + localZ + ") not found in region " +
                    regionPath.getFileName() + " - offset: " + chunkOffsets[index] + ", length: " + chunkLengths[index]);
            }
            return exists;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deletes a chunk from the region (marks as empty).
     * Physical space is not reclaimed until region is compacted.
     */
    public void deleteChunk(int localX, int localZ) throws IOException {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            throw new IllegalArgumentException("Local coordinates must be 0-31");
        }

        int index = getChunkIndex(localX, localZ);

        lock.writeLock().lock();
        try {
            // Mark chunk as deleted
            chunkOffsets[index] = 0;
            chunkLengths[index] = 0;

            // Update header
            updateHeaderEntry(index);

            // Ensure changes are written
            file.getFD().sync();

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the file size of this region.
     */
    public long getFileSize() throws IOException {
        lock.readLock().lock();
        try {
            return file.length();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the number of chunks stored in this region.
     */
    public int getChunkCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                if (chunkOffsets[i] != 0 && chunkLengths[i] != 0) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Flushes any pending writes to disk.
     * Call this to ensure data durability before shutdown or critical operations.
     */
    public void flush() throws IOException {
        lock.writeLock().lock();
        try {
            if (needsSync) {
                file.getFD().sync();
                unsyncedWrites = 0;
                needsSync = false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            // Flush any pending writes before closing
            if (needsSync) {
                file.getFD().sync();
            }

            if (file != null) {
                file.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadHeader() throws IOException {
        if (file.length() < HEADER_SIZE) {
            // New file - initialize with empty header
            // CRITICAL FIX: Don't use setLength() - it may not zero-fill on all platforms!
            // Instead, explicitly write zeros to ensure header is properly initialized
            file.seek(0);

            // Write 8192 bytes of zeros (header size)
            byte[] zeros = new byte[HEADER_SIZE];
            file.write(zeros);

            // Sync to ensure zeros are written to disk before any chunk data
            file.getFD().sync();

            // Arrays are already zero-initialized by Java
            // Header on disk now matches in-memory state (all zeros)
            System.out.println("[REGION-INIT] Initialized new region file with zero-filled header: " + regionPath.getFileName());
        } else {
            // Existing file - load header
            file.seek(0);

            // Read chunk offsets (first 4KB)
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                chunkOffsets[i] = file.readInt();
            }

            // Read chunk lengths (second 4KB)
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                chunkLengths[i] = file.readInt();
            }
        }
    }

    private void writeFullHeader() throws IOException {
        file.seek(0);

        // Write chunk offsets
        for (int offset : chunkOffsets) {
            file.writeInt(offset);
        }

        // Write chunk lengths
        for (int length : chunkLengths) {
            file.writeInt(length);
        }

        file.getFD().sync();
    }

    private void updateHeaderEntry(int chunkIndex) throws IOException {
        // Update offset entry
        file.seek(chunkIndex * 4);
        file.writeInt(chunkOffsets[chunkIndex]);

        // Update length entry
        file.seek(4096 + chunkIndex * 4);
        file.writeInt(chunkLengths[chunkIndex]);
    }

    private int getChunkIndex(int localX, int localZ) {
        return localX + localZ * 32;
    }

    public Path getRegionPath() {
        return regionPath;
    }
}