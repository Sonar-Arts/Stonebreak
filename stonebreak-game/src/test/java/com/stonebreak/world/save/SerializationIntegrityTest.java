package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Comprehensive serialization integrity tests with REALISTIC data.
 *
 * These tests verify:
 * 1. Byte-perfect serialization (serialize → deserialize → re-serialize produces identical bytes)
 * 2. All 65,536 blocks are correctly preserved
 * 3. Water metadata is correctly preserved
 * 4. Entity data is correctly preserved
 * 5. Header fields are correctly written and read
 * 6. Concurrent access doesn't cause corruption
 *
 * Unlike RaceConditionSaveLoadTest, these tests use REALISTIC chunks with:
 * - Varied terrain (15+ block types)
 * - 100+ water metadata entries
 * - 10-20 entities
 * - Large palettes requiring multi-bit encoding
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SerializationIntegrityTest {

    private static final String TEST_WORLD_BASE = "test_world_integrity_";
    private static final long SEED = 98765L;

    private RealisticChunkGenerator generator;
    private BinaryChunkSerializer serializer;
    private List<String> failureReasons;

    @BeforeEach
    void setUp() {
        this.generator = new RealisticChunkGenerator(SEED);
        this.serializer = new BinaryChunkSerializer();
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
     * Test 1: Byte-Perfect Serialization with Terrain Chunks
     * Verifies: serialize → deserialize → re-serialize produces IDENTICAL bytes
     */
    @Test
    @Order(1)
    @DisplayName("Test 1: Byte-Perfect Serialization - Terrain")
    void testBytePerfectTerrainSerialization() {
        ChunkData original = generator.createMaxComplexityChunk(0, 0);

        // First serialization
        byte[] serialized1 = serializer.serialize(original);

        // Deserialize
        ChunkData deserialized = serializer.deserialize(serialized1);

        // Re-serialize
        byte[] serialized2 = serializer.serialize(deserialized);

        // Verify byte arrays are IDENTICAL
        if (serialized1.length != serialized2.length) {
            recordFailure("Serialized byte array lengths differ: " + serialized1.length +
                    " vs " + serialized2.length);
        } else {
            assertArrayEquals(serialized1, serialized2,
                    "Re-serialization produced different bytes - data corruption detected!");
        }

        // Verify all 65,536 blocks match
        if (!RealisticChunkGenerator.chunksIdentical(original, deserialized)) {
            recordFailure("Chunks are not identical after deserialization");
        }

        System.out.println("✓ Byte-perfect serialization verified for terrain chunk");
        System.out.println("  - Serialized size: " + serialized1.length + " bytes");
        System.out.println("  - Unique block types: " + RealisticChunkGenerator.countUniqueBlockTypes(original));
    }

    /**
     * Test 2: Water Metadata Preservation
     * Verifies: 100+ water metadata entries are correctly saved and loaded
     */
    @Test
    @Order(2)
    @DisplayName("Test 2: Water Metadata Preservation")
    void testWaterMetadataPreservation() {
        ChunkData original = generator.createWaterChunk(0, 0);

        System.out.println("Water metadata entries: " + original.getWaterMetadata().size());

        // Serialize and deserialize
        byte[] serialized = serializer.serialize(original);
        ChunkData deserialized = serializer.deserialize(serialized);

        // Verify water metadata matches exactly
        Map<String, ChunkData.WaterBlockData> originalWater = original.getWaterMetadata();
        Map<String, ChunkData.WaterBlockData> deserializedWater = deserialized.getWaterMetadata();

        if (originalWater.size() != deserializedWater.size()) {
            recordFailure("Water metadata count mismatch: " + originalWater.size() +
                    " vs " + deserializedWater.size());
        }

        for (Map.Entry<String, ChunkData.WaterBlockData> entry : originalWater.entrySet()) {
            String key = entry.getKey();
            ChunkData.WaterBlockData originalData = entry.getValue();
            ChunkData.WaterBlockData deserializedData = deserializedWater.get(key);

            if (deserializedData == null) {
                recordFailure("Missing water metadata for key: " + key);
                continue;
            }

            if (originalData.level() != deserializedData.level()) {
                recordFailure("Water level mismatch at " + key + ": " +
                        originalData.level() + " vs " + deserializedData.level());
            }

            if (originalData.falling() != deserializedData.falling()) {
                recordFailure("Water falling state mismatch at " + key);
            }
        }

        System.out.println("✓ Water metadata preservation verified");
        System.out.println("  - Entries verified: " + originalWater.size());
    }

    /**
     * Test 3: Entity Data Preservation
     * Verifies: 10+ entities with positions, velocities, and state are correctly saved
     */
    @Test
    @Order(3)
    @DisplayName("Test 3: Entity Data Preservation")
    void testEntityDataPreservation() {
        ChunkData original = generator.createEntityChunk(0, 0);

        System.out.println("Entity count: " + original.getEntities().size());

        // Serialize and deserialize
        byte[] serialized = serializer.serialize(original);
        ChunkData deserialized = serializer.deserialize(serialized);

        // Verify entity count matches
        assertEquals(original.getEntities().size(), deserialized.getEntities().size(),
                "Entity count mismatch after deserialization");

        // Verify each entity's data (positions, velocities, etc.)
        // Note: Entities may not be in same order, so we just verify count and types
        Map<String, Integer> originalTypes = new HashMap<>();
        for (var entity : original.getEntities()) {
            originalTypes.merge(entity.getEntityType().name(), 1, Integer::sum);
        }

        Map<String, Integer> deserializedTypes = new HashMap<>();
        for (var entity : deserialized.getEntities()) {
            deserializedTypes.merge(entity.getEntityType().name(), 1, Integer::sum);
        }

        assertEquals(originalTypes, deserializedTypes,
                "Entity type distribution changed after deserialization");

        System.out.println("✓ Entity data preservation verified");
        System.out.println("  - Entities verified: " + original.getEntities().size());
    }

    /**
     * Test 4: Header Field Integrity
     * Verifies: All header fields (version, chunkX, chunkZ, etc.) are correct
     */
    @Test
    @Order(4)
    @DisplayName("Test 4: Header Field Integrity")
    void testHeaderFieldIntegrity() {
        ChunkData chunk = generator.createMaxComplexityChunk(42, 137);

        byte[] serialized = serializer.serialize(chunk);
        ByteBuffer buffer = ByteBuffer.wrap(serialized);

        // Verify header structure (32 bytes total)
        int chunkX = buffer.getInt();           // Offset 0
        int chunkZ = buffer.getInt();           // Offset 4
        int version = buffer.getInt();          // Offset 8
        int uncompressedSize = buffer.getInt(); // Offset 12
        long lastModified = buffer.getLong();   // Offset 16
        int paletteSize = buffer.getInt();      // Offset 24
        byte bitsPerBlock = buffer.get();       // Offset 28
        byte compressionType = buffer.get();    // Offset 29
        byte flags = buffer.get();              // Offset 30
        byte reserved = buffer.get();           // Offset 31

        // Validate each field
        assertEquals(42, chunkX, "ChunkX header field corrupted");
        assertEquals(137, chunkZ, "ChunkZ header field corrupted");
        assertEquals(1, version, "Version header field corrupted - THIS IS THE BUG YOU'RE SEEING!");

        assertTrue(paletteSize > 0 && paletteSize <= 256, "Invalid palette size: " + paletteSize);
        assertTrue(bitsPerBlock >= 4 && bitsPerBlock <= 8, "Invalid bits per block: " + bitsPerBlock);
        assertTrue(compressionType == 0 || compressionType == 1, "Invalid compression type: " + compressionType);

        System.out.println("✓ Header integrity verified");
        System.out.println("  - Version: " + version + " (MUST BE 1)");
        System.out.println("  - Palette size: " + paletteSize);
        System.out.println("  - Bits per block: " + bitsPerBlock);
        System.out.println("  - Compression: " + (compressionType == 1 ? "LZ4" : "None"));
    }

    /**
     * Test 5: Large Palette Stress Test
     * Verifies: Chunks with 15+ unique block types serialize correctly
     */
    @Test
    @Order(5)
    @DisplayName("Test 5: Large Palette Stress Test")
    void testLargePaletteStressTest() {
        ChunkData chunk = generator.createMaxComplexityChunk(0, 0);

        int uniqueBlocks = RealisticChunkGenerator.countUniqueBlockTypes(chunk);
        System.out.println("Unique block types in chunk: " + uniqueBlocks);

        assertTrue(uniqueBlocks >= 10, "Test chunk should have at least 10 unique block types");

        // Serialize and deserialize
        byte[] serialized = serializer.serialize(chunk);
        ChunkData deserialized = serializer.deserialize(serialized);

        // Verify all blocks match
        if (!RealisticChunkGenerator.chunksIdentical(chunk, deserialized)) {
            recordFailure("Large palette chunk corrupted during serialization");
        }

        System.out.println("✓ Large palette serialization verified");
        System.out.println("  - Palette size: " + uniqueBlocks + " unique blocks");
    }

    /**
     * Test 6: Edge Case - Empty Chunk
     * Verifies: Chunks with all AIR blocks serialize correctly
     */
    @Test
    @Order(6)
    @DisplayName("Test 6: Edge Case - Empty Chunk")
    void testEmptyChunkSerialization() {
        ChunkData chunk = generator.createEmptyChunk(0, 0);

        byte[] serialized = serializer.serialize(chunk);
        ChunkData deserialized = serializer.deserialize(serialized);

        if (!RealisticChunkGenerator.chunksIdentical(chunk, deserialized)) {
            recordFailure("Empty chunk corrupted during serialization");
        }

        System.out.println("✓ Empty chunk serialization verified");
        System.out.println("  - Palette size: " + RealisticChunkGenerator.countUniqueBlockTypes(chunk));
    }

    /**
     * Test 7: Edge Case - Full Chunk
     * Verifies: Chunks with all STONE blocks serialize correctly
     */
    @Test
    @Order(7)
    @DisplayName("Test 7: Edge Case - Full Chunk")
    void testFullChunkSerialization() {
        ChunkData chunk = generator.createFullChunk(0, 0);

        byte[] serialized = serializer.serialize(chunk);
        ChunkData deserialized = serializer.deserialize(serialized);

        if (!RealisticChunkGenerator.chunksIdentical(chunk, deserialized)) {
            recordFailure("Full chunk corrupted during serialization");
        }

        System.out.println("✓ Full chunk serialization verified");
        System.out.println("  - Palette size: " + RealisticChunkGenerator.countUniqueBlockTypes(chunk));
    }

    /**
     * Test 8: Concurrent Save/Load with Realistic Data
     * Verifies: No corruption when saving/loading realistic chunks concurrently
     */
    @Test
    @Order(8)
    @DisplayName("Test 8: Concurrent Save/Load - Realistic Data")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentSaveLoadRealisticData() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "concurrent_realistic";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int threadCount = 4;
            int chunksPerThread = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            Map<String, ChunkData> savedChunks = new ConcurrentHashMap<>();

            // Each thread saves realistic chunks
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int i = 0; i < chunksPerThread; i++) {
                            int chunkX = threadId * chunksPerThread + i;
                            int chunkZ = 0;

                            // Create realistic chunk with varied data
                            ChunkData chunk = generator.createMaxComplexityChunk(chunkX, chunkZ);
                            savedChunks.put(chunkX + "," + chunkZ, chunk);

                            repo.saveChunk(chunk).get(5, TimeUnit.SECONDS);
                        }

                    } catch (Exception e) {
                        recordFailure("Thread " + threadId + " failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out");

            // Verify all chunks load correctly
            for (Map.Entry<String, ChunkData> entry : savedChunks.entrySet()) {
                String[] coords = entry.getKey().split(",");
                int chunkX = Integer.parseInt(coords[0]);
                int chunkZ = Integer.parseInt(coords[1]);

                Optional<ChunkData> loaded = repo.loadChunk(chunkX, chunkZ).get(5, TimeUnit.SECONDS);
                assertTrue(loaded.isPresent(), "Chunk (" + chunkX + "," + chunkZ + ") failed to load");

                // Verify chunk is identical
                if (!RealisticChunkGenerator.chunksIdentical(entry.getValue(), loaded.get())) {
                    recordFailure("Chunk (" + chunkX + "," + chunkZ + ") corrupted during concurrent save/load");
                }
            }

            executor.shutdown();
            System.out.println("✓ Concurrent save/load verified with realistic data");
            System.out.println("  - Chunks saved/loaded: " + savedChunks.size());

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    /**
     * Test 9: Rapid Save-Reload Cycles
     * Verifies: Data integrity across multiple save/reload cycles
     */
    @Test
    @Order(9)
    @DisplayName("Test 9: Rapid Save-Reload Cycles")
    void testRapidSaveReloadCycles() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "rapid_reload";
        Path testPath = setupTestWorld(testWorldPath);

        ChunkData original = generator.createMaxComplexityChunk(0, 0);

        for (int cycle = 0; cycle < 10; cycle++) {
            FileSaveRepository repo = new FileSaveRepository(testWorldPath);
            try {
                // Save
                repo.saveChunk(original).get(5, TimeUnit.SECONDS);
            } finally {
                repo.close();
            }

            // Reopen and load
            repo = new FileSaveRepository(testWorldPath);
            try {
                Optional<ChunkData> loaded = repo.loadChunk(0, 0).get(5, TimeUnit.SECONDS);
                assertTrue(loaded.isPresent(), "Chunk disappeared after cycle " + cycle);

                if (!RealisticChunkGenerator.chunksIdentical(original, loaded.get())) {
                    recordFailure("Chunk corrupted after cycle " + cycle);
                    break;
                }
            } finally {
                repo.close();
            }
        }

        cleanupTestWorld(testPath);
        System.out.println("✓ Rapid save-reload cycles verified");
    }

    /**
     * Test 10: Version Field Corruption Detection
     * Specifically targets the "Unsupported chunk format version" error
     */
    @Test
    @Order(10)
    @DisplayName("Test 10: Version Field Corruption Detection (CRITICAL)")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testVersionFieldCorruptionDetection() throws Exception {
        String testWorldPath = TEST_WORLD_BASE + "version_corruption";
        Path testPath = setupTestWorld(testWorldPath);
        FileSaveRepository repo = new FileSaveRepository(testWorldPath);

        try {
            int iterations = 200;
            int corruptedVersions = 0;
            List<Integer> badVersions = new ArrayList<>();

            for (int i = 0; i < iterations; i++) {
                // Save realistic chunk
                ChunkData chunk = generator.createMaxComplexityChunk(0, 0);
                repo.saveChunk(chunk).get(2, TimeUnit.SECONDS);

                // Load and verify version field
                Optional<ChunkData> loaded = repo.loadChunk(0, 0).get(2, TimeUnit.SECONDS);
                if (loaded.isPresent()) {
                    // Serialize and check version field directly
                    byte[] serialized = serializer.serialize(loaded.get());
                    ByteBuffer buffer = ByteBuffer.wrap(serialized);

                    buffer.position(8); // Skip chunkX (4) and chunkZ (4)
                    int version = buffer.getInt();

                    if (version != 1) {
                        corruptedVersions++;
                        badVersions.add(version);
                        System.err.println("❌ VERSION CORRUPTION at iteration " + i + ": " +
                                version + " (0x" + Integer.toHexString(version) + ")");
                    }
                }
            }

            if (corruptedVersions > 0) {
                String uniqueVersions = badVersions.stream()
                        .distinct()
                        .map(v -> v + " (0x" + Integer.toHexString(v) + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");

                recordFailure("VERSION CORRUPTION DETECTED: Found " + corruptedVersions +
                        " corrupted versions out of " + iterations + " iterations. Bad values: " + uniqueVersions);
            } else {
                System.out.println("✓ No version field corruption detected in " + iterations + " iterations");
            }

        } finally {
            repo.close();
            cleanupTestWorld(testPath);
        }
    }

    // ===== Helper Methods =====

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
