package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.WorldData;
import org.joml.Vector3f;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON serializer for WorldData.
 * All serialization logic centralized here - follows Single Responsibility.
 */
public class JsonWorldSerializer implements Serializer<WorldData> {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public byte[] serialize(WorldData world) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"seed\": ").append(world.getSeed()).append(",\n");
        json.append("  \"worldName\": \"").append(escapeJson(world.getWorldName())).append("\",\n");

        Vector3f spawn = world.getSpawnPosition();
        json.append("  \"spawnPosition\": {\n");
        json.append("    \"x\": ").append(spawn.x).append(",\n");
        json.append("    \"y\": ").append(spawn.y).append(",\n");
        json.append("    \"z\": ").append(spawn.z).append("\n");
        json.append("  },\n");

        json.append("  \"creationTime\": \"").append(world.getCreatedTime().format(FORMATTER)).append("\",\n");
        json.append("  \"lastPlayed\": \"").append(world.getLastPlayed().format(FORMATTER)).append("\",\n");
        json.append("  \"totalPlayTimeMillis\": ").append(world.getTotalPlayTimeMillis()).append(",\n");
        json.append("  \"formatVersion\": ").append(world.getFormatVersion()).append("\n");
        json.append("}");

        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public WorldData deserialize(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);

        try {
            return WorldData.builder()
                .seed(extractLong(json, "seed"))
                .worldName(extractString(json, "worldName"))
                .spawnPosition(extractVector3f(json, "spawnPosition"))
                .createdTime(extractDateTime(json, "creationTime"))
                .lastPlayed(extractDateTime(json, "lastPlayed"))
                .totalPlayTimeMillis(extractLong(json, "totalPlayTimeMillis"))
                .formatVersion(extractInt(json, "formatVersion", 1))
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize WorldData: " + e.getMessage(), e);
        }
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalArgumentException("Missing or invalid key: " + key);
    }

    private long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalArgumentException("Missing or invalid key: " + key);
    }

    private int extractInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return defaultValue;
    }

    private LocalDateTime extractDateTime(String json, String key) {
        String value = extractString(json, key);
        return LocalDateTime.parse(value, FORMATTER);
    }

    private Vector3f extractVector3f(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\{[^}]+\"x\"\\s*:\\s*([\\d.\\-]+)[^}]+\"y\"\\s*:\\s*([\\d.\\-]+)[^}]+\"z\"\\s*:\\s*([\\d.\\-]+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            float x = Float.parseFloat(m.group(1));
            float y = Float.parseFloat(m.group(2));
            float z = Float.parseFloat(m.group(3));
            return new Vector3f(x, y, z);
        }
        return new Vector3f(0, 100, 0); // Default spawn
    }
}
