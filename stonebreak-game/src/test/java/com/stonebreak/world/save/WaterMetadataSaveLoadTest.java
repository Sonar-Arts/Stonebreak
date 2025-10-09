package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSystem;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.util.StateConverter;

import java.util.Map;

/**
 * Comprehensive unit test for water metadata save/load system.
 *
 * Test Flow:
 * 1. Create a chunk with water blocks in various states (source, flowing, falling)
 * 2. Register water blocks with WaterSystem to simulate water simulation
 * 3. Create a snapshot (save) that should extract water metadata
 * 4. Clear WaterSystem to simulate chunk unload
 * 5. Load from snapshot and verify water metadata is restored
 * 6. Verify all water states are correctly preserved
 *
 * This validates the water metadata pipeline including:
 * - Water block state tracking in WaterSystem
 * - Metadata extraction during snapshot creation
 * - Metadata serialization in ChunkData
 * - Metadata restoration during chunk loading
 * - Water state preservation across save/load cycle
 */
public class WaterMetadataSaveLoadTest {

    private static final int TEST_CHUNK_X = 5;
    private static final int TEST_CHUNK_Z = 7;

    public static void main(String[] args) {
        System.out.println("=== Water Metadata Save/Load Test ===\n");

        WaterMetadataSaveLoadTest test = new WaterMetadataSaveLoadTest();
        try {
            test.runTest();
            System.out.println("\n✅ ALL WATER METADATA TESTS PASSED!");
        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void runTest() throws Exception {
        // Phase 1: Setup
        System.out.println("Phase 1: Setup Water Test Environment");
        System.out.println("--------------------------------------");

        // Create test world in test mode (bypasses MmsAPI/rendering requirements)
        TestWorld testWorld = new TestWorld();
        WaterSystem waterSystem = testWorld.getWaterSystem();
        System.out.println("✓ Test World and WaterSystem initialized (test mode)");

        // Phase 2: Create Chunk with Water Blocks
        System.out.println("\nPhase 2: Create Chunk with Water Blocks");
        System.out.println("----------------------------------------");

        Chunk chunk = createChunkWithWater();
        System.out.println("✓ Created chunk at position (" + TEST_CHUNK_X + ", " + TEST_CHUNK_Z + ")");

        // Place water blocks at different positions with different states
        // Source water at y=100 (will become flowing water below)
        chunk.setBlock(5, 100, 5, BlockType.WATER);
        chunk.setBlock(5, 99, 5, BlockType.WATER);
        chunk.setBlock(5, 98, 5, BlockType.WATER);
        chunk.setBlock(5, 97, 5, BlockType.WATER);

        // Create a water pool with some flowing edges
        for (int x = 7; x < 10; x++) {
            for (int z = 7; z < 10; z++) {
                chunk.setBlock(x, 64, z, BlockType.WATER);
            }
        }
        // Add some flowing water at edges
        chunk.setBlock(10, 64, 7, BlockType.WATER);
        chunk.setBlock(10, 64, 8, BlockType.WATER);

        System.out.println("✓ Placed water blocks in chunk");

        // Phase 3: Simulate WaterSystem State
        System.out.println("\nPhase 3: Simulate WaterSystem States");
        System.out.println("-------------------------------------");

        // Manually load water block states into WaterSystem using loadWaterMetadata
        // This simulates water that has already flowed and settled into various states
        Map<String, ChunkData.WaterBlockData> initialWaterMetadata = new java.util.HashMap<>();

        // Flowing water cascade (these WILL be saved)
        initialWaterMetadata.put("5,99,5", new ChunkData.WaterBlockData(1, true));   // Falling level 1
        initialWaterMetadata.put("5,98,5", new ChunkData.WaterBlockData(2, true));   // Falling level 2
        initialWaterMetadata.put("5,97,5", new ChunkData.WaterBlockData(3, true));   // Falling level 3

        // Flowing at edges (these WILL be saved)
        initialWaterMetadata.put("10,64,7", new ChunkData.WaterBlockData(6, false)); // Flow level 6
        initialWaterMetadata.put("10,64,8", new ChunkData.WaterBlockData(7, false)); // Flow level 7 (weakest)

        // Load the water metadata into WaterSystem
        waterSystem.loadWaterMetadata(TEST_CHUNK_X, TEST_CHUNK_Z, initialWaterMetadata);

        // Now call onChunkLoaded to register ALL water blocks (sources will be added automatically)
        waterSystem.onChunkLoaded(chunk);

        int totalWater = waterSystem.getTrackedWaterCount();
        System.out.println("✓ Water states loaded into WaterSystem");
        System.out.println("  Total tracked water blocks: " + totalWater);
        System.out.println("  Expected: source blocks + 5 flowing/falling blocks");
        System.out.println("  Expected to save: 5 metadata entries (non-source only)");

        // Phase 4: Create Snapshot (SAVE)
        System.out.println("\nPhase 4: Create Snapshot (Save Operation)");
        System.out.println("------------------------------------------");

        CcoSerializableSnapshot snapshot = chunk.createSnapshot(testWorld);
        System.out.println("✓ Snapshot created");

        // Verify water metadata was extracted
        Map<String, ChunkData.WaterBlockData> waterMetadata = snapshot.getWaterMetadata();
        System.out.println("✓ Water metadata extracted: " + waterMetadata.size() + " entries");

        // Verify we got the expected flowing water entries
        assert waterMetadata.size() == 5 :
            "Expected 5 water metadata entries (3 falling + 2 flowing), got " + waterMetadata.size();
        System.out.println("✓ Correct number of metadata entries saved");

        // Verify specific entries
        verifyMetadataEntry(waterMetadata, 5, 99, 5, 1, true, "Falling water at y=99");
        verifyMetadataEntry(waterMetadata, 5, 98, 5, 2, true, "Falling water at y=98");
        verifyMetadataEntry(waterMetadata, 5, 97, 5, 3, true, "Falling water at y=97");
        verifyMetadataEntry(waterMetadata, 10, 64, 7, 6, false, "Flowing water at edge (10,64,7)");
        verifyMetadataEntry(waterMetadata, 10, 64, 8, 7, false, "Flowing water at edge (10,64,8)");

        // Phase 5: Simulate Chunk Unload
        System.out.println("\nPhase 5: Simulate Chunk Unload");
        System.out.println("-------------------------------");

        // Clear water system (simulating onChunkUnloaded)
        waterSystem.onChunkUnloaded(chunk);
        int remainingWater = waterSystem.getTrackedWaterCount();
        System.out.println("✓ WaterSystem cleared for chunk");
        System.out.println("  Remaining tracked water: " + remainingWater + " (should be 0 or from other chunks)");

        // Phase 6: Load from Snapshot
        System.out.println("\nPhase 6: Load from Snapshot");
        System.out.println("----------------------------");

        // Create new chunk and load from snapshot
        Chunk loadedChunk = new Chunk(TEST_CHUNK_X, TEST_CHUNK_Z);
        loadedChunk.loadFromSnapshot(snapshot, testWorld);
        System.out.println("✓ Chunk loaded from snapshot");

        // Verify water metadata was loaded into WaterSystem
        int loadedWaterCount = waterSystem.getTrackedWaterCount();
        System.out.println("✓ Water metadata loaded into WaterSystem");
        System.out.println("  Loaded water blocks: " + loadedWaterCount + " (should be 5)");

        assert loadedWaterCount >= 5 :
            "Expected at least 5 water blocks loaded, got " + loadedWaterCount;

        // Phase 7: Verify Water States Preserved
        System.out.println("\nPhase 7: Verify Water States Preserved");
        System.out.println("---------------------------------------");

        // Calculate world coordinates
        int worldX = TEST_CHUNK_X * 16;
        int worldZ = TEST_CHUNK_Z * 16;

        // Verify falling water cascade
        verifyWaterState(waterSystem, worldX + 5, 99, worldZ + 5, 1, true, "Falling y=99");
        verifyWaterState(waterSystem, worldX + 5, 98, worldZ + 5, 2, true, "Falling y=98");
        verifyWaterState(waterSystem, worldX + 5, 97, worldZ + 5, 3, true, "Falling y=97");

        // Verify flowing water at edges
        verifyWaterState(waterSystem, worldX + 10, 64, worldZ + 7, 6, false, "Flowing edge (10,64,7)");
        verifyWaterState(waterSystem, worldX + 10, 64, worldZ + 8, 7, false, "Flowing edge (10,64,8)");

        System.out.println("✓ All water states correctly preserved!");

        // Phase 8: Verify Block Types Preserved
        System.out.println("\nPhase 8: Verify Block Types Preserved");
        System.out.println("--------------------------------------");

        assert loadedChunk.getBlock(5, 100, 5) == BlockType.WATER : "Source water block missing";
        assert loadedChunk.getBlock(5, 99, 5) == BlockType.WATER : "Falling water block missing";
        assert loadedChunk.getBlock(5, 98, 5) == BlockType.WATER : "Falling water block missing";
        assert loadedChunk.getBlock(5, 97, 5) == BlockType.WATER : "Falling water block missing";
        assert loadedChunk.getBlock(10, 64, 7) == BlockType.WATER : "Flowing water block missing";
        assert loadedChunk.getBlock(10, 64, 8) == BlockType.WATER : "Flowing water block missing";

        System.out.println("✓ All water block types preserved");

        // Phase 9: Performance Stats
        System.out.println("\nPhase 9: Performance Statistics");
        System.out.println("--------------------------------");

        int totalWaterBlocks = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (loadedChunk.getBlock(x, y, z) == BlockType.WATER) {
                        totalWaterBlocks++;
                    }
                }
            }
        }

