package com.stonebreak.world.save;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Binary world metadata format for the new save system.
 * Replaces JSON world metadata with efficient binary storage.
 * 
 * Binary Format:
 * - Header (32 bytes): Magic number, version, data size, timestamps
 * - Core Data (variable): Seed, spawn position, world name
 * - Custom Properties (variable): Key-value pairs for extensibility
 */
public class BinaryWorldMetadata {
    
    /** Magic number for world metadata files */
    private static final int MAGIC_NUMBER = 0x53544F4E; // "STON" in ASCII
    
    /** Current metadata format version */
    private static final int FORMAT_VERSION = 1;
    
    /** Maximum world name length */
    private static final int MAX_WORLD_NAME_LENGTH = 256;
    
    /** Maximum property key/value length */
    private static final int MAX_PROPERTY_LENGTH = 512;
    
    // Core world data
    private long seed;
    private String worldName;
    private Vector3f spawnPosition;
    private long createdTime;
    private long lastPlayed;
    private long totalPlayTime;
    private int gameMode;
    private boolean cheatsEnabled;
    
    // Extensibility
    private final Map<String, String> customProperties;
    
    /**
     * Create empty world metadata.
     */
    public BinaryWorldMetadata() {
        this.spawnPosition = new Vector3f(0, 64, 0);
        this.createdTime = System.currentTimeMillis();
        this.lastPlayed = System.currentTimeMillis();
        this.totalPlayTime = 0;
        this.gameMode = 0; // Survival
        this.cheatsEnabled = false;
        this.customProperties = new HashMap<>();
    }
    
    /**
     * Create world metadata with basic information.
     * @param worldName World name
     * @param seed World seed
     */
    public BinaryWorldMetadata(String worldName, long seed) {
        this();
        this.worldName = worldName;
        this.seed = seed;
    }
    
    /**
     * Serialize this metadata to binary format.
     * @return Binary representation
     * @throws IOException if serialization fails
     */
    public byte[] serialize() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096); // Initial allocation
        
        // Write header
        buffer.putInt(MAGIC_NUMBER);
        buffer.putInt(FORMAT_VERSION);
        buffer.putLong(createdTime);
        buffer.putLong(lastPlayed);
        buffer.putLong(totalPlayTime);
        buffer.putInt(0); // Data size placeholder - will be filled later
        
        int dataStartPosition = buffer.position();
        
        // Write core data
        buffer.putLong(seed);
        writeString(buffer, worldName != null ? worldName : "");
        buffer.putFloat(spawnPosition.x);
        buffer.putFloat(spawnPosition.y);
        buffer.putFloat(spawnPosition.z);
        buffer.putInt(gameMode);
        buffer.put((byte) (cheatsEnabled ? 1 : 0));
        
        // Write custom properties
        buffer.putInt(customProperties.size());
        for (Map.Entry<String, String> entry : customProperties.entrySet()) {
            writeString(buffer, entry.getKey());
            writeString(buffer, entry.getValue());
        }
        
        // Update data size in header
        int dataSize = buffer.position() - dataStartPosition;
        buffer.putInt(28, dataSize); // Position 28 is where data size is stored
        
        // Return trimmed array
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Deserialize metadata from binary format.
     * @param data Binary metadata
     * @return Deserialized metadata
     * @throws IOException if deserialization fails
     */
    public static BinaryWorldMetadata deserialize(byte[] data) throws IOException {
        if (data.length < 32) {
            throw new IOException("Invalid metadata: too small");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        // Read and validate header
        int magic = buffer.getInt();
        if (magic != MAGIC_NUMBER) {
            throw new IOException("Invalid metadata: bad magic number");
        }
        
        int version = buffer.getInt();
        if (version > FORMAT_VERSION) {
            throw new IOException("Unsupported metadata version: " + version);
        }
        
        BinaryWorldMetadata metadata = new BinaryWorldMetadata();
        
        // Read header timestamps
        metadata.createdTime = buffer.getLong();
        metadata.lastPlayed = buffer.getLong();
        metadata.totalPlayTime = buffer.getLong();
        int dataSize = buffer.getInt();
        
        // Validate data size
        if (buffer.remaining() != dataSize) {
            throw new IOException("Invalid metadata: data size mismatch");
        }
        
        // Read core data
        metadata.seed = buffer.getLong();
        metadata.worldName = readString(buffer);
        metadata.spawnPosition = new Vector3f(
            buffer.getFloat(),
            buffer.getFloat(),
            buffer.getFloat()
        );
        metadata.gameMode = buffer.getInt();
        metadata.cheatsEnabled = buffer.get() != 0;
        
        // Read custom properties
        int propertyCount = buffer.getInt();
        for (int i = 0; i < propertyCount; i++) {
            String key = readString(buffer);
            String value = readString(buffer);
            metadata.customProperties.put(key, value);
        }
        
        return metadata;
    }
    
    /**
     * Save metadata to a file.
     * @param worldPath World directory path
     * @throws IOException if saving fails
     */
    public void saveToFile(String worldPath) throws IOException {
        Path metadataPath = Paths.get(worldPath, "world.dat");
        byte[] data = serialize();
        
        // Atomic write using temporary file
        Path tempPath = Paths.get(worldPath, "world.dat.tmp");
        Files.write(tempPath, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tempPath, metadataPath);
    }
    
    /**
     * Load metadata from a file.
     * @param worldPath World directory path
     * @return Loaded metadata
     * @throws IOException if loading fails
     */
    public static BinaryWorldMetadata loadFromFile(String worldPath) throws IOException {
        Path metadataPath = Paths.get(worldPath, "world.dat");
        if (!Files.exists(metadataPath)) {
            throw new IOException("World metadata not found: " + metadataPath);
        }
        
        byte[] data = Files.readAllBytes(metadataPath);
        return deserialize(data);
    }
    
    /**
     * Check if world metadata exists.
     * @param worldPath World directory path
     * @return True if metadata exists
     */
    public static boolean exists(String worldPath) {
        Path metadataPath = Paths.get(worldPath, "world.dat");
        return Files.exists(metadataPath);
    }
    
    /**
     * Update last played time and total play time.
     * @param sessionDuration Duration of this session in milliseconds
     */
    public void updatePlayTime(long sessionDuration) {
        this.lastPlayed = System.currentTimeMillis();
        this.totalPlayTime += sessionDuration;
    }
    
    /**
     * Write a string to the buffer with length prefix.
     * @param buffer ByteBuffer to write to
     * @param str String to write
     */
    private static void writeString(ByteBuffer buffer, String str) {
        if (str == null) str = "";
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }
    
    /**
     * Read a string from the buffer with length prefix.
     * @param buffer ByteBuffer to read from
     * @return Read string
     */
    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length == 0) return "";
        
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    // Getters and setters
    
    public long getSeed() {
        return seed;
    }
    
    public void setSeed(long seed) {
        this.seed = seed;
    }
    
    public String getWorldName() {
        return worldName;
    }
    
    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }
    
    public Vector3f getSpawnPosition() {
        return new Vector3f(spawnPosition);
    }
    
    public void setSpawnPosition(Vector3f spawnPosition) {
        this.spawnPosition.set(spawnPosition);
    }
    
    public void setSpawnPosition(float x, float y, float z) {
        this.spawnPosition.set(x, y, z);
    }
    
    public long getCreatedTime() {
        return createdTime;
    }
    
    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }
    
    public LocalDateTime getCreatedDateTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(createdTime), ZoneOffset.UTC);
    }
    
    public long getLastPlayed() {
        return lastPlayed;
    }
    
    public void setLastPlayed(long lastPlayed) {
        this.lastPlayed = lastPlayed;
    }
    
    public LocalDateTime getLastPlayedDateTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPlayed), ZoneOffset.UTC);
    }
    
    public long getTotalPlayTime() {
        return totalPlayTime;
    }
    
    public void setTotalPlayTime(long totalPlayTime) {
        this.totalPlayTime = totalPlayTime;
    }
    
    public int getGameMode() {
        return gameMode;
    }
    
    public void setGameMode(int gameMode) {
        this.gameMode = gameMode;
    }
    
    public boolean isCheatsEnabled() {
        return cheatsEnabled;
    }
    
    public void setCheatsEnabled(boolean cheatsEnabled) {
        this.cheatsEnabled = cheatsEnabled;
    }
    
    /**
     * Get a custom property.
     * @param key Property key
     * @return Property value, or null if not found
     */
    public String getProperty(String key) {
        return customProperties.get(key);
    }
    
    /**
     * Set a custom property.
     * @param key Property key
     * @param value Property value
     */
    public void setProperty(String key, String value) {
        if (key != null && value != null) {
            customProperties.put(key, value);
        }
    }
    
    /**
     * Remove a custom property.
     * @param key Property key
     * @return Previous value, or null if not found
     */
    public String removeProperty(String key) {
        return customProperties.remove(key);
    }
    
    /**
     * Get all custom properties.
     * @return Copy of custom properties map
     */
    public Map<String, String> getCustomProperties() {
        return new HashMap<>(customProperties);
    }
    
    /**
     * Clear all custom properties.
     */
    public void clearCustomProperties() {
        customProperties.clear();
    }
    
    /**
     * Get the estimated serialized size.
     * @return Estimated size in bytes
     */
    public int getEstimatedSize() {
        int size = 32; // Header
        size += 8; // Seed
        size += 4 + (worldName != null ? worldName.getBytes(StandardCharsets.UTF_8).length : 0); // World name
        size += 12; // Spawn position (3 floats)
        size += 4; // Game mode
        size += 1; // Cheats enabled
        size += 4; // Property count
        
        for (Map.Entry<String, String> entry : customProperties.entrySet()) {
            size += 4 + entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            size += 4 + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        
        return size;
    }
    
    @Override
    public String toString() {
        return String.format(
            "WorldMetadata[name='%s', seed=%d, spawn=(%.1f,%.1f,%.1f), created=%s, played=%dms]",
            worldName, seed, spawnPosition.x, spawnPosition.y, spawnPosition.z,
            getCreatedDateTime(), totalPlayTime
        );
    }
}