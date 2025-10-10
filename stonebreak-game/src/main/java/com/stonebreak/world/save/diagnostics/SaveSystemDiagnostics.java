package com.stonebreak.world.save.diagnostics;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.SaveService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Diagnostic utility for verifying the save system is working correctly during gameplay.
 *
 * Enhanced to diagnose why saved chunks are not being loaded when a world is reopened.
 *
 * Problem Analysis:
 * 1. When a world is loaded in Game.java, it creates a FRESH World instance (empty, no chunks)
 * 2. WorldChunkStore has save-first logic in loadChunkFromSaveOrGenerate() that should load chunks from disk
 * 3. But blocks placed by the player are not appearing when the world is reloaded
 *
 * Usage: Call printDiagnostics() or diagnoseChunkLoading() from game code or via debug console.
 */
public class SaveSystemDiagnostics {

    /**
     * Prints comprehensive diagnostics about the current save system state.
     */
    public static void printDiagnostics() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SAVE SYSTEM DIAGNOSTICS");
        System.out.println("=".repeat(60));

        Game game = Game.getInstance();
        if (game == null) {
            System.out.println("❌ Game instance not available");
            return;
        }

        // Check save service
        var saveService = game.getSaveService();
        if (saveService == null) {
            System.out.println("❌ Save service is NULL");
            return;
        }

        System.out.println("✓ Save service: AVAILABLE");

        // Check world
        World world = game.getWorld();
        if (world == null) {
            System.out.println("❌ World is NULL");
            return;
        }

        System.out.println("✓ World instance available");

        // Analyze loaded chunks
        analyzeLoadedChunks(world, saveService);

        // Check dirty chunks
        analyzeDirtyChunks(world);

        // Check region files
        analyzeRegionFiles(saveService);

