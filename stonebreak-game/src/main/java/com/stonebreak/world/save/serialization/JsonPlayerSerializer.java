package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.util.JsonParsingUtil;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import org.joml.Vector3f;
import org.joml.Vector2f;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * JSON serializer for PlayerData.
 * All serialization logic centralized here - follows Single Responsibility.
 */
public class JsonPlayerSerializer {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public byte[] serialize(PlayerData player) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        Vector3f pos = player.getPosition();
        json.append("  \"position\": {\n");
        json.append("    \"x\": ").append(pos.x).append(",\n");
        json.append("    \"y\": ").append(pos.y).append(",\n");
        json.append("    \"z\": ").append(pos.z).append("\n");
        json.append("  },\n");

        Vector2f rot = player.getRotation();
        json.append("  \"rotation\": {\n");
        json.append("    \"x\": ").append(rot.x).append(",\n");
        json.append("    \"y\": ").append(rot.y).append("\n");
        json.append("  },\n");

        json.append("  \"health\": ").append(player.getHealth()).append(",\n");
        json.append("  \"isFlying\": ").append(player.isFlying()).append(",\n");
        json.append("  \"gameMode\": ").append(player.getGameMode()).append(",\n");
        json.append("  \"selectedHotbarSlot\": ").append(player.getSelectedHotbarSlot()).append(",\n");

        if (player.getWorldName() != null) {
            json.append("  \"worldName\": \"").append(JsonParsingUtil.escapeJson(player.getWorldName())).append("\",\n");
        }

        json.append("  \"lastSaved\": \"").append(player.getLastSaved().format(FORMATTER)).append("\",\n");

        // Serialize inventory as simple {id, count} pairs
        json.append("  \"inventory\": [\n");
        ItemStack[] inv = player.getInventory();
        System.out.println("[SAVE-DEBUG] ========== SAVING PLAYER INVENTORY ==========");
        System.out.println("[SAVE-DEBUG] Total inventory slots: " + inv.length);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv[i];
            int id = (stack == null) ? BlockType.AIR.getId() : stack.getBlockTypeId();
            int count = (stack == null) ? 0 : stack.getCount();

            // DIAGNOSTIC LOGGING:
            if (stack != null && id > 0) {
                System.out.println("[SAVE-DEBUG] Slot " + i + ": " +
                    stack.getName() + " (ID=" + id + " count=" + count +
                    " item=" + stack.getItem().getClass().getSimpleName() + ")");
            }

            json.append("    {\"id\": ").append(id).append(", \"count\": ").append(count).append("}");
            if (i < 35) json.append(",");
            json.append("\n");
        }
        System.out.println("[SAVE-DEBUG] ========== END INVENTORY SAVE ==========");
        json.append("  ]\n");

        json.append("}");

        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public PlayerData deserialize(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);

        try {
            PlayerData.Builder builder = PlayerData.builder()
                .position(JsonParsingUtil.extractVector3f(json, "position"))
                .rotation(JsonParsingUtil.extractVector2f(json, "rotation"))
                .health(JsonParsingUtil.extractFloat(json, "health", 20.0f))
                .flying(JsonParsingUtil.extractBoolean(json, "isFlying", false))
                .gameMode(JsonParsingUtil.extractInt(json, "gameMode", 1))
                .selectedHotbarSlot(JsonParsingUtil.extractInt(json, "selectedHotbarSlot", 0))
                .lastSaved(JsonParsingUtil.extractDateTime(json, "lastSaved"));

            String worldName = JsonParsingUtil.extractStringOptional(json, "worldName");
            if (worldName != null) {
                builder.worldName(worldName);
            }

            // Deserialize inventory
            ItemStack[] inventory = new ItemStack[36];
            String invPattern = "\"inventory\"\\s*:\\s*\\[(.*?)\\]";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(invPattern, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(json);

            if (m.find()) {
                String invJson = m.group(1);
                String[] items = invJson.split("\\}\\s*,?\\s*");

                for (int i = 0; i < Math.min(36, items.length); i++) {
                    String item = items[i];
                    int id = JsonParsingUtil.extractIntFromObject(item, "id", BlockType.AIR.getId());
                    int count = JsonParsingUtil.extractIntFromObject(item, "count", 0);
                    inventory[i] = new ItemStack(id, count);
                }
            }

            // Fill remaining slots with AIR
            for (int i = 0; i < 36; i++) {
                if (inventory[i] == null) {
                    inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
                }
            }

            builder.inventory(inventory);
            return builder.build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize PlayerData: " + e.getMessage(), e);
        }
    }
}
