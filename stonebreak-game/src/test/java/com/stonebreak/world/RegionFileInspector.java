package com.stonebreak.world;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Diagnostic utility to inspect region file headers and diagnose chunk storage issues.
 */
public class RegionFileInspector {

    private static final int HEADER_SIZE = 8192;
    private static final int CHUNKS_PER_REGION = 1024;

    public static void main(String[] args) {
        // Inspect the specific region file from the diagnostic
        String worldPath = "worlds/Big Succ 2";
        int regionX = -1;
        int regionZ = -1;

        inspectRegion(worldPath, regionX, regionZ);
    }

    /**
     * Inspects a region file and prints detailed header information.
     */
    public static void inspectRegion(String worldPath, int regionX, int regionZ) {
        Path regionPath = Paths.get(worldPath, "regions", "r." + regionX + "." + regionZ + ".mcr");

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     REGION FILE INSPECTOR                                     ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ Region: (" + regionX + ", " + regionZ + ")                                                                ║");
        System.out.println("║ File: " + padRight(regionPath.toString(), 67) + "║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        try (RandomAccessFile file = new RandomAccessFile(regionPath.toFile(), "r")) {
            long fileSize = file.length();
            System.out.println("File size: " + formatBytes(fileSize));
            System.out.println();

            if (fileSize < HEADER_SIZE) {
                System.out.println("ERROR: File is too small (less than 8KB header)");
                return;
            }

            // Read header
            int[] offsets = new int[CHUNKS_PER_REGION];
            int[] lengths = new int[CHUNKS_PER_REGION];

            file.seek(0);
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                offsets[i] = file.readInt();
            }

            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                lengths[i] = file.readInt();
            }

            // Analyze header
            int totalChunks = 0;
            long totalStoredBytes = 0;
            int corruptedChunks = 0;

            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                if (offsets[i] != 0 && lengths[i] != 0) {
                    totalChunks++;
                    totalStoredBytes += lengths[i];

                    // Check for corruption
                    if (offsets[i] < HEADER_SIZE || offsets[i] + lengths[i] > fileSize) {
                        corruptedChunks++;
                    }
                }
            }

            System.out.println("═══════════════════ HEADER SUMMARY ═══════════════════");
            System.out.println("Total chunks stored: " + totalChunks + " / " + CHUNKS_PER_REGION);
            System.out.println("Total data size: " + formatBytes(totalStoredBytes));
            System.out.println("Corrupted chunk entries: " + corruptedChunks);
            System.out.println("File overhead: " + formatBytes(fileSize - totalStoredBytes - HEADER_SIZE));
            System.out.println();

            // Inspect specific chunk (-1, -2)
            System.out.println("═══════════════════ CHUNK (-1, -2) ═══════════════════");
            int chunkX = -1;
            int chunkZ = -2;
            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            int index = localX + localZ * 32;

            System.out.println("Chunk world coords: (" + chunkX + ", " + chunkZ + ")");
            System.out.println("Local coords in region: (" + localX + ", " + localZ + ")");
            System.out.println("Header index: " + index);
            System.out.println();
            System.out.println("Offset: " + offsets[index] + " (0x" + Integer.toHexString(offsets[index]) + ")");
            System.out.println("Length: " + lengths[index] + " bytes");
            System.out.println();

            if (offsets[index] == 0 && lengths[index] == 0) {
                System.out.println("STATUS: ✗ CHUNK NOT STORED");
                System.out.println("This chunk has never been saved to this region file.");
            } else if (offsets[index] < HEADER_SIZE) {
                System.out.println("STATUS: ✗ CORRUPTED (offset in header area)");
            } else if (offsets[index] + lengths[index] > fileSize) {
                System.out.println("STATUS: ✗ CORRUPTED (extends past file end)");
            } else {
                System.out.println("STATUS: ✓ VALID ENTRY");
            }
            System.out.println();

            // Show nearby chunks for context
            System.out.println("═══════════════════ NEARBY CHUNKS ═══════════════════");
            System.out.println("Showing chunks within 2 blocks of (-1, -2):");
            System.out.println();

            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int testChunkX = chunkX + dx;
                    int testChunkZ = chunkZ + dz;
                    int testLocalX = Math.floorMod(testChunkX, 32);
                    int testLocalZ = Math.floorMod(testChunkZ, 32);
                    int testIndex = testLocalX + testLocalZ * 32;

                    boolean hasChunk = offsets[testIndex] != 0 && lengths[testIndex] != 0;
                    String status = hasChunk ? "✓" : "✗";
                    String marker = (testChunkX == chunkX && testChunkZ == chunkZ) ? " <-- TARGET" : "";

                    System.out.println(status + " Chunk (" + testChunkX + ", " + testChunkZ + ") " +
                            "local(" + testLocalX + ", " + testLocalZ + ") " +
                            "idx=" + testIndex + marker);
                }
            }
            System.out.println();

            // List ALL stored chunks
            System.out.println("═══════════════════ ALL STORED CHUNKS ═══════════════════");
            System.out.println("Total: " + totalChunks + " chunks");
            System.out.println();

            int displayCount = 0;
            for (localZ = 0; localZ < 32; localZ++) {
                for (localX = 0; localX < 32; localX++) {
                    int idx = localX + localZ * 32;
                    if (offsets[idx] != 0 && lengths[idx] != 0) {
                        int worldX = regionX * 32 + localX;
                        int worldZ = regionZ * 32 + localZ;

                        System.out.println("  Chunk (" + worldX + ", " + worldZ + ") " +
                                "local(" + localX + ", " + localZ + ") " +
                                "offset=" + offsets[idx] + " " +
                                "length=" + lengths[idx]);

                        displayCount++;
                        if (displayCount >= 50) {
                            System.out.println("  ... (showing first 50 of " + totalChunks + " total)");
                            break;
                        }
                    }
                }
                if (displayCount >= 50) break;
            }

        } catch (Exception e) {
            System.out.println("ERROR reading region file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    private static String padRight(String s, int length) {
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        return s + " ".repeat(length - s.length());
    }
}
