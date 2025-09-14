package com.stonebreak.world.save.core;

import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Simplified player state focused on basic gameplay data only.
 * No complex physics, AI state, or entity relationships.
 */
public class PlayerState {

    private Vector3f position;      // World position
    private Vector2f rotation;      // Camera yaw/pitch
    private float health;           // Health points
    private boolean isFlying;       // Flight state
    private int gameMode;           // 0=Survival, 1=Creative
    private ItemStack[] inventory;  // Inventory contents (36 slots: 9 hotbar + 27 main)
    private int selectedHotbarSlot; // Currently selected hotbar slot
    private long lastSaved;         // Timestamp of last save

    public PlayerState() {
        this.position = new Vector3f(0, 100, 0);
        this.rotation = new Vector2f(0, 0);
        this.health = 20.0f;
        this.isFlying = false;
        this.gameMode = 1; // Creative mode
        this.inventory = new ItemStack[36];
        this.selectedHotbarSlot = 0;
        this.lastSaved = System.currentTimeMillis();

        // Initialize empty inventory slots
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }

    // Binary serialization for efficient storage
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);

        // Position and rotation
        buffer.putFloat(position.x);
        buffer.putFloat(position.y);
        buffer.putFloat(position.z);
        buffer.putFloat(rotation.x); // yaw
        buffer.putFloat(rotation.y); // pitch

        // Player state
        buffer.putFloat(health);
        buffer.put((byte) (isFlying ? 1 : 0));
        buffer.putInt(gameMode);
        buffer.putInt(selectedHotbarSlot);
        buffer.putLong(lastSaved);

        // Inventory serialization (simplified)
        buffer.putInt(inventory.length);
        for (ItemStack item : inventory) {
            if (item == null || item.isEmpty()) {
                buffer.putInt(0); // Empty slot
                buffer.putInt(0);
            } else {
                buffer.putInt(item.getItem().getId());
                buffer.putInt(item.getCount());
            }
        }

        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    public static PlayerState deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        PlayerState state = new PlayerState();

        // Position and rotation
        float x = buffer.getFloat();
        float y = buffer.getFloat();
        float z = buffer.getFloat();
        state.position = new Vector3f(x, y, z);

        float yaw = buffer.getFloat();
        float pitch = buffer.getFloat();
        state.rotation = new Vector2f(yaw, pitch);

        // Player state
        state.health = buffer.getFloat();
        state.isFlying = buffer.get() == 1;
        state.gameMode = buffer.getInt();
        state.selectedHotbarSlot = buffer.getInt();
        state.lastSaved = buffer.getLong();

        // Inventory deserialization
        int inventorySize = buffer.getInt();
        state.inventory = new ItemStack[inventorySize];
        for (int i = 0; i < inventorySize; i++) {
            int itemId = buffer.getInt();
            int count = buffer.getInt();

            if (itemId == 0 || count == 0) {
                state.inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
            } else {
                state.inventory[i] = new ItemStack(itemId, count);
            }
        }

        return state;
    }

    // Getters and setters
    public Vector3f getPosition() { return position; }
    public void setPosition(Vector3f position) { this.position = position; }

    public Vector2f getRotation() { return rotation; }
    public void setRotation(Vector2f rotation) { this.rotation = rotation; }

    public float getHealth() { return health; }
    public void setHealth(float health) { this.health = health; }

    public boolean isFlying() { return isFlying; }
    public void setFlying(boolean flying) { isFlying = flying; }

    public int getGameMode() { return gameMode; }
    public void setGameMode(int gameMode) { this.gameMode = gameMode; }

    public ItemStack[] getInventory() { return inventory; }
    public void setInventory(ItemStack[] inventory) { this.inventory = inventory; }

    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public void setSelectedHotbarSlot(int selectedHotbarSlot) { this.selectedHotbarSlot = selectedHotbarSlot; }

    public long getLastSaved() { return lastSaved; }
    public void updateLastSaved() { this.lastSaved = System.currentTimeMillis(); }
}