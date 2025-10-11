package com.stonebreak.world.save.diagnostics;

import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import com.stonebreak.world.save.storage.binary.RegionFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tool to scan region files and remove corrupted chunks.
 * Run this to clean up a world after corruption has been detected.
 *
 * Usage: java CorruptionCleanupTool <world-path>
 * Example: java CorruptionCleanupTool worlds/MyWorld
 */
public class CorruptionCleanupTool {

    private final BinaryChunkSerializer serializer;
    private int totalChunksScanned = 0;
    private int corruptedChunksFound = 0;
    private int chunksDeleted = 0;
    private final List<String> corruptionLog = new ArrayList<>();

    public CorruptionCleanupTool() {
        this.serializer = new BinaryChunkSerializer();
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java CorruptionCleanupTool <world-path>");
            System.err.println("Example: java CorruptionCleanupTool worlds/MyWorld");
            System.exit(1);
        }

        String worldPath = args[0];
        CorruptionCleanupTool tool = new CorruptionCleanupTool();

        try {
            tool.scanAndCleanWorld(worldPath);
            tool.printReport();
        } catch (Exception e) {
            System.err.println("ERROR: Failed to clean world: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void scanAndCleanWorld(String worldPath) throws IOException {
        Path regionsDir = Paths.get(worldPath, "regions");

        if (!Files.exists(regionsDir)) {
            System.err.println("ERROR: Regions directory not found: " + regionsDir);
            return;
        }

        System.out.println("=== CORRUPTION CLEANUP TOOL ===");
        System.out.println("Scanning world: " + worldPath);
        System.out.println("Regions directory: " + regionsDir);
        System.out.println();

        // Find all region files
        List<Path> regionFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(regionsDir, 1)) {
            paths.filter(path -> path.toString().endsWith(".mcr"))
                 .forEach(regionFiles::add);
        }

        System.out.println("Found " + regionFiles.size() + " region files to scan");
        System.out.println();

        // Scan each region file
        for (Path regionPath : regionFiles) {
            scanRegionFile(regionPath);
        }
    }

    private void scanRegionFile(Path regionPath) {
        System.out.println("Scanning: " + regionPath.getFileName());

        try (RegionFile regionFile = new RegionFile(regionPath)) {
            int regionCorrupted = 0;

            // Scan all 1024 possible chunks in this region (32x32)
            for (int localX = 0; localX < 32; localX++) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    totalChunksScanned++;

                    // Try to read chunk data
                    byte[] chunkData;
                    try {
                        chunkData = regionFile.readChunk(localX, localZ);
                    } catch (IOException e) {
                        String error = String.format("  [CORRUPTED] Failed to read chunk (%d, %d): %s",
                            localX, localZ, e.getMessage());
                        corruptionLog.add(error);
                        corruptedChunksFound++;
                        regionCorrupted++;

                        // Try to delete the corrupted chunk
                        try {
                            regionFile.deleteChunk(localX, localZ);
                            chunksDeleted++;
                            System.out.println(error + " - DELETED");
                        } catch (IOException deleteError) {
                            System.out.println(error + " - DELETE FAILED: " + deleteError.getMessage());
                        }
                        continue;
                    }

                    // Skip if chunk doesn't exist
                    if (chunkData == null) {
                        continue;
                    }

                    // Try to deserialize chunk data
                    try {
                        serializer.deserialize(chunkData);
                        // Chunk is valid - no action needed
                    } catch (Exception e) {
                        String error = String.format("  [CORRUPTED] Failed to deserialize chunk (%d, %d): %s",
                            localX, localZ, e.getMessage());
                        corruptionLog.add(error);
                        corruptedChunksFound++;
                        regionCorrupted++;

                        // Delete the corrupted chunk
                        try {
                            regionFile.deleteChunk(localX, localZ);
                            chunksDeleted++;
                            System.out.println(error + " - DELETED");
                        } catch (IOException deleteError) {
                            System.out.println(error + " - DELETE FAILED: " + deleteError.getMessage());
                        }
                    }
                }
            }

            if (regionCorrupted > 0) {
                System.out.println("  → Found and deleted " + regionCorrupted + " corrupted chunks");
            } else {
                System.out.println("  → No corruption detected");
            }
            System.out.println();

        } catch (IOException e) {
            System.err.println("  ERROR: Failed to open region file: " + e.getMessage());
            System.out.println();
        }
    }

    private void printReport() {
        System.out.println("=== CLEANUP REPORT ===");
        System.out.println("Total chunks scanned: " + totalChunksScanned);
        System.out.println("Corrupted chunks found: " + corruptedChunksFound);
        System.out.println("Corrupted chunks deleted: " + chunksDeleted);
        System.out.println();

        if (corruptedChunksFound > 0) {
            System.out.println("=== CORRUPTION LOG ===");
            for (String log : corruptionLog) {
                System.out.println(log);
            }
            System.out.println();

            System.out.println("✓ Cleanup complete! Corrupted chunks have been removed.");
            System.out.println("  The game will regenerate these chunks when you load the world.");
        } else {
            System.out.println("✓ No corruption found! Your world save is healthy.");
        }
    }
}
