package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSystem;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Integration test for water flow save/load through the full game save system.
 *
 * This test validates the complete pipeline:
 * 1. World creation with water blocks
 * 2. Water flow simulation (source detection + flowing water)
 * 3. Save via SaveService (chunk + water metadata)
 * 4. World unload (cleanup WaterSystem)
 * 5. Load via SaveService (restore chunks + water metadata)
 * 6. Verify water states fully restored (including source detection)
 *
 * This is a full integration test that exercises the entire save/load system.
 */
public class WaterFlowSaveLoadIntegrationTest {

    private static final int TEST_CHUNK_X = 5;
    private static final int TEST_CHUNK_Z = 7;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║       WATER FLOW SAVE/LOAD INTEGRATION TEST                        ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        WaterFlowSaveLoadIntegrationTest test = new WaterFlowSaveLoadIntegrationTest();
        try {
            test.runTest();
            System.out.println("\n╔════════════════════════════════════════════════════════════════════╗");
            System.out.println("║                  ✅ ALL TESTS PASSED!                               ║");
            System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        } catch (Exception e) {
            System.err.println("\n╔════════════════════════════════════════════════════════════════════╗");
            System.err.println("║                  ❌ TEST FAILED!                                    ║");
            System.err.println("╚════════════════════════════════════════════════════════════════════╝");
            System.err.println("\nError: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void runTest() throws Exception {
        Path testWorldPath = null;

        try {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 1: SETUP TEST ENVIRONMENT
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 1: SETUP TEST ENVIRONMENT");
            System.out.println("═══════════════════════════════════════════════════════════════");

            // Create temporary world directory
            testWorldPath = Files.createTempDirectory("stonebreak-water-test-");
            System.out.println("✓ Created test world directory: " + testWorldPath);

            // Create SaveService
            SaveService saveService = new SaveService(testWorldPath.toString());
            System.out.println("✓ SaveService initialized");

            // Create test world in test mode
            TestWorld world = new TestWorld();
            saveService.initialize(null, null, world);
            System.out.println("✓ Test World created (test mode - no rendering)");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 2: CREATE CHUNK WITH WATER
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 2: CREATE CHUNK WITH WATER BLOCKS");
            System.out.println("═══════════════════════════════════════════════════════════════");

            Chunk chunk = createChunkWithWater();
            System.out.println("✓ Created chunk at (" + TEST_CHUNK_X + ", " + TEST_CHUNK_Z + ")");

            // Create a water cascade (source + flowing)
            chunk.setBlock(5, 100, 5, BlockType.WATER);  // Source at top
            chunk.setBlock(5, 99, 5, BlockType.WATER);   // Flowing below
            chunk.setBlock(5, 98, 5, BlockType.WATER);   // Flowing below
            chunk.setBlock(5, 97, 5, BlockType.WATER);   // Flowing below

            // Create a water pool (multiple sources)
            for (int x = 7; x < 10; x++) {
                for (int z = 7; z < 10; z++) {
                    chunk.setBlock(x, 64, z, BlockType.WATER);
                }
            }

            // Add flowing water at edges
            chunk.setBlock(10, 64, 7, BlockType.WATER);
            chunk.setBlock(10, 64, 8, BlockType.WATER);

            int totalWaterBlocks = countWaterBlocks(chunk);
            System.out.println("✓ Placed " + totalWaterBlocks + " water blocks");
            System.out.println("  - 1 cascade source (5,100,5)");
            System.out.println("  - 3 cascade flowing (5,99-97,5)");
            System.out.println("  - 9 pool sources (7-9,64,7-9)");
            System.out.println("  - 2 pool flowing edges (10,64,7-8)");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 3: SIMULATE WATER FLOW (METADATA SETUP)
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 3: SIMULATE WATER FLOW");
            System.out.println("═══════════════════════════════════════════════════════════════");

            WaterSystem waterSystem = world.getWaterSystem();

            // Pre-load water metadata (simulating water that has already flowed)
            java.util.Map<String, com.stonebreak.world.save.model.ChunkData.WaterBlockData> initialMetadata =
                new java.util.HashMap<>();

            // Cascade flowing water (NOT sources)
            initialMetadata.put("5,99,5", new com.stonebreak.world.save.model.ChunkData.WaterBlockData(1, true));
            initialMetadata.put("5,98,5", new com.stonebreak.world.save.model.ChunkData.WaterBlockData(2, true));
            initialMetadata.put("5,97,5", new com.stonebreak.world.save.model.ChunkData.WaterBlockData(3, true));

            // Pool edge flowing water (NOT sources)
            initialMetadata.put("10,64,7", new com.stonebreak.world.save.model.ChunkData.WaterBlockData(6, false));
            initialMetadata.put("10,64,8", new com.stonebreak.world.save.model.ChunkData.WaterBlockData(7, false));

            // Load metadata into WaterSystem
            waterSystem.loadWaterMetadata(TEST_CHUNK_X, TEST_CHUNK_Z, initialMetadata);
            System.out.println("✓ Loaded " + initialMetadata.size() + " flowing water metadata entries");

            // Call onChunkLoaded to detect source blocks
            waterSystem.onChunkLoaded(chunk);
            System.out.println("✓ Called onChunkLoaded - sources auto-detected");

            int trackedWater = waterSystem.getTrackedWaterCount();
            System.out.println("✓ WaterSystem tracking " + trackedWater + " water blocks");
            System.out.println("  - Expected: 10 sources + 5 flowing = 15 total");

            if (trackedWater != 15) {
                throw new AssertionError("Expected 15 tracked water blocks, got " + trackedWater);
            }
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 4: SAVE CHUNK (GAME SAVE)
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 4: SAVE CHUNK (GAME SAVE)");
            System.out.println("═══════════════════════════════════════════════════════════════");

            // Mark chunk as dirty to ensure it gets saved
            chunk.markDirty();
            System.out.println("✓ Marked chunk as dirty");

            // Save chunk through SaveService
            saveService.saveChunk(chunk).join();
            System.out.println("✓ Chunk saved via SaveService");

            // Verify chunk file exists
            boolean chunkExists = saveService.chunkExists(TEST_CHUNK_X, TEST_CHUNK_Z).join();
            if (!chunkExists) {
                throw new AssertionError("Chunk file should exist after save");
            }
            System.out.println("✓ Verified chunk file exists on disk");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 5: UNLOAD WORLD (SIMULATE GAME EXIT)
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 5: UNLOAD WORLD (SIMULATE GAME EXIT)");
            System.out.println("═══════════════════════════════════════════════════════════════");

            // Unload chunk from WaterSystem
            waterSystem.onChunkUnloaded(chunk);
            int remainingWater = waterSystem.getTrackedWaterCount();
            System.out.println("✓ Called onChunkUnloaded");
            System.out.println("✓ WaterSystem cleared: " + remainingWater + " blocks remaining (should be 0)");

            if (remainingWater != 0) {
                throw new AssertionError("WaterSystem should be empty after unload, got " + remainingWater);
            }

            // Close save service (simulate game shutdown)
            saveService.close();
            System.out.println("✓ SaveService closed");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 6: LOAD WORLD (SIMULATE GAME RESTART)
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 6: LOAD WORLD (SIMULATE GAME RESTART)");
            System.out.println("═══════════════════════════════════════════════════════════════");

            // Create new SaveService and World (fresh instances)
            SaveService loadSaveService = new SaveService(testWorldPath.toString());
            TestWorld loadWorld = new TestWorld();
            loadSaveService.initialize(null, null, loadWorld);
            System.out.println("✓ Created fresh World and SaveService");

            WaterSystem loadWaterSystem = loadWorld.getWaterSystem();

            // Load chunk from disk
            Chunk loadedChunk = loadSaveService.loadChunk(TEST_CHUNK_X, TEST_CHUNK_Z).join();
            if (loadedChunk == null) {
                throw new AssertionError("Failed to load chunk from disk");
            }
            System.out.println("✓ Loaded chunk from disk");

            // Verify block data loaded
            int loadedWaterBlocks = countWaterBlocks(loadedChunk);
            System.out.println("✓ Loaded " + loadedWaterBlocks + " water blocks from save");

            if (loadedWaterBlocks != totalWaterBlocks) {
                throw new AssertionError("Water block count mismatch: expected " + totalWaterBlocks +
                    ", got " + loadedWaterBlocks);
            }

            // Call onChunkLoaded to restore water state (including source detection)
            loadWaterSystem.onChunkLoaded(loadedChunk);
            System.out.println("✓ Called onChunkLoaded - water states restored");

            int restoredWater = loadWaterSystem.getTrackedWaterCount();
            System.out.println("✓ WaterSystem tracking " + restoredWater + " water blocks after load");

            if (restoredWater != trackedWater) {
                throw new AssertionError("Water count mismatch after load: expected " + trackedWater +
                    ", got " + restoredWater);
            }
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 7: VERIFY WATER STATES
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 7: VERIFY WATER STATES PRESERVED");
            System.out.println("═══════════════════════════════════════════════════════════════");

            // Calculate world coordinates
            int worldX = TEST_CHUNK_X * 16;
            int worldZ = TEST_CHUNK_Z * 16;

            // Verify cascade source
            verifyWaterState(loadWaterSystem, worldX + 5, 100, worldZ + 5, 0, false, "Cascade source");

            // Verify cascade flowing
            verifyWaterState(loadWaterSystem, worldX + 5, 99, worldZ + 5, 1, true, "Cascade flowing y=99");
            verifyWaterState(loadWaterSystem, worldX + 5, 98, worldZ + 5, 2, true, "Cascade flowing y=98");
            verifyWaterState(loadWaterSystem, worldX + 5, 97, worldZ + 5, 3, true, "Cascade flowing y=97");

            // Verify pool sources (sample)
            verifyWaterState(loadWaterSystem, worldX + 7, 64, worldZ + 7, 0, false, "Pool source (7,64,7)");
            verifyWaterState(loadWaterSystem, worldX + 8, 64, worldZ + 8, 0, false, "Pool source (8,64,8)");

            // Verify pool flowing edges
            verifyWaterState(loadWaterSystem, worldX + 10, 64, worldZ + 7, 6, false, "Pool edge (10,64,7)");
            verifyWaterState(loadWaterSystem, worldX + 10, 64, worldZ + 8, 7, false, "Pool edge (10,64,8)");

            System.out.println("✓ All water states verified!");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 8: VERIFY BLOCK TYPES
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 8: VERIFY BLOCK TYPES PRESERVED");
            System.out.println("═══════════════════════════════════════════════════════════════");

            verifyBlockType(loadedChunk, 5, 100, 5, BlockType.WATER, "Cascade source");
            verifyBlockType(loadedChunk, 5, 99, 5, BlockType.WATER, "Cascade flowing y=99");
            verifyBlockType(loadedChunk, 5, 98, 5, BlockType.WATER, "Cascade flowing y=98");
            verifyBlockType(loadedChunk, 5, 97, 5, BlockType.WATER, "Cascade flowing y=97");
            verifyBlockType(loadedChunk, 7, 64, 7, BlockType.WATER, "Pool source");
            verifyBlockType(loadedChunk, 10, 64, 7, BlockType.WATER, "Pool edge");
            verifyBlockType(loadedChunk, 10, 64, 8, BlockType.WATER, "Pool edge");

            System.out.println("✓ All block types preserved!");
            System.out.println();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 9: PERFORMANCE METRICS
            // ═══════════════════════════════════════════════════════════════
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PHASE 9: PERFORMANCE METRICS");
            System.out.println("═══════════════════════════════════════════════════════════════");

            System.out.println("Total water blocks: " + totalWaterBlocks);
            System.out.println("Source blocks: 10 (not saved as metadata)");
            System.out.println("Flowing blocks: 5 (saved as metadata)");
            System.out.println("Metadata overhead: 5/" + totalWaterBlocks + " = " +
                String.format("%.1f%%", (5.0 / totalWaterBlocks * 100)));
            System.out.println("✓ Efficient metadata storage!");
            System.out.println();

            // Cleanup
            loadSaveService.close();

        } finally {
            // Cleanup test directory
            if (testWorldPath != null && Files.exists(testWorldPath)) {
                System.out.println("Cleaning up test directory...");
                Files.walk(testWorldPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path);
                        }
                    });
                System.out.println("✓ Test directory cleaned up");
            }
        }
    }

    private Chunk createChunkWithWater() {
        Chunk chunk = new Chunk(TEST_CHUNK_X, TEST_CHUNK_Z);

        // Initialize all blocks to AIR
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    chunk.setBlock(x, y, z, BlockType.AIR);
                }
            }
        }

        return chunk;
    }

    private int countWaterBlocks(Chunk chunk) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (chunk.getBlock(x, y, z) == BlockType.WATER) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void verifyWaterState(WaterSystem waterSystem, int worldX, int y, int worldZ,
                                  int expectedLevel, boolean expectedFalling, String description) {
        var waterBlock = waterSystem.getWaterBlock(worldX, y, worldZ);

        if (waterBlock == null) {
            throw new AssertionError("Missing water block: " + description +
                " at (" + worldX + "," + y + "," + worldZ + ")");
        }

        if (waterBlock.level() != expectedLevel) {
            throw new AssertionError(description + " level mismatch: expected " + expectedLevel +
                ", got " + waterBlock.level());
        }

        if (waterBlock.falling() != expectedFalling) {
            throw new AssertionError(description + " falling state mismatch: expected " + expectedFalling +
                ", got " + waterBlock.falling());
        }

        System.out.println("  ✓ " + description + " verified: level=" + expectedLevel +
            ", falling=" + expectedFalling);
    }

    private void verifyBlockType(Chunk chunk, int localX, int y, int localZ,
                                 BlockType expectedType, String description) {
        BlockType actual = chunk.getBlock(localX, y, localZ);
        if (actual != expectedType) {
            throw new AssertionError(description + " block type mismatch: expected " + expectedType +
                ", got " + actual);
        }
        System.out.println("  ✓ " + description + " block type preserved");
    }

    /**
     * Test World implementation using test mode constructor.
     */
    private static class TestWorld extends World {
        public TestWorld() {
            super(new com.stonebreak.world.operations.WorldConfiguration(), 54321L, true);
        }
    }
}
