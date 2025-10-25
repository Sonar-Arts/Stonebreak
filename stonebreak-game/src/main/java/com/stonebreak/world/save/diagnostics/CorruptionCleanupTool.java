package com.stonebreak.world.save.diagnostics;

import com.stonebreak.world.save.io.ChunkCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans chunk files produced by the new save system and deletes unreadable entries.
 * Usage: {@code java CorruptionCleanupTool <world-path>}.
 */
public class CorruptionCleanupTool {

    private int totalChunksScanned = 0;
    private int corruptedChunksFound = 0;
    private int chunksDeleted = 0;
    private final List<String> corruptionLog = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java CorruptionCleanupTool <world-path>");
            System.err.println("Example: java CorruptionCleanupTool worlds/MyWorld");
            System.exit(1);
        }

        CorruptionCleanupTool tool = new CorruptionCleanupTool();
        try {
            tool.scanAndCleanWorld(args[0]);
            tool.printReport();
        } catch (Exception e) {
            System.err.println("ERROR: Failed to clean world: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void scanAndCleanWorld(String worldPath) throws IOException {
        Path chunkRoot = Paths.get(worldPath, "chunks");
        if (!Files.exists(chunkRoot)) {
            System.err.println("Chunk directory not found: " + chunkRoot);
            return;
        }

        System.out.println("=== CORRUPTION CLEANUP TOOL ===");
        System.out.println("Scanning world: " + worldPath);
        System.out.println("Chunk directory: " + chunkRoot);
        System.out.println();

        List<Path> chunkFiles;
        try (Stream<Path> files = Files.walk(chunkRoot)) {
            chunkFiles = files
                .filter(path -> path.toString().endsWith(".sbc"))
                .toList();
        }

        System.out.println("Found " + chunkFiles.size() + " chunk files to scan");
        System.out.println();

        for (Path chunkFile : chunkFiles) {
            scanChunkFile(chunkFile);
        }
    }

    private void scanChunkFile(Path chunkFile) {
        totalChunksScanned++;
        try {
            byte[] payload = Files.readAllBytes(chunkFile);
            ChunkCodec.decode(payload); // Throws on corruption
        } catch (Exception e) {
            corruptedChunksFound++;
            String message = "[CORRUPTED] " + chunkFile + " - " + e.getMessage();
            corruptionLog.add(message);
            try {
                Files.deleteIfExists(chunkFile);
                chunksDeleted++;
                System.out.println(message + " - DELETED");
            } catch (IOException deleteError) {
                System.out.println(message + " - DELETE FAILED: " + deleteError.getMessage());
            }
        }
    }

    private void printReport() {
        System.out.println();
        System.out.println("=== CLEANUP REPORT ===");
        System.out.println("Total chunks scanned: " + totalChunksScanned);
        System.out.println("Corrupted chunks found: " + corruptedChunksFound);
        System.out.println("Corrupted chunks deleted: " + chunksDeleted);
        System.out.println();

        if (corruptedChunksFound > 0) {
            System.out.println("=== CORRUPTION LOG ===");
            corruptionLog.forEach(System.out::println);
            System.out.println();
            System.out.println("✓ Cleanup complete! Corrupted chunks have been removed.");
            System.out.println("  The game will regenerate these chunks when you load the world.");
        } else {
            System.out.println("✓ No corruption found! Your world save is healthy.");
        }
    }
}
