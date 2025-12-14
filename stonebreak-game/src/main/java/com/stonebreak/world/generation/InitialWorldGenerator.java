package com.stonebreak.world.generation;

import com.stonebreak.player.Player;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.util.StateConverter;
import org.joml.Vector3f;

/**
 * Handles initial world generation for a new or existing world.
 * Manages chunk generation, player spawning, and save system initialization.
 */
public class InitialWorldGenerator {

    private final World world;
    private final Player player;
    private final LoadingScreen loadingScreen;
    private final SaveService saveService;
    private final String worldName;
    private final long worldSeed;
    private final Runnable completeWorldGenerationCallback;

    private WorldData currentWorldData;
    private TimeOfDay timeOfDay;

    /**
     * Creates a new InitialWorldGenerator.
     *
     * @param world The world instance to generate in
     * @param player The player entity
     * @param loadingScreen The loading screen for progress updates (nullable)
     * @param saveService The save service for loading/saving world data (nullable)
     * @param worldName The name of the world
     * @param worldSeed The seed for world generation
     * @param completeWorldGenerationCallback Callback to invoke when generation completes
     */
    public InitialWorldGenerator(World world, Player player, LoadingScreen loadingScreen,
                                  SaveService saveService, String worldName, long worldSeed,
                                  Runnable completeWorldGenerationCallback) {
        this.world = world;
        this.player = player;
        this.loadingScreen = loadingScreen;
        this.saveService = saveService;
        this.worldName = worldName;
        this.worldSeed = worldSeed;
        this.completeWorldGenerationCallback = completeWorldGenerationCallback;
    }

    /**
     * Performs the initial world generation.
     * This includes loading or creating world data, generating chunks, and positioning the player.
     *
     * @return The initialized TimeOfDay instance
     * @throws RuntimeException if generation fails critically
     */
    public TimeOfDay performInitialGeneration() {
        try {
            // Update progress through the loading screen
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Initializing Noise System");
            }

            // Give a brief moment for the loading screen to render
            Thread.sleep(100);

            // Generate initial chunks around spawn point
            if (world != null && player != null) {
                // Try to load existing player data before setting default position
                Vector3f playerPosition = new Vector3f(0, 100, 0); // Temporary default
                boolean isNewPlayer = false; // Track if this is a first-time spawn

                if (saveService != null) {
                    try {
                        // Create or load world metadata
                        currentWorldData = WorldData.builder()
                            .seed(worldSeed)
                            .worldName(worldName)
                            .build();

                        // Initialize save system with game state
                        saveService.initialize(currentWorldData, player, world);
                        System.out.println("[SAVE-SYSTEM] ✓ Initialized save system for world '" + worldName + "'");

                        // Try to load existing player data
                        SaveService.LoadResult loadResult = saveService.loadWorld().get();
                        if (loadResult.isSuccess() && loadResult.getPlayerData() != null) {
                            // Apply the loaded player state
                            StateConverter.applyPlayerData(player, loadResult.getPlayerData());
                            playerPosition = new Vector3f(loadResult.getPlayerData().getPosition());
                            System.out.println("[PLAYER-DATA] ✓ Loaded existing player data for world '" + worldName + "': position=" +
                                playerPosition.x + "," + playerPosition.y + "," + playerPosition.z);

                            // Update current world data if loaded
                            if (loadResult.getWorldData() != null) {
                                currentWorldData = loadResult.getWorldData();

                                // Initialize TimeOfDay with loaded world time
                                long savedTimeTicks = currentWorldData.getWorldTimeTicks();
                                timeOfDay = new TimeOfDay(savedTimeTicks);
                                System.out.println("[TIME-SYSTEM] ✓ Loaded world time: " + savedTimeTicks + " ticks (" + timeOfDay.getTimeString() + ")");
                            }
                        } else {
                            // No existing player data - mark as new player
                            isNewPlayer = true;
                            player.giveStartingItems();
                            timeOfDay = new TimeOfDay(TimeOfDay.NOON);
                            System.out.println("[PLAYER-DATA] ✓ No existing player data found for world '" + worldName + "' - treating as new world, giving starting items");
                            System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon");
                        }

                        // Start auto-save
                        saveService.startAutoSave();
                    } catch (Exception e) {
                        // Failed to initialize save system or load player data
                        System.err.println("[SAVE-SYSTEM] ✗ CRITICAL ERROR: Save system initialization failed for world '" + worldName + "'!");
                        System.err.println("[SAVE-SYSTEM] Error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        e.printStackTrace();

                        isNewPlayer = true;
                        player.giveStartingItems();
                        System.out.println("[PLAYER-DATA] Save system failed, giving starting items as fallback: " + e.getMessage());
                    }
                } else {
                    // No save system available, give starting items and use defaults
                    isNewPlayer = true;
                    player.giveStartingItems();
                    // Initialize time at noon for new worlds
                    timeOfDay = new TimeOfDay(TimeOfDay.NOON);
                    System.out.println("[PLAYER-DATA] No save system available for world '" + worldName + "', giving starting items");
                    System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon (no save system)");
                }

                // Generate chunks around player position
                int playerChunkX = (int) Math.floor(playerPosition.x / 16);
                int playerChunkZ = (int) Math.floor(playerPosition.z / 16);
                int renderDistance = 4; // Smaller initial area

                if (loadingScreen != null) {
                    loadingScreen.updateProgress("Generating Base Terrain Shape");
                }

                // Generate chunks in expanding rings
                long lastProgressUpdate = System.currentTimeMillis();
                int chunksGenerated = 0;

                for (int ring = 0; ring <= renderDistance; ring++) {
                    for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                        for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                            // Only generate chunks on the edge of the current ring
                            if (ring == 0 || x == playerChunkX - ring || x == playerChunkX + ring ||
                                z == playerChunkZ - ring || z == playerChunkZ + ring) {

                                world.getChunkAt(x, z); // This generates the chunk
                                chunksGenerated++;

                                // Rate limiting: pause every few chunks to prevent excessive CPU usage
                                if (chunksGenerated % 3 == 0) {
                                    long currentTime = System.currentTimeMillis();
                                    // Rate limit progress updates to 50ms intervals
                                    if (currentTime - lastProgressUpdate < 50) {
                                        continue;
                                    }
                                    lastProgressUpdate = System.currentTimeMillis();
                                }
                            }
                        }
                    }

                    // Update progress based on ring completion
                    if (loadingScreen != null) {
                        switch (ring) {
                            case 1 -> loadingScreen.updateProgress("Determining Biomes");
                            case 2 -> loadingScreen.updateProgress("Applying Biome Materials");
                            case 3 -> loadingScreen.updateProgress("Adding Surface Decorations & Details");
                            case 4 -> loadingScreen.updateProgress("Meshing Chunk");
                        }
                    }
                }

                // Calculate surface-based spawn position for new players
                if (isNewPlayer) {
                    // Create spawn calculator with randomized spawn chunk
                    SpawnLocationCalculator spawnCalculator = new SpawnLocationCalculator(world);
                    Vector3f spawnPosition = spawnCalculator.calculateSpawnPosition();

                    playerPosition.set(spawnPosition);
                    player.getPosition().set(spawnPosition);
                    System.out.println("[SPAWN] New player spawn position set: (" +
                        playerPosition.x + ", " + playerPosition.y + ", " + playerPosition.z + ")");
                }

                // Give time for all chunks to finish processing
                Thread.sleep(500);

                // Complete world generation
                if (completeWorldGenerationCallback != null) {
                    completeWorldGenerationCallback.run();
                }
            }

            return timeOfDay;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("World generation interrupted: " + e.getMessage());
            throw new RuntimeException("World generation interrupted", e);
        } catch (Exception e) {
            System.err.println("Error during world generation: " + e.getMessage());
            System.err.println("World generation error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            // Still complete the generation to avoid being stuck
            if (completeWorldGenerationCallback != null) {
                completeWorldGenerationCallback.run();
            }
            throw new RuntimeException("World generation failed", e);
        }
    }

    /**
     * Gets the current world data.
     * @return The world data, or null if not initialized
     */
    public WorldData getCurrentWorldData() {
        return currentWorldData;
    }
}
