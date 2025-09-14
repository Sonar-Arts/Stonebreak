package com.stonebreak.world.save;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.joml.Vector3f;

import java.time.LocalDateTime;

/**
 * Contains metadata about a saved world, including generation settings,
 * spawn information, and save timestamps.
 */
public class WorldSaveMetadata {
    
    @JsonProperty("worldName")
    private String worldName;
    
    @JsonProperty("seed")
    private long seed;
    
    @JsonProperty("spawnX")
    private float spawnX;
    
    @JsonProperty("spawnY") 
    private float spawnY;
    
    @JsonProperty("spawnZ")
    private float spawnZ;
    
    @JsonProperty("creationTime")
    @JsonAlias({"creationDate"})
    private LocalDateTime creationTime;
    
    @JsonProperty("lastPlayed")
    private LocalDateTime lastPlayed;
    
    @JsonProperty("totalPlayTime")
    private long totalPlayTimeMillis;
    
    @JsonProperty("version")
    private String gameVersion;
    
    @JsonProperty("difficulty")
    private String difficulty;
    
    @JsonProperty("gameMode")
    private String gameMode;
    
    @JsonProperty("worldType")
    private String worldType;
    
    @JsonProperty("preGeneratedChunks")
    private int preGeneratedChunks;
    
    @JsonProperty("schemaVersion")
    private Integer schemaVersion;

    /**
     * Default constructor for Jackson deserialization.
     */
    public WorldSaveMetadata() {
    }

    /**
     * Creates new world metadata with basic information.
     */
    public WorldSaveMetadata(String worldName, long seed, Vector3f spawnPosition) {
        this.worldName = worldName;
        this.seed = seed;
        this.spawnX = spawnPosition.x;
        this.spawnY = spawnPosition.y;
        this.spawnZ = spawnPosition.z;
        this.creationTime = LocalDateTime.now();
        this.lastPlayed = LocalDateTime.now();
        this.totalPlayTimeMillis = 0;
        this.gameVersion = "1.0-SNAPSHOT";
        this.difficulty = "NORMAL";
        this.gameMode = "SURVIVAL";
        this.worldType = "DEFAULT";
        this.preGeneratedChunks = 0;
        this.schemaVersion = 1;
    }

    // Getters
    public String getWorldName() {
        return worldName;
    }

    public long getSeed() {
        return seed;
    }

    @JsonIgnore
    public Vector3f getSpawnPosition() {
        return new Vector3f(spawnX, spawnY, spawnZ);
    }

    public float getSpawnX() {
        return spawnX;
    }

    public float getSpawnY() {
        return spawnY;
    }

    public float getSpawnZ() {
        return spawnZ;
    }

    public LocalDateTime getCreationTime() {
        return creationTime;
    }

    public LocalDateTime getLastPlayed() {
        return lastPlayed;
    }

    public long getTotalPlayTimeMillis() {
        return totalPlayTimeMillis;
    }

    public String getGameVersion() {
        return gameVersion;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getGameMode() {
        return gameMode;
    }

    public String getWorldType() {
        return worldType;
    }

    public int getPreGeneratedChunks() {
        return preGeneratedChunks;
    }
    
    public Integer getSchemaVersion() {
        return schemaVersion;
    }
    

    // Setters
    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    @JsonIgnore
    public void setSpawnPosition(Vector3f spawnPosition) {
        this.spawnX = spawnPosition.x;
        this.spawnY = spawnPosition.y;
        this.spawnZ = spawnPosition.z;
    }

    public void setSpawnX(float spawnX) {
        this.spawnX = spawnX;
    }

    public void setSpawnY(float spawnY) {
        this.spawnY = spawnY;
    }

    public void setSpawnZ(float spawnZ) {
        this.spawnZ = spawnZ;
    }

    public void setCreationTime(LocalDateTime creationTime) {
        this.creationTime = creationTime;
    }

    public void setLastPlayed(LocalDateTime lastPlayed) {
        this.lastPlayed = lastPlayed;
    }

    public void setTotalPlayTimeMillis(long totalPlayTimeMillis) {
        this.totalPlayTimeMillis = totalPlayTimeMillis;
    }

    public void setGameVersion(String gameVersion) {
        this.gameVersion = gameVersion;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public void setWorldType(String worldType) {
        this.worldType = worldType;
    }

    public void setPreGeneratedChunks(int preGeneratedChunks) {
        this.preGeneratedChunks = preGeneratedChunks;
    }
    
    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    /**
     * Updates the last played time to now and adds to total play time.
     */
    public void updatePlayTime(long sessionTimeMillis) {
        this.lastPlayed = LocalDateTime.now();
        this.totalPlayTimeMillis += sessionTimeMillis;
    }

    /**
     * Increments the count of pre-generated chunks.
     */
    public void incrementPreGeneratedChunks() {
        this.preGeneratedChunks++;
    }
}