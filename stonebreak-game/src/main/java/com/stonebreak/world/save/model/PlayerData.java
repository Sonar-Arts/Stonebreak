package com.stonebreak.world.save.model;

import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * Pure data model for player state.
 * No serialization logic - follows SOLID principles.
 * Immutable for thread safety.
 */
public final class PlayerData {
    private final Vector3f position;
    private final Vector2f rotation; // yaw, pitch
    private final float health;
    private final boolean flying;
    private final int gameMode; // 0=Survival, 1=Creative
    private final ItemStack[] inventory; // 36 slots: 9 hotbar + 27 main
    private final int selectedHotbarSlot;
    private final LocalDateTime lastSaved;
    private final String worldName;

    private PlayerData(Builder builder) {
        this.position = new Vector3f(builder.position);
        this.rotation = new Vector2f(builder.rotation);
        this.health = builder.health;
        this.flying = builder.flying;
        this.gameMode = builder.gameMode;
        this.inventory = Arrays.copyOf(builder.inventory, builder.inventory.length);
        this.selectedHotbarSlot = builder.selectedHotbarSlot;
        this.lastSaved = builder.lastSaved;
        this.worldName = builder.worldName;
    }

    // Getters - return defensive copies for mutable objects
    public Vector3f getPosition() { return new Vector3f(position); }
    public Vector2f getRotation() { return new Vector2f(rotation); }
    public float getHealth() { return health; }
    public boolean isFlying() { return flying; }
    public int getGameMode() { return gameMode; }
    public ItemStack[] getInventory() { return Arrays.copyOf(inventory, inventory.length); }
    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public LocalDateTime getLastSaved() { return lastSaved; }
    public String getWorldName() { return worldName; }

    /**
     * Creates a new PlayerData with updated last saved time.
     */
    public PlayerData withLastSaved(LocalDateTime lastSaved) {
        return new Builder(this)
            .lastSaved(lastSaved)
            .build();
    }

    /**
     * Creates default player data with spawn position.
     */
    public static PlayerData createDefault(String worldName) {
        return builder()
            .worldName(worldName)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Vector3f position = new Vector3f(0, 100, 0);
        private Vector2f rotation = new Vector2f(0, 0);
        private float health = 20.0f;
        private boolean flying = false;
        private int gameMode = 1; // Creative mode default
        private ItemStack[] inventory = new ItemStack[36];
        private int selectedHotbarSlot = 0;
        private LocalDateTime lastSaved = LocalDateTime.now();
        private String worldName;

        public Builder() {
            // Initialize empty inventory
            for (int i = 0; i < inventory.length; i++) {
                inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
            }
        }

        public Builder(PlayerData data) {
            this.position = new Vector3f(data.position);
            this.rotation = new Vector2f(data.rotation);
            this.health = data.health;
            this.flying = data.flying;
            this.gameMode = data.gameMode;
            this.inventory = Arrays.copyOf(data.inventory, data.inventory.length);
            this.selectedHotbarSlot = data.selectedHotbarSlot;
            this.lastSaved = data.lastSaved;
            this.worldName = data.worldName;
        }

        public Builder position(Vector3f position) {
            this.position = new Vector3f(position);
            return this;
        }

        public Builder rotation(Vector2f rotation) {
            this.rotation = new Vector2f(rotation);
            return this;
        }

        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public Builder flying(boolean flying) {
            this.flying = flying;
            return this;
        }

        public Builder gameMode(int gameMode) {
            this.gameMode = gameMode;
            return this;
        }

        public Builder inventory(ItemStack[] inventory) {
            if (inventory.length != 36) {
                throw new IllegalArgumentException("Inventory must have exactly 36 slots");
            }
            this.inventory = Arrays.copyOf(inventory, inventory.length);
            return this;
        }

        public Builder selectedHotbarSlot(int selectedHotbarSlot) {
            this.selectedHotbarSlot = selectedHotbarSlot;
            return this;
        }

        public Builder lastSaved(LocalDateTime lastSaved) {
            this.lastSaved = lastSaved;
            return this;
        }

        public Builder worldName(String worldName) {
            this.worldName = worldName;
            return this;
        }

        public PlayerData build() {
            return new PlayerData(this);
        }
    }
}
