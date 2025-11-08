package com.stonebreak.ui.terrainmapper.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * Handles business logic and actions for the Terrain Mapper screen.
 * Manages world creation, validation, and navigation.
 */
public class TerrainActionHandler {

    private final TerrainStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;

    public TerrainActionHandler(TerrainStateManager stateManager, WorldDiscoveryManager discoveryManager) {
        this.stateManager = stateManager;
        this.discoveryManager = discoveryManager;
    }

    /**
     * Returns to the world select screen.
     */
    public void goBack() {
        Game.getInstance().setState(GameState.WORLD_SELECT);
    }

    /**
     * Creates a new world with the current input values.
     */
    public void createWorld() {
        // Get input values
        String worldName = stateManager.getWorldNameField().getText().trim();
        String seedText = stateManager.getSeedField().getText().trim();

        // Validate world name
        String validationError = discoveryManager.validateWorldName(worldName);
        if (validationError != null) {
            System.err.println("Cannot create world: " + validationError);
            // TODO: Show error message in UI (future enhancement)
            return;
        }

        // Parse seed
        long seed;
        if (seedText.isEmpty()) {
            seed = new Random().nextLong();
            System.out.println("Generated random seed: " + seed);
        } else {
            try {
                seed = Long.parseLong(seedText);
                System.out.println("Using provided seed: " + seed);
            } catch (NumberFormatException e) {
                // Use hash of seed text if not a valid number
                seed = seedText.hashCode();
                System.out.println("Using seed from text hash: " + seed);
            }
        }

        // Create the world
        if (createNewWorld(worldName, seed)) {
            System.out.println("World created successfully. Starting world generation...");

            // Reset state for next use
            stateManager.reset();

            // Start world generation
            Game.getInstance().startWorldGeneration(worldName, seed);
        }
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
                .spawnPosition(new Vector3f(0, 100, 0))
                .createdTime(LocalDateTime.now())
                .lastPlayed(LocalDateTime.now())
                .totalPlayTimeMillis(0)
                .build();

            // Ensure worlds directory exists
            if (!discoveryManager.ensureWorldsDirectoryExists()) {
                System.err.println("Failed to create worlds directory");
                return false;
            }

            // Create world directory structure
            try {
                Path worldDir = Paths.get("worlds", worldName);
                Files.createDirectories(worldDir);

                // Create regions directory for chunk storage
                Path regionsDir = worldDir.resolve("regions");
                Files.createDirectories(regionsDir);

                // Save world metadata using JsonWorldSerializer
                JsonWorldSerializer serializer = new JsonWorldSerializer();
                byte[] jsonBytes = serializer.serialize(worldData);

                Files.write(worldDir.resolve("metadata.json"), jsonBytes);
                // Also write to world.json for backward compatibility
                Files.write(worldDir.resolve("world.json"), jsonBytes);

                System.out.println("World created successfully: " + worldName);
                return true;

            } catch (java.io.IOException ioEx) {
                System.err.println("Error creating world directory or data file: " + ioEx.getMessage());
                ioEx.printStackTrace();
                return false;
            }

        } catch (Exception e) {
            System.err.println("Error creating world '" + worldName + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
