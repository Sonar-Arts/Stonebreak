package com.stonebreak.world;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.SaveService;

import java.util.List;

/**
 * Debug tool to verify save system is working correctly.
 * Call these methods from your debug console or add keybindings to test.
 */
public class SaveSystemDebugger {

    /**
     * Forces an immediate save of all dirty chunks and prints results.
     * Use this to manually trigger saves during testing.
     */
    public static void forceSaveNow() {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        FORCE SAVE NOW                                         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        Game game = Game.getInstance();
        if (game == null) {
            System.out.println("ERROR: Game instance not available");
            return;
        }

        SaveService saveService = game.getSaveService();
        if (saveService == null) {
            System.out.println("ERROR: Save service not initialized");
            return;
        }

        World world = game.getWorld();
        if (world == null) {
            System.out.println("ERROR: World not loaded");
            return;
        }

        List<Chunk> dirtyChunks = world.getDirtyChunks();
        System.out.println("Found " + dirtyChunks.size() + " dirty chunks");

        if (dirtyChunks.isEmpty()) {
            System.out.println("✓ No dirty chunks - nothing to save");
            return;
        }

        System.out.println();
        System.out.println("Dirty chunks:");
        for (Chunk chunk : dirtyChunks) {
            System.out.println("  - Chunk (" + chunk.getX() + ", " + chunk.getZ() + ") " +
                    "last modified: " + chunk.getLastModified());
        }

        System.out.println();
        System.out.println("Saving all dirty chunks...");

        try {
            saveService.saveDirtyChunks().get(); // Blocking call
            System.out.println("✓ Save completed successfully");

            // Verify chunks are now clean
            List<Chunk> stillDirty = world.getDirtyChunks();
            if (stillDirty.isEmpty()) {
                System.out.println("✓ All chunks are now clean");
            } else {
                System.out.println("⚠ WARNING: " + stillDirty.size() + " chunks are still dirty after save!");
            }

        } catch (Exception e) {
            System.out.println("✗ Save FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tests the save system by placing a block and immediately saving.
     */
    public static void testSaveWithBlock(int chunkX, int chunkZ) {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     TEST SAVE WITH BLOCK                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        Game game = Game.getInstance();
        if (game == null || game.getWorld() == null || game.getSaveService() == null) {
            System.out.println("ERROR: Game, world, or save service not available");
            return;
        }

        World world = game.getWorld();
        SaveService saveService = game.getSaveService();

        System.out.println("1. Ensuring chunk (" + chunkX + ", " + chunkZ + ") is loaded...");

        if (!world.hasChunkAt(chunkX, chunkZ)) {
            System.out.println("   Chunk not loaded - loading it now...");
            // The chunk will be loaded on next frame
            System.out.println("   ⚠ Chunk needs to be loaded first - try again after moving closer");
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (chunk == null) {
            System.out.println("   ✗ Failed to get chunk");
            return;
        }

        System.out.println("   ✓ Chunk loaded");
        System.out.println();

        System.out.println("2. Checking if chunk is currently dirty...");
        boolean wasDirty = chunk.isDirty();
        System.out.println("   Dirty before test: " + wasDirty);
        System.out.println();

        System.out.println("3. Placing test block at (8, 100, 8)...");
        BlockType originalBlock = chunk.getBlock(8, 100, 8);
        System.out.println("   Original block: " + originalBlock);

        // Place a different block
        BlockType testBlock = (originalBlock == BlockType.STONE) ? BlockType.DIRT : BlockType.STONE;
        chunk.setBlock(8, 100, 8, testBlock);

        System.out.println("   New block: " + testBlock);
        System.out.println();

        System.out.println("4. Verifying chunk is now dirty...");
        boolean isDirtyNow = chunk.isDirty();
        System.out.println("   Dirty after placing block: " + isDirtyNow);

        if (!isDirtyNow) {
            System.out.println("   ✗ FAILURE: Chunk should be dirty but isn't!");
            System.out.println("   → This indicates a bug in the dirty marking system");
            return;
        }
        System.out.println("   ✓ Chunk correctly marked as dirty");
        System.out.println();

        System.out.println("5. Saving chunk...");
        try {
            saveService.saveChunk(chunk).get(); // Blocking
            System.out.println("   ✓ Save completed");
        } catch (Exception e) {
            System.out.println("   ✗ Save failed: " + e.getMessage());
            return;
        }
        System.out.println();

        System.out.println("6. Verifying chunk is now clean...");
        boolean isDirtyAfterSave = chunk.isDirty();
        System.out.println("   Dirty after save: " + isDirtyAfterSave);

        if (isDirtyAfterSave) {
            System.out.println("   ⚠ WARNING: Chunk is still dirty after save!");
        } else {
            System.out.println("   ✓ Chunk correctly marked as clean");
        }
        System.out.println();

        System.out.println("7. Verifying chunk exists on disk...");
        try {
            boolean exists = saveService.chunkExists(chunkX, chunkZ).get();
            System.out.println("   Chunk exists on disk: " + exists);

            if (exists) {
                System.out.println("   ✓ SUCCESS: Chunk was saved to disk");
            } else {
                System.out.println("   ✗ FAILURE: Chunk doesn't exist on disk after save!");
            }
        } catch (Exception e) {
            System.out.println("   ✗ Error checking existence: " + e.getMessage());
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST COMPLETE");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    /**
     * Prints current dirty chunk status.
     */
    public static void printDirtyChunkStatus() {
        System.out.println("═══════════════════ DIRTY CHUNK STATUS ═══════════════════");

        Game game = Game.getInstance();
        if (game == null || game.getWorld() == null) {
            System.out.println("ERROR: Game or world not available");
            return;
        }

        World world = game.getWorld();
        List<Chunk> dirtyChunks = world.getDirtyChunks();

        System.out.println("Total loaded chunks: " + world.getLoadedChunkCount());
        System.out.println("Dirty chunks: " + dirtyChunks.size());

        if (dirtyChunks.isEmpty()) {
            System.out.println("✓ No unsaved changes");
        } else {
            System.out.println();
            System.out.println("Chunks with unsaved changes:");
            for (Chunk chunk : dirtyChunks) {
                System.out.println("  - (" + chunk.getX() + ", " + chunk.getZ() + ") " +
                        "modified: " + chunk.getLastModified());
            }
        }

        System.out.println("══════════════════════════════════════════════════════════");
    }

    /**
     * Verifies that auto-save is running.
     */
    public static void checkAutoSaveStatus() {
        System.out.println("═══════════════════ AUTO-SAVE STATUS ═══════════════════");

        Game game = Game.getInstance();
        if (game == null) {
            System.out.println("ERROR: Game not available");
            return;
        }

        SaveService saveService = game.getSaveService();
        if (saveService == null) {
            System.out.println("✗ Save service is NULL - auto-save not running");
            return;
        }

        System.out.println("✓ Save service is initialized");
        System.out.println("World path: " + saveService.getWorldPath());
        System.out.println();
        System.out.println("Auto-save runs every 30 seconds automatically.");
        System.out.println("Watch console for: [AUTO-SAVE] Completed in XXms");
        System.out.println();
        System.out.println("To manually trigger save, use: SaveSystemDebugger.forceSaveNow()");

        System.out.println("══════════════════════════════════════════════════════════");
    }
}
