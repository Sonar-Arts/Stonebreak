package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.util.JsonParsingUtil;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON serializer for EntityData.
 * Handles serialization/deserialization of all entity types.
 */
public class JsonEntitySerializer {

    public byte[] serialize(EntityData entity) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"entityType\": \"").append(entity.getEntityType().name()).append("\",\n");

        // Position
        Vector3f pos = entity.getPosition();
        json.append("  \"position\": {\"x\": ").append(pos.x).append(", \"y\": ").append(pos.y)
            .append(", \"z\": ").append(pos.z).append("},\n");

        // Velocity
        Vector3f vel = entity.getVelocity();
        json.append("  \"velocity\": {\"x\": ").append(vel.x).append(", \"y\": ").append(vel.y)
            .append(", \"z\": ").append(vel.z).append("},\n");

        // Rotation
        Vector3f rot = entity.getRotation();
        json.append("  \"rotation\": {\"x\": ").append(rot.x).append(", \"y\": ").append(rot.y)
            .append(", \"z\": ").append(rot.z).append("},\n");

        // Basic properties
        json.append("  \"health\": ").append(entity.getHealth()).append(",\n");
        json.append("  \"maxHealth\": ").append(entity.getMaxHealth()).append(",\n");
        json.append("  \"age\": ").append(entity.getAge()).append(",\n");
        json.append("  \"alive\": ").append(entity.isAlive()).append(",\n");

        // Custom data
        json.append("  \"customData\": {\n");
        Map<String, Object> customData = entity.getCustomData();
        int index = 0;
        for (Map.Entry<String, Object> entry : customData.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": ");
            serializeValue(json, entry.getValue());
            if (index < customData.size() - 1) {
                json.append(",");
            }
            json.append("\n");
            index++;
        }
        json.append("  }\n");
        json.append("}");

        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public EntityData deserialize(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);

        try {
            EntityType entityType = EntityType.valueOf(JsonParsingUtil.extractString(json, "entityType"));
            Vector3f position = JsonParsingUtil.extractVector3f(json, "position");
            Vector3f velocity = JsonParsingUtil.extractVector3f(json, "velocity");
            Vector3f rotation = JsonParsingUtil.extractVector3f(json, "rotation");
            float health = (float) JsonParsingUtil.extractDouble(json, "health");
            float maxHealth = (float) JsonParsingUtil.extractDouble(json, "maxHealth");
            float age = (float) JsonParsingUtil.extractDouble(json, "age");
            boolean alive = JsonParsingUtil.extractBoolean(json, "alive");

            // Extract custom data
            Map<String, Object> customData = extractCustomData(json, entityType);

            return EntityData.builder()
                .entityType(entityType)
                .position(position)
                .velocity(velocity)
                .rotation(rotation)
                .health(health)
                .maxHealth(maxHealth)
                .age(age)
                .alive(alive)
                .customData(customData)
                .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize EntityData: " + e.getMessage(), e);
        }
    }

    private void serializeValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String) {
            json.append("\"").append(JsonParsingUtil.escapeJson((String) value)).append("\"");
        } else if (value instanceof BlockType) {
            json.append("\"").append(((BlockType) value).name()).append("\"");
        } else if (value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Number) {
            json.append(value);
        } else {
            json.append("\"").append(value.toString()).append("\"");
        }
    }

    private Map<String, Object> extractCustomData(String json, EntityType entityType) {
        Map<String, Object> customData = new HashMap<>();

        switch (entityType) {
            case BLOCK_DROP -> {
                String blockTypeStr = JsonParsingUtil.extractStringFromObject(json, "customData", "blockType");
                float despawnTimer = (float) JsonParsingUtil.extractDoubleFromObject(json, "customData", "despawnTimer");
                int stackCount = JsonParsingUtil.extractIntFromObject(json, "customData", "stackCount");

                customData.put("blockType", BlockType.valueOf(blockTypeStr));
                customData.put("despawnTimer", despawnTimer);
                customData.put("stackCount", stackCount);
            }
            case ITEM_DROP -> {
                int itemId = JsonParsingUtil.extractIntFromObject(json, "customData", "itemId");
                boolean isBlockType = JsonParsingUtil.extractBooleanFromObject(json, "customData", "isBlockType");
                int itemCount = JsonParsingUtil.extractIntFromObject(json, "customData", "itemCount");
                float despawnTimer = (float) JsonParsingUtil.extractDoubleFromObject(json, "customData", "despawnTimer");
                int stackCount = JsonParsingUtil.extractIntFromObject(json, "customData", "stackCount");

                customData.put("itemId", itemId);
                customData.put("isBlockType", isBlockType);
                customData.put("itemCount", itemCount);
                customData.put("despawnTimer", despawnTimer);
                customData.put("stackCount", stackCount);
            }
            case COW -> {
                String textureVariant = JsonParsingUtil.extractStringFromObject(json, "customData", "textureVariant");
                boolean canBeMilked = JsonParsingUtil.extractBooleanFromObject(json, "customData", "canBeMilked");
                float milkRegenTimer = (float) JsonParsingUtil.extractDoubleFromObject(json, "customData", "milkRegenTimer");
                String aiState = JsonParsingUtil.extractStringFromObject(json, "customData", "aiState");

                customData.put("textureVariant", textureVariant);
                customData.put("canBeMilked", canBeMilked);
                customData.put("milkRegenTimer", milkRegenTimer);
                customData.put("aiState", aiState);
            }
        }

        return customData;
    }
}
