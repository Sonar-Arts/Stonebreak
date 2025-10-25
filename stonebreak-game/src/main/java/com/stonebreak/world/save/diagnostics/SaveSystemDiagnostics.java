package com.stonebreak.world.save.diagnostics;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.io.ChunkCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight diagnostics tailored for the new chunk save format.
 * Focuses on surfacing actionable information rather than exhaustive logging.
 */
public final class SaveSystemDiagnostics {

    private static final int REGION_SIZE = 32;
    private static final DecimalFormat BYTES = new DecimalFormat("#,###");

    private SaveSystemDiagnostics() {
    }

    public static void printDiagnostics() {
        System.out.println("\n==== SAVE SYSTEM DIAGNOSTICS ====");

        Game game = Game.getInstance();
        if (game == null) {
            System.out.println("⚠ Game instance unavailable");
            return;
        }

        SaveService saveService = game.getSaveService();
        if (saveService == null) {
            System.out.println("⚠ Save service not initialised");
            return;
        }

        World world = game.getWorld();
        if (world == null) {
            System.out.println("⚠ World not loaded");
            return;
        }

        System.out.println("World path   : " + saveService.getWorldPath());
        System.out.println("Loaded chunks: " + world.getLoadedChunkCount());
        System.out.println("Dirty chunks : " + world.getDirtyChunkCount());

        summarizeChunkFiles(saveService.getWorldPath());
        sampleLoadedChunks(world);

        System.out.println("=================================\n");
    }

    public static void diagnoseChunkLoading(String worldName, int chunkX, int chunkZ) {
        System.out.println("\n=== CHUNK DIAGNOSTICS (" + worldName + " @ " + chunkX + "," + chunkZ + ") ===");

        Game game = Game.getInstance();
        SaveService saveService = (game != null) ? game.getSaveService() : null;
        if (saveService == null) {
            System.out.println("⚠ Save service unavailable");
            return;
        }

        World world = game.getWorld();
        if (world == null) {
            System.out.println("⚠ World not loaded in memory");
        } else if (world.hasChunkAt(chunkX, chunkZ)) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk != null) {
                System.out.println("Loaded chunk state:");
                System.out.println("  Dirty          : " + chunk.isDirty());
                System.out.println("  Mesh generated : " + chunk.isMeshGenerated());
                System.out.println("  Non-air blocks : " + countNonAirBlocks(chunk));
            }
        } else {
            System.out.println("Chunk not currently loaded in memory");
        }

        Path chunkFile = chunkFilePath(saveService.getWorldPath(), chunkX, chunkZ);
        if (Files.exists(chunkFile)) {
            try {
                long size = Files.size(chunkFile);
                System.out.println("Chunk file exists: " + chunkFile.getFileName() + " (" + formatBytes(size) + ")");
                byte[] payload = Files.readAllBytes(chunkFile);
                ChunkCodec.decode(payload); // throws if corrupt
                System.out.println("✓ Chunk payload decoded successfully");
            } catch (IOException | RuntimeException e) {
                System.out.println("⚠ Failed to read chunk payload: " + e.getMessage());
            }
        } else {
            System.out.println("⚠ Chunk file not found: " + chunkFile);
        }

        try {
            Chunk loaded = saveService.loadChunk(chunkX, chunkZ).get(5, TimeUnit.SECONDS);
            if (loaded == null) {
                System.out.println("Result: chunk will be regenerated (not saved yet or failed to decode).");
            } else {
                System.out.println("Result: chunk would load with " + countNonAirBlocks(loaded) + " non-air blocks.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            System.out.println("⚠ loadChunk() threw: " + e.getMessage());
        }

        System.out.println("==========================================\n");
    }

    public static void verifyChunkPersistence(int chunkX, int chunkZ) {
        Game game = Game.getInstance();
        if (game == null || game.getWorld() == null) {
            System.out.println("⚠ Game or world not initialised");
            return;
        }

        World world = game.getWorld();
        if (!world.hasChunkAt(chunkX, chunkZ)) {
            System.out.println("⚠ Chunk (" + chunkX + "," + chunkZ + ") not currently loaded");
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (chunk == null) {
            System.out.println("⚠ Chunk reference is null");
            return;
        }

        System.out.println("Chunk (" + chunkX + "," + chunkZ + ") state:");
        System.out.println("  Non-air blocks : " + countNonAirBlocks(chunk));
        System.out.println("  Dirty flag     : " + chunk.isDirty());
        System.out.println("  Mesh generated : " + chunk.isMeshGenerated());
    }

    private static void summarizeChunkFiles(String worldPath) {
        Path chunkRoot = Paths.get(worldPath, "chunks");
        if (!Files.exists(chunkRoot)) {
            System.out.println("Chunk directory missing: " + chunkRoot);
            return;
        }

        try {
            long fileCount;
            long totalBytes;
            try (var files = Files.walk(chunkRoot)) {
                List<Path> fileList = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sbc"))
                    .toList();
                fileCount = fileList.size();
                totalBytes = 0;
                for (Path file : fileList) {
                    totalBytes += Files.size(file);
                }
            }
            System.out.println("Chunk files    : " + fileCount);
            System.out.println("Total disk use : " + formatBytes(totalBytes));
        } catch (IOException e) {
            System.out.println("⚠ Failed to inspect chunk directory: " + e.getMessage());
        }
    }

    private static void sampleLoadedChunks(World world) {
        List<Chunk> chunks = new java.util.ArrayList<>(world.getAllChunks());
        if (chunks.isEmpty()) {
            System.out.println("No chunks loaded to sample.");
            return;
        }

        System.out.println("Sampled chunks:");
        chunks.stream()
            .limit(5)
            .forEach(chunk -> System.out.println("  (" + chunk.getX() + "," + chunk.getZ() + ") "
                + "blocks=" + countNonAirBlocks(chunk)
                + " dirty=" + chunk.isDirty()
                + " mesh=" + chunk.isMeshGenerated()));
    }

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

    private static Path chunkFilePath(String worldPath, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, REGION_SIZE);
        int regionZ = Math.floorDiv(chunkZ, REGION_SIZE);
        return Paths.get(worldPath, "chunks",
            "r." + regionX + "." + regionZ,
            "c." + chunkX + "." + chunkZ + ".sbc");
    }

    private static String formatBytes(long value) {
        return BYTES.format(value) + " bytes";
    }
}
