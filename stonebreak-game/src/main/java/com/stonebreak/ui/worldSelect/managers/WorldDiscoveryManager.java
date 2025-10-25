package com.stonebreak.ui.worldSelect.managers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stonebreak.world.save.model.WorldData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalDateTime;

/**
 * Manages world discovery and world data retrieval from the worlds directory.
 * Scans for existing worlds and provides world data information.
 */
public class WorldDiscoveryManager {

    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String WORLD_DATA_FILENAME = "world.json";

    private final ObjectMapper objectMapper;

    // Cache for world data to avoid repeated file reads
    private final Map<String, WorldData> worldDataCache = new HashMap<>();
    private long lastScanTime = 0;
    private static final long CACHE_VALIDITY_MS = 5000; // 5 seconds

    public WorldDiscoveryManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ===== WORLD DISCOVERY =====

    /**
     * Scans the worlds directory and returns a list of available world names.
     * Results are sorted by last played time (most recent first).
     */
    public List<String> discoverWorlds() {
        try {
            Path worldsPath = Paths.get(WORLDS_DIRECTORY);

            if (!Files.exists(worldsPath)) {
                Files.createDirectories(worldsPath);
                return new ArrayList<>();
            }

            List<String> worlds = new ArrayList<>();

            // Find all subdirectories in the worlds directory
            try {
                Files.list(worldsPath)
                    .filter(Files::isDirectory)
                    .forEach(worldPath -> {
                        String worldName = worldPath.getFileName().toString();

                        // Verify this is a valid world directory
                        if (isValidWorldDirectory(worldPath)) {
                            worlds.add(worldName);
                        }
                    });
            } catch (IOException e) {
                System.err.println("Error scanning worlds directory: " + e.getMessage());
                return new ArrayList<>();
            }

            // Sort worlds by last played time (most recent first)
            worlds.sort((a, b) -> {
                WorldData worldDataA = getWorldData(a);
                WorldData worldDataB = getWorldData(b);

                Long timeA = worldDataA != null && worldDataA.getLastPlayed() != null
                    ? worldDataA.getLastPlayed().toEpochSecond(java.time.ZoneOffset.UTC) * 1000
                    : null;
                Long timeB = worldDataB != null && worldDataB.getLastPlayed() != null
                    ? worldDataB.getLastPlayed().toEpochSecond(java.time.ZoneOffset.UTC) * 1000
                    : null;

                // Handle null times (put them at the end)
                if (timeA == null && timeB == null) return a.compareTo(b);
                if (timeA == null) return 1;
                if (timeB == null) return -1;

                // Sort by most recent first
                return timeB.compareTo(timeA);
            });

            return worlds;

        } catch (Exception e) {
            System.err.println("Unexpected error during world discovery: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Checks if a directory is a valid world directory by looking for expected files.
     */
    private boolean isValidWorldDirectory(Path worldPath) {
        try {
            // Check for world data file (required)
            Path worldDataPath = worldPath.resolve(WORLD_DATA_FILENAME);
            if (!Files.exists(worldDataPath)) {
                return false;
            }

            // Check for chunks directory (JSON format) or regions directory (Binary format)
            Path chunksPath = worldPath.resolve("chunks");
            Path regionsPath = worldPath.resolve("regions");

            return Files.exists(chunksPath) || Files.exists(regionsPath);

        } catch (Exception e) {
            return false;
        }
    }

    // ===== METADATA MANAGEMENT =====

    /**
     * Gets world data for a specific world, using cache when possible.
     */
    public WorldData getWorldData(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return null;
        }

        // Check cache first
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastScanTime < CACHE_VALIDITY_MS && worldDataCache.containsKey(worldName)) {
            return worldDataCache.get(worldName);
        }

        // Load from file
        WorldData worldData = loadWorldData(worldName);

        // Update cache
        if (worldData != null) {
            worldDataCache.put(worldName, worldData);
            lastScanTime = currentTime;
        }

        return worldData;
    }

    /**
     * Legacy compatibility method - returns WorldData under the old method name.
     * @deprecated Use getWorldData() instead
     */
    @Deprecated
    public WorldData getWorldMetadata(String worldName) {
        return getWorldData(worldName);
    }

    /**
     * Loads world data from the file system.
     */
    private WorldData loadWorldData(String worldName) {
        try {
            Path worldDataPath = Paths.get(WORLDS_DIRECTORY, worldName, WORLD_DATA_FILENAME);

            if (!Files.exists(worldDataPath)) {
                return null;
            }

            return objectMapper.readValue(worldDataPath.toFile(), WorldData.class);

        } catch (IOException e) {
            System.err.println("Error loading world data for world '" + worldName + "': " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("Unexpected error loading world data for world '" + worldName + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Refreshes the world data cache for all worlds.
     */
    public void refreshMetadataCache() {
        worldDataCache.clear();
        lastScanTime = 0;

        // Pre-load world data for discovered worlds
        List<String> worlds = discoverWorlds();
        for (String worldName : worlds) {
            getWorldData(worldName);
        }
    }

    /**
     * Clears the world data cache.
     */
    public void clearCache() {
        worldDataCache.clear();
        lastScanTime = 0;
    }

    // ===== WORLD CREATION VALIDATION =====

    /**
     * Checks if a world name is valid and available.
     */
    public boolean isWorldNameAvailable(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return false;
        }

        // Check for invalid characters
        String trimmedName = worldName.trim();
        if (!trimmedName.matches("[a-zA-Z0-9 _-]+")) {
            return false;
        }

        // Check if world already exists
        Path worldPath = Paths.get(WORLDS_DIRECTORY, trimmedName);
        return !Files.exists(worldPath);
    }

    /**
     * Validates that a world name meets all requirements.
     */
    public String validateWorldName(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return "World name cannot be empty";
        }

        String trimmedName = worldName.trim();

        if (trimmedName.length() > 32) {
            return "World name too long (max 32 characters)";
        }

        if (!trimmedName.matches("[a-zA-Z0-9 _-]+")) {
            return "World name can only contain letters, numbers, spaces, hyphens, and underscores";
        }

        if (!isWorldNameAvailable(trimmedName)) {
            return "A world with that name already exists";
        }

        return null; // Valid
    }

    // ===== UTILITY METHODS =====

    /**
     * Gets the worlds directory path.
     */
    public Path getWorldsDirectory() {
        return Paths.get(WORLDS_DIRECTORY);
    }

    /**
     * Creates the worlds directory if it doesn't exist.
     */
    public boolean ensureWorldsDirectoryExists() {
        try {
            Path worldsPath = getWorldsDirectory();
            if (!Files.exists(worldsPath)) {
                Files.createDirectories(worldsPath);
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to create worlds directory: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets a display-friendly summary of world information.
     */
    public String getWorldDisplayInfo(String worldName) {
        WorldData worldData = getWorldData(worldName);
        if (worldData == null) {
            return worldName;
        }

        StringBuilder info = new StringBuilder(worldName);

        if (worldData.getLastPlayed() != null) {
            long lastPlayedMillis = worldData.getLastPlayed().toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
            info.append(" (Last played: ").append(formatTimestamp(lastPlayedMillis)).append(")");
        }

        return info.toString();
    }

    /**
     * Formats a timestamp for display purposes.
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "Unknown";

        LocalDateTime dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault());
        LocalDateTime now = LocalDateTime.now();

        if (dateTime.toLocalDate().equals(now.toLocalDate())) {
            return "Today at " + dateTime.getHour() + ":" + String.format("%02d", dateTime.getMinute());
        }

        return dateTime.getMonthValue() + "/" + dateTime.getDayOfMonth() + "/" + dateTime.getYear();
    }
}