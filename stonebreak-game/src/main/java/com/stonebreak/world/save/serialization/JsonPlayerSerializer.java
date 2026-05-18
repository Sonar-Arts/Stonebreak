package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.util.JsonParsingUtil;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import org.joml.Vector3f;
import org.joml.Vector2f;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

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
            String state = (stack == null) ? null : stack.getState();

            // DIAGNOSTIC LOGGING:
            if (stack != null && id > 0) {
                System.out.println("[SAVE-DEBUG] Slot " + i + ": " +
                    stack.getName() + " (ID=" + id + " count=" + count +
                    (state != null ? " state=" + state : "") +
                    " item=" + stack.getItem().getClass().getSimpleName() + ")");
            }

            json.append("    {\"id\": ").append(id).append(", \"count\": ").append(count);
            if (state != null) {
                json.append(", \"state\": \"").append(JsonParsingUtil.escapeJson(state)).append("\"");
            }
            json.append("}");
            if (i < 35) json.append(",");
            json.append("\n");
        }
        System.out.println("[SAVE-DEBUG] ========== END INVENTORY SAVE ==========");
        json.append("  ],\n");

        // RPG / character progression
        String classId = player.getSelectedClassId();
        if (classId != null) {
            json.append("  \"characterClass\": \"").append(JsonParsingUtil.escapeJson(classId)).append("\",\n");
        } else {
            json.append("  \"characterClass\": null,\n");
        }
        json.append("  \"remainingCp\": ").append(player.getRemainingCp()).append(",\n");
        json.append("  \"remainingSp\": ").append(player.getRemainingSkillPoints()).append(",\n");
        json.append("  \"remainingFp\": ").append(player.getRemainingFeatPoints()).append(",\n");

        // spentAbilityCp map
        json.append("  \"spentAbilityCp\": {");
        Map<String, Integer> abilityCp = player.getSpentAbilityCp();
        boolean firstEntry = true;
        for (Map.Entry<String, Integer> e : abilityCp.entrySet()) {
            if (!firstEntry) json.append(", ");
            json.append("\"").append(JsonParsingUtil.escapeJson(e.getKey())).append("\": ").append(e.getValue());
            firstEntry = false;
        }
        json.append("},\n");

        // skillLevels map
        json.append("  \"skillLevels\": {");
        Map<String, Integer> skills = player.getSkillLevels();
        firstEntry = true;
        for (Map.Entry<String, Integer> e : skills.entrySet()) {
            if (!firstEntry) json.append(", ");
            json.append("\"").append(JsonParsingUtil.escapeJson(e.getKey())).append("\": ").append(e.getValue());
            firstEntry = false;
        }
        json.append("},\n");

        // acquiredFeats set
        json.append("  \"acquiredFeats\": [");
        Set<String> feats = player.getAcquiredFeatIds();
        firstEntry = true;
        for (String featId : feats) {
            if (!firstEntry) json.append(", ");
            json.append("\"").append(JsonParsingUtil.escapeJson(featId)).append("\"");
            firstEntry = false;
        }
        json.append("],\n");

        // Ability scores
        int[] scores = player.getAbilityScores();
        json.append("  \"abilityScores\": [");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) json.append(", ");
            json.append(scores[i]);
        }
        json.append("],\n");
        json.append("  \"remainingAp\": ").append(player.getRemainingAp()).append("\n");

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
                    ItemStack stack = new ItemStack(id, count);
                    String state = extractStateFromItem(item);
                    if (state != null) {
                        stack.setState(state);
                    }
                    inventory[i] = stack;
                }
            }

            // Fill remaining slots with AIR
            for (int i = 0; i < 36; i++) {
                if (inventory[i] == null) {
                    inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
                }
            }

            builder.inventory(inventory);

            // RPG / character progression (gracefully absent in old saves)
            String characterClass = JsonParsingUtil.extractStringOptional(json, "characterClass");
            if (characterClass != null) {
                builder.selectedClassId(characterClass);
            }
            builder.remainingCp(JsonParsingUtil.extractInt(json, "remainingCp", 100));
            builder.remainingSp(JsonParsingUtil.extractInt(json, "remainingSp", 100));
            builder.remainingFp(JsonParsingUtil.extractInt(json, "remainingFp", 100));
            builder.spentAbilityCp(JsonParsingUtil.extractStringIntMap(json, "spentAbilityCp"));
            builder.skillLevels(JsonParsingUtil.extractStringIntMap(json, "skillLevels"));
            builder.acquiredFeatIds(JsonParsingUtil.extractStringSet(json, "acquiredFeats"));

            // Ability scores (backward-compat: missing → all 10)
            int[] abilityScores = {10, 10, 10, 10, 10, 10};
            java.util.regex.Pattern scoresPattern = java.util.regex.Pattern.compile(
                "\"abilityScores\"\\s*:\\s*\\[([^\\]]+)\\]");
            java.util.regex.Matcher scoresMatcher = scoresPattern.matcher(json);
            if (scoresMatcher.find()) {
                String[] parts = scoresMatcher.group(1).trim().split("\\s*,\\s*");
                for (int i = 0; i < Math.min(6, parts.length); i++) {
                    try { abilityScores[i] = Integer.parseInt(parts[i].trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
            builder.abilityScores(abilityScores);
            builder.remainingAp(JsonParsingUtil.extractInt(json, "remainingAp", 27));

            return builder.build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize PlayerData: " + e.getMessage(), e);
        }
    }

    /**
     * Extract an inventory item's optional {@code "state"} field. Returns
     * {@code null} when the field is absent or empty (default state).
     * Inline regex avoids broadening {@link JsonParsingUtil} for one caller.
     */
    private static String extractStateFromItem(String itemJson) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"state\"\\s*:\\s*\"([^\"]*)\"").matcher(itemJson);
        if (m.find()) {
            String s = m.group(1);
            return s.isBlank() ? null : s;
        }
        return null;
    }
}
