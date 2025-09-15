package com.stonebreak.ui.worldSelect.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.managers.WorldSaveSystem;
import com.stonebreak.world.save.core.WorldMetadata;
import org.joml.Vector3f;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Handles business logic and actions for the WorldSelectScreen.
 * Manages world creation, loading, and navigation between screens.
 */
public class WorldActionHandler {

    private final WorldStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;

    // Callbacks for screen transitions
    private Runnable onReturnToMainMenu;
    private Runnable onWorldLoaded;
    private Runnable onRefreshWorlds;

    public WorldActionHandler(WorldStateManager stateManager, WorldDiscoveryManager discoveryManager) {
        this.stateManager = stateManager;
        this.discoveryManager = discoveryManager;
    }

    // ===== CALLBACK SETUP =====

    /**
     * Sets up callbacks for screen transitions and world operations.
     */
    public void setCallbacks(Runnable onReturnToMainMenu, Runnable onWorldLoaded, Runnable onRefreshWorlds) {
        this.onReturnToMainMenu = onReturnToMainMenu;
        this.onWorldLoaded = onWorldLoaded;
        this.onRefreshWorlds = onRefreshWorlds;
    }

    // ===== WORLD LOADING ACTIONS =====

    /**
     * Loads the currently selected world.
     */
    public void loadSelectedWorld() {
        String selectedWorld = stateManager.getSelectedWorld();
        if (selectedWorld != null) {
            loadWorld(selectedWorld);
        }
    }

    /**
     * Loads a specific world by name.
     */
    public void loadWorld(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            System.err.println("Cannot load world: invalid world name");
            return;
        }

