package com.stonebreak.world.save;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Serializable representation of entity data for world saves.
 * Contains position, state, AI data, and type information for all entities.
 */
public class EntityData {
    
    // Entity identification
    @JsonProperty("entityType")
    private String entityType;
    
    @JsonProperty("entityId")
    private long entityId; // Unique identifier for this entity instance
    
    // Position and physics
    @JsonProperty("positionX")
    private float positionX;
    
    @JsonProperty("positionY")
    private float positionY;
    
    @JsonProperty("positionZ")
    private float positionZ;
    
    @JsonProperty("velocityX")
    private float velocityX;
    
    @JsonProperty("velocityY")
    private float velocityY;
    
    @JsonProperty("velocityZ")
    private float velocityZ;
    
    @JsonProperty("rotationX")
    private float rotationX;
    
    @JsonProperty("rotationY")
    private float rotationY;
    
    @JsonProperty("rotationZ")
    private float rotationZ;
    
    // Entity state
    @JsonProperty("health")
    private float health;
    
    @JsonProperty("maxHealth")
    private float maxHealth;
    
    @JsonProperty("onGround")
    private boolean onGround;
    
    @JsonProperty("inWater")
    private boolean inWater;
    
    @JsonProperty("alive")
    private boolean alive;
    
    @JsonProperty("age")
    private float age;
    
    // Living entity specific data
    @JsonProperty("isMoving")
    private boolean isMoving;
    
    @JsonProperty("invulnerable")
    private boolean invulnerable;
    
    @JsonProperty("invulnerabilityTimer")
    private float invulnerabilityTimer;
    
    // AI and behavior data (stored as generic key-value pairs)
    @JsonProperty("aiData")
    private Map<String, Object> aiData;
    
    // Entity dimensions (stored for validation)
    @JsonProperty("width")
    private float width;
    
    @JsonProperty("height")
    private float height;
    
    @JsonProperty("length")
    private float length;
    
    @JsonProperty("legHeight")
    private float legHeight;
    
    /**
     * Default constructor for Jackson deserialization.
     */
    public EntityData() {
        this.aiData = new HashMap<>();
        this.alive = true;
        this.health = 10.0f;
        this.maxHealth = 10.0f;
    }
    
    /**
     * Creates EntityData from an Entity instance.
     */
    public EntityData(Entity entity, long entityId) {
        this.entityType = entity.getType().name();
        this.entityId = entityId;
        
        // Position and physics
        Vector3f pos = entity.getPosition();
        this.positionX = pos.x;
        this.positionY = pos.y;
        this.positionZ = pos.z;
        
        Vector3f vel = entity.getVelocity();
        this.velocityX = vel.x;
        this.velocityY = vel.y;
        this.velocityZ = vel.z;
        
        Vector3f rot = entity.getRotation();
        this.rotationX = rot.x;
        this.rotationY = rot.y;
        this.rotationZ = rot.z;
        
        // Entity state
        this.health = entity.getHealth();
        this.maxHealth = entity.getMaxHealth();
        this.onGround = entity.isOnGround();
        this.inWater = entity.isInWater();
        this.alive = entity.isAlive();
        this.age = entity.getAge();
        
        // Dimensions
        this.width = entity.getWidth();
        this.height = entity.getHeight();
        this.length = entity.getLength();
        
        // Living entity specific data
        if (entity instanceof LivingEntity livingEntity) {
            this.isMoving = livingEntity.isMoving();
            this.invulnerable = livingEntity.isInvulnerable();
            this.invulnerabilityTimer = livingEntity.getInvulnerabilityTimer();
            this.legHeight = livingEntity.getLegHeight();
            
            // TODO: Serialize AI data when AI system is implemented
            // For now, initialize empty map
            this.aiData = new HashMap<>();
        } else {
            this.isMoving = false;
            this.invulnerable = false;
            this.invulnerabilityTimer = 0.0f;
            this.legHeight = 0.0f;
            this.aiData = new HashMap<>();
        }
    }
    
