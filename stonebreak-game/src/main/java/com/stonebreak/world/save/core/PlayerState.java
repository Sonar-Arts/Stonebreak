package com.stonebreak.world.save.core;

import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
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
 * Simplified player state focused on basic gameplay data only.
 * No complex physics, AI state, or entity relationships.
 */

// Custom serializers for Vector3f to match existing format
class Vector3fSerializerPS extends JsonSerializer<Vector3f> {
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

class Vector3fDeserializerPS extends JsonDeserializer<Vector3f> {
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

// Custom serializers for Vector2f
class Vector2fSerializer extends JsonSerializer<Vector2f> {
    @Override
    public void serialize(Vector2f value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeStartObject();
            gen.writeNumberField("x", value.x); // yaw
            gen.writeNumberField("y", value.y); // pitch
            gen.writeEndObject();
        }
    }
}

class Vector2fDeserializer extends JsonDeserializer<Vector2f> {
    @Override
    public Vector2f deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == null) {
            return new Vector2f(0, 0);
        }
        JsonNode node = p.getCodec().readTree(p);
        if (node.isNull()) {
            return new Vector2f(0, 0);
        }
        float x = (float) node.get("x").asDouble();
        float y = (float) node.get("y").asDouble();
        return new Vector2f(x, y);
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerState {

    @JsonProperty("position")
    @JsonSerialize(using = Vector3fSerializerPS.class)
    @JsonDeserialize(using = Vector3fDeserializerPS.class)
    private Vector3f position;      // World position

    @JsonProperty("rotation")
    @JsonSerialize(using = Vector2fSerializer.class)
    @JsonDeserialize(using = Vector2fDeserializer.class)
    private Vector2f rotation;      // Camera yaw/pitch

    @JsonProperty("health")
    private float health;           // Health points

    @JsonProperty("isFlying")
    private boolean isFlying;       // Flight state

    @JsonProperty("gameMode")
    private int gameMode;           // 0=Survival, 1=Creative

    @JsonProperty("inventory")
    private ItemStack[] inventory;  // Inventory contents (36 slots: 9 hotbar + 27 main)

    @JsonProperty("selectedHotbarSlot")
    private int selectedHotbarSlot; // Currently selected hotbar slot

    @JsonProperty("lastSaved")
    private LocalDateTime lastSaved; // Timestamp of last save

    public PlayerState() {
        this.position = new Vector3f(0, 100, 0);
        this.rotation = new Vector2f(0, 0);
        this.health = 20.0f;
        this.isFlying = false;
        this.gameMode = 1; // Creative mode
        this.inventory = new ItemStack[36];
        this.selectedHotbarSlot = 0;
        this.lastSaved = LocalDateTime.now();

        // Initialize empty inventory slots
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }

    // Helper method to update last saved time
    public void updateLastSaved() {
        this.lastSaved = LocalDateTime.now();
    }


    // Getters and setters
    public Vector3f getPosition() { return position; }
    public void setPosition(Vector3f position) { this.position = position; }

    public Vector2f getRotation() { return rotation; }
    public void setRotation(Vector2f rotation) { this.rotation = rotation; }

    public float getHealth() { return health; }
    public void setHealth(float health) { this.health = health; }

    public boolean isFlying() { return isFlying; }
    public void setFlying(boolean flying) { isFlying = flying; }

    public int getGameMode() { return gameMode; }
    public void setGameMode(int gameMode) { this.gameMode = gameMode; }

    public ItemStack[] getInventory() { return inventory; }
    public void setInventory(ItemStack[] inventory) { this.inventory = inventory; }

    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public void setSelectedHotbarSlot(int selectedHotbarSlot) { this.selectedHotbarSlot = selectedHotbarSlot; }

    public LocalDateTime getLastSaved() { return lastSaved; }
    public void setLastSaved(LocalDateTime lastSaved) { this.lastSaved = lastSaved; }

    // Legacy compatibility method
    public void setLastSaved(long lastSaved) {
        this.lastSaved = LocalDateTime.ofEpochSecond(lastSaved / 1000, (int) (lastSaved % 1000) * 1000000, ZoneOffset.UTC);
    }

    public long getLastSavedMillis() {
        return lastSaved != null ? lastSaved.toEpochSecond(ZoneOffset.UTC) * 1000 : System.currentTimeMillis();
    }

}