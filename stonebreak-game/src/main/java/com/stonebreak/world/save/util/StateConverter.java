package com.stonebreak.world.save.util;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Inventory;
import org.joml.Vector3f;
import org.joml.Vector2f;

/**
 * Utility class for converting between game objects and data models.
 * Follows Single Responsibility - only handles conversions.
 * Eliminates duplication across save system.
 */
public final class StateConverter {
    private StateConverter() {} // Utility class

    /**
     * Converts Player to PlayerData.
     * Single source of truth for player state conversion.
     */
    public static PlayerData toPlayerData(Player player, String worldName) {
        // Build combined inventory (9 hotbar + 27 main)
        ItemStack[] combinedInventory = new ItemStack[36];
        Inventory inventory = player.getInventory();

        // Copy hotbar items (slots 0-8)
        for (int i = 0; i < 9; i++) {
            combinedInventory[i] = inventory.getHotbarItem(i);
        }

        // Copy main inventory items (slots 9-35)
        for (int i = 0; i < 27; i++) {
            combinedInventory[i + 9] = inventory.getMainInventoryItem(i);
        }

        return PlayerData.builder()
            .position(new Vector3f(player.getPosition()))
            .rotation(new Vector2f(player.getCamera().getYaw(), player.getCamera().getPitch()))
            .health(player.getHealth())
            .flying(player.isFlying())
            .gameMode(1) // Creative mode default
            .inventory(combinedInventory)
            .selectedHotbarSlot(inventory.getSelectedSlot())
            .worldName(worldName)
            .build();
    }

    /**
     * Applies PlayerData to Player.
     * Single source of truth for player state application.
     */
    public static void applyPlayerData(Player player, PlayerData data) {
        // Apply position and rotation
        player.getPosition().set(data.getPosition());
        player.getCamera().setYaw(data.getRotation().x);
        player.getCamera().setPitch(data.getRotation().y);

        // Apply player state
        player.setHealth(data.getHealth());
        player.setFlying(data.isFlying());

        // Apply inventory
        ItemStack[] loadedInventory = data.getInventory();
        if (loadedInventory != null && loadedInventory.length == 36) {
            Inventory inventory = player.getInventory();

            // Clear existing inventory first to ensure clean slate
            for (int i = 0; i < 9; i++) {
                inventory.setHotbarItem(i, null);
            }
            for (int i = 0; i < 27; i++) {
                inventory.setMainInventoryItem(i, null);
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
            inventory.setSelectedSlot(data.getSelectedHotbarSlot());
        }
    }

    /**
     * Converts Chunk to ChunkData using CCO snapshot API.
     */
    public static ChunkData toChunkData(Chunk chunk) {
        CcoSerializableSnapshot snapshot = chunk.createSnapshot();
        return snapshot.toChunkData();
    }

    /**
     * Applies ChunkData to Chunk using CCO snapshot API.
     */
    public static void applyChunkData(Chunk chunk, ChunkData data) {
        // Convert ChunkData back to CCO snapshot
        CcoSerializableSnapshot snapshot = new CcoSerializableSnapshot(
            data.getChunkX(),
            data.getChunkZ(),
            data.getBlocks(),
            data.getLastModified(),
            data.isFeaturesPopulated()
        );

        // Load from snapshot
        chunk.loadFromSnapshot(snapshot);

        // NOTE: Do NOT call markClean() here!
        // loadFromSnapshot() marks the chunk as MESH_DIRTY, which is correct - loaded chunks need mesh regeneration.
        // markClean() should ONLY be called after a successful save, not after loading.
    }

    /**
     * Creates ChunkData and applies to existing Chunk.
     * Used when loading chunk from disk.
     */
    public static Chunk createChunkFromData(ChunkData data) {
        Chunk chunk = new Chunk(data.getChunkX(), data.getChunkZ());
        applyChunkData(chunk, data);
        return chunk;
    }
}
