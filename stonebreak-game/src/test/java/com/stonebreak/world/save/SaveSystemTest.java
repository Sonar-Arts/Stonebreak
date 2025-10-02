package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fresh test suite for save system validation.
 * Tests core save/load operations with simplified approach.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SaveSystemTest {

    private static Path testDirectory;
    private SaveService service;

    @BeforeAll
    static void initializeTestEnvironment() throws IOException {
        testDirectory = Files.createTempDirectory("stonebreak-test-");
        System.out.println("Test directory: " + testDirectory);
    }

    @AfterAll
    static void cleanupTestEnvironment() throws IOException {
        if (testDirectory != null && Files.exists(testDirectory)) {
            Files.walk(testDirectory)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + path);
                    }
                });
        }
    }

    @BeforeEach
    void setup() {
        service = new SaveService(testDirectory.toString());
    }

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Save single chunk with stone block")
    void saveSingleChunk() throws Exception {
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(5, 5, 5, BlockType.STONE);

        service.saveChunk(chunk).join();

        assertTrue(service.chunkExists(0, 0).join());
    }

    @Test
    @Order(2)
    @DisplayName("Load previously saved chunk")
    void loadSavedChunk() throws Exception {
        Chunk original = new Chunk(1, 1);
        original.setBlock(10, 10, 10, BlockType.DIRT);

        service.saveChunk(original).join();
        Chunk loaded = service.loadChunk(1, 1).join();

        assertNotNull(loaded);
        assertEquals(BlockType.DIRT, loaded.getBlock(10, 10, 10));
    }

    @Test
    @Order(3)
    @DisplayName("Load non-existent chunk returns null")
    void loadNonExistentChunk() throws Exception {
        Chunk result = service.loadChunk(999, 999).join();
        assertNull(result);
    }

    @Test
    @Order(4)
    @DisplayName("Overwrite existing chunk data")
    void overwriteChunk() throws Exception {
        Chunk first = new Chunk(2, 2);
        first.setBlock(0, 0, 0, BlockType.GRASS);
        service.saveChunk(first).join();

        Chunk second = new Chunk(2, 2);
        second.setBlock(0, 0, 0, BlockType.COBBLESTONE);
        service.saveChunk(second).join();

        Chunk loaded = service.loadChunk(2, 2).join();
        assertEquals(BlockType.COBBLESTONE, loaded.getBlock(0, 0, 0));
    }

    @Test
    @Order(5)
    @DisplayName("Handle multiple different block types")
    void multipleBlockTypes() throws Exception {
        Chunk chunk = new Chunk(3, 3);
        chunk.setBlock(0, 0, 0, BlockType.BEDROCK);
        chunk.setBlock(1, 1, 1, BlockType.WATER);
        chunk.setBlock(2, 2, 2, BlockType.SAND);
        chunk.setBlock(3, 3, 3, BlockType.WOOD);

        service.saveChunk(chunk).join();
        Chunk loaded = service.loadChunk(3, 3).join();

        assertEquals(BlockType.BEDROCK, loaded.getBlock(0, 0, 0));
        assertEquals(BlockType.WATER, loaded.getBlock(1, 1, 1));
        assertEquals(BlockType.SAND, loaded.getBlock(2, 2, 2));
        assertEquals(BlockType.WOOD, loaded.getBlock(3, 3, 3));
    }

    @Test
    @Order(6)
    @DisplayName("Save and load chunk with negative coordinates")
    void negativeCoordinates() throws Exception {
        Chunk chunk = new Chunk(-5, -10);
        chunk.setBlock(7, 7, 7, BlockType.IRON_ORE);

        service.saveChunk(chunk).join();
        Chunk loaded = service.loadChunk(-5, -10).join();

        assertNotNull(loaded);
        assertEquals(-5, loaded.getX());
        assertEquals(-10, loaded.getZ());
        assertEquals(BlockType.IRON_ORE, loaded.getBlock(7, 7, 7));
    }

    @Test
    @Order(7)
    @DisplayName("Verify chunk existence check")
    void verifyChunkExistence() throws Exception {
        assertFalse(service.chunkExists(50, 50).join());

        Chunk chunk = new Chunk(50, 50);
        service.saveChunk(chunk).join();

        assertTrue(service.chunkExists(50, 50).join());
    }

    @Test
    @Order(8)
    @DisplayName("Save multiple chunks in sequence")
    void saveMultipleChunks() throws Exception {
        for (int i = 0; i < 5; i++) {
            Chunk chunk = new Chunk(i, 0);
            chunk.setBlock(i, i, i, BlockType.STONE);
            service.saveChunk(chunk).join();
        }

        for (int i = 0; i < 5; i++) {
            assertTrue(service.chunkExists(i, 0).join());
            Chunk loaded = service.loadChunk(i, 0).join();
            assertEquals(BlockType.STONE, loaded.getBlock(i, i, i));
        }
    }

    @Test
    @Order(9)
    @DisplayName("Preserve chunk features flag")
    void preserveChunkFlags() throws Exception {
        Chunk chunk = new Chunk(7, 7);
        chunk.setBlock(0, 0, 0, BlockType.STONE);
        chunk.setFeaturesPopulated(true);

        service.saveChunk(chunk).join();
        Chunk loaded = service.loadChunk(7, 7).join();

        assertTrue(loaded.areFeaturesPopulated());
    }

    @Test
    @Order(10)
    @DisplayName("Handle empty chunk with all air blocks")
    void emptyChunkSave() throws Exception {
        Chunk emptyChunk = new Chunk(8, 8);

        service.saveChunk(emptyChunk).join();
        Chunk loaded = service.loadChunk(8, 8).join();

        assertNotNull(loaded);
        assertEquals(BlockType.AIR, loaded.getBlock(0, 0, 0));
        assertEquals(BlockType.AIR, loaded.getBlock(15, 255, 15));
    }

    @Test
    @Order(11)
    @DisplayName("Close service gracefully")
    void closeServiceGracefully() {
        assertDoesNotThrow(() -> service.close());
    }

    @Test
    @Order(12)
    @DisplayName("Verify region file structure creation")
    void verifyRegionFileStructure() throws Exception {
        Chunk chunk = new Chunk(0, 0);
        service.saveChunk(chunk).join();

        Path regionsDir = testDirectory.resolve("regions");
        assertTrue(Files.exists(regionsDir));
        assertTrue(Files.isDirectory(regionsDir));
    }

    @Test
    @Order(13)
    @DisplayName("Player modifications survive save and load")
    void playerModificationsPersist() throws Exception {
        // Simulate player placing blocks
        Chunk chunk = new Chunk(10, 10);
        chunk.setBlock(0, 64, 0, BlockType.COBBLESTONE);
        chunk.setBlock(1, 64, 0, BlockType.COBBLESTONE);
        chunk.setBlock(2, 64, 0, BlockType.COBBLESTONE);

        service.saveChunk(chunk).join();
        Chunk loaded = service.loadChunk(10, 10).join();

        // Verify player-placed blocks are still there
        assertEquals(BlockType.COBBLESTONE, loaded.getBlock(0, 64, 0));
        assertEquals(BlockType.COBBLESTONE, loaded.getBlock(1, 64, 0));
        assertEquals(BlockType.COBBLESTONE, loaded.getBlock(2, 64, 0));
    }

    @Test
    @Order(14)
    @DisplayName("Modified terrain is not reset to generated state")
    void modifiedTerrainNotReset() throws Exception {
        // Simulate terrain generation creating stone
        Chunk generatedChunk = new Chunk(15, 15);
        generatedChunk.setBlock(5, 5, 5, BlockType.STONE);
        service.saveChunk(generatedChunk).join();

        // Player modifies the chunk (breaks stone, places dirt)
        Chunk modifiedChunk = new Chunk(15, 15);
        modifiedChunk.setBlock(5, 5, 5, BlockType.AIR);
        modifiedChunk.setBlock(6, 6, 6, BlockType.DIRT);
        service.saveChunk(modifiedChunk).join();

        // Load chunk and verify modifications persist
        Chunk loaded = service.loadChunk(15, 15).join();
        assertEquals(BlockType.AIR, loaded.getBlock(5, 5, 5), "Stone should be broken");
        assertEquals(BlockType.DIRT, loaded.getBlock(6, 6, 6), "Dirt should be placed");
    }

    @Test
    @Order(15)
    @DisplayName("Save overwrite preserves latest state only")
    void saveOverwritePreservesLatestState() throws Exception {
        // Initial terrain generation
        Chunk gen1 = new Chunk(20, 20);
        gen1.setBlock(0, 0, 0, BlockType.STONE);
        service.saveChunk(gen1).join();

        // Player modification #1
        Chunk mod1 = new Chunk(20, 20);
        mod1.setBlock(0, 0, 0, BlockType.DIRT);
        service.saveChunk(mod1).join();

        // Player modification #2
        Chunk mod2 = new Chunk(20, 20);
        mod2.setBlock(0, 0, 0, BlockType.GRASS);
        service.saveChunk(mod2).join();

        // Verify only the latest modification exists
        Chunk loaded = service.loadChunk(20, 20).join();
        assertEquals(BlockType.GRASS, loaded.getBlock(0, 0, 0),
            "Only the most recent save should be preserved");
    }

    @Test
    @Order(16)
    @DisplayName("Chunk with player edits distinct from generated terrain")
    void playerEditsDistinctFromGeneration() throws Exception {
        // Create a chunk simulating generation
        Chunk generated = new Chunk(25, 25);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                generated.setBlock(x, 63, z, BlockType.GRASS);
                generated.setBlock(x, 62, z, BlockType.DIRT);
                generated.setBlock(x, 61, z, BlockType.STONE);
            }
        }
        service.saveChunk(generated).join();

        // Player breaks specific blocks
        Chunk edited = new Chunk(25, 25);
        // Copy all blocks from generated chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                edited.setBlock(x, 63, z, BlockType.GRASS);
                edited.setBlock(x, 62, z, BlockType.DIRT);
                edited.setBlock(x, 61, z, BlockType.STONE);
            }
        }
        // Player removes some grass
        edited.setBlock(5, 63, 5, BlockType.AIR);
        edited.setBlock(6, 63, 6, BlockType.AIR);
        service.saveChunk(edited).join();

        // Load and verify edits are preserved
        Chunk loaded = service.loadChunk(25, 25).join();
        assertEquals(BlockType.AIR, loaded.getBlock(5, 63, 5), "Player removal should persist");
        assertEquals(BlockType.AIR, loaded.getBlock(6, 63, 6), "Player removal should persist");
        assertEquals(BlockType.GRASS, loaded.getBlock(7, 63, 7), "Unmodified blocks should remain");
    }

    @Test
    @Order(17)
    @DisplayName("Multiple save-load cycles preserve data integrity")
    void multipleSaveLoadCycles() throws Exception {
        Chunk chunk = new Chunk(30, 30);

        // Cycle 1: Generate terrain
        chunk.setBlock(8, 8, 8, BlockType.STONE);
        service.saveChunk(chunk).join();
        chunk = service.loadChunk(30, 30).join();
        assertEquals(BlockType.STONE, chunk.getBlock(8, 8, 8));

        // Cycle 2: Player modifies
        chunk.setBlock(8, 8, 8, BlockType.COBBLESTONE);
        service.saveChunk(chunk).join();
        chunk = service.loadChunk(30, 30).join();
        assertEquals(BlockType.COBBLESTONE, chunk.getBlock(8, 8, 8));

        // Cycle 3: Player builds more
        chunk.setBlock(9, 9, 9, BlockType.WOOD);
        service.saveChunk(chunk).join();
        chunk = service.loadChunk(30, 30).join();
        assertEquals(BlockType.COBBLESTONE, chunk.getBlock(8, 8, 8));
        assertEquals(BlockType.WOOD, chunk.getBlock(9, 9, 9));

        // Cycle 4: Player removes blocks
        chunk.setBlock(8, 8, 8, BlockType.AIR);
        service.saveChunk(chunk).join();
        chunk = service.loadChunk(30, 30).join();
        assertEquals(BlockType.AIR, chunk.getBlock(8, 8, 8));
        assertEquals(BlockType.WOOD, chunk.getBlock(9, 9, 9));
    }

    @Test
    @Order(18)
    @DisplayName("Modify existing world Test 45 and verify persistence")
    void modifyExistingWorld45() throws Exception {
        // Use actual world "Test 45" directory in project
        Path world45Path = Path.of("C:\\Users\\Chace\\Projects\\SB - Project - IntelliJ\\worlds\\Test 45");

        // Skip test if world Test 45 doesn't exist
        if (!Files.exists(world45Path)) {
            System.out.println("Skipping test: World 'Test 45' does not exist at " + world45Path);
            return;
        }

        // Open world Test 45 directly (no temp service needed)
        SaveService world45Service = new SaveService(world45Path.toString());

        try {
            // World coordinates: (-2, 74, 3)
            // Chunk coordinates: chunkX = floor(-2/16) = -1, chunkZ = floor(3/16) = 0
            // Local coordinates: localX = -2 - (-1*16) = 14, localZ = 3 - (0*16) = 3
            int worldX = -2;
            int worldY = 74;
            int worldZ = 3;
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            int localX = worldX - (chunkX * 16);
            int localZ = worldZ - (chunkZ * 16);

            System.out.println("Target world coords: (" + worldX + ", " + worldY + ", " + worldZ + ")");
            System.out.println("Chunk coords: (" + chunkX + ", " + chunkZ + ")");
            System.out.println("Local coords: (" + localX + ", " + localZ + ")");

            // Check if chunk exists first
            boolean chunkExists = world45Service.chunkExists(chunkX, chunkZ).join();
            System.out.println("Chunk exists: " + chunkExists);

            if (!chunkExists) {
                System.out.println("Chunk (" + chunkX + "," + chunkZ + ") doesn't exist - skipping test");
                return;
            }

            // Load the chunk containing the target position
            Chunk existingChunk = world45Service.loadChunk(chunkX, chunkZ).join();
            assertNotNull(existingChunk, "Chunk should have loaded since it exists");
            System.out.println("Loaded existing chunk (" + chunkX + "," + chunkZ + ") from world Test 45");

            // Record original state
            BlockType originalBlock = existingChunk.getBlock(localX, worldY, localZ);
            System.out.println("Original block at world(" + worldX + "," + worldY + "," + worldZ + "): " + originalBlock);

            // Place cobblestone at the target location
            BlockType markerBlock = BlockType.COBBLESTONE;
            existingChunk.setBlock(localX, worldY, localZ, markerBlock);

            // Save the modified chunk
            world45Service.saveChunk(existingChunk).join();
            System.out.println("Saved modified chunk with cobblestone at world(" + worldX + "," + worldY + "," + worldZ + ")");

            // Close and reopen service (simulate world reload)
            world45Service.close();
            world45Service = new SaveService(world45Path.toString());

            // Load chunk again and verify modifications persisted
            Chunk reloaded = world45Service.loadChunk(chunkX, chunkZ).join();
            assertNotNull(reloaded, "Chunk (" + chunkX + "," + chunkZ + ") should exist in world Test 45");

            BlockType reloadedBlock = reloaded.getBlock(localX, worldY, localZ);
            System.out.println("After reload - Block at world(" + worldX + "," + worldY + "," + worldZ + "): " + reloadedBlock);

            assertEquals(markerBlock, reloadedBlock,
                "Modified block should persist and not be regenerated");

            System.out.println("SUCCESS: Cobblestone persisted after world reload!");
            System.out.println("NOT restoring original state - leaving cobblestone in place for verification");

        } finally {
            world45Service.close();
        }
    }
}
