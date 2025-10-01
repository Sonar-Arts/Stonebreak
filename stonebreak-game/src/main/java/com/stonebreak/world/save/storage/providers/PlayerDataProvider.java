package com.stonebreak.world.save.storage.providers;

import com.stonebreak.world.save.core.PlayerState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.world.save.util.JsonMapperUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

/**
 * Provides access to basic player state data.
 * Single responsibility: abstract player data access from storage details.
 * Simplified compared to old system - no complex physics or entity relationships.
 */
public class PlayerDataProvider {

    private static final String PLAYER_DATA_FILE = "player.json";

    private final String worldPath;
    private final ObjectMapper objectMapper;

    public PlayerDataProvider(String worldPath) {
        this.worldPath = worldPath;
        this.objectMapper = JsonMapperUtil.getSharedMapper();

        // Async directory creation to avoid blocking initialization
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(Paths.get(worldPath));
            } catch (Exception e) {
                System.err.println("[INIT-WARNING] Failed to create world directory: " + worldPath + " - " + e.getMessage());
            }
        });
    }

    /**
     * Loads player state from storage.
     * Uses a robust JSON mapping that serializes inventory as {id,count} pairs
     * to avoid polymorphic Item deserialization issues.
     */
    public CompletableFuture<PlayerState> loadPlayerState() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();

                if (!Files.exists(playerPath)) {
                    return null; // No saved player data
                }

                String jsonContent = Files.readString(playerPath, StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonContent);

                PlayerState state = new PlayerState();

                // Position
                var pos = root.path("position");
                if (pos.isObject()) {
                    float px = (float) pos.path("x").asDouble(0);
                    float py = (float) pos.path("y").asDouble(100);
                    float pz = (float) pos.path("z").asDouble(0);
                    state.setPosition(new org.joml.Vector3f(px, py, pz));
                }

                // Rotation
                var rot = root.path("rotation");
                if (rot.isObject()) {
                    float rx = (float) rot.path("x").asDouble(0);
                    float ry = (float) rot.path("y").asDouble(0);
                    state.setRotation(new org.joml.Vector2f(rx, ry));
                }

                // Scalars
                state.setHealth((float) root.path("health").asDouble(20.0));
                state.setFlying(root.path("isFlying").asBoolean(false));
                state.setGameMode(root.path("gameMode").asInt(1));
                state.setSelectedHotbarSlot(root.path("selectedHotbarSlot").asInt(0));
                state.setWorldName(root.path("worldName").asText(null)); // Load world name for validation

                // Inventory
                com.fasterxml.jackson.databind.JsonNode inv = root.path("inventory");
                if (inv.isArray() && inv.size() == 36) {
                    com.stonebreak.items.ItemStack[] stacks = new com.stonebreak.items.ItemStack[36];
                    for (int i = 0; i < 36; i++) {
                        var slot = inv.get(i);
                        if (slot != null && slot.isObject()) {
                            int id = slot.path("id").asInt(0);
                            int count = slot.path("count").asInt(0);
                            stacks[i] = new com.stonebreak.items.ItemStack(id, count);
                        } else {
                            stacks[i] = new com.stonebreak.items.ItemStack(com.stonebreak.blocks.BlockType.AIR.getId(), 0);
                        }
                    }
                    state.setInventory(stacks);
                }

                // lastSaved (optional)
                if (root.has("lastSaved")) {
                    try {
                        // Expect ISO-8601 string or numeric millis
                        if (root.get("lastSaved").isNumber()) {
                            state.setLastSaved(root.get("lastSaved").asLong());
                        } else if (root.get("lastSaved").isTextual()) {
                            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(root.get("lastSaved").asText());
                            state.setLastSaved(dt);
                        }
                    } catch (Exception ignore) { }
                }

                return state;

            } catch (Exception e) {
                throw new RuntimeException("Failed to load player state", e);
            }
        });
    }

    /**
     * Saves player state to storage.
     * Serializes inventory as {id,count} pairs to ensure stable, portable JSON.
     */
    public CompletableFuture<Void> savePlayerState(PlayerState playerState) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (playerState == null) {
                    throw new IllegalArgumentException("PlayerState cannot be null");
                }

                Path playerPath = getPlayerDataPath();

                // Ensure parent directory exists
                Path parentDir = playerPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                // Build JSON structure
                java.util.Map<String, Object> root = new java.util.HashMap<>();

                var pos = new java.util.HashMap<String, Object>();
                pos.put("x", playerState.getPosition().x);
                pos.put("y", playerState.getPosition().y);
                pos.put("z", playerState.getPosition().z);
                root.put("position", pos);

                var rot = new java.util.HashMap<String, Object>();
                rot.put("x", playerState.getRotation().x);
                rot.put("y", playerState.getRotation().y);
                root.put("rotation", rot);

                root.put("health", playerState.getHealth());
                root.put("isFlying", playerState.isFlying());
                root.put("gameMode", playerState.getGameMode());
                root.put("selectedHotbarSlot", playerState.getSelectedHotbarSlot());
                root.put("worldName", playerState.getWorldName()); // Save world name for validation
                root.put("lastSaved", playerState.getLastSaved());

                // Inventory: 36 slots
                java.util.List<java.util.Map<String, Object>> inv = new java.util.ArrayList<>(36);
                com.stonebreak.items.ItemStack[] stacks = playerState.getInventory();
                if (stacks == null || stacks.length != 36) {
                    stacks = new com.stonebreak.items.ItemStack[36];
                }
                for (int i = 0; i < 36; i++) {
                    com.stonebreak.items.ItemStack s = stacks[i];
                    int id = (s == null) ? com.stonebreak.blocks.BlockType.AIR.getId() : s.getBlockTypeId();
                    int count = (s == null) ? 0 : Math.max(0, s.getCount());
                    var slot = new java.util.HashMap<String, Object>();
                    slot.put("id", id);
                    slot.put("count", count);
                    inv.add(slot);
                }
                root.put("inventory", inv);

                String jsonContent = JsonMapperUtil.getPrettyWriter().writeValueAsString(root);
                if (jsonContent == null || jsonContent.trim().isEmpty()) {
                    throw new RuntimeException("Serialized player state is empty");
                }

                // Atomic write using temporary file
                Path tempPath = Paths.get(playerPath.toString() + ".tmp");
                Files.writeString(tempPath, jsonContent, StandardCharsets.UTF_8);
                Files.move(tempPath, playerPath, StandardCopyOption.REPLACE_EXISTING);

                // Update last saved timestamp
                playerState.updateLastSaved();

                System.out.println("[SAVE] Successfully saved player state to: " + playerPath);

            } catch (Exception e) {
                System.err.println("[SAVE-ERROR] Failed to save player state: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to save player state", e);
            }
        });
    }

    /**
     * Checks if player data exists for this world.
     */
    public CompletableFuture<Boolean> playerDataExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path playerPath = getPlayerDataPath();
            return Files.exists(playerPath) && Files.isReadable(playerPath);
        });
    }

    /**
     * Deletes player data for this world.
     * Used when resetting player state.
     */
    public CompletableFuture<Void> deletePlayerData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    Files.delete(playerPath);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete player data", e);
            }
        });
    }

    /**
     * Creates a backup of current player data.
     */
    public CompletableFuture<Void> backupPlayerData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    Path backupPath = Paths.get(worldPath, "player_backup_" + timestamp + ".json");
                    Files.copy(playerPath, backupPath);
                }
            } catch (Exception e) {
                System.err.println("Failed to backup player data: " + e.getMessage());
            }
        });
    }

    /**
     * Gets the size of the player data file in bytes.
     */
    public CompletableFuture<Long> getPlayerDataSize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerPath = getPlayerDataPath();
                if (Files.exists(playerPath)) {
                    return Files.size(playerPath);
                }
                return 0L;
            } catch (Exception e) {
                return 0L;
            }
        });
    }

    /**
     * Gets the world path this provider is associated with.
     */
    public String getWorldPath() {
        return worldPath;
    }

    /**
     * Gets the world name from the path.
     */
    public String getWorldName() {
        return Paths.get(worldPath).getFileName().toString();
    }

    private Path getPlayerDataPath() {
        return Paths.get(worldPath, PLAYER_DATA_FILE);
    }
}
