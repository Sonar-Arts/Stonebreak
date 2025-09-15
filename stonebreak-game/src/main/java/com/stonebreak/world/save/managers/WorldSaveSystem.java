package com.stonebreak.world.save.managers;

import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.core.PlayerState;
import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Inventory;
import java.util.concurrent.CompletableFuture;

/**
 * Main integration point for the new save system with the existing game.
 * Provides a simple interface that the existing World/Player classes can use.
 * Follows SOLID principles by coordinating the specialized managers.
 */
public class WorldSaveSystem implements AutoCloseable {

    private final String worldPath;
    private final WorldSaveManager saveManager;
    private final WorldLoadManager loadManager;
    private final AutoSaveScheduler autoSaveScheduler;

    // Current game state references
    private volatile World currentWorld;
    private volatile Player currentPlayer;
    private volatile WorldMetadata currentWorldMetadata;

    public WorldSaveSystem(String worldPath) {
        this.worldPath = worldPath;
        this.saveManager = new WorldSaveManager(worldPath);
        this.loadManager = new WorldLoadManager(worldPath);
        this.autoSaveScheduler = new AutoSaveScheduler(saveManager);
    }

    /**
     * Initializes the save system with game state references.
     */
    public void initialize(World world, Player player, WorldMetadata worldMetadata) {
        this.currentWorld = world;
        this.currentPlayer = player;
        this.currentWorldMetadata = worldMetadata;

        // Start auto-save
        autoSaveScheduler.startAutoSave(world, player, worldMetadata);
    }

    /**
     * Saves the current world state immediately.
     */
    public CompletableFuture<Void> saveWorldNow() {
        if (currentWorld == null || currentPlayer == null || currentWorldMetadata == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Save system not initialized"));
        }

        PlayerState playerState = createPlayerState(currentPlayer);
        updateWorldMetadata(currentWorldMetadata);

        return saveManager.saveCompleteWorldState(
            currentWorldMetadata,
            playerState,
            currentWorld.getDirtyChunks()
        );
    }

    /**
     * Saves only dirty chunks (for auto-save optimization).
     */
    public CompletableFuture<Void> saveWorldDirtyChunks() {
        if (currentWorld == null || currentPlayer == null || currentWorldMetadata == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Save system not initialized"));
        }

        PlayerState playerState = createPlayerState(currentPlayer);
        updateWorldMetadata(currentWorldMetadata);

        return CompletableFuture.allOf(
            saveManager.saveWorldMetadata(currentWorldMetadata),
            saveManager.savePlayerState(playerState),
            saveManager.saveDirtyChunks(currentWorld.getDirtyChunks())
        );
    }

    /**
     * Loads world metadata.
     */
    public CompletableFuture<WorldMetadata> loadWorldMetadata() {
        return loadManager.loadWorldMetadata();
    }

    /**
     * Loads player state with defaults if none exists.
     */
    public CompletableFuture<PlayerState> loadPlayerState() {
        return loadManager.loadPlayerStateWithDefaults();
    }

    /**
     * Loads complete world state.
     */
    public CompletableFuture<WorldLoadManager.WorldLoadResult> loadCompleteWorldState() {
        return loadManager.loadCompleteWorldState();
    }

    /**
     * Applies loaded player state to the current player.
     */
    public void applyPlayerState(PlayerState playerState) {
        if (currentPlayer == null || playerState == null) {
            return;
        }

        // Apply position and rotation
        currentPlayer.getPosition().set(playerState.getPosition());
        currentPlayer.getCamera().setYaw(playerState.getRotation().x);
        currentPlayer.getCamera().setPitch(playerState.getRotation().y);

        // Apply player state
        currentPlayer.setHealth(playerState.getHealth());
        currentPlayer.setFlying(playerState.isFlying());

        // Apply inventory
        applyInventoryState(currentPlayer.getInventory(), playerState);
    }

    /**
     * Applies loaded inventory state to the player's inventory.
     */
    private void applyInventoryState(Inventory inventory, PlayerState playerState) {
        ItemStack[] loadedInventory = playerState.getInventory();
        if (loadedInventory == null || loadedInventory.length != 36) {
            return; // Invalid inventory data
        }

        // Apply hotbar items (slots 0-8)
        for (int i = 0; i < 9; i++) {
            if (loadedInventory[i] != null) {
                inventory.setHotbarItem(i, loadedInventory[i]);
            }
        }

        // Apply main inventory items (slots 9-35)
        for (int i = 0; i < 27; i++) {
            if (loadedInventory[i + 9] != null) {
                inventory.setMainInventoryItem(i, loadedInventory[i + 9]);
            }
        }

        // Set selected hotbar slot
        inventory.setSelectedSlot(playerState.getSelectedHotbarSlot());
    }

    /**
     * Creates a PlayerState from the current player.
     */
    private PlayerState createPlayerState(Player player) {
        PlayerState state = new PlayerState();

        // Copy position and rotation
        state.setPosition(new Vector3f(player.getPosition()));
        state.setRotation(new Vector2f(player.getCamera().getYaw(), player.getCamera().getPitch()));

        // Copy player state
        state.setHealth(player.getHealth());
        state.setFlying(player.isFlying());
        state.setGameMode(1); // Default to creative mode

        // Copy inventory
        ItemStack[] combinedInventory = new ItemStack[36];
        Inventory inventory = player.getInventory();

        for (int i = 0; i < 9; i++) {
            combinedInventory[i] = inventory.getHotbarItem(i);
        }
        for (int i = 0; i < 27; i++) {
            combinedInventory[i + 9] = inventory.getMainInventoryItem(i);
        }

        state.setInventory(combinedInventory);
        state.setSelectedHotbarSlot(inventory.getSelectedSlot());

        return state;
    }

    /**
     * Updates world metadata with current play time.
     */
    private void updateWorldMetadata(WorldMetadata metadata) {
        // Update last played time
        metadata.setLastPlayed(System.currentTimeMillis());
    }

    /**
     * Checks if the world exists and is valid.
     */
    public CompletableFuture<Boolean> worldExists() {
        return loadManager.validateWorldExists();
    }

    /**
     * Gets auto-save statistics.
     */
    public AutoSaveScheduler.AutoSaveStats getAutoSaveStats() {
        return autoSaveScheduler.getStats();
    }

    /**
     * Gets save statistics.
     */
    public WorldSaveManager.SaveStats getSaveStats() {
        return saveManager.getSaveStats();
    }

    /**
     * Gets load statistics.
     */
    public WorldLoadManager.LoadStats getLoadStats() {
        return loadManager.getLoadStats();
    }

    /**
     * Stops auto-save (useful when switching worlds).
     */
    public void stopAutoSave() {
        autoSaveScheduler.stopAutoSave();
    }

    /**
     * Gets the world path.
     */
    public String getWorldPath() {
        return worldPath;
    }

    @Override
    public void close() {
        stopAutoSave();

        try {
            autoSaveScheduler.close();
        } catch (Exception e) {
            System.err.println("Error closing auto-save scheduler: " + e.getMessage());
        }

        try {
            saveManager.close();
        } catch (Exception e) {
            System.err.println("Error closing save manager: " + e.getMessage());
        }

        try {
            loadManager.close();
        } catch (Exception e) {
            System.err.println("Error closing load manager: " + e.getMessage());
        }

        System.out.println("WorldSaveSystem closed successfully");
    }
}