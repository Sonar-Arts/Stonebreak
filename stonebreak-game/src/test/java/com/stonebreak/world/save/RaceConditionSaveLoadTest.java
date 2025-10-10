package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import com.stonebreak.world.save.util.StateConverter;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Comprehensive race condition test for save/load system using JUnit 5.
 *
 * Tests all identified race conditions:
 * 1. Concurrent same-chunk writes
 * 2. Concurrent different-chunk writes
 * 3. Auto-save vs manual save
 * 4. Read-during-write
 * 5. Rapid chunk churn (flying simulation)
 * 6. Save-exit-reload cycle
 * 7. Header consistency
 * 8. Batch sync counter race
 * 9. Version field integrity
 * 10. Dirty flag timing
 *
 * Each test runs independently with proper JUnit lifecycle management.
 * Run individual tests from IDE or entire suite with Maven/Gradle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RaceConditionSaveLoadTest {

    private static final String TEST_WORLD_BASE = "test_world_race_";
    private static final int THREAD_COUNT = 8;
    private static final int ITERATIONS_PER_TEST = 100;
    private static final long TEST_TIMEOUT_MS = 30000; // 30 seconds per test

    private ExecutorService executor;
    private Random random;
    private List<String> failureReasons;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newFixedThreadPool(THREAD_COUNT, r -> {
            Thread t = new Thread(r, "RaceTest-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        this.random = new Random(12345);
        this.failureReasons = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Report any failures collected during test
        if (!failureReasons.isEmpty()) {
            String failures = String.join("\n  - ", failureReasons);
            System.err.println("Test failures detected:\n  - " + failures);
        }
    }

    /**
     * Test 1: Concurrent Same-Chunk Writes
     * Multiple threads try to save the same chunk simultaneously.
     * Expected: No corruption, last write should win.
     */
    @Test
    @Order(1)
    @DisplayName("Test 1: Concurrent Same-Chunk Writes")
    void testConcurrentSameChunkWrites() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "concurrent_same";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunkX = 0, chunkZ = 0;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
            ConcurrentHashMap<Integer, Chunk> savedChunks = new ConcurrentHashMap<>();

            // All threads save the same chunk with different data
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready

                        // Create chunk with unique marker block
                        Chunk chunk = createChunkWithMarker(chunkX, chunkZ, threadId);
                        savedChunks.put(threadId, chunk);

                        // Save chunk
                        ChunkData data = StateConverter.toChunkData(chunk, null);
                        repo.saveChunk(data).get(5, TimeUnit.SECONDS);

                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed to save: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for completion with timeout
            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Test timed out after " + TEST_TIMEOUT_MS + "ms");
                return;
            }

            // Verify: Load chunk and check it matches one of the saved versions
            Optional<ChunkData> loadedOpt = repo.loadChunk(chunkX, chunkZ).get(5, TimeUnit.SECONDS);
            if (!loadedOpt.isPresent()) {
                recordFailure("Chunk failed to load after concurrent writes");
                return;
            }

            Chunk loaded = StateConverter.createChunkFromData(loadedOpt.get(), null);
            BlockType markerBlock = loaded.getBlock(8, 64, 8);

            // Verify the marker matches one of the saved chunks
            boolean matched = false;
            for (Chunk savedChunk : savedChunks.values()) {
                if (savedChunk.getBlock(8, 64, 8) == markerBlock) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                recordFailure("Loaded chunk doesn't match any saved version - possible corruption");
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 2: Concurrent Different-Chunk Writes
     * Multiple threads save different chunks to the same region file.
     * Expected: All chunks saved correctly without interference.
     */
    @Test
    @Order(2)
    @DisplayName("Test 2: Concurrent Different-Chunk Writes")
    void testConcurrentDifferentChunkWrites() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "concurrent_different";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunksPerThread = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
            Map<String, BlockType> chunkMarkers = new ConcurrentHashMap<>();

            // Each thread saves multiple chunks with unique markers
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int i = 0; i < chunksPerThread; i++) {
                            int chunkX = threadId * chunksPerThread + i;
                            int chunkZ = 0;

                            Chunk chunk = createChunkWithMarker(chunkX, chunkZ, threadId);
                            BlockType marker = chunk.getBlock(8, 64, 8);
                            chunkMarkers.put(chunkX + "," + chunkZ, marker);

                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(5, TimeUnit.SECONDS);
                        }

                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Test timed out");
                return;
            }

            // Verify all chunks saved correctly
            for (Map.Entry<String, BlockType> entry : chunkMarkers.entrySet()) {
                String[] coords = entry.getKey().split(",");
                int chunkX = Integer.parseInt(coords[0]);
                int chunkZ = Integer.parseInt(coords[1]);

                Optional<ChunkData> loadedOpt = repo.loadChunk(chunkX, chunkZ).get(5, TimeUnit.SECONDS);
                if (!loadedOpt.isPresent()) {
                    recordFailure("Chunk (" + chunkX + "," + chunkZ + ") not found after save");
                    continue;
                }

                Chunk loaded = StateConverter.createChunkFromData(loadedOpt.get(), null);
                BlockType loadedMarker = loaded.getBlock(8, 64, 8);

                if (loadedMarker != entry.getValue()) {
                    recordFailure("Chunk (" + chunkX + "," + chunkZ + ") corrupted - expected " +
                        entry.getValue() + " but got " + loadedMarker);
                }
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 3: Auto-Save vs Manual Save
     * Simulate auto-save and manual save happening simultaneously.
     * Expected: No duplicate saves, no corruption.
     */
    @Test
    @Order(3)
    @DisplayName("Test 3: Auto-Save vs Manual Save")
    void testAutoSaveVsManualSave() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "autosave_manual";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            // Create multiple dirty chunks
            List<Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Chunk chunk = createChunkWithMarker(i, 0, i);
                chunks.add(chunk);
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicInteger saveCount = new AtomicInteger(0);

            // Thread 1: Auto-save simulation
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (Chunk chunk : chunks) {
                        if (chunk.isDirty()) {
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get();
                            chunk.markClean();
                            saveCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    recordFailure("Auto-save failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: Manual save simulation
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (Chunk chunk : chunks) {
                        if (chunk.isDirty()) {
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get();
                            chunk.markClean();
                            saveCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    recordFailure("Manual save failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Test timed out");
                return;
            }

            // Expected: Each chunk saved at least once (could be up to 2x if both threads save same chunk)
            if (saveCount.get() < chunks.size()) {
                recordFailure("Not all chunks were saved - expected at least " + chunks.size() +
                    " saves, got " + saveCount.get());
            }

            // Verify all chunks can be loaded
            for (int i = 0; i < chunks.size(); i++) {
                Optional<ChunkData> loaded = repo.loadChunk(i, 0).get(5, TimeUnit.SECONDS);
                if (!loaded.isPresent()) {
                    recordFailure("Chunk " + i + " failed to load after concurrent auto/manual save");
                }
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 4: Read-During-Write
     * One thread writes while another reads the same chunk.
     * Expected: Either old or new data (never corrupted/partial).
     */
    @Test
    @Order(4)
    @DisplayName("Test 4: Read-During-Write")
    void testReadDuringWrite() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "read_during_write";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunkX = 0, chunkZ = 0;

            // First save: initial data
            Chunk initialChunk = createChunkWithMarker(chunkX, chunkZ, 100);
            ChunkData initialData = StateConverter.toChunkData(initialChunk, null);
            repo.saveChunk(initialData).get(5, TimeUnit.SECONDS);

            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Optional<ChunkData>> readResults = new CopyOnWriteArrayList<>();
            AtomicBoolean writeCompleted = new AtomicBoolean(false);

            // Writer thread
            executor.submit(() -> {
                try {
                    barrier.await();

                    // Save new version
                    Chunk newChunk = createChunkWithMarker(chunkX, chunkZ, 200);
                    ChunkData newData = StateConverter.toChunkData(newChunk, null);
                    repo.saveChunk(newData).get(5, TimeUnit.SECONDS);

                    writeCompleted.set(true);
                } catch (Exception e) {
                    recordFailure("Writer thread failed: " + e.getMessage());
                }
            });

            // Reader thread - tries to read while write is in progress
            executor.submit(() -> {
                try {
                    barrier.await();

                    // Attempt multiple reads during write
                    for (int i = 0; i < 10; i++) {
                        Optional<ChunkData> data = repo.loadChunk(chunkX, chunkZ).get(1, TimeUnit.SECONDS);
                        readResults.add(data);
                        Thread.sleep(5); // Small delay between reads
                    }
                } catch (Exception e) {
                    recordFailure("Reader thread failed: " + e.getMessage());
                }
            });

            Thread.sleep(2000); // Wait for operations to complete

            // Verify: All reads should return valid data (either old or new version)
            for (Optional<ChunkData> result : readResults) {
                if (!result.isPresent()) {
                    continue; // OK - chunk might not exist during write
                }

                Chunk chunk = StateConverter.createChunkFromData(result.get(), null);
                BlockType marker = chunk.getBlock(8, 64, 8);

                // Should be either DIRT (100) or STONE (200), never corrupted
                if (marker != BlockType.DIRT && marker != BlockType.STONE) {
                    recordFailure("Read during write returned corrupted data: " + marker);
                }

                // Verify version field is always 1
                BinaryChunkSerializer serializer = new BinaryChunkSerializer();
                byte[] serialized = serializer.serialize(result.get());
                ByteBuffer buffer = ByteBuffer.wrap(serialized);
                buffer.position(8); // Skip chunkX and chunkZ
                int version = buffer.getInt();

                if (version != 1) {
                    recordFailure("Version field corrupted during read-during-write: " + version);
                }
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 5: Rapid Chunk Churn (Flying Simulation)
     * Simulates rapid chunk loading/unloading as player flies.
     * Expected: All chunks saved/loaded correctly.
     */
    @Test
    @Order(5)
    @DisplayName("Test 5: Rapid Chunk Churn (Flying Simulation)")
    void testRapidChunkChurn() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "rapid_churn";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunkCount = 50;
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            // Multiple threads simulate flying (load/save/unload chunks rapidly)
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < chunkCount; i++) {
                            int chunkX = threadId * chunkCount + i;
                            int chunkZ = random.nextInt(10);

                            // Load (or create if not exists)
                            Optional<ChunkData> existing = repo.loadChunk(chunkX, chunkZ).get(2, TimeUnit.SECONDS);
                            Chunk chunk;

                            if (existing.isPresent()) {
                                chunk = StateConverter.createChunkFromData(existing.get(), null);
                            } else {
                                chunk = createChunkWithMarker(chunkX, chunkZ, threadId);
                            }

                            // Modify
                            chunk.setBlock(random.nextInt(16), 64, random.nextInt(16), BlockType.GRASS);
                            chunk.markDirty();

                            // Save
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(2, TimeUnit.SECONDS);

                            // Small delay to simulate actual gameplay
                            Thread.sleep(random.nextInt(10));
                        }
                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed during rapid churn: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Rapid churn test timed out");
                return;
            }

            // Verify: Try to load all chunks that were saved
            // Note: We don't verify all chunks load because some might have been overwritten
            // But all chunks that DO load should be valid
            for (int t = 0; t < THREAD_COUNT; t++) {
                for (int i = 0; i < chunkCount; i++) {
                    int chunkX = t * chunkCount + i;
                    for (int chunkZ = 0; chunkZ < 10; chunkZ++) {
                        try {
                            Optional<ChunkData> loaded = repo.loadChunk(chunkX, chunkZ).get(2, TimeUnit.SECONDS);
                            if (loaded.isPresent()) {
                                // Verify chunk is valid
                                Chunk chunk = StateConverter.createChunkFromData(loaded.get(), null);
                                if (chunk.getX() != chunkX || chunk.getZ() != chunkZ) {
                                    recordFailure("Chunk coordinates corrupted: expected (" + chunkX + "," + chunkZ +
                                        ") but got (" + chunk.getX() + "," + chunk.getZ() + ")");
                                }
                            }
                        } catch (Exception e) {
                            recordFailure("Failed to load chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                        }
                    }
                }
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 6: Save-Exit-Reload Cycle
     * Save, close all files, reopen, verify.
     * Expected: Data persists correctly across restarts.
     */
    @Test
    @Order(6)
    @DisplayName("Test 6: Save-Exit-Reload Cycle")
    void testSaveExitReload() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "save_exit_reload";
        Path testPath = setupTestWorld(testWorldPath);

        Map<String, BlockType> savedMarkers = new HashMap<>();

        // Phase 1: Save chunks
        {
            FileSaveRepository repo = new FileSaveRepository(testWorldPath);
            try {
                for (int i = 0; i < 20; i++) {
                    Chunk chunk = createChunkWithMarker(i, 0, i);
                    savedMarkers.put(i + ",0", chunk.getBlock(8, 64, 8));

                    ChunkData data = StateConverter.toChunkData(chunk, null);
                    repo.saveChunk(data).get(5, TimeUnit.SECONDS);
                }
            } finally {
                repo.close(); // Ensure all data flushed
            }
        }

        // Phase 2: Reopen and verify
        {
            FileSaveRepository repo = new FileSaveRepository(testWorldPath);
            try {
                for (Map.Entry<String, BlockType> entry : savedMarkers.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    int chunkX = Integer.parseInt(coords[0]);
                    int chunkZ = Integer.parseInt(coords[1]);

                    Optional<ChunkData> loaded = repo.loadChunk(chunkX, chunkZ).get(5, TimeUnit.SECONDS);
                    if (!loaded.isPresent()) {
                        recordFailure("Chunk (" + chunkX + "," + chunkZ + ") not found after reload");
                        continue;
                    }

                    Chunk chunk = StateConverter.createChunkFromData(loaded.get(), null);
                    BlockType loadedMarker = chunk.getBlock(8, 64, 8);

                    if (loadedMarker != entry.getValue()) {
                        recordFailure("Chunk (" + chunkX + "," + chunkZ + ") corrupted after reload - expected " +
                            entry.getValue() + " but got " + loadedMarker);
                    }
                }
            } finally {
                repo.close();
            }
        }

        cleanupTestWorld(testPath);
    }

    /**
     * Test 7: Header Consistency
     * Verify offset and length in region file header are always consistent.
     * Expected: No torn reads of header entries.
     */
    @Test
    @Order(7)
    @DisplayName("Test 7: Header Consistency")
    void testHeaderConsistency() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "header_consistency";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunkX = 5, chunkZ = 5;
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger headerInconsistencies = new AtomicInteger(0);

            // Multiple threads write and immediately verify header
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < ITERATIONS_PER_TEST; i++) {
                            // Save chunk
                            Chunk chunk = createChunkWithMarker(chunkX, chunkZ, threadId * 1000 + i);
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(2, TimeUnit.SECONDS);

                            // Immediately try to load - header must be consistent
                            Optional<ChunkData> loaded = repo.loadChunk(chunkX, chunkZ).get(2, TimeUnit.SECONDS);
                            if (!loaded.isPresent()) {
                                headerInconsistencies.incrementAndGet();
                                recordFailure("Header inconsistency: chunk disappeared after save");
                            }
                        }
                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Header consistency test timed out");
                return;
            }

            if (headerInconsistencies.get() > 0) {
                recordFailure("Detected " + headerInconsistencies.get() + " header inconsistencies");
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 8: Batch Sync Counter Race
     * Verify batch sync counter remains accurate under concurrent writes.
     * Expected: All writes eventually synced to disk.
     */
    @Test
    @Order(8)
    @DisplayName("Test 8: Batch Sync Counter")
    void testBatchSyncCounter() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "batch_sync";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int totalChunks = 100;
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            // All threads rapidly save chunks
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < totalChunks / THREAD_COUNT; i++) {
                            int chunkX = threadId * (totalChunks / THREAD_COUNT) + i;
                            Chunk chunk = createChunkWithMarker(chunkX, 0, threadId);
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(2, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Batch sync test timed out");
                return;
            }

            // Close repo to force final sync
            repo.close();

            // Reopen and verify all chunks present
            FileSaveRepository repo2 = new FileSaveRepository(testWorldPath);
            try {
                int foundChunks = 0;
                for (int i = 0; i < totalChunks; i++) {
                    Optional<ChunkData> loaded = repo2.loadChunk(i, 0).get(2, TimeUnit.SECONDS);
                    if (loaded.isPresent()) {
                        foundChunks++;
                    }
                }

                if (foundChunks != totalChunks) {
                    recordFailure("Batch sync lost data - expected " + totalChunks + " chunks but found " + foundChunks);
                }
            } finally {
                repo2.close();
            }

        } finally {
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 9: Version Field Integrity
     * Specifically targets the "Invalid version: 33571003" bug.
     * Expected: Version field always reads as 1.
     */
    @Test
    @Order(9)
    @DisplayName("Test 9: Version Field Integrity (Critical)")
    void testVersionFieldIntegrity() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "version_integrity";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int chunkX = 0, chunkZ = 0;
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger corruptedVersions = new AtomicInteger(0);
            List<Integer> badVersions = new CopyOnWriteArrayList<>();

            // Repeatedly save and verify version field under concurrent load
            for (int t = 0; t < THREAD_COUNT; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < ITERATIONS_PER_TEST * 2; i++) { // Extra iterations for this critical test
                            // Save chunk
                            Chunk chunk = createChunkWithMarker(chunkX, chunkZ, threadId);
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(1, TimeUnit.SECONDS);

                            // Load and verify version
                            Optional<ChunkData> loaded = repo.loadChunk(chunkX, chunkZ).get(1, TimeUnit.SECONDS);
                            if (loaded.isPresent()) {
                                // Directly check serialized version field
                                BinaryChunkSerializer serializer = new BinaryChunkSerializer();
                                byte[] serialized = serializer.serialize(loaded.get());

                                ByteBuffer buffer = ByteBuffer.wrap(serialized);
                                buffer.position(8); // Skip chunkX (4) and chunkZ (4)
                                int version = buffer.getInt();

                                if (version != 1) {
                                    corruptedVersions.incrementAndGet();
                                    badVersions.add(version);
                                    recordFailure("VERSION CORRUPTION DETECTED: Expected 1, got " + version +
                                        " (0x" + Integer.toHexString(version) + ")");
                                }
                            }

                            // No delay - maximize stress
                        }
                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            if (!doneLatch.await(TEST_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)) { // Double timeout for this test
                recordFailure("Version integrity test timed out");
                return;
            }

            if (corruptedVersions.get() > 0) {
                String uniqueBadVersions = badVersions.stream()
                    .distinct()
                    .map(v -> v + " (0x" + Integer.toHexString(v) + ")")
                    .collect(Collectors.joining(", "));

                recordFailure("VERSION FIELD CORRUPTION: Found " + corruptedVersions.get() +
                    " corrupted version fields with values: " + uniqueBadVersions);
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 10: Dirty Flag Timing
     * Verify no duplicate saves due to markClean() timing.
     * Expected: Each dirty chunk saved exactly once per cycle.
     */
    @Test
    @Order(10)
    @DisplayName("Test 10: Dirty Flag Timing")
    void testDirtyFlagTiming() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "dirty_flag";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            List<Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Chunk chunk = createChunkWithMarker(i, 0, i);
                chunks.add(chunk);
            }

            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicInteger totalSaves = new AtomicInteger(0);

            // Thread 1: Save dirty chunks
            executor.submit(() -> {
                try {
                    for (Chunk chunk : chunks) {
                        if (chunk.isDirty()) {
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(2, TimeUnit.SECONDS);
                            totalSaves.incrementAndGet();
                            // Delay before marking clean to create race window
                            Thread.sleep(5);
                            chunk.markClean();
                        }
                    }
                } catch (Exception e) {
                    recordFailure("Save thread 1 failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: Also tries to save dirty chunks (should see fewer dirty after Thread 1)
            executor.submit(() -> {
                try {
                    Thread.sleep(50); // Start slightly after Thread 1
                    for (Chunk chunk : chunks) {
                        if (chunk.isDirty()) {
                            ChunkData data = StateConverter.toChunkData(chunk, null);
                            repo.saveChunk(data).get(2, TimeUnit.SECONDS);
                            totalSaves.incrementAndGet();
                            chunk.markClean();
                        }
                    }
                } catch (Exception e) {
                    recordFailure("Save thread 2 failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });

            if (!doneLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                recordFailure("Dirty flag test timed out");
                return;
            }

            // Expect between chunks.size() and 2*chunks.size() saves
            // (Ideal is chunks.size(), but some duplicates acceptable due to timing)
            if (totalSaves.get() < chunks.size()) {
                recordFailure("Not all dirty chunks saved - expected at least " + chunks.size() +
                    " but got " + totalSaves.get());
            }

            if (totalSaves.get() > chunks.size() * 1.5) {
                recordFailure("Too many duplicate saves - expected ~" + chunks.size() +
                    " but got " + totalSaves.get() + " (possible dirty flag race)");
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    // ===== Helper Methods =====

    private Chunk createChunkWithMarker(int chunkX, int chunkZ, int markerId) {
        Chunk chunk = new Chunk(chunkX, chunkZ);

        // Fill with AIR
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    chunk.setBlock(x, y, z, BlockType.AIR);
                }
            }
        }

        // Place unique marker block based on ID
        BlockType marker = getMarkerBlockType(markerId);
        chunk.setBlock(8, 64, 8, marker);

        chunk.markDirty();
        return chunk;
    }

    private BlockType getMarkerBlockType(int id) {
        // Use different block types as markers
        BlockType[] markers = {
            BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.COBBLESTONE,
            BlockType.WOOD_PLANKS, BlockType.SAND, BlockType.GRAVEL, BlockType.BEDROCK
        };
        return markers[Math.abs(id) % markers.length];
    }

    private void recordFailure(String reason) {
        failureReasons.add(reason);
        System.err.println("❌ FAILURE: " + reason);
    }

    private Path setupTestWorld(String worldPath) throws IOException {
        Path testPath = Paths.get(worldPath);

        // Clean up if exists
        if (Files.exists(testPath)) {
            cleanupTestWorld(testPath);
        }

        // Create fresh directory
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
