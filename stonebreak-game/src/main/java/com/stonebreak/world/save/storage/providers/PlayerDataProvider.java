package com.stonebreak.world.save.storage.providers;

import com.stonebreak.world.save.core.PlayerState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * Provides access to basic player state data.
 * Single responsibility: abstract player data access from storage details.
 * Simplified compared to old system - no complex physics or entity relationships.
 */
public class PlayerDataProvider {

    private static final String PLAYER_DATA_FILE = "player.dat";

    private final String worldPath;

    public PlayerDataProvider(String worldPath) {
        this.worldPath = worldPath;

        // Ensure world directory exists
        try {
            Files.createDirectories(Paths.get(worldPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    /**
     * Loads player state from storage.
     */
    public CompletableFuture<PlayerState> loadPlayerState() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();

                if (!Files.exists(playerPath)) {
                    return null; // No saved player data
                }

                byte[] data = Files.readAllBytes(playerPath);
                return PlayerState.deserialize(data);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load player state", e);
            }
        });
    }

    /**
     * Saves player state to storage.
     */
    public CompletableFuture<Void> savePlayerState(PlayerState playerState) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                byte[] data = playerState.serialize();

                // Atomic write using temporary file
                Path tempPath = Paths.get(playerPath.toString() + ".tmp");
                Files.write(tempPath, data);
                Files.move(tempPath, playerPath);

                // Update last saved timestamp
                playerState.updateLastSaved();

            } catch (Exception e) {
                throw new RuntimeException("Failed to save player state", e);
            }
        });
    }

    /**
     * Checks if player data exists for this world.
     */
    public CompletableFuture<Boolean> playerDataExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path playerPath = getPlayerDataPath();
            return Files.exists(playerPath) && Files.isReadable(playerPath);
        });
    }

    /**
     * Deletes player data for this world.
     * Used when resetting player state.
     */
    public CompletableFuture<Void> deletePlayerData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    Files.delete(playerPath);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete player data", e);
            }
        });
    }

    /**
     * Creates a backup of current player data.
     */
    public CompletableFuture<Void> backupPlayerData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    Path backupPath = Paths.get(worldPath, "player_backup_" + timestamp + ".dat");
                    Files.copy(playerPath, backupPath);
                }
            } catch (Exception e) {
                System.err.println("Failed to backup player data: " + e.getMessage());
            }
        });
    }

    /**
     * Gets the size of the player data file in bytes.
     */
    public CompletableFuture<Long> getPlayerDataSize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    return Files.size(playerPath);
                }
                return 0L;
            } catch (Exception e) {
                return 0L;
            }
        });
    }

    /**
     * Gets the world path this provider is associated with.
     */
    public String getWorldPath() {
        return worldPath;
    }

    /**
     * Gets the world name from the path.
     */
    public String getWorldName() {
        return Paths.get(worldPath).getFileName().toString();
    }

    private Path getPlayerDataPath() {
        return Paths.get(worldPath, PLAYER_DATA_FILE);
    }
}