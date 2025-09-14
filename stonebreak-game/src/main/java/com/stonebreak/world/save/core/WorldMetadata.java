package com.stonebreak.world.save.core;

import org.joml.Vector3f;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Simplified world metadata focused on terrain data only.
 * No entity complexity, AI data, or enterprise features.
 */
public class WorldMetadata {

    private long seed;              // World generation seed
    private String worldName;       // Display name
    private Vector3f spawnPosition; // Player spawn point
    private long createdTime;       // Creation timestamp
    private long lastPlayed;        // Last access time
    private long totalPlayTime;     // Total session time in milliseconds
    private int formatVersion;      // Save format version

    public WorldMetadata() {
        this.spawnPosition = new Vector3f(0, 100, 0);
        this.formatVersion = 1;
        this.createdTime = System.currentTimeMillis();
        this.lastPlayed = this.createdTime;
        this.totalPlayTime = 0;
    }

    public WorldMetadata(long seed, String worldName) {
        this();
        this.seed = seed;
        this.worldName = worldName;
    }

    // Binary serialization for efficient storage
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        buffer.putInt(0x53544F4E); // Magic number "STON" for validation
        buffer.putInt(formatVersion);
        buffer.putLong(seed);
        buffer.putLong(createdTime);
        buffer.putLong(lastPlayed);
        buffer.putLong(totalPlayTime);

        // Spawn position
        buffer.putFloat(spawnPosition.x);
        buffer.putFloat(spawnPosition.y);
        buffer.putFloat(spawnPosition.z);

        // World name (length-prefixed UTF-8)
        byte[] nameBytes = worldName.getBytes();
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);

        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    public static WorldMetadata deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        int magic = buffer.getInt();
        if (magic != 0x53544F4E) {
            throw new IllegalArgumentException("Invalid world metadata format");
        }

        WorldMetadata metadata = new WorldMetadata();
        metadata.formatVersion = buffer.getInt();
        metadata.seed = buffer.getLong();
        metadata.createdTime = buffer.getLong();
        metadata.lastPlayed = buffer.getLong();
        metadata.totalPlayTime = buffer.getLong();

        // Spawn position
        float x = buffer.getFloat();
        float y = buffer.getFloat();
        float z = buffer.getFloat();
        metadata.spawnPosition = new Vector3f(x, y, z);

        // World name
        int nameLength = buffer.getInt();
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        metadata.worldName = new String(nameBytes);

        return metadata;
    }

    // Getters and setters
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public Vector3f getSpawnPosition() { return spawnPosition; }
    public void setSpawnPosition(Vector3f spawnPosition) { this.spawnPosition = spawnPosition; }

    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

    public long getLastPlayed() { return lastPlayed; }
    public void setLastPlayed(long lastPlayed) { this.lastPlayed = lastPlayed; }

    public long getTotalPlayTime() { return totalPlayTime; }
    public void setTotalPlayTime(long totalPlayTime) { this.totalPlayTime = totalPlayTime; }

    public int getFormatVersion() { return formatVersion; }

    public void addPlayTime(long sessionTime) {
        this.totalPlayTime += sessionTime;
        this.lastPlayed = System.currentTimeMillis();
    }
}