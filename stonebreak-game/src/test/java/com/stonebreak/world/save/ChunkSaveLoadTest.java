package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.storage.binary.RegionFileManager;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import com.stonebreak.world.save.util.StateConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive unit test for chunk save/load system.
 *
 * Test Flow:
 * 1. Create a clean chunk with all AIR blocks
 * 2. Place a STONE block at a specific position
 * 3. Save the chunk to disk
 * 4. Clear memory and unload the chunk
 * 5. Load the chunk from disk
 * 6. Verify the STONE block is still at the correct position
 *
 * This validates the entire save/load pipeline including:
 * - Block placement and dirty flag management
 * - Binary encoding with palette compression
 * - Region file storage
 * - Chunk decoding and restoration
 */
public class ChunkSaveLoadTest {

    private static final String TEST_WORLD_PATH = "test_world_save_load";
    private static final int TEST_CHUNK_X = 0;
    private static final int TEST_CHUNK_Z = 0;
    private static final int TEST_BLOCK_X = 5;
    private static final int TEST_BLOCK_Y = 64;
    private static final int TEST_BLOCK_Z = 5;

    public static void main(String[] args) {
        System.out.println("=== Chunk Save/Load Test ===\n");

        ChunkSaveLoadTest test = new ChunkSaveLoadTest();
        try {
            test.runTest();
            System.out.println("\n✅ ALL TESTS PASSED!");
        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void runTest() throws Exception {
        // Phase 1: Setup
        System.out.println("Phase 1: Setup and Initialization");
        System.out.println("----------------------------------");
        Path testWorldPath = setupTestEnvironment();
        RegionFileManager regionManager = new RegionFileManager(testWorldPath.toString());
        System.out.println("✓ Test environment created: " + testWorldPath);
        System.out.println("✓ RegionFileManager initialized\n");

        try {
            // Phase 2: Block Placement Simulation
            System.out.println("Phase 2: Block Placement Simulation");
            System.out.println("------------------------------------");
            Chunk originalChunk = createChunkWithBlock();
            System.out.println("✓ Created chunk at position (" + TEST_CHUNK_X + ", " + TEST_CHUNK_Z + ")");
            System.out.println("✓ Placed STONE block at local position (" + TEST_BLOCK_X + ", " + TEST_BLOCK_Y + ", " + TEST_BLOCK_Z + ")");

            // Verify block placement
            BlockType placedBlock = originalChunk.getBlock(TEST_BLOCK_X, TEST_BLOCK_Y, TEST_BLOCK_Z);
            assert placedBlock == BlockType.STONE : "Block placement failed - expected STONE, got " + placedBlock;
            System.out.println("✓ Verified block placement: " + placedBlock);

            // Verify dirty flag
            assert originalChunk.isDirty() : "Chunk should be marked as DIRTY after block placement";
            System.out.println("✓ Chunk marked as DIRTY (needs save)\n");

            // Phase 3: Manual Save
            System.out.println("Phase 3: Manual Save Operation");
            System.out.println("--------------------------------");
            CompletableFuture<Void> saveFuture = regionManager.saveChunk(originalChunk);
            saveFuture.get(10, TimeUnit.SECONDS); // Wait for save with timeout
            System.out.println("✓ Chunk saved to region file");

            // Verify clean flag after save
            assert !originalChunk.isDirty() : "Chunk should be marked as CLEAN after save";
            System.out.println("✓ Chunk marked as CLEAN after save");

            // Verify region file exists
            Path regionsDir = testWorldPath.resolve("regions");
            assert Files.exists(regionsDir) : "Regions directory should exist";
            long regionFileCount = Files.list(regionsDir)
                .filter(p -> p.toString().endsWith(".mcr"))
                .count();
            assert regionFileCount > 0 : "Region file should exist on disk";
            System.out.println("✓ Region file created on disk: " + regionFileCount + " file(s)\n");

            // Phase 4: Cleanup and Memory Clear
            System.out.println("Phase 4: Cleanup and Memory Clear");
            System.out.println("----------------------------------");
            // Clear reference to simulate chunk unload
            originalChunk = null;
            System.gc(); // Suggest garbage collection
            System.out.println("✓ Original chunk reference cleared");
            System.out.println("✓ Memory cleanup requested\n");

            // Phase 5: Load from Disk
            System.out.println("Phase 5: Load from Disk");
            System.out.println("-----------------------");
            CompletableFuture<Chunk> loadFuture = regionManager.loadChunk(TEST_CHUNK_X, TEST_CHUNK_Z);
            Chunk loadedChunk = loadFuture.get(10, TimeUnit.SECONDS); // Wait for load with timeout

            assert loadedChunk != null : "Loaded chunk should not be null";
            System.out.println("✓ Chunk loaded from region file");

            // Verify chunk is clean (loaded chunks are always clean)
            assert !loadedChunk.isDirty() : "Loaded chunk should be marked as CLEAN";
            System.out.println("✓ Loaded chunk marked as CLEAN\n");

            // Phase 6: Verification
            System.out.println("Phase 6: Verification and Assertions");
            System.out.println("-------------------------------------");

            // Verify chunk position
            assert loadedChunk.getX() == TEST_CHUNK_X : "Chunk X position mismatch";
            assert loadedChunk.getZ() == TEST_CHUNK_Z : "Chunk Z position mismatch";
            System.out.println("✓ Chunk position verified: (" + loadedChunk.getX() + ", " + loadedChunk.getZ() + ")");

            // Verify the STONE block is present at the correct position
            BlockType loadedBlock = loadedChunk.getBlock(TEST_BLOCK_X, TEST_BLOCK_Y, TEST_BLOCK_Z);
            assert loadedBlock == BlockType.STONE :
                "Block verification failed - expected STONE, got " + loadedBlock;
            System.out.println("✓ STONE block verified at position (" + TEST_BLOCK_X + ", " + TEST_BLOCK_Y + ", " + TEST_BLOCK_Z + ")");

            // Verify surrounding blocks are still AIR
            BlockType adjacentBlock1 = loadedChunk.getBlock(TEST_BLOCK_X + 1, TEST_BLOCK_Y, TEST_BLOCK_Z);
            BlockType adjacentBlock2 = loadedChunk.getBlock(TEST_BLOCK_X, TEST_BLOCK_Y + 1, TEST_BLOCK_Z);
            BlockType adjacentBlock3 = loadedChunk.getBlock(TEST_BLOCK_X, TEST_BLOCK_Y, TEST_BLOCK_Z + 1);

            assert adjacentBlock1 == BlockType.AIR : "Adjacent blocks should be AIR";
            assert adjacentBlock2 == BlockType.AIR : "Adjacent blocks should be AIR";
            assert adjacentBlock3 == BlockType.AIR : "Adjacent blocks should be AIR";
            System.out.println("✓ Surrounding blocks verified as AIR");

            // Verify chunk metadata
            assert loadedChunk.getLastModified() != null : "Chunk should have last modified timestamp";
            System.out.println("✓ Chunk metadata verified (last modified: " + loadedChunk.getLastModified() + ")");

            // Additional verification: count non-AIR blocks
            int nonAirCount = countNonAirBlocks(loadedChunk);
            assert nonAirCount == 1 : "Expected exactly 1 non-AIR block, found " + nonAirCount;
            System.out.println("✓ Block count verified: " + nonAirCount + " non-AIR block(s)");

            // Verify palette compression effectiveness
            BinaryChunkSerializer serializer = new BinaryChunkSerializer();
            byte[] encodedData = serializer.serialize(StateConverter.toChunkData(loadedChunk, null));
            int encodedSize = encodedData.length;
            int uncompressedSize = 16 * 16 * 256 * 4; // Rough estimate of uncompressed size
            double compressionRatio = (1.0 - (double)encodedSize / uncompressedSize) * 100;
            System.out.println("✓ Palette compression: " + encodedSize + " bytes (" +
                String.format("%.1f", compressionRatio) + "% reduction)");

        } finally {
            // Phase 7: Cleanup
            System.out.println("\nPhase 7: Cleanup and Resource Disposal");
            System.out.println("---------------------------------------");
            regionManager.close();
            System.out.println("✓ RegionFileManager closed");

            cleanupTestEnvironment(testWorldPath);
            System.out.println("✓ Test environment cleaned up");
        }
    }

    /**
     * Creates a test chunk filled with AIR and places a STONE block at a specific position.
     */
    private Chunk createChunkWithBlock() {
        Chunk chunk = new Chunk(TEST_CHUNK_X, TEST_CHUNK_Z);

        // Initialize all blocks to AIR
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    chunk.setBlock(x, y, z, BlockType.AIR);
                }
            }
        }

        // Place a STONE block at the test position
        chunk.setBlock(TEST_BLOCK_X, TEST_BLOCK_Y, TEST_BLOCK_Z, BlockType.STONE);

        // Mark chunk as dirty to simulate player edit
        chunk.markDirty();

        return chunk;
    }

    /**
     * Counts non-AIR blocks in a chunk for verification.
     */
    private int countNonAirBlocks(Chunk chunk) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (chunk.getBlock(x, y, z) != BlockType.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Sets up a temporary test environment.
     */
    private Path setupTestEnvironment() throws IOException {
        Path testPath = Paths.get(TEST_WORLD_PATH);

        // Clean up if exists from previous run
        if (Files.exists(testPath)) {
            cleanupTestEnvironment(testPath);
        }

        // Create fresh test directory
        Files.createDirectories(testPath);

        return testPath;
    }

    /**
     * Cleans up the test environment.
     */
    private void cleanupTestEnvironment(Path testPath) throws IOException {
        if (Files.exists(testPath)) {
            Files.walk(testPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Warning: Failed to delete " + path + ": " + e.getMessage());
                    }
                });
        }
    }
}