    /**
     * Gets the EntityType enum from the stored string.
     */
    public EntityType getEntityTypeEnum() {
        try {
            return EntityType.valueOf(entityType);
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: Unknown entity type '" + entityType + "', defaulting to COW");
            return EntityType.COW; // Fallback to a known type
        }
    }
    
    /**
     * Validates that the stored dimensions match the expected dimensions for the entity type.
     */
    public boolean validateDimensions() {
        EntityType type = getEntityTypeEnum();
        return Math.abs(width - type.getWidth()) < 0.01f &&
               Math.abs(height - type.getHeight()) < 0.01f &&
               Math.abs(length - type.getLength()) < 0.01f &&
               Math.abs(legHeight - type.getLegHeight()) < 0.01f;
    }
    
    /**
     * Creates position vector from stored coordinates.
     */
    public Vector3f getPositionVector() {
        return new Vector3f(positionX, positionY, positionZ);
    }
    
    /**
     * Creates velocity vector from stored coordinates.
     */
    public Vector3f getVelocityVector() {
        return new Vector3f(velocityX, velocityY, velocityZ);
    }
    
    /**
     * Creates rotation vector from stored coordinates.
     */
    public Vector3f getRotationVector() {
        return new Vector3f(rotationX, rotationY, rotationZ);
    }
    
    // Getters and setters
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    
    public long getEntityId() { return entityId; }
    public void setEntityId(long entityId) { this.entityId = entityId; }
    
    public float getPositionX() { return positionX; }
    public void setPositionX(float positionX) { this.positionX = positionX; }
    
    public float getPositionY() { return positionY; }
    public void setPositionY(float positionY) { this.positionY = positionY; }
    
    public float getPositionZ() { return positionZ; }
    public void setPositionZ(float positionZ) { this.positionZ = positionZ; }
    
    public float getVelocityX() { return velocityX; }
    public void setVelocityX(float velocityX) { this.velocityX = velocityX; }
    
    public float getVelocityY() { return velocityY; }
    public void setVelocityY(float velocityY) { this.velocityY = velocityY; }
    
    public float getVelocityZ() { return velocityZ; }
    public void setVelocityZ(float velocityZ) { this.velocityZ = velocityZ; }
    
    public float getRotationX() { return rotationX; }
    public void setRotationX(float rotationX) { this.rotationX = rotationX; }
    
    public float getRotationY() { return rotationY; }
    public void setRotationY(float rotationY) { this.rotationY = rotationY; }
    
    public float getRotationZ() { return rotationZ; }
    public void setRotationZ(float rotationZ) { this.rotationZ = rotationZ; }
    
    public float getHealth() { return health; }
    public void setHealth(float health) { this.health = health; }
    
    public float getMaxHealth() { return maxHealth; }
    public void setMaxHealth(float maxHealth) { this.maxHealth = maxHealth; }
    
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    
    public boolean isInWater() { return inWater; }
    public void setInWater(boolean inWater) { this.inWater = inWater; }
    
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    
    public float getAge() { return age; }
    public void setAge(float age) { this.age = age; }
    
    public boolean isMoving() { return isMoving; }
    public void setMoving(boolean isMoving) { this.isMoving = isMoving; }
    
    public boolean isInvulnerable() { return invulnerable; }
    public void setInvulnerable(boolean invulnerable) { this.invulnerable = invulnerable; }
    
    public float getInvulnerabilityTimer() { return invulnerabilityTimer; }
    public void setInvulnerabilityTimer(float invulnerabilityTimer) { this.invulnerabilityTimer = invulnerabilityTimer; }
    
    public Map<String, Object> getAiData() { return aiData; }
    public void setAiData(Map<String, Object> aiData) { this.aiData = aiData; }
    
    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }
    
    public float getHeight() { return height; }
    public void setHeight(float height) { this.height = height; }
    
    public float getLength() { return length; }
    public void setLength(float length) { this.length = length; }
    
    public float getLegHeight() { return legHeight; }
    public void setLegHeight(float legHeight) { this.legHeight = legHeight; }
    
    public Vector3f getPosition() {
        return new Vector3f(positionX, positionY, positionZ);
    }
    
    public String getType() {
        return entityType;
    }
}