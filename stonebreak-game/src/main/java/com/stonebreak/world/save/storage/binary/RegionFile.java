package com.stonebreak.world.save.storage.binary;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Handles individual region files (.mcr) containing 32×32 chunks each.
 * Single responsibility: manage one region file's storage operations.
 * Thread-safe with read/write locks for concurrent access.
 */
public class RegionFile implements AutoCloseable {

    private static final int HEADER_SIZE = 8192; // 8KB header (4KB offsets + 4KB lengths)
    private static final int CHUNKS_PER_REGION = 1024; // 32×32 = 1024 chunks

    private final RandomAccessFile file;
    private final int[] chunkOffsets = new int[CHUNKS_PER_REGION];
    private final int[] chunkLengths = new int[CHUNKS_PER_REGION];
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path regionPath;

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

            // Validate offset and length are within file bounds
            long fileLength = file.length();
            if (offset < HEADER_SIZE || offset + length > fileLength) {
                System.err.println("[REGION-ERROR] Invalid chunk data at (" + localX + "," + localZ +
                    ") in region " + regionPath.getFileName() +
                    " - offset: " + offset + ", length: " + length + ", file size: " + fileLength);
                System.err.println("[REGION-ERROR] Marking chunk as corrupted and returning null");
                // Mark chunk as invalid to prevent future read attempts
                chunkOffsets[index] = 0;
                chunkLengths[index] = 0;
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
     * Uses atomic write strategy to prevent corruption.
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

            // Update in-memory tables
            chunkOffsets[index] = (int) offset;
            chunkLengths[index] = data.length;

            // Write updated header entries atomically
            updateHeaderEntry(index);

            // Ensure data is written to disk
            file.getFD().sync();

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

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
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
            file.setLength(HEADER_SIZE);
            // Arrays are already zero-initialized
            writeFullHeader();
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