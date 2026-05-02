package com.stonebreak.core.world;

import org.joml.Vector3f;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.util.StateConverter;
import com.stonebreak.world.spawn.SpawnLocator;

/**
 * Orchestrates world loading and generation. Owns the loading-screen
 * progress flow, save-service lifecycle for the active world, and the
 * bookkeeping for {@code currentWorldName / currentWorldSeed / currentWorldData}
 * held on {@link Game}. Extracted from {@code Game}'s {@code startWorldGeneration / performInitialWorldGeneration /
 * performWorldLoadingOrGeneration / createNewWorldWithGeneration / completeWorldGeneration}.
 */
public final class WorldGenerationCoordinator {

    private static final int INITIAL_RENDER_DISTANCE = 4;

    private final Game game;
    private final WorldLifecycle worldLifecycle;

    public WorldGenerationCoordinator(Game game, WorldLifecycle worldLifecycle) {
        this.game = game;
        this.worldLifecycle = worldLifecycle;
    }

    public void startWorldGeneration() {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.show();
            System.out.println("Started world generation with loading screen");
            new Thread(this::performInitialWorldGeneration).start();
        }
    }

    public void startWorldGeneration(String worldName, long seed) {
        System.out.println("Starting world generation for: " + worldName + " with seed: " + seed);

        SaveService previousSaveService = game.getSaveService();
        if (previousSaveService != null) {
            try {
                previousSaveService.stopAutoSave();
                System.out.println("[SAVE-SYSTEM] Stopped auto-save on previous world");
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Error stopping auto-save before world switch: " + e.getMessage());
            }

            try {
                System.out.println("[SAVE-SYSTEM] Flushing previous save service before switching worlds");
                previousSaveService.flushSavesBlocking("world switch");
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Flush failed during world switch: " + e.getMessage());
            }

            try {
                previousSaveService.close();
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Error closing previous save service: " + e.getMessage());
            }
        }

        String worldPath = "worlds/" + worldName;
        game.setSaveService(new SaveService(worldPath));
        game.setCurrentWorldName(worldName);
        game.setCurrentWorldSeed(seed);

        LoadingScreen loadingScreen = game.getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.show();
            new Thread(() -> performWorldLoadingOrGeneration(worldName, seed)).start();
        }
    }

    private void performInitialWorldGeneration() {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        try {
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Initializing Noise System");
            }

            Thread.sleep(100);

            World world = Game.getWorld();
            Player player = Game.getPlayer();
            if (world != null && player != null) {
                Vector3f playerPosition = null;
                boolean isNewPlayer = false;

                SaveService saveService = game.getSaveService();
                String currentWorldName = game.getCurrentWorldName();
                if (saveService != null) {
                    try {
                        WorldData currentWorldData = WorldData.builder()
                            .seed(game.getCurrentWorldSeed())
                            .worldName(currentWorldName)
                            .build();
                        game.setCurrentWorldData(currentWorldData);

                        saveService.initialize(currentWorldData, player, world);
                        System.out.println("[SAVE-SYSTEM] ✓ Initialized save system for world '" + currentWorldName + "'");

                        SaveService.LoadResult loadResult = saveService.loadWorld().get();
                        if (loadResult.isSuccess() && loadResult.getPlayerData() != null) {
                            StateConverter.applyPlayerData(player, loadResult.getPlayerData());
                            playerPosition = new Vector3f(loadResult.getPlayerData().getPosition());
                            System.out.println("[PLAYER-DATA] ✓ Loaded existing player data for world '" + currentWorldName + "': position=" +
                                playerPosition.x + "," + playerPosition.y + "," + playerPosition.z);

                            if (loadResult.getWorldData() != null) {
                                currentWorldData = loadResult.getWorldData();
                                game.setCurrentWorldData(currentWorldData);

                                long savedTimeTicks = currentWorldData.getWorldTimeTicks();
                                TimeOfDay timeOfDay = new TimeOfDay(savedTimeTicks);
                                game.setTimeOfDay(timeOfDay);
                                System.out.println("[TIME-SYSTEM] ✓ Loaded world time: " + savedTimeTicks + " ticks (" + timeOfDay.getTimeString() + ")");
                            }
                        } else {
                            isNewPlayer = true;
                            player.giveStartingItems();
                            game.setTimeOfDay(new TimeOfDay(TimeOfDay.NOON));
                            System.out.println("[PLAYER-DATA] ✓ No existing player data found for world '" + currentWorldName + "' - treating as new world, giving starting items");
                            System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon");
                        }

                        saveService.startAutoSave();
                    } catch (Exception e) {
                        System.err.println("[SAVE-SYSTEM] ✗ CRITICAL ERROR: Save system initialization failed for world '" + currentWorldName + "'!");
                        System.err.println("[SAVE-SYSTEM] Error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        e.printStackTrace();

                        isNewPlayer = true;
                        player.giveStartingItems();
                        System.out.println("[PLAYER-DATA] Save system failed, giving starting items as fallback: " + e.getMessage());
                    }
                } else {
                    isNewPlayer = true;
                    player.giveStartingItems();
                    game.setTimeOfDay(new TimeOfDay(TimeOfDay.NOON));
                    System.out.println("[PLAYER-DATA] No save system available for world '" + currentWorldName + "', giving starting items");
                    System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon (no save system)");
                }

                if (isNewPlayer) {
                    playerPosition = locateAndApplyNewPlayerSpawn(world, player, saveService);
                } else if (playerPosition == null) {
                    playerPosition = new Vector3f(player.getPosition());
                }

                generateChunksAroundPosition(world, loadingScreen, playerPosition);

                Thread.sleep(500);

                completeWorldGeneration();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("World generation interrupted: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error during world generation: " + e.getMessage());
            System.err.println("World generation error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            completeWorldGeneration();
        }
    }

    /**
     * Picks a safe surface spawn for a brand-new player, applies it to the
     * player and world, and persists it through {@link SaveService} when one
     * is available. Returns the chosen position so chunk pre-generation can
     * center on it.
     */
    private Vector3f locateAndApplyNewPlayerSpawn(World world, Player player, SaveService saveService) {
        Vector3f spawn = new SpawnLocator(world).findSafeSurfaceSpawn();

        player.setPosition(spawn);
        world.setSpawnPosition(spawn);

        WorldData currentWorldData = game.getCurrentWorldData();
        if (currentWorldData != null) {
            WorldData updated = new WorldData.Builder(currentWorldData)
                .spawnPosition(spawn)
                .build();
            game.setCurrentWorldData(updated);
            if (saveService != null) {
                saveService.initialize(updated, player, world);
                System.out.println("[SPAWN] Updated SaveService world data with new spawn: " + spawn);
            }
        }

        System.out.println("[SPAWN] New player spawn applied: " + spawn);
        return spawn;
    }

    private void generateChunksAroundPosition(World world, LoadingScreen loadingScreen, Vector3f playerPosition) {
        int playerChunkX = (int) Math.floor(playerPosition.x / 16);
        int playerChunkZ = (int) Math.floor(playerPosition.z / 16);

        if (loadingScreen != null) {
            loadingScreen.updateProgress("Generating Base Terrain Shape");
        }

        long lastProgressUpdate = System.currentTimeMillis();
        int chunksGenerated = 0;

        for (int ring = 0; ring <= INITIAL_RENDER_DISTANCE; ring++) {
            for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                    if (ring == 0 || x == playerChunkX - ring || x == playerChunkX + ring ||
                        z == playerChunkZ - ring || z == playerChunkZ + ring) {

                        world.getChunkAt(x, z);
                        chunksGenerated++;

                        if (chunksGenerated % 3 == 0) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastProgressUpdate < 50) {
                                continue;
                            }
                            lastProgressUpdate = System.currentTimeMillis();
                        }
                    }
                }
            }

            if (loadingScreen != null) {
                switch (ring) {
                    case 1 -> loadingScreen.updateProgress("Determining Biomes");
                    case 2 -> loadingScreen.updateProgress("Applying Biome Materials");
                    case 3 -> loadingScreen.updateProgress("Adding Surface Decorations & Details");
                    case 4 -> loadingScreen.updateProgress("Meshing Chunk");
                }
            }
        }
    }

    private void performWorldLoadingOrGeneration(String worldName, long seed) {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        try {
            SaveService saveService = game.getSaveService();

            if (saveService == null) {
                System.err.println("[SAVE-SYSTEM] SaveService is null - creating new world");
                createNewWorldWithGeneration(worldName, seed);
                return;
            }

            java.io.File worldDir = new java.io.File("worlds", worldName);
            boolean worldExists = worldDir.exists() && worldDir.isDirectory();

            if (worldExists) {
                if (loadingScreen != null) {
                    loadingScreen.updateProgress("Loading World: " + worldName);
                }
                System.out.println("Loading existing world: " + worldName);

                saveService.loadWorld()
                    .thenAccept(result -> {
                        try {
                            if (result.isSuccess() && result.getWorldData() != null) {
                                WorldData worldData = result.getWorldData();

                                World newWorld = worldLifecycle.createFreshWorldInstance(worldData.getSeed());
                                worldLifecycle.replaceWorldInstance(newWorld);
                                System.out.println("[WORLD-ISOLATION] Created fresh World instance for loading with seed: " + worldData.getSeed());

                                if (worldData.getSpawnPosition() != null) {
                                    newWorld.setSpawnPosition(worldData.getSpawnPosition());
                                }

                                game.setCurrentWorldSeed(worldData.getSeed());
                                game.setCurrentWorldData(worldData);

                                Player freshPlayer = Game.getPlayer();
                                World freshWorld = Game.getWorld();

                                System.out.println("[SAVE-SYSTEM] Reinitializing save system after world replacement for existing world");
                                saveService.initialize(worldData, freshPlayer, freshWorld);

                                long savedTimeTicks = worldData.getWorldTimeTicks();
                                TimeOfDay timeOfDay = new TimeOfDay(savedTimeTicks);
                                game.setTimeOfDay(timeOfDay);
                                System.out.println("[TIME-SYSTEM] ✓ Loaded world time: " + savedTimeTicks + " ticks (" + timeOfDay.getTimeString() + ")");

                                if (result.getPlayerData() != null) {
                                    StateConverter.applyPlayerData(freshPlayer, result.getPlayerData());
                                    System.out.println("[PLAYER-DATA] Applied loaded player data to position: " +
                                        freshPlayer.getPosition().x + ", " + freshPlayer.getPosition().y + ", " + freshPlayer.getPosition().z);

                                    Vector3f playerPos = result.getPlayerData().getPosition();
                                    int playerChunkX = (int) Math.floor(playerPos.x / 16);
                                    int playerChunkZ = (int) Math.floor(playerPos.z / 16);

                                    if (loadingScreen != null) {
                                        loadingScreen.updateProgress("Loading chunks around player...");
                                    }

                                    for (int ring = 0; ring <= INITIAL_RENDER_DISTANCE; ring++) {
                                        for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                                            for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                                                if (ring == 0 || x == playerChunkX - ring || x == playerChunkX + ring ||
                                                    z == playerChunkZ - ring || z == playerChunkZ + ring) {
                                                    freshWorld.getChunkAt(x, z);
                                                }
                                            }
                                        }
                                    }

                                    try {
                                        Thread.sleep(300);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                } else {
                                    freshPlayer.giveStartingItems();
                                    if (Game.getTimeOfDay() == null) {
                                        game.setTimeOfDay(new TimeOfDay(TimeOfDay.NOON));
                                        System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon (no player data)");
                                    }
                                    System.out.println("[PLAYER-DATA] No player data found - giving starting items");

                                    Vector3f newSpawn = locateAndApplyNewPlayerSpawn(freshWorld, freshPlayer, saveService);
                                    int spawnChunkX = (int) Math.floor(newSpawn.x / 16);
                                    int spawnChunkZ = (int) Math.floor(newSpawn.z / 16);
                                    for (int ring = 0; ring <= INITIAL_RENDER_DISTANCE; ring++) {
                                        for (int x = spawnChunkX - ring; x <= spawnChunkX + ring; x++) {
                                            for (int z = spawnChunkZ - ring; z <= spawnChunkZ + ring; z++) {
                                                if (ring == 0 || x == spawnChunkX - ring || x == spawnChunkX + ring ||
                                                    z == spawnChunkZ - ring || z == spawnChunkZ + ring) {
                                                    freshWorld.getChunkAt(x, z);
                                                }
                                            }
                                        }
                                    }
                                    try {
                                        Thread.sleep(300);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }

                                saveService.startAutoSave();

                                System.out.println("Successfully loaded complete world state for: " + worldName);
                            } else {
                                System.out.println("World load incomplete or invalid; generating new world.");
                                createNewWorldWithGeneration(worldName, seed);
                            }
                        } catch (Exception e) {
                            System.err.println("Error applying loaded world state: " + e.getMessage());
                            e.printStackTrace();
                        }
                    })
                    .exceptionally(throwable -> {
                        System.err.println("Failed to load world: " + worldName + " - " + throwable.getMessage());
                        LoadingScreen ls = game.getLoadingScreen();
                        if (ls != null) {
                            ls.updateProgress("Load failed - generating new world...");
                        }
                        createNewWorldWithGeneration(worldName, seed);
                        return null;
                    })
                    .thenRun(this::completeWorldGeneration);
            } else {
                createNewWorldWithGeneration(worldName, seed);
            }

        } catch (Exception e) {
            System.err.println("Error during world loading/generation: " + e.getMessage());
            e.printStackTrace();

            if (loadingScreen != null) {
                loadingScreen.updateProgress("Error occurred - falling back to default generation");
            }

            try {
                Thread.sleep(1000);
                performInitialWorldGeneration();
            } catch (Exception fallbackError) {
                System.err.println("Fallback generation also failed: " + fallbackError.getMessage());
                completeWorldGeneration();
            }
        }
    }

    private void createNewWorldWithGeneration(String worldName, long seed) {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        try {
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Creating New World: " + worldName);
            }
            System.out.println("Creating new world: " + worldName + " with seed: " + seed);

            World newWorld = worldLifecycle.createFreshWorldInstance(seed);
            worldLifecycle.replaceWorldInstance(newWorld);
            System.out.println("[WORLD-ISOLATION] Created fresh World instance with seed: " + seed);

            performInitialWorldGeneration();

        } catch (Exception e) {
            System.err.println("Error creating new world: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public void completeWorldGeneration() {
        LoadingScreen loadingScreen = game.getLoadingScreen();
        if (loadingScreen != null) {
            loadingScreen.hide();

            com.stonebreak.input.MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
            if (mouseCaptureManager != null) {
                mouseCaptureManager.forceUpdate();
            }
        }

        SaveService saveService = game.getSaveService();
        WorldData currentWorldData = game.getCurrentWorldData();
        if (saveService != null && currentWorldData != null) {
            System.out.println("[SAVE-SYSTEM] ✓ Save system is working properly after world generation");
        } else {
            System.err.println("[SAVE-SYSTEM] ✗ CRITICAL: Save system is NOT working after world generation!");
            System.err.println("[SAVE-SYSTEM] saveService = " + (saveService != null ? "not null" : "NULL"));
            System.err.println("[SAVE-SYSTEM] currentWorldData = " + (currentWorldData != null ? "not null" : "NULL"));
        }
    }
}
