package com.stonebreak.world.save.core;

import org.joml.Vector3f;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Simplified world metadata focused on terrain data only.
 * No entity complexity, AI data, or enterprise features.
 * Now uses Jackson JSON serialization for human-readable format.
 */
// Custom serializers for Vector3f to match existing format
class Vector3fSerializer extends JsonSerializer<Vector3f> {
    @Override
    public void serialize(Vector3f value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeStartObject();
            gen.writeNumberField("x", value.x);
            gen.writeNumberField("y", value.y);
            gen.writeNumberField("z", value.z);
            gen.writeEndObject();
        }
    }
}

class Vector3fDeserializer extends JsonDeserializer<Vector3f> {
    @Override
    public Vector3f deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == null) {
            return new Vector3f(0, 100, 0);
        }
        JsonNode node = p.getCodec().readTree(p);
        if (node.isNull()) {
            return new Vector3f(0, 100, 0);
        }
        float x = (float) node.get("x").asDouble();
        float y = (float) node.get("y").asDouble();
        float z = (float) node.get("z").asDouble();
        return new Vector3f(x, y, z);
    }
}

class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            // Handle array format: [year, month, day, hour, minute, second, nanos]
            JsonNode node = p.getCodec().readTree(p);
            if (node.isArray() && node.size() >= 6) {
                int year = node.get(0).asInt();
                int month = node.get(1).asInt();
                int day = node.get(2).asInt();
                int hour = node.get(3).asInt();
                int minute = node.get(4).asInt();
                int second = node.get(5).asInt();
                int nanos = node.size() > 6 ? node.get(6).asInt() : 0;
                return LocalDateTime.of(year, month, day, hour, minute, second, nanos);
            }
        } else if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            // Handle legacy timestamp format
            long timestamp = p.getLongValue();
            return timestampToLocalDateTime(timestamp);
        } else if (p.currentToken() == JsonToken.VALUE_STRING) {
            // Handle ISO string format (default Jackson behavior)
            return LocalDateTime.parse(p.getText());
        }

        // Default fallback
        return LocalDateTime.now();
    }

    private static LocalDateTime timestampToLocalDateTime(long timestamp) {
        return LocalDateTime.ofEpochSecond(timestamp / 1000, (int) (timestamp % 1000) * 1000000, ZoneOffset.UTC);
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldMetadata {

    @JsonProperty("seed")
    private long seed;              // World generation seed

    @JsonProperty("worldName")
    private String worldName;       // Display name

    @JsonProperty("spawnPosition")
    @JsonSerialize(using = Vector3fSerializer.class)
    @JsonDeserialize(using = Vector3fDeserializer.class)
    private Vector3f spawnPosition; // Player spawn point

    @JsonProperty("creationTime")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime creationTime; // Creation timestamp

    @JsonProperty("lastPlayed")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime lastPlayed;   // Last access time

    @JsonProperty("totalPlayTimeMillis")
    private long totalPlayTime;     // Total session time in milliseconds

    @JsonProperty("formatVersion")
    private int formatVersion;      // Save format version

    public WorldMetadata() {
        this.spawnPosition = new Vector3f(0, 100, 0);
        this.formatVersion = 1;
        this.creationTime = LocalDateTime.now();
        this.lastPlayed = this.creationTime;
        this.totalPlayTime = 0;
    }

    public WorldMetadata(long seed, String worldName) {
        this();
        this.seed = seed;
        this.worldName = worldName;
    }

    // Helper method to convert legacy timestamp to LocalDateTime
    public static LocalDateTime timestampToLocalDateTime(long timestamp) {
        return LocalDateTime.ofEpochSecond(timestamp / 1000, (int) (timestamp % 1000) * 1000000, ZoneOffset.UTC);
    }

    // Helper method to convert LocalDateTime to timestamp for legacy compatibility
    public long getCreatedTimeMillis() {
        return creationTime != null ? creationTime.toEpochSecond(ZoneOffset.UTC) * 1000 : System.currentTimeMillis();
    }

    public long getLastPlayedMillis() {
        return lastPlayed != null ? lastPlayed.toEpochSecond(ZoneOffset.UTC) * 1000 : System.currentTimeMillis();
    }

    // Getters and setters
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public Vector3f getSpawnPosition() { return spawnPosition; }
    public void setSpawnPosition(Vector3f spawnPosition) { this.spawnPosition = spawnPosition; }

    public LocalDateTime getCreatedTime() { return creationTime; }
    public void setCreatedTime(LocalDateTime creationTime) { this.creationTime = creationTime; }

    // Legacy compatibility methods
    public void setCreatedTimeMillis(long createdTime) {
        this.creationTime = timestampToLocalDateTime(createdTime);
    }

    // Support for legacy "createdTime" field name that maps to "creationTime"
    @JsonProperty("createdTime")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    public void setCreatedTimeAlias(LocalDateTime createdTime) {
        this.creationTime = createdTime;
    }

    public LocalDateTime getLastPlayed() { return lastPlayed; }
    public void setLastPlayed(LocalDateTime lastPlayed) { this.lastPlayed = lastPlayed; }

    // Legacy compatibility methods
    public void setLastPlayedMillis(long lastPlayed) {
        this.lastPlayed = timestampToLocalDateTime(lastPlayed);
    }

    public long getTotalPlayTime() { return totalPlayTime; }
    public void setTotalPlayTime(long totalPlayTime) { this.totalPlayTime = totalPlayTime; }

    public int getFormatVersion() { return formatVersion; }

    public void addPlayTime(long sessionTime) {
        this.totalPlayTime += sessionTime;
        this.lastPlayed = LocalDateTime.now();
    }
}