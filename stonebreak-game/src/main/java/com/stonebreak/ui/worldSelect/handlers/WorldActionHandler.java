package com.stonebreak.ui.worldSelect.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;
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

            // Note: SaveService is created per-world, not a singleton
            // The actual world loading will be handled by Game.startWorldGeneration

            // Get world metadata to retrieve the seed
            WorldData worldData = discoveryManager.getWorldData(worldName);
            long seed;

            if (worldData != null && worldData.getSeed() != 0) {
                seed = worldData.getSeed();
                System.out.println("Using existing world seed: " + seed);
            } else {
                // If no world data or seed, generate a random one for new worlds
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
     * Opens the terrain mapper screen for world creation.
     */
    public void openCreateWorldDialog() {
        // Transition to terrain mapper screen
        Game.getInstance().setState(GameState.TERRAIN_MAPPER);

        // Reset terrain mapper state for fresh world creation
        if (Game.getInstance().getTerrainMapperScreen() != null) {
            Game.getInstance().getTerrainMapperScreen().reset();
        }
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

            // Create world data
            WorldData worldData = WorldData.builder()
                .worldName(worldName)
                .seed(seed)
                .spawnPosition(new Vector3f(0, -999, 0))  // Sentinel value - will be calculated during first load
                .createdTime(LocalDateTime.now())
                .lastPlayed(LocalDateTime.now())
                .totalPlayTimeMillis(0)
                .build();

            // Create world directory
            if (!discoveryManager.ensureWorldsDirectoryExists()) {
                System.err.println("Failed to create worlds directory");
                return false;
            }

            // Create world directory and save world data file
            try {
                java.nio.file.Path worldDir = java.nio.file.Paths.get("worlds", worldName);
                java.nio.file.Files.createDirectories(worldDir);

                // Prepare chunk directory for new save system
                java.nio.file.Path chunksDir = worldDir.resolve("chunks");
                java.nio.file.Files.createDirectories(chunksDir);

                // Save world data using the JsonWorldSerializer
                com.stonebreak.world.save.serialization.JsonWorldSerializer serializer =
                    new com.stonebreak.world.save.serialization.JsonWorldSerializer();

                byte[] jsonBytes = serializer.serialize(worldData);
                java.nio.file.Files.write(worldDir.resolve("world.json"), jsonBytes);
                java.nio.file.Files.write(worldDir.resolve("metadata.json"), jsonBytes);

                System.out.println("World created successfully: " + worldName);
                return true;
            } catch (java.io.IOException ioEx) {
                System.err.println("Error creating world directory or data file: " + ioEx.getMessage());
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
     * Gets world data for a specific world.
     */
    public WorldData getWorldData(String worldName) {
        return discoveryManager.getWorldData(worldName);
    }

    /**
     * Checks if a world exists.
     */
    public boolean worldExists(String worldName) {
        return !discoveryManager.isWorldNameAvailable(worldName);
    }
}
