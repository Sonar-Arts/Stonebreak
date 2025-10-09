package com.stonebreak.world.save.model;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure data model for entity state serialization.
 * Immutable for thread safety - follows SOLID principles.
 */
public final class EntityData {
    private final EntityType entityType;
    private final Vector3f position;
    private final Vector3f velocity;
    private final Vector3f rotation;
    private final float health;
    private final float maxHealth;
    private final float age;
    private final boolean alive;
    private final Map<String, Object> customData; // For entity-specific properties

    private EntityData(Builder builder) {
        this.entityType = builder.entityType;
        this.position = new Vector3f(builder.position);
        this.velocity = new Vector3f(builder.velocity);
        this.rotation = new Vector3f(builder.rotation);
        this.health = builder.health;
        this.maxHealth = builder.maxHealth;
        this.age = builder.age;
        this.alive = builder.alive;
        this.customData = new HashMap<>(builder.customData);
    }

    // Getters - return defensive copies for mutable objects
    public EntityType getEntityType() { return entityType; }
    public Vector3f getPosition() { return new Vector3f(position); }
    public Vector3f getVelocity() { return new Vector3f(velocity); }
    public Vector3f getRotation() { return new Vector3f(rotation); }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public float getAge() { return age; }
    public boolean isAlive() { return alive; }
    public Map<String, Object> getCustomData() { return new HashMap<>(customData); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private EntityType entityType;
        private Vector3f position = new Vector3f(0, 0, 0);
        private Vector3f velocity = new Vector3f(0, 0, 0);
        private Vector3f rotation = new Vector3f(0, 0, 0);
        private float health = 10.0f;
        private float maxHealth = 10.0f;
        private float age = 0.0f;
        private boolean alive = true;
        private Map<String, Object> customData = new HashMap<>();

        public Builder entityType(EntityType entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder position(Vector3f position) {
            this.position = new Vector3f(position);
            return this;
        }

        public Builder velocity(Vector3f velocity) {
            this.velocity = new Vector3f(velocity);
            return this;
        }

        public Builder rotation(Vector3f rotation) {
            this.rotation = new Vector3f(rotation);
            return this;
        }

        public Builder health(float health) {
            this.health = health;
            return this;
        }

        public Builder maxHealth(float maxHealth) {
            this.maxHealth = maxHealth;
            return this;
        }

        public Builder age(float age) {
            this.age = age;
            return this;
        }

        public Builder alive(boolean alive) {
            this.alive = alive;
            return this;
        }

        public Builder customData(Map<String, Object> customData) {
            this.customData = new HashMap<>(customData);
            return this;
        }

        public Builder addCustomData(String key, Object value) {
            this.customData.put(key, value);
            return this;
        }

        public EntityData build() {
            if (entityType == null) {
                throw new IllegalStateException("EntityType cannot be null");
            }
            return new EntityData(this);
        }
    }

    /**
     * Specialized builder for BlockDrop entities.
     */
    public static class BlockDropData {
        private final EntityData baseData;
        private final BlockType blockType;
        private final float despawnTimer;
        private final int stackCount;

        public BlockDropData(EntityData baseData, BlockType blockType, float despawnTimer, int stackCount) {
            this.baseData = baseData;
            this.blockType = blockType;
            this.despawnTimer = despawnTimer;
            this.stackCount = stackCount;
        }

        public EntityData getBaseData() { return baseData; }
        public BlockType getBlockType() { return blockType; }
        public float getDespawnTimer() { return despawnTimer; }
        public int getStackCount() { return stackCount; }

        public static BlockDropData fromCustomData(EntityData entityData) {
            Map<String, Object> customData = entityData.getCustomData();
            BlockType blockType = (BlockType) customData.get("blockType");
            float despawnTimer = (float) customData.getOrDefault("despawnTimer", 300.0f);
            int stackCount = (int) customData.getOrDefault("stackCount", 1);
            return new BlockDropData(entityData, blockType, despawnTimer, stackCount);
        }
    }

    /**
     * Specialized builder for Cow entities.
     */
    public static class CowData {
        private final EntityData baseData;
        private final String textureVariant;
        private final boolean canBeMilked;
        private final float milkRegenTimer;
        private final String aiState;

        public CowData(EntityData baseData, String textureVariant, boolean canBeMilked,
                      float milkRegenTimer, String aiState) {
            this.baseData = baseData;
            this.textureVariant = textureVariant;
            this.canBeMilked = canBeMilked;
            this.milkRegenTimer = milkRegenTimer;
            this.aiState = aiState;
        }

        public EntityData getBaseData() { return baseData; }
        public String getTextureVariant() { return textureVariant; }
        public boolean canBeMilked() { return canBeMilked; }
        public float getMilkRegenTimer() { return milkRegenTimer; }
        public String getAiState() { return aiState; }

        public static CowData fromCustomData(EntityData entityData) {
            Map<String, Object> customData = entityData.getCustomData();
            String textureVariant = (String) customData.getOrDefault("textureVariant", "default");
            boolean canBeMilked = (boolean) customData.getOrDefault("canBeMilked", true);
            float milkRegenTimer = (float) customData.getOrDefault("milkRegenTimer", 0.0f);
            String aiState = (String) customData.getOrDefault("aiState", "IDLE");
            return new CowData(entityData, textureVariant, canBeMilked, milkRegenTimer, aiState);
        }
    }
}
