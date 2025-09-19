package com.stonebreak.world.save.managers;

import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.core.PlayerState;
import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Inventory;
import java.time.LocalDateTime;
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
     * Checks if the save system is properly initialized with all required components.
     */
    public boolean isInitialized() {
        return currentWorld != null && currentPlayer != null && currentWorldMetadata != null;
    }

    /**
     * Updates the world and player references without losing existing metadata or state.
     * This is used when world/player objects are recreated but we want to preserve
     * the save system's initialization state and metadata.
     */
    public void updateReferences(World newWorld, Player newPlayer) {
        if (isInitialized()) {
            this.currentWorld = newWorld;
            this.currentPlayer = newPlayer;
            System.out.println("[SAVE-SYSTEM] Updated object references to maintain save system functionality");
        } else {
            // If save system isn't fully initialized but we have metadata, complete initialization
            if (currentWorldMetadata != null && newWorld != null && newPlayer != null) {
                System.out.println("[SAVE-SYSTEM] Completing initialization with new references");
                initialize(newWorld, newPlayer, currentWorldMetadata);
            } else {
                // Store references for later initialization
                this.currentWorld = newWorld;
                this.currentPlayer = newPlayer;
                System.out.println("[SAVE-SYSTEM] Stored references for later initialization (world=" + (newWorld != null) + ", player=" + (newPlayer != null) + ", metadata=" + (currentWorldMetadata != null) + ")");
            }
        }
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
     * Loads a single chunk if it exists in storage.
     */
    public java.util.concurrent.CompletableFuture<com.stonebreak.world.chunk.Chunk> loadChunk(int chunkX, int chunkZ) {
        return loadManager.loadChunk(chunkX, chunkZ);
    }

    /**
     * Checks if a given chunk exists in storage.
     */
    public java.util.concurrent.CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return loadManager.chunkExists(chunkX, chunkZ);
    }

    /**
     * Applies loaded player state to the current player.
     */
    public void applyPlayerState(PlayerState playerState) {
        if (currentPlayer == null || playerState == null) {
            return;
        }

        // Validate that the player state belongs to this world
        if (playerState.getWorldName() != null && currentWorldMetadata != null) {
            String currentWorldName = currentWorldMetadata.getWorldName();
            if (!playerState.getWorldName().equals(currentWorldName)) {
                System.out.println("[WORLD-ISOLATION] Player data from world '" + playerState.getWorldName() +
                    "' does not match current world '" + currentWorldName + "'. Skipping player data load.");
                return; // Do not apply player state from different world
            }
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

        // Set world name for validation
        if (currentWorldMetadata != null) {
            state.setWorldName(currentWorldMetadata.getWorldName());
        }

        return state;
    }

    /**
     * Updates world metadata with current play time.
     */
    private void updateWorldMetadata(WorldMetadata metadata) {
        // Update last played time
        metadata.setLastPlayed(LocalDateTime.now());
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
