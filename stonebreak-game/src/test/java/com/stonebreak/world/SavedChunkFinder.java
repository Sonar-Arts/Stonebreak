package com.stonebreak.world;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility to find all chunks that are actually saved in a world.
 * Helps diagnose which chunks were successfully persisted to disk.
 */
public class SavedChunkFinder {

    private static final int HEADER_SIZE = 8192;
    private static final int CHUNKS_PER_REGION = 1024;

    public static void main(String[] args) {
        String worldPath = "worlds/Big Succ 2";
        findAllSavedChunks(worldPath);
    }

    /**
     * Scans all region files and lists every chunk that is actually saved.
     */
    public static void findAllSavedChunks(String worldPath) {
        Path regionsDir = Paths.get(worldPath, "regions");

        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        SAVED CHUNK FINDER                                     ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ World: " + padRight(worldPath, 68) + "║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        if (!Files.exists(regionsDir)) {
            System.out.println("ERROR: Regions directory does not exist");
            return;
        }

        try (Stream<Path> files = Files.list(regionsDir)) {
            List<Path> regionFiles = files.filter(f -> f.toString().endsWith(".mcr"))
                    .sorted()
                    .toList();

            int totalRegions = regionFiles.size();
            int totalChunks = 0;
            List<ChunkLocation> allChunks = new ArrayList<>();

            System.out.println("Found " + totalRegions + " region files");
            System.out.println();

            for (Path regionFile : regionFiles) {
                // Parse region coords from filename (e.g., "r.-1.-1.mcr")
                String filename = regionFile.getFileName().toString();
                String[] parts = filename.replace(".mcr", "").split("\\.");
                if (parts.length != 3) {
                    System.out.println("⚠ Skipping malformed filename: " + filename);
                    continue;
                }

                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);

                System.out.println("═══ Region (" + regionX + ", " + regionZ + ") ═══ " + filename + " ═══");

                List<ChunkLocation> chunks = scanRegionFile(regionFile, regionX, regionZ);
                allChunks.addAll(chunks);
                totalChunks += chunks.size();

                System.out.println("  Found " + chunks.size() + " chunks in this region");

                // Show chunk range
                if (!chunks.isEmpty()) {
                    int minX = chunks.stream().mapToInt(c -> c.chunkX).min().orElse(0);
                    int maxX = chunks.stream().mapToInt(c -> c.chunkX).max().orElse(0);
                    int minZ = chunks.stream().mapToInt(c -> c.chunkZ).min().orElse(0);
                    int maxZ = chunks.stream().mapToInt(c -> c.chunkZ).max().orElse(0);
                    System.out.println("  Chunk range: X[" + minX + " to " + maxX + "], Z[" + minZ + " to " + maxZ + "]");
                }
                System.out.println();
            }

            System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║                              SUMMARY                                          ║");
            System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
            System.out.println("║ Total regions: " + totalRegions);
            System.out.println("║ Total saved chunks: " + totalChunks);
            System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Show all chunks in a grid format
            if (totalChunks > 0 && totalChunks <= 200) {
                System.out.println("═══════════════════ ALL SAVED CHUNKS ═══════════════════");
                allChunks.stream()
                        .sorted((a, b) -> {
                            int cmp = Integer.compare(a.chunkZ, b.chunkZ);
                            return cmp != 0 ? cmp : Integer.compare(a.chunkX, b.chunkX);
                        })
                        .forEach(chunk -> System.out.println(
                                "  (" + chunk.chunkX + ", " + chunk.chunkZ + ") " +
                                        "in region (" + chunk.regionX + ", " + chunk.regionZ + ") " +
                                        "[" + formatBytes(chunk.dataSize) + "]"
                        ));
            } else if (totalChunks > 200) {
                System.out.println("(Too many chunks to display - " + totalChunks + " total)");
            }

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Scans a single region file and returns all chunks it contains.
     */
    private static List<ChunkLocation> scanRegionFile(Path regionFile, int regionX, int regionZ) {
        List<ChunkLocation> chunks = new ArrayList<>();

        try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (file.length() < HEADER_SIZE) {
                return chunks; // Empty or corrupted
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

            // Find all valid chunks
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int index = localX + localZ * 32;

                    if (offsets[index] != 0 && lengths[index] != 0) {
                        // Validate entry
                        if (offsets[index] >= HEADER_SIZE &&
                                offsets[index] + lengths[index] <= file.length()) {

                            int chunkX = regionX * 32 + localX;
                            int chunkZ = regionZ * 32 + localZ;

                            chunks.add(new ChunkLocation(
                                    chunkX, chunkZ,
                                    regionX, regionZ,
                                    localX, localZ,
                                    lengths[index]
                            ));
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("  ERROR reading region file: " + e.getMessage());
        }

        return chunks;
    }

    /**
     * Checks if a specific chunk is saved.
     */
    public static boolean isChunkSaved(String worldPath, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);

        Path regionFile = Paths.get(worldPath, "regions", "r." + regionX + "." + regionZ + ".mcr");

        if (!Files.exists(regionFile)) {
            return false;
        }

        try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (file.length() < HEADER_SIZE) {
                return false;
            }

            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            int index = localX + localZ * 32;

            file.seek(index * 4);
            int offset = file.readInt();

            file.seek(4096 + index * 4);
            int length = file.readInt();

            return offset != 0 && length != 0;

        } catch (Exception e) {
            return false;
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

    /**
     * Represents a chunk location with metadata.
     */
    private static class ChunkLocation {
        final int chunkX, chunkZ;
        final int regionX, regionZ;
        final int localX, localZ;
        final int dataSize;

        ChunkLocation(int chunkX, int chunkZ, int regionX, int regionZ,
                      int localX, int localZ, int dataSize) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.localX = localX;
            this.localZ = localZ;
            this.dataSize = dataSize;
        }
    }
}