        System.out.println("✓ Total water blocks in chunk: " + totalWaterBlocks);
        System.out.println("✓ Metadata entries saved: " + waterMetadata.size());
        System.out.println("✓ Metadata overhead: " + waterMetadata.size() + "/" + totalWaterBlocks +
            " (" + String.format("%.1f", (waterMetadata.size() * 100.0 / totalWaterBlocks)) + "%)");
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

    private void verifyMetadataEntry(Map<String, ChunkData.WaterBlockData> metadata,
                                    int localX, int y, int localZ,
                                    int expectedLevel, boolean expectedFalling,
                                    String description) {
        String key = localX + "," + y + "," + localZ;
        ChunkData.WaterBlockData data = metadata.get(key);

        assert data != null :
            "Missing metadata for " + description + " at (" + localX + "," + y + "," + localZ + ")";
        assert data.level() == expectedLevel :
            description + " has wrong level: expected " + expectedLevel + ", got " + data.level();
        assert data.falling() == expectedFalling :
            description + " has wrong falling state: expected " + expectedFalling + ", got " + data.falling();

        System.out.println("  ✓ " + description + " metadata verified: level=" + expectedLevel +
            ", falling=" + expectedFalling);
    }

    private void verifyWaterState(WaterSystem waterSystem, int worldX, int y, int worldZ,
                                 int expectedLevel, boolean expectedFalling,
                                 String description) {
        var waterBlock = waterSystem.getWaterBlock(worldX, y, worldZ);

        assert waterBlock != null :
            "Missing water block for " + description + " at (" + worldX + "," + y + "," + worldZ + ")";
        assert waterBlock.level() == expectedLevel :
            description + " has wrong level: expected " + expectedLevel + ", got " + waterBlock.level();
        assert waterBlock.falling() == expectedFalling :
            description + " has wrong falling state: expected " + expectedFalling + ", got " + waterBlock.falling();

        System.out.println("  ✓ " + description + " state verified: level=" + expectedLevel +
            ", falling=" + expectedFalling);
    }

    /**
     * Minimal test implementation of World for water metadata testing.
     * Uses test mode constructor to bypass MmsAPI/rendering requirements.
     */
    private static class TestWorld extends World {
        public TestWorld() {
            // Use package-private test constructor with testMode=true
            // This bypasses MmsAPI and rendering system initialization
            super(new com.stonebreak.world.operations.WorldConfiguration(), 12345L, true);
        }
    }
}
