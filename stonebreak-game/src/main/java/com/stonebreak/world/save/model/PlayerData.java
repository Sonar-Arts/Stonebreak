package com.stonebreak.world.save.model;

import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private final boolean spectator;
    private final int gameMode; // 0=Survival, 1=Creative
    private final ItemStack[] inventory; // 36 slots: 9 hotbar + 27 main
    private final int selectedHotbarSlot;
    private final LocalDateTime lastSaved;
    private final String worldName;

    // RPG / character progression
    private final String selectedClassId;
    private final Map<String, Integer> spentAbilityCp;
    private final Map<String, Integer> skillLevels;
    private final Set<String> acquiredFeatIds;
    private final int remainingCp;
    private final int remainingSp;
    private final int remainingFp;
    private final int[] abilityScores;  // length 6: STR DEX CON INT WIS CHA
    private final int remainingAp;

    private PlayerData(Builder builder) {
        this.position = new Vector3f(builder.position);
        this.rotation = new Vector2f(builder.rotation);
        this.health = builder.health;
        this.flying = builder.flying;
        this.spectator = builder.spectator;
        this.gameMode = builder.gameMode;
        this.inventory = Arrays.copyOf(builder.inventory, builder.inventory.length);
        this.selectedHotbarSlot = builder.selectedHotbarSlot;
        this.lastSaved = builder.lastSaved;
        this.worldName = builder.worldName;
        this.selectedClassId = builder.selectedClassId;
        this.spentAbilityCp = Collections.unmodifiableMap(new HashMap<>(builder.spentAbilityCp));
        this.skillLevels = Collections.unmodifiableMap(new HashMap<>(builder.skillLevels));
        this.acquiredFeatIds = Collections.unmodifiableSet(new HashSet<>(builder.acquiredFeatIds));
        this.remainingCp = builder.remainingCp;
        this.remainingSp = builder.remainingSp;
        this.remainingFp = builder.remainingFp;
        this.abilityScores = Arrays.copyOf(builder.abilityScores, 6);
        this.remainingAp = builder.remainingAp;
    }

    // Getters - return defensive copies for mutable objects
    public Vector3f getPosition() { return new Vector3f(position); }
    public Vector2f getRotation() { return new Vector2f(rotation); }
    public float getHealth() { return health; }
    public boolean isFlying() { return flying; }
    public boolean isSpectator() { return spectator; }
    public int getGameMode() { return gameMode; }
    public ItemStack[] getInventory() { return Arrays.copyOf(inventory, inventory.length); }
    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public LocalDateTime getLastSaved() { return lastSaved; }
    public String getWorldName() { return worldName; }
    public String getSelectedClassId() { return selectedClassId; }
    public Map<String, Integer> getSpentAbilityCp() { return spentAbilityCp; }
    public Map<String, Integer> getSkillLevels() { return skillLevels; }
    public Set<String> getAcquiredFeatIds() { return acquiredFeatIds; }
    public int getRemainingCp() { return remainingCp; }
    public int getRemainingSkillPoints() { return remainingSp; }
    public int getRemainingFeatPoints() { return remainingFp; }
    public int[] getAbilityScores() { return Arrays.copyOf(abilityScores, 6); }
    public int getRemainingAp() { return remainingAp; }

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
        private boolean spectator = false;
        private int gameMode = 1; // Creative mode default
        private ItemStack[] inventory = new ItemStack[36];
        private int selectedHotbarSlot = 0;
        private LocalDateTime lastSaved = LocalDateTime.now();
        private String worldName;
        private String selectedClassId = null;
        private Map<String, Integer> spentAbilityCp = new HashMap<>();
        private Map<String, Integer> skillLevels = new HashMap<>();
        private Set<String> acquiredFeatIds = new HashSet<>();
        private int remainingCp = 100;
        private int remainingSp = 100;
        private int remainingFp = 100;
        private int[] abilityScores = {10, 10, 10, 10, 10, 10};
        private int remainingAp = 27;

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
            this.spectator = data.spectator;
            this.gameMode = data.gameMode;
            this.inventory = Arrays.copyOf(data.inventory, data.inventory.length);
            this.selectedHotbarSlot = data.selectedHotbarSlot;
            this.lastSaved = data.lastSaved;
            this.worldName = data.worldName;
            this.selectedClassId = data.selectedClassId;
            this.spentAbilityCp = new HashMap<>(data.spentAbilityCp);
            this.skillLevels = new HashMap<>(data.skillLevels);
            this.acquiredFeatIds = new HashSet<>(data.acquiredFeatIds);
            this.remainingCp = data.remainingCp;
            this.remainingSp = data.remainingSp;
            this.remainingFp = data.remainingFp;
            this.abilityScores = Arrays.copyOf(data.abilityScores, 6);
            this.remainingAp = data.remainingAp;
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

        public Builder spectator(boolean spectator) {
            this.spectator = spectator;
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

        public Builder selectedClassId(String selectedClassId) {
            this.selectedClassId = selectedClassId;
            return this;
        }

        public Builder spentAbilityCp(Map<String, Integer> spentAbilityCp) {
            this.spentAbilityCp = new HashMap<>(spentAbilityCp);
            return this;
        }

        public Builder skillLevels(Map<String, Integer> skillLevels) {
            this.skillLevels = new HashMap<>(skillLevels);
            return this;
        }

        public Builder acquiredFeatIds(Set<String> acquiredFeatIds) {
            this.acquiredFeatIds = new HashSet<>(acquiredFeatIds);
            return this;
        }

        public Builder remainingCp(int remainingCp) {
            this.remainingCp = remainingCp;
            return this;
        }

        public Builder remainingSp(int remainingSp) {
            this.remainingSp = remainingSp;
            return this;
        }

        public Builder remainingFp(int remainingFp) {
            this.remainingFp = remainingFp;
            return this;
        }

        public Builder abilityScores(int[] abilityScores) {
            if (abilityScores != null && abilityScores.length == 6) {
                this.abilityScores = Arrays.copyOf(abilityScores, 6);
            }
            return this;
        }

        public Builder remainingAp(int remainingAp) {
            this.remainingAp = remainingAp;
            return this;
        }

        public PlayerData build() {
            return new PlayerData(this);
        }
    }
}