        try {
            System.out.println("Loading world: " + worldName);

            // Note: WorldSaveSystem is created per-world, not a singleton
            // The actual world loading will be handled by Game.startWorldGeneration

            // Get world metadata to retrieve the seed
            WorldMetadata metadata = discoveryManager.getWorldMetadata(worldName);
            long seed;

            if (metadata != null && metadata.getSeed() != 0) {
                seed = metadata.getSeed();
                System.out.println("Using existing world seed: " + seed);
            } else {
                // If no metadata or seed, generate a random one for new worlds
                seed = new Random().nextLong();
                System.out.println("Generated new world seed: " + seed);
            }

            // Trigger actual world loading/generation process
            Game.getInstance().startWorldGeneration(worldName, seed);

            // Trigger callback for world loaded
            if (onWorldLoaded != null) {
                onWorldLoaded.run();
            }

        } catch (Exception e) {
            System.err.println("Error loading world '" + worldName + "': " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== WORLD CREATION ACTIONS =====

    /**
     * Opens the create world dialog.
     */
    public void openCreateWorldDialog() {
        stateManager.openCreateDialog();
    }

    /**
     * Closes the create world dialog.
     */
    public void closeCreateWorldDialog() {
        stateManager.closeCreateDialog();
    }

    /**
     * Creates a new world from the dialog input.
     */
    public void createWorldFromDialog() {
        String worldName = stateManager.getNewWorldName().trim();
        String seedText = stateManager.getNewWorldSeed().trim();

        // Validate world name
        String validationError = discoveryManager.validateWorldName(worldName);
        if (validationError != null) {
            System.err.println("Invalid world name: " + validationError);
            return;
        }

        // Parse seed
        long seed;
        if (seedText.isEmpty()) {
            seed = new Random().nextLong();
        } else {
            try {
                seed = Long.parseLong(seedText);
            } catch (NumberFormatException e) {
                // Use hash of seed text
                seed = seedText.hashCode();
            }
        }

        // Create the world
        if (createNewWorld(worldName, seed)) {
            stateManager.closeCreateDialog();
            refreshWorlds();
        }
    }

    /**
     * Creates a new world with the specified name and seed.
     */
    public boolean createNewWorld(String worldName, String seedText) {
        String trimmedName = worldName != null ? worldName.trim() : "";

        // Validate world name
        String validationError = discoveryManager.validateWorldName(trimmedName);
        if (validationError != null) {
            System.err.println("Cannot create world: " + validationError);
            return false;
        }

        // Parse seed
        long seed;
        if (seedText == null || seedText.trim().isEmpty()) {
            seed = new Random().nextLong();
        } else {
            try {
                seed = Long.parseLong(seedText.trim());
            } catch (NumberFormatException e) {
                seed = seedText.trim().hashCode();
            }
        }

        return createNewWorld(trimmedName, seed);
    }

    /**
     * Creates a new world with the specified name and seed.
     */
    private boolean createNewWorld(String worldName, long seed) {
        try {
            System.out.println("Creating new world: " + worldName + " with seed: " + seed);

            // Create world metadata
            WorldMetadata metadata = new WorldMetadata();
            metadata.setWorldName(worldName);
            metadata.setSeed(seed);
            metadata.setSpawnPosition(new Vector3f(0, 100, 0)); // Default spawn
            metadata.setCreatedTime(System.currentTimeMillis());
            metadata.setLastPlayed(System.currentTimeMillis());
            metadata.setTotalPlayTime(0);

            // Create world directory
            if (!discoveryManager.ensureWorldsDirectoryExists()) {
                System.err.println("Failed to create worlds directory");
                return false;
            }

            // Create world directory and save metadata file
            try {
                java.nio.file.Path worldDir = java.nio.file.Paths.get("worlds", worldName);
                java.nio.file.Files.createDirectories(worldDir);

                // Create chunks directory (required by WorldDiscoveryManager validation)
                java.nio.file.Path chunksDir = worldDir.resolve("chunks");
                java.nio.file.Files.createDirectories(chunksDir);

                // Save metadata to JSON file (to match discoveryManager expectations)
                java.nio.file.Path metadataFile = worldDir.resolve("metadata.json");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

                // Convert WorldMetadata to a JSON-compatible format
                java.util.Map<String, Object> metadataMap = new java.util.HashMap<>();
                metadataMap.put("worldName", metadata.getWorldName());
                metadataMap.put("seed", metadata.getSeed());
                metadataMap.put("spawnPosition", java.util.Map.of(
                    "x", metadata.getSpawnPosition().x,
                    "y", metadata.getSpawnPosition().y,
                    "z", metadata.getSpawnPosition().z
                ));
                metadataMap.put("creationTime", java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(metadata.getCreatedTimeMillis()),
                    java.time.ZoneId.systemDefault()
                ));
                metadataMap.put("lastPlayed", java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(metadata.getLastPlayedMillis()),
                    java.time.ZoneId.systemDefault()
                ));
                metadataMap.put("totalPlayTimeMillis", metadata.getTotalPlayTime());

                mapper.writeValue(metadataFile.toFile(), metadataMap);

                System.out.println("World created successfully: " + worldName);
                return true;
            } catch (java.io.IOException ioEx) {
                System.err.println("Error creating world directory or metadata: " + ioEx.getMessage());
                return false;
            }

        } catch (Exception e) {
            System.err.println("Error creating world '" + worldName + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ===== NAVIGATION ACTIONS =====

    /**
     * Returns to the main menu.
     */
    public void returnToMainMenu() {
        Game.getInstance().setState(GameState.MAIN_MENU);

        if (onReturnToMainMenu != null) {
            onReturnToMainMenu.run();
        }
    }

    // ===== WORLD MANAGEMENT ACTIONS =====

    /**
     * Refreshes the world list from the file system.
     */
    public void refreshWorlds() {
        // Clear discovery manager cache
        discoveryManager.clearCache();

        // Get updated world list
        stateManager.setWorldList(discoveryManager.discoverWorlds());

        if (onRefreshWorlds != null) {
            onRefreshWorlds.run();
        }
    }

    /**
     * Opens a world for editing (placeholder for future functionality).
     */
    public void editWorld(String worldName) {
        // TODO: Implement world editing functionality
        System.out.println("Edit world requested for: " + worldName);
    }

    /**
     * Deletes a world (placeholder for future functionality).
     */
    public void deleteWorld(String worldName) {
        // TODO: Implement world deletion functionality
        System.out.println("Delete world requested for: " + worldName);
    }

    /**
     * Duplicates a world (placeholder for future functionality).
     */
    public void duplicateWorld(String worldName) {
        // TODO: Implement world duplication functionality
        System.out.println("Duplicate world requested for: " + worldName);
    }

    // ===== UTILITY METHODS =====

    /**
     * Gets world information for display purposes.
     */
    public String getWorldDisplayInfo(String worldName) {
        return discoveryManager.getWorldDisplayInfo(worldName);
    }

    /**
     * Gets metadata for a specific world.
     */
    public WorldMetadata getWorldMetadata(String worldName) {
        return discoveryManager.getWorldMetadata(worldName);
    }

    /**
     * Checks if a world exists.
     */
    public boolean worldExists(String worldName) {
        return !discoveryManager.isWorldNameAvailable(worldName);
    }
}