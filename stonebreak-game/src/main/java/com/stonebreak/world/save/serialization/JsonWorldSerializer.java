package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.util.JsonParsingUtil;
import org.joml.Vector3f;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * JSON serializer for WorldData.
 * All serialization logic centralized here - follows Single Responsibility.
 */
public class JsonWorldSerializer {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public byte[] serialize(WorldData world) {
        Vector3f spawn = world.getSpawnPosition();
        System.out.println("[JsonWorldSerializer.serialize()] Serializing spawn position: " + spawn);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"seed\": ").append(world.getSeed()).append(",\n");
        json.append("  \"worldName\": \"").append(JsonParsingUtil.escapeJson(world.getWorldName())).append("\",\n");

        json.append("  \"spawnPosition\": {\n");
        json.append("    \"x\": ").append(spawn.x).append(",\n");
        json.append("    \"y\": ").append(spawn.y).append(",\n");
        json.append("    \"z\": ").append(spawn.z).append("\n");
        json.append("  },\n");

        json.append("  \"creationTime\": \"").append(world.getCreatedTime().format(FORMATTER)).append("\",\n");
        json.append("  \"lastPlayed\": \"").append(world.getLastPlayed().format(FORMATTER)).append("\",\n");
        json.append("  \"totalPlayTimeMillis\": ").append(world.getTotalPlayTimeMillis()).append(",\n");
        json.append("  \"worldTimeTicks\": ").append(world.getWorldTimeTicks()).append(",\n");
        json.append("  \"formatVersion\": ").append(world.getFormatVersion()).append("\n");
        json.append("}");

        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public WorldData deserialize(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);

        try {
            return WorldData.builder()
                .seed(JsonParsingUtil.extractLong(json, "seed"))
                .worldName(JsonParsingUtil.extractString(json, "worldName"))
                .spawnPosition(JsonParsingUtil.extractVector3f(json, "spawnPosition"))
                .createdTime(JsonParsingUtil.extractDateTime(json, "creationTime"))
                .lastPlayed(JsonParsingUtil.extractDateTime(json, "lastPlayed"))
                .totalPlayTimeMillis(JsonParsingUtil.extractLong(json, "totalPlayTimeMillis"))
                .worldTimeTicks(JsonParsingUtil.extractLong(json, "worldTimeTicks", 6000L)) // Default to NOON if not present
                .formatVersion(JsonParsingUtil.extractInt(json, "formatVersion", 1))
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize WorldData: " + e.getMessage(), e);
        }
    }
}
