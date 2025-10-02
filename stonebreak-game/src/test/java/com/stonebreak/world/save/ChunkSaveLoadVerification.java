package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.storage.binary.RegionFileManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verification test that mimics real-world game behavior:
 * 1. Generate a chunk naturally
 * 2. Place blocks (player edit)
 * 3. Mark as dirty
 * 4. Save on unload
 * 5. Reload from disk
 * 6. Verify blocks persist
 *
 * This test validates that the save-first approach works end-to-end.
 */
public class ChunkSaveLoadVerification {

    private static final String TEST_WORLD_PATH = "test_world_verification";
    private static final int TEST_CHUNK_X = 5;
    private static final int TEST_CHUNK_Z = -3;

    // Test various block placement scenarios
    private static final TestBlock[] TEST_BLOCKS = {
        new TestBlock(0, 64, 0, BlockType.STONE),      // Corner block
        new TestBlock(15, 64, 15, BlockType.DIRT),     // Opposite corner
        new TestBlock(8, 100, 8, BlockType.GRASS),     // High altitude
        new TestBlock(5, 5, 5, BlockType.STONE),       // Low altitude
        new TestBlock(10, 64, 10, BlockType.GRASS),    // Center area
    };

    public static void main(String[] args) {
        System.out.println("=== Chunk Save/Load Verification ===");
        System.out.println("This test verifies dirty chunk handling as it occurs in the game\n");

        ChunkSaveLoadVerification test = new ChunkSaveLoadVerification();
        try {
            test.runVerification();
            System.out.println("\n✅ VERIFICATION PASSED!");
            System.out.println("Dirty chunks are being saved and loaded correctly.");
        } catch (Exception e) {
            System.err.println("\n❌ VERIFICATION FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void runVerification() throws Exception {
        System.out.println("=== Phase 1: Simulated World Generation ===");
        Path testWorldPath = setupTestEnvironment();
        RegionFileManager regionManager = new RegionFileManager(testWorldPath.toString());
        System.out.println("✓ Test world created: " + testWorldPath);
        System.out.println("✓ Region manager initialized\n");

        try {
            // Phase 2: Simulate chunk generation with terrain
            System.out.println("=== Phase 2: Generate Chunk with Terrain ===");
            Chunk generatedChunk = generateChunkWithTerrain();
            System.out.println("✓ Generated chunk at (" + TEST_CHUNK_X + ", " + TEST_CHUNK_Z + ")");
            System.out.println("✓ Chunk has natural terrain (simulated)");

            int initialBlockCount = countNonAirBlocks(generatedChunk);
            System.out.println("✓ Initial terrain blocks: " + initialBlockCount + "\n");

            // Phase 3: Simulate player edits
            System.out.println("=== Phase 3: Simulate Player Block Placement ===");
            for (TestBlock testBlock : TEST_BLOCKS) {
                generatedChunk.setBlock(testBlock.x, testBlock.y, testBlock.z, testBlock.type);
                System.out.println("✓ Placed " + testBlock.type + " at (" +
                    testBlock.x + ", " + testBlock.y + ", " + testBlock.z + ")");
            }

            // Mark chunk as dirty (this happens automatically in setBlock, but being explicit)
            assert generatedChunk.isDirty() : "Chunk should be dirty after block placement";
            System.out.println("✓ Chunk marked as DIRTY (has unsaved edits)");

            int editedBlockCount = countNonAirBlocks(generatedChunk);
            System.out.println("✓ Total blocks after edits: " + editedBlockCount + "\n");

            // Phase 4: Save dirty chunk (simulates unload behavior)
            System.out.println("=== Phase 4: Save Dirty Chunk on Unload ===");
            System.out.println("Simulating chunk unload with dirty flag...");

            CompletableFuture<Void> saveFuture = regionManager.saveChunk(generatedChunk);
            saveFuture.get(10, TimeUnit.SECONDS);

            assert !generatedChunk.isDirty() : "Chunk should be CLEAN after save";
            System.out.println("✓ Dirty chunk saved successfully");
            System.out.println("✓ Chunk now marked as CLEAN");
            System.out.println("✓ Region file created on disk\n");

            // Phase 5: Complete world unload and reload from disk
            System.out.println("=== Phase 5: Complete World Unload and Reload ===");
            generatedChunk = null;

            // Close the manager to flush all caches and resources
            regionManager.close();
            System.out.println("✓ Region manager closed (all caches cleared)");

            // Create a NEW manager instance - forces disk read
            regionManager = new RegionFileManager(testWorldPath.toString());
            System.out.println("✓ New region manager created");
            System.out.println("✓ This ensures chunk loads from disk, not cache\n");

            CompletableFuture<Chunk> loadFuture = regionManager.loadChunk(TEST_CHUNK_X, TEST_CHUNK_Z);
            Chunk loadedChunk = loadFuture.get(10, TimeUnit.SECONDS);

            assert loadedChunk != null : "Chunk should load successfully";
            assert !loadedChunk.isDirty() : "Loaded chunk should be CLEAN";
            System.out.println("✓ Chunk loaded from disk");
            System.out.println("✓ Loaded chunk state: CLEAN\n");

            // Phase 6: Verify ALL block placements persisted
            System.out.println("=== Phase 6: Verify Block Persistence ===");

            int verifiedCount = 0;
            for (TestBlock testBlock : TEST_BLOCKS) {
                BlockType actualBlock = loadedChunk.getBlock(testBlock.x, testBlock.y, testBlock.z);

                if (actualBlock != testBlock.type) {
                    throw new AssertionError(
                        "Block verification failed at (" + testBlock.x + ", " + testBlock.y + ", " + testBlock.z + ")\n" +
                        "Expected: " + testBlock.type + "\n" +
                        "Got: " + actualBlock
                    );
                }

                System.out.println("✓ Verified " + testBlock.type + " at (" +
                    testBlock.x + ", " + testBlock.y + ", " + testBlock.z + ")");
                verifiedCount++;
            }

            System.out.println("✓ All " + verifiedCount + " placed blocks verified");

            int finalBlockCount = countNonAirBlocks(loadedChunk);
            assert finalBlockCount == editedBlockCount :
                "Block count mismatch: expected " + editedBlockCount + ", got " + finalBlockCount;
            System.out.println("✓ Total block count matches: " + finalBlockCount + "\n");

            // Phase 7: Verify chunk metadata
            System.out.println("=== Phase 7: Verify Chunk Metadata ===");
            assert loadedChunk.getX() == TEST_CHUNK_X : "Chunk X position mismatch";
            assert loadedChunk.getZ() == TEST_CHUNK_Z : "Chunk Z position mismatch";
            assert loadedChunk.getLastModified() != null : "Chunk should have last modified timestamp";

            System.out.println("✓ Chunk position: (" + loadedChunk.getX() + ", " + loadedChunk.getZ() + ")");
            System.out.println("✓ Last modified: " + loadedChunk.getLastModified());
            System.out.println("✓ Features populated: " + loadedChunk.areFeaturesPopulated());
            System.out.println("✓ Metadata verified\n");

            // Phase 8: Verify save-first approach
            System.out.println("=== Phase 8: Verify Save-First Approach ===");
            System.out.println("Testing that loaded chunks are used instead of regeneration...");

            // Reload the same chunk again - should load from save, not regenerate
            Chunk reloadedChunk = regionManager.loadChunk(TEST_CHUNK_X, TEST_CHUNK_Z).get(10, TimeUnit.SECONDS);

            // Verify all our placed blocks are still there
            for (TestBlock testBlock : TEST_BLOCKS) {
                BlockType actualBlock = reloadedChunk.getBlock(testBlock.x, testBlock.y, testBlock.z);
                assert actualBlock == testBlock.type :
                    "Second load failed - save-first not working correctly";
            }

            System.out.println("✓ Chunk reloaded successfully from save");
            System.out.println("✓ All edits persisted across multiple loads");
            System.out.println("✓ Save-first approach confirmed working\n");

        } finally {
            System.out.println("=== Cleanup ===");
            regionManager.close();
            System.out.println("✓ Region manager closed");

            cleanupTestEnvironment(testWorldPath);
            System.out.println("✓ Test environment cleaned up");
        }
    }

    /**
     * Generates a chunk with simulated terrain.
     * In the real game, this would be done by TerrainGenerationSystem.
     */
    private Chunk generateChunkWithTerrain() {
        Chunk chunk = new Chunk(TEST_CHUNK_X, TEST_CHUNK_Z);

        // Simulate natural terrain generation (simplified)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Bedrock layer
                chunk.setBlock(x, 0, z, BlockType.STONE);

                // Stone layers
                for (int y = 1; y < 60; y++) {
                    chunk.setBlock(x, y, z, BlockType.STONE);
                }

                // Dirt layers
                for (int y = 60; y < 63; y++) {
                    chunk.setBlock(x, y, z, BlockType.DIRT);
                }

                // Grass top layer
                chunk.setBlock(x, 63, z, BlockType.GRASS);

                // Air above
                for (int y = 64; y < 256; y++) {
                    chunk.setBlock(x, y, z, BlockType.AIR);
                }
            }
        }

        // Mark as clean initially (natural generation doesn't need saving yet)
        chunk.markClean();

        return chunk;
    }

    /**
     * Counts non-AIR blocks in a chunk.
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

    private Path setupTestEnvironment() throws IOException {
        Path testPath = Paths.get(TEST_WORLD_PATH);

        if (Files.exists(testPath)) {
            cleanupTestEnvironment(testPath);
        }

        Files.createDirectories(testPath);
        return testPath;
    }

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

    /**
     * Helper class to represent a test block placement.
     */
    private static class TestBlock {
        final int x, y, z;
        final BlockType type;

        TestBlock(int x, int y, int z, BlockType type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
        }
    }
}
