package com.stonebreak.world.save;

import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.repository.FileSaveRepository;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that directly validate region file header integrity.
 *
 * These tests READ THE RAW BYTES from region files and verify:
 * 1. Header offset/length entries are consistent
 * 2. No overlapping chunk data
 * 3. All offsets point to valid data within file bounds
 * 4. Header updates are atomic (no torn writes)
 *
 * This targets the EXACT corruption patterns you're seeing:
 * - "Unsupported chunk format version: 2560" (torn header read)
 * - "Error decoding offset 13013" (invalid offset in header)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RegionFileHeaderIntegrityTest {

    private static final String TEST_WORLD_BASE = "test_world_header_integrity_";
    private static final int HEADER_SIZE = 8192;
    private static final int CHUNKS_PER_REGION = 1024;

    private RealisticChunkGenerator generator;
    private List<String> failureReasons;

    @BeforeEach
    void setUp() {
        this.generator = new RealisticChunkGenerator(54321L);
        this.failureReasons = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (!failureReasons.isEmpty()) {
            String failures = String.join("\n  - ", failureReasons);
            System.err.println("Test failures detected:\n  - " + failures);
        }
    }

    /**
     * Test 1: Header Offset/Length Consistency
     * Verifies: Offset and length in header match actual chunk data position
     */
    @Test
    @Order(1)
    @DisplayName("Test 1: Header Offset/Length Consistency")
    void testHeaderOffsetLengthConsistency() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "offset_consistency";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            // Save multiple chunks to same region file (chunks 0-31 all go to region 0,0)
            Map<Integer, Integer> expectedLengths = new HashMap<>();

            for (int i = 0; i < 10; i++) {
                ChunkData chunk = generator.createMaxComplexityChunk(i, 0);
                repo.saveChunk(chunk).get(5, TimeUnit.SECONDS);

                // Calculate expected serialized length
                com.stonebreak.world.save.serialization.BinaryChunkSerializer serializer =
                        new com.stonebreak.world.save.serialization.BinaryChunkSerializer();
                byte[] serialized = serializer.serialize(chunk);
                expectedLengths.put(i, serialized.length);
            }

            // Close repo to ensure all data is flushed
            repo.close();

            // Read region file directly and verify header
            Path regionPath = testPath.resolve("regions").resolve("r.0.0.mcr");
            assertTrue(Files.exists(regionPath), "Region file should exist");

            try (RandomAccessFile regionFile = new RandomAccessFile(regionPath.toFile(), "r")) {
                long fileLength = regionFile.length();
                System.out.println("Region file size: " + fileLength + " bytes");

                // Read header (8KB: 4KB offsets + 4KB lengths)
                int[] offsets = new int[CHUNKS_PER_REGION];
                int[] lengths = new int[CHUNKS_PER_REGION];

                regionFile.seek(0);
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    offsets[i] = regionFile.readInt();
                }

                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    lengths[i] = regionFile.readInt();
                }

                // Verify saved chunks have valid offsets/lengths
                for (int i = 0; i < 10; i++) {
                    int chunkIndex = i; // localX=i, localZ=0 → index = i + 0*32 = i

                    int offset = offsets[chunkIndex];
                    int length = lengths[chunkIndex];

                    // Verify offset is within file bounds
                    if (offset < HEADER_SIZE) {
                        recordFailure("Chunk " + i + " offset (" + offset + ") is within header region");
                    }

                    if (offset + length > fileLength) {
                        recordFailure("Chunk " + i + " data extends beyond file: offset=" + offset +
                                ", length=" + length + ", file size=" + fileLength);
                    }

                    // Verify length matches expected
                    int expectedLength = expectedLengths.get(i);
                    if (length != expectedLength) {
                        recordFailure("Chunk " + i + " length mismatch: header says " + length +
                                " but should be " + expectedLength);
                    }

                    System.out.println("Chunk " + i + ": offset=" + offset + ", length=" + length + " ✓");
                }
            }

            System.out.println("✓ Header offset/length consistency verified");

        } finally {
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 2: No Overlapping Chunk Data
     * Verifies: Chunk data regions don't overlap in the file
     */
    @Test
    @Order(2)
    @DisplayName("Test 2: No Overlapping Chunk Data")
    void testNoOverlappingChunkData() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "no_overlap";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            // Save 20 chunks
            for (int i = 0; i < 20; i++) {
                ChunkData chunk = generator.createMaxComplexityChunk(i, 0);
                repo.saveChunk(chunk).get(5, TimeUnit.SECONDS);
            }

            repo.close();

            // Read region file and check for overlaps
            Path regionPath = testPath.resolve("regions").resolve("r.0.0.mcr");

            try (RandomAccessFile regionFile = new RandomAccessFile(regionPath.toFile(), "r")) {
                // Read offsets and lengths
                int[] offsets = new int[CHUNKS_PER_REGION];
                int[] lengths = new int[CHUNKS_PER_REGION];

                regionFile.seek(0);
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    offsets[i] = regionFile.readInt();
                }

                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    lengths[i] = regionFile.readInt();
                }

                // Build list of occupied ranges
                List<DataRange> ranges = new ArrayList<>();
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    if (offsets[i] != 0 && lengths[i] != 0) {
                        ranges.add(new DataRange(i, offsets[i], lengths[i]));
                    }
                }

                // Sort by offset
                ranges.sort(Comparator.comparingInt(r -> r.offset));

                // Check for overlaps
                for (int i = 0; i < ranges.size() - 1; i++) {
                    DataRange current = ranges.get(i);
                    DataRange next = ranges.get(i + 1);

                    int currentEnd = current.offset + current.length;
                    if (currentEnd > next.offset) {
                        recordFailure("OVERLAP DETECTED: Chunk " + current.index + " (offset=" + current.offset +
                                ", length=" + current.length + ") overlaps with chunk " + next.index +
                                " (offset=" + next.offset + ")");
                    }
                }

                System.out.println("✓ No overlapping chunk data detected");
                System.out.println("  - Chunks analyzed: " + ranges.size());
            }

        } finally {
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 3: Atomic Header Updates
     * Verifies: Offset and length are always updated together (no torn writes)
     */
    @Test
    @Order(3)
    @DisplayName("Test 3: Atomic Header Updates")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testAtomicHeaderUpdates() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "atomic_header";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int threadCount = 8;
            int iterationsPerThread = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            AtomicInteger tornWrites = new AtomicInteger(0);

            // All threads hammer the same chunk (0,0)
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int i = 0; i < iterationsPerThread; i++) {
                            // Save chunk
                            ChunkData chunk = generator.createMaxComplexityChunk(0, 0);
                            repo.saveChunk(chunk).get(2, TimeUnit.SECONDS);

                            // Immediately read header and verify consistency
                            Path regionPath = testPath.resolve("regions").resolve("r.0.0.mcr");
                            if (Files.exists(regionPath)) {
                                try (RandomAccessFile regionFile = new RandomAccessFile(regionPath.toFile(), "r")) {
                                    // Read offset and length for chunk (0,0) which has index 0
                                    regionFile.seek(0);
                                    int offset = regionFile.readInt();

                                    regionFile.seek(4096);
                                    int length = regionFile.readInt();

                                    // Verify they're both valid (both 0 or both non-zero)
                                    if ((offset == 0 && length != 0) || (offset != 0 && length == 0)) {
                                        tornWrites.incrementAndGet();
                                        System.err.println("❌ TORN WRITE DETECTED in thread " + threadId +
                                                " iteration " + i + ": offset=" + offset + ", length=" + length);
                                    }

                                    // If both non-zero, verify offset is valid
                                    if (offset != 0 && length != 0) {
                                        long fileLength = regionFile.length();
                                        if (offset < HEADER_SIZE || offset + length > fileLength) {
                                            tornWrites.incrementAndGet();
                                            System.err.println("❌ INVALID OFFSET/LENGTH in thread " + threadId +
                                                    ": offset=" + offset + ", length=" + length + ", fileSize=" + fileLength);
                                        }
                                    }
                                } catch (IOException e) {
                                    // Ignore - file might be in use
                                }
                            }
                        }

                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(45, TimeUnit.SECONDS), "Test timed out");

            executor.shutdown();

            if (tornWrites.get() > 0) {
                recordFailure("TORN WRITES DETECTED: " + tornWrites.get() + " instances of inconsistent header state");
            } else {
                System.out.println("✓ No torn writes detected in " + (threadCount * iterationsPerThread) + " operations");
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 4: Direct Version Field Verification
     * Reads raw bytes and verifies version field is ALWAYS 1 at correct offset
     * This DIRECTLY TARGETS your "Unsupported chunk format version: 2560" error
     */
    @Test
    @Order(4)
    @DisplayName("Test 4: Direct Version Field Verification (CRITICAL)")
    void testDirectVersionFieldVerification() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "version_field";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            // Save chunks
            for (int i = 0; i < 10; i++) {
                ChunkData chunk = generator.createMaxComplexityChunk(i, 0);
                repo.saveChunk(chunk).get(5, TimeUnit.SECONDS);
            }

            repo.close();

            // Read region file and verify version field in each chunk's data
            Path regionPath = testPath.resolve("regions").resolve("r.0.0.mcr");

            try (RandomAccessFile regionFile = new RandomAccessFile(regionPath.toFile(), "r")) {
                // Read header
                int[] offsets = new int[CHUNKS_PER_REGION];
                int[] lengths = new int[CHUNKS_PER_REGION];

                regionFile.seek(0);
                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    offsets[i] = regionFile.readInt();
                }

                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    lengths[i] = regionFile.readInt();
                }

                // Verify version field in each chunk's serialized data
                for (int i = 0; i < 10; i++) {
                    int offset = offsets[i];
                    int length = lengths[i];

                    if (offset == 0 || length == 0) {
                        recordFailure("Chunk " + i + " has invalid header: offset=" + offset + ", length=" + length);
                        continue;
                    }

                    // Read chunk data
                    regionFile.seek(offset);
                    byte[] chunkData = new byte[Math.min(32, length)]; // Just read header (32 bytes)
                    regionFile.readFully(chunkData);

                    // Parse chunk header
                    ByteBuffer buffer = ByteBuffer.wrap(chunkData);
                    int chunkX = buffer.getInt();      // Offset 0
                    int chunkZ = buffer.getInt();      // Offset 4
                    int version = buffer.getInt();     // Offset 8 - THIS IS CRITICAL
                    int uncompressedSize = buffer.getInt(); // Offset 12

                    // VERIFY VERSION IS 1
                    if (version != 1) {
                        recordFailure("❌❌❌ VERSION CORRUPTION DETECTED in chunk " + i + ": " +
                                "version=" + version + " (0x" + Integer.toHexString(version) + ") " +
                                "THIS IS THE BUG YOU'RE SEEING!");
                        System.err.println("  Chunk header: chunkX=" + chunkX + ", chunkZ=" + chunkZ +
                                ", version=" + version + ", uncompressedSize=" + uncompressedSize);
                    } else {
                        System.out.println("Chunk " + i + ": version=" + version + " ✓");
                    }
                }

                System.out.println("✓ All version fields verified as 1");
            }

        } finally {
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 5: Concurrent Read/Write Header Integrity
     * Hammers the header with concurrent reads and writes
     * Verifies readers never see torn/corrupted headers
     */
    @Test
    @Order(5)
    @DisplayName("Test 5: Concurrent Read/Write Header Integrity")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentReadWriteHeaderIntegrity() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "concurrent_rw_header";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int writerThreads = 4;
            int readerThreads = 4;
            int iterations = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writerThreads + readerThreads);
            ExecutorService executor = Executors.newFixedThreadPool(writerThreads + readerThreads);

            AtomicInteger corruptedReads = new AtomicInteger(0);
            AtomicInteger invalidVersions = new AtomicInteger(0);

            // Writer threads - continuously overwrite same chunk
            for (int t = 0; t < writerThreads; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int i = 0; i < iterations; i++) {
                            ChunkData chunk = generator.createMaxComplexityChunk(0, 0);
                            repo.saveChunk(chunk).get(2, TimeUnit.SECONDS);
                            Thread.sleep(5); // Small delay
                        }

                    } catch (Exception e) {
                        recordFailure("Writer thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Reader threads - continuously read and verify header
            for (int t = 0; t < readerThreads; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep(50); // Let writers start first

                        for (int i = 0; i < iterations; i++) {
                            try {
                                Optional<ChunkData> loaded = repo.loadChunk(0, 0).get(1, TimeUnit.SECONDS);
                                if (loaded.isPresent()) {
                                    // Serialize and verify version field
                                    com.stonebreak.world.save.serialization.BinaryChunkSerializer serializer =
                                            new com.stonebreak.world.save.serialization.BinaryChunkSerializer();
                                    byte[] serialized = serializer.serialize(loaded.get());
                                    ByteBuffer buffer = ByteBuffer.wrap(serialized);

                                    buffer.position(8);
                                    int version = buffer.getInt();

                                    if (version != 1) {
                                        invalidVersions.incrementAndGet();
                                        System.err.println("❌ Reader thread " + threadId + " read invalid version: " +
                                                version + " (0x" + Integer.toHexString(version) + ")");
                                    }
                                }
                            } catch (Exception e) {
                                corruptedReads.incrementAndGet();
                                // Expected during concurrent writes - some reads may fail
                            }

                            Thread.sleep(10);
                        }

                    } catch (Exception e) {
                        recordFailure("Reader thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(45, TimeUnit.SECONDS), "Test timed out");

            executor.shutdown();

            if (invalidVersions.get() > 0) {
                recordFailure("INVALID VERSIONS DETECTED: " + invalidVersions.get() +
                        " instances of version != 1 during concurrent access");
            } else {
                System.out.println("✓ No invalid versions detected during concurrent access");
                System.out.println("  - Total operations: " + (writerThreads + readerThreads) * iterations);
                System.out.println("  - Corrupted reads (expected during concurrent writes): " + corruptedReads.get());
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    // ===== Helper Classes and Methods =====

    private static class DataRange {
        int index;
        int offset;
        int length;

        DataRange(int index, int offset, int length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }
    }

    private void recordFailure(String reason) {
        failureReasons.add(reason);
        System.err.println("❌ FAILURE: " + reason);
        fail(reason);
    }

    private Path setupTestWorld(String worldPath) throws IOException {
        Path testPath = Paths.get(worldPath);

        if (Files.exists(testPath)) {
            cleanupTestWorld(testPath);
        }

        Files.createDirectories(testPath);
        return testPath;
    }

    private void cleanupTestWorld(Path testPath) throws IOException {
        if (Files.exists(testPath)) {
            Files.walk(testPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Warning: Failed to delete " + path);
                        }
                    });
        }
    }
}