        System.out.println("=".repeat(60) + "\n");
    }

    /**
     * Analyzes all currently loaded chunks.
     */
    private static void analyzeLoadedChunks(World world, SaveService saveService) {
        System.out.println("\n--- Loaded Chunks Analysis ---");

        int totalChunks = world.getLoadedChunkCount();
        int chunksWithBlocks = 0;
        int chunksFromSave = 0;
        int chunksGenerated = 0;

        System.out.println("Total loaded chunks: " + totalChunks);

        if (totalChunks == 0) {
            System.out.println("⚠ No chunks loaded - player may not have spawned yet");
            return;
        }

        // Sample a few chunks to check their state
        List<ChunkInfo> sampleChunks = new ArrayList<>();
        int sampleCount = 0;
        int maxSamples = 5;

        for (var chunk : world.getAllChunks()) {
            if (chunk == null) continue;

            int blockCount = countNonAirBlocks(chunk);
            if (blockCount > 0) {
                chunksWithBlocks++;
            }

            // Try to determine if chunk was loaded from save
            // (In practice, we'd need to track this, but we can infer from state)
            boolean likelyFromSave = !chunk.isDirty() && blockCount > 0;
            if (likelyFromSave) {
                chunksFromSave++;
            } else if (blockCount > 0) {
                chunksGenerated++;
            }

            // Sample the first few chunks for detailed info
            if (sampleCount < maxSamples && blockCount > 0) {
                sampleChunks.add(new ChunkInfo(chunk, blockCount));
                sampleCount++;
            }
        }

        System.out.println("Chunks with blocks: " + chunksWithBlocks);
        System.out.println("Estimated chunks from save: " + chunksFromSave);
        System.out.println("Estimated newly generated: " + chunksGenerated);

        if (!sampleChunks.isEmpty()) {
            System.out.println("\nSample chunk details:");
            for (ChunkInfo info : sampleChunks) {
                System.out.println("  Chunk (" + info.x + ", " + info.z + "): " +
                    info.blockCount + " blocks, " +
                    (info.isDirty ? "DIRTY" : "CLEAN") + ", " +
                    (info.hasMesh ? "HAS MESH" : "NO MESH"));
            }
        }
    }

    /**
     * Analyzes dirty chunks that need saving.
     */
    private static void analyzeDirtyChunks(World world) {
        System.out.println("\n--- Dirty Chunks Analysis ---");

        int dirtyCount = world.getDirtyChunkCount();
        System.out.println("Total dirty chunks: " + dirtyCount);

        if (dirtyCount == 0) {
            System.out.println("✓ No unsaved changes (all chunks are clean)");
            return;
        }

        System.out.println("⚠ " + dirtyCount + " chunks have unsaved player edits");

        // Sample dirty chunks
        List<ChunkInfo> dirtyChunks = new ArrayList<>();
        int sampleCount = 0;
        int maxSamples = 5;

        for (var chunk : world.getAllChunks()) {
            if (chunk != null && chunk.isDirty() && sampleCount < maxSamples) {
                dirtyChunks.add(new ChunkInfo(chunk, countNonAirBlocks(chunk)));
                sampleCount++;
            }
        }

        if (!dirtyChunks.isEmpty()) {
            System.out.println("Sample dirty chunks:");
            for (ChunkInfo info : dirtyChunks) {
                System.out.println("  Chunk (" + info.x + ", " + info.z + "): " +
                    info.blockCount + " blocks");
            }
        }
    }

    /**
     * Analyzes region file status.
     */
    private static void analyzeRegionFiles(SaveService saveService) {
        System.out.println("\n--- Region Files Status ---");

        if (saveService == null) {
            System.out.println("❌ Save service not initialized - no region files");
            return;
        }

        System.out.println("✓ Save service initialized and ready");
        System.out.println("World path: " + saveService.getWorldPath());

        System.out.println("Region files are stored in: " + saveService.getWorldPath() + "/regions");
    }

    /**
     * Verifies that a specific chunk's blocks persist across save/load.
     * This should be called AFTER placing blocks and AFTER reloading the world.
     */
    public static void verifyChunkPersistence(int chunkX, int chunkZ) {
        System.out.println("\n--- Chunk Persistence Verification ---");
        System.out.println("Checking chunk (" + chunkX + ", " + chunkZ + ")");

        Game game = Game.getInstance();
        if (game == null || game.getWorld() == null) {
            System.out.println("❌ Game or world not available");
            return;
        }

        World world = game.getWorld();
        if (!world.hasChunkAt(chunkX, chunkZ)) {
            System.out.println("⚠ Chunk not currently loaded");
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (chunk == null) {
            System.out.println("❌ Chunk is null");
            return;
        }

        int blockCount = countNonAirBlocks(chunk);
        boolean isDirty = chunk.isDirty();
        boolean hasMesh = chunk.isMeshGenerated();

        System.out.println("Chunk state:");
        System.out.println("  Position: (" + chunk.getX() + ", " + chunk.getZ() + ")");
        System.out.println("  Non-AIR blocks: " + blockCount);
        System.out.println("  Dirty flag: " + (isDirty ? "DIRTY (unsaved)" : "CLEAN (saved)"));
        System.out.println("  Has mesh: " + (hasMesh ? "YES" : "NO"));
        System.out.println("  Features populated: " + chunk.areFeaturesPopulated());
        System.out.println("  Last modified: " + chunk.getLastModified());

        if (blockCount == 0) {
            System.out.println("⚠ Chunk has no blocks - may be all AIR or not loaded correctly");
        } else {
            System.out.println("✓ Chunk has block data");
        }

        if (!hasMesh && blockCount > 0) {
            System.out.println("⚠ WARNING: Chunk has blocks but no mesh - blocks won't be visible!");
        }
    }

    /**
     * Counts non-AIR blocks in a chunk.
     */
    private static int countNonAirBlocks(Chunk chunk) {
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
     * Helper class to store chunk information.
     */
    private static class ChunkInfo {
        final int x, z;
        final int blockCount;
        final boolean isDirty;
        final boolean hasMesh;

        ChunkInfo(Chunk chunk, int blockCount) {
            this.x = chunk.getX();
            this.z = chunk.getZ();
            this.blockCount = blockCount;
            this.isDirty = chunk.isDirty();
            this.hasMesh = chunk.isMeshGenerated();
        }
    }

    // ==================== ENHANCED DIAGNOSTICS FOR SAVE-FIRST LOADING ====================

    /**
     * Performs a complete diagnostic of the save system for a given world and chunk position.
     * Prints detailed information about each step of the load process to identify where it fails.
     *
     * @param worldName The name of the world to diagnose
     * @param chunkX The X coordinate of the chunk to check
     * @param chunkZ The Z coordinate of the chunk to check
     */
    public static void diagnoseChunkLoading(String worldName, int chunkX, int chunkZ) {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     SAVE SYSTEM DIAGNOSTIC REPORT                             ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ World: " + padRight(worldName, 70) + "║");
        System.out.println("║ Chunk: (" + chunkX + ", " + chunkZ + ")" + padRight("", 65) + "║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Step 1: Check if save service is available
        System.out.println("┌─ STEP 1: Save Service Check ───────────────────────────────────────────────┐");
        Game game = Game.getInstance();
        SaveService saveService = (game != null) ? game.getSaveService() : null;

        if (saveService == null) {
            System.out.println("│ ✗ FAILURE: SaveService is NULL                                             │");
            System.out.println("│   → Game.getSaveService() returned null                                    │");
            System.out.println("│   → This is the root cause - save service was never created               │");
            System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
            return;
        }

        System.out.println("│ ✓ SUCCESS: SaveService exists (not null)                                   │");
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // Step 2: Check if chunk files exist on disk
        System.out.println("┌─ STEP 2: Disk Storage Check ───────────────────────────────────────────────┐");
        String worldPath = "worlds/" + worldName;
        Path regionsPath = Paths.get(worldPath, "regions");

        if (!Files.exists(regionsPath)) {
            System.out.println("│ ✗ FAILURE: Regions directory does not exist                                │");
            System.out.println("│   Path: " + regionsPath.toAbsolutePath());
            System.out.println("│   → No chunks have ever been saved for this world                          │");
            System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
            return;
        }

        System.out.println("│ ✓ SUCCESS: Regions directory exists                                        │");
        System.out.println("│   Path: " + regionsPath.toAbsolutePath());

        // Calculate which region file should contain this chunk
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        String regionFileName = "r." + regionX + "." + regionZ + ".mcr";
        Path regionFilePath = regionsPath.resolve(regionFileName);

        System.out.println("│                                                                             │");
        System.out.println("│ Expected region file for chunk (" + chunkX + ", " + chunkZ + "):                            │");
        System.out.println("│   Region coords: (" + regionX + ", " + regionZ + ")                                          │");
        System.out.println("│   File name: " + regionFileName);

        if (!Files.exists(regionFilePath)) {
            System.out.println("│ ✗ FAILURE: Region file does not exist                                      │");
            System.out.println("│   → This chunk has never been saved                                        │");
            System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
            return;
        }

        System.out.println("│ ✓ SUCCESS: Region file exists                                              │");
        try {
            long fileSize = Files.size(regionFilePath);
            System.out.println("│   File size: " + formatBytes(fileSize));
        } catch (Exception e) {
            System.out.println("│   (Could not read file size)                                               │");
        }

        // List all region files for context
        try {
            System.out.println("│                                                                             │");
            System.out.println("│ All region files in world:                                                 │");
            try (Stream<Path> files = Files.list(regionsPath)) {
                files.filter(f -> f.toString().endsWith(".mcr"))
                     .forEach(f -> {
                         try {
                             long size = Files.size(f);
                             System.out.println("│   - " + f.getFileName() + " (" + formatBytes(size) + ")");
                         } catch (Exception e) {
                             System.out.println("│   - " + f.getFileName() + " (size unknown)");
                         }
                     });
            }
        } catch (Exception e) {
            System.out.println("│   (Could not list region files)                                            │");
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // Step 3: Test actual chunk loading (chunkExists() was removed in cleanup)
        System.out.println("┌─ STEP 3: Chunk Load Attempt ───────────────────────────────────────────────┐");
        try {
            CompletableFuture<Chunk> loadFuture = saveService.loadChunk(chunkX, chunkZ);
            Chunk loadedChunk = loadFuture.get(); // Blocking call for diagnostics

            if (loadedChunk == null) {
                System.out.println("│ ✗ FAILURE: loadChunk() returned NULL                                       │");
                System.out.println("│   → Chunk exists but failed to decode                                      │");
                System.out.println("│   → Possible data corruption or version mismatch                           │");
                System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
                return;
            }

            System.out.println("│ ✓ SUCCESS: Chunk loaded successfully                                       │");
            System.out.println("│   Chunk position: (" + loadedChunk.getX() + ", " + loadedChunk.getZ() + ")                                   │");
            System.out.println("│   Chunk state:                                                              │");
            System.out.println("│     - isDirty: " + loadedChunk.isDirty());
            System.out.println("│     - areFeaturesPopulated: " + loadedChunk.areFeaturesPopulated());
            System.out.println("│                                                                             │");

            // Count non-air blocks
            int nonAirBlocks = countNonAirBlocks(loadedChunk);
            System.out.println("│   Block analysis:                                                           │");
            System.out.println("│     - Total blocks: 65,536 (16x256x16)                                      │");
            System.out.println("│     - Non-AIR blocks: " + nonAirBlocks);
            System.out.println("│     - AIR blocks: " + (65536 - nonAirBlocks));

            if (nonAirBlocks == 0) {
                System.out.println("│                                                                             │");
                System.out.println("│ ⚠ WARNING: Chunk contains ONLY air blocks                                  │");
                System.out.println("│   → This suggests the chunk was saved empty or data was lost               │");
            }

            // Sample some blocks
            System.out.println("│                                                                             │");
            System.out.println("│   Sample blocks (5 random locations):                                       │");
            sampleChunkBlocks(loadedChunk);

        } catch (Exception e) {
            System.out.println("│ ✗ FAILURE: Exception during loadChunk() call                               │");
            System.out.println("│   Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("│   Caused by: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
            return;
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // Step 4: Check if World is using save-first logic
        System.out.println("┌─ STEP 4: Runtime Behavior Check ───────────────────────────────────────────┐");
        World world = Game.getWorld();
        if (world == null) {
            System.out.println("│ ⚠ WARNING: World is NULL (not loaded yet)                                  │");
            System.out.println("│   → Cannot verify runtime behavior without a loaded world                  │");
        } else {
            System.out.println("│ ✓ World instance exists                                                    │");
            System.out.println("│   World seed: " + world.getSeed());
            System.out.println("│   Loaded chunks: " + world.getLoadedChunkCount());
            System.out.println("│                                                                             │");
            System.out.println("│ NOTE: WorldChunkStore.loadChunkFromSaveOrGenerate() should be called       │");
            System.out.println("│       when chunks are requested. Check console logs for:                   │");
            System.out.println("│       - [SAVE-FIRST] Loaded chunk (...) from save data                     │");
            System.out.println("│       - [SAVE-FIRST] Saving newly generated chunk (...)                    │");
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // Final summary
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              DIAGNOSTIC SUMMARY                               ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ ✓ Save system is initialized                                                  ║");
        System.out.println("║ ✓ Region files exist on disk                                                  ║");
        System.out.println("║ ✓ Chunk can be loaded from disk                                               ║");
        System.out.println("║                                                                               ║");
        System.out.println("║ CONCLUSION:                                                                   ║");
        System.out.println("║ The save/load infrastructure is working correctly. If chunks are still        ║");
        System.out.println("║ appearing empty when the world loads, the issue is likely:                    ║");
        System.out.println("║                                                                               ║");
        System.out.println("║ 1. World is creating a FRESH instance instead of loading existing world      ║");
        System.out.println("║ 2. Save system is initialized AFTER chunks are already generated             ║");
        System.out.println("║ 3. WorldChunkStore is not calling loadChunkFromSaveOrGenerate()              ║");
        System.out.println("║                                                                               ║");
        System.out.println("║ RECOMMENDED ACTIONS:                                                          ║");
        System.out.println("║ • Check Game.java world creation - ensure it doesn't bypass save system      ║");
        System.out.println("║ • Verify save system is initialized BEFORE any chunks are generated          ║");
        System.out.println("║ • Add logging to WorldChunkStore.loadChunkFromSaveOrGenerate() to confirm    ║");
        System.out.println("║   it's being called during world load                                         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
    }

    /**
     * Quick diagnostic that prints a summary of the save service state.
     */
    public static void quickDiagnostic() {
        System.out.println("========== SAVE SERVICE QUICK DIAGNOSTIC ==========");

        Game game = Game.getInstance();
        SaveService saveService = (game != null) ? game.getSaveService() : null;

        System.out.println("Game instance: " + (game != null ? "✓ EXISTS" : "✗ NULL"));
        System.out.println("SaveService: " + (saveService != null ? "✓ EXISTS" : "✗ NULL"));

        if (saveService != null) {
            System.out.println("World path: " + saveService.getWorldPath());
        }

        World world = (game != null) ? Game.getWorld() : null;
        System.out.println("World instance: " + (world != null ? "✓ EXISTS" : "✗ NULL"));

        if (world != null) {
            System.out.println("World seed: " + world.getSeed());
            System.out.println("Loaded chunks: " + world.getLoadedChunkCount());
            System.out.println("Dirty chunks: " + world.getDirtyChunkCount());
        }

        System.out.println("===================================================");
    }

    /**
     * Tests if the save service can successfully save and load a chunk.
     * Creates a test chunk with known blocks, saves it, and attempts to reload it.
     */
    public static void testSaveLoadRoundTrip(String worldName, int testChunkX, int testChunkZ) {
        System.out.println("========== SAVE/LOAD ROUND-TRIP TEST ==========");
        System.out.println("World: " + worldName);
        System.out.println("Test chunk: (" + testChunkX + ", " + testChunkZ + ")");
        System.out.println();

        Game game = Game.getInstance();
        SaveService saveService = (game != null) ? game.getSaveService() : null;

        if (saveService == null) {
            System.out.println("ERROR: Save service not available");
            return;
        }

        try {
            // Create test chunk with known pattern
            Chunk testChunk = new Chunk(testChunkX, testChunkZ);
            testChunk.setBlock(0, 64, 0, BlockType.STONE);
            testChunk.setBlock(8, 64, 8, BlockType.DIRT);
            testChunk.setBlock(15, 64, 15, BlockType.GRASS);

            System.out.println("1. Created test chunk with pattern:");
            System.out.println("   [0,64,0] = STONE");
            System.out.println("   [8,64,8] = DIRT");
            System.out.println("   [15,64,15] = GRASS");

            // Save the chunk
            System.out.println("2. Saving test chunk...");
            saveService.saveChunk(testChunk).get(); // Blocking
            System.out.println("   ✓ Chunk saved");

            // Load the chunk back (chunkExists() was removed in cleanup)
            System.out.println("3. Loading chunk back...");
            Chunk loadedChunk = saveService.loadChunk(testChunkX, testChunkZ).get();

            if (loadedChunk == null) {
                System.out.println("   ✗ Failed to load chunk (returned null)");
                return;
            }
            System.out.println("   ✓ Chunk loaded");

            // Verify blocks match
            System.out.println("4. Verifying block data:");
            boolean match1 = loadedChunk.getBlock(0, 64, 0) == BlockType.STONE;
            boolean match2 = loadedChunk.getBlock(8, 64, 8) == BlockType.DIRT;
            boolean match3 = loadedChunk.getBlock(15, 64, 15) == BlockType.GRASS;

            System.out.println("   " + (match1 ? "✓" : "✗") + " [0,64,0] = " + loadedChunk.getBlock(0, 64, 0) + " (expected STONE)");
            System.out.println("   " + (match2 ? "✓" : "✗") + " [8,64,8] = " + loadedChunk.getBlock(8, 64, 8) + " (expected DIRT)");
            System.out.println("   " + (match3 ? "✓" : "✗") + " [15,64,15] = " + loadedChunk.getBlock(15, 64, 15) + " (expected GRASS)");

            if (match1 && match2 && match3) {
                System.out.println();
                System.out.println("SUCCESS: Save/load round-trip works correctly!");
            } else {
                System.out.println();
                System.out.println("FAILURE: Loaded data doesn't match saved data!");
            }

        } catch (Exception e) {
            System.out.println("ERROR during test: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("===============================================");
    }

    /**
     * Verifies the save-first logic is being executed during chunk loading.
     * This should be called from WorldChunkStore.loadChunkFromSaveOrGenerate()
     * to confirm the method is being invoked.
     */
    public static void logSaveFirstAttempt(int chunkX, int chunkZ, String stage, String result) {
        System.out.println("[SAVE-FIRST-TRACE] Chunk (" + chunkX + ", " + chunkZ + ") | Stage: " + stage + " | Result: " + result);
    }

    /**
     * Samples some blocks from a chunk for diagnostics.
     */
    private static void sampleChunkBlocks(Chunk chunk) {
        // Sample at different heights
        int[][] samples = {
            {0, 0, 0},      // Corner, bedrock level
            {8, 64, 8},     // Center, sea level
            {15, 128, 15},  // Corner, high altitude
            {7, 200, 7},    // Center, near build limit
            {4, 80, 12}     // Random location
        };

        for (int[] sample : samples) {
            int x = sample[0];
            int y = sample[1];
            int z = sample[2];
            BlockType block = chunk.getBlock(x, y, z);
            System.out.println("│     [" + x + ", " + y + ", " + z + "]: " + block);
        }
    }

    /**
     * Formats bytes into a human-readable string.
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    /**
     * Pads a string to the right with spaces.
     */
    private static String padRight(String s, int length) {
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        return s + " ".repeat(length - s.length());
    }
}
