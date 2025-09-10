package com.stonebreak.world.save;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.player.Camera;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains serializable player data for world saves.
 * This includes position, camera orientation, inventory, and game state.
 */
public class PlayerData {
    
    // Position and physics
    @JsonProperty("positionX")
    private float positionX;
    
    @JsonProperty("positionY")
    private float positionY;
    
    @JsonProperty("positionZ")
    private float positionZ;
    
    @JsonProperty("velocityX")
    private float velocityX;
    
    @JsonProperty("velocityY")
    private float velocityY;
    
    @JsonProperty("velocityZ")
    private float velocityZ;
    
    // Camera orientation
    @JsonProperty("cameraYaw")
    private float cameraYaw;
    
    @JsonProperty("cameraPitch")
    private float cameraPitch;
    
    // Player state
    @JsonProperty("onGround")
    private boolean onGround;
    
    @JsonProperty("flightEnabled")
    private boolean flightEnabled;
    
    @JsonProperty("isFlying")
    private boolean isFlying;
    
    @JsonProperty("health")
    private float health;
    
    // Inventory data
    @JsonProperty("hotbarItems")
    private List<SerializableItemStack> hotbarItems;
    
    @JsonProperty("mainInventoryItems")
    private List<SerializableItemStack> mainInventoryItems;
    
    @JsonProperty("selectedHotbarSlot")
    private int selectedHotbarSlot;
    
    // Game state
    @JsonProperty("gameMode")
    private String gameMode;
    
    @JsonProperty("totalPlayTime")
    private long totalPlayTimeMillis;

    /**
     * Default constructor for Jackson deserialization.
     */
    public PlayerData() {
        this.hotbarItems = new ArrayList<>();
        this.mainInventoryItems = new ArrayList<>();
        this.health = 20.0f; // Full health
        this.gameMode = "SURVIVAL";
        this.totalPlayTimeMillis = 0;
    }

    /**
     * Creates PlayerData from a Player instance.
     */
    public PlayerData(Player player) {
        // Position and velocity
        Vector3f pos = player.getPosition();
        this.positionX = pos.x;
        this.positionY = pos.y;
        this.positionZ = pos.z;
        
        Vector3f vel = player.getVelocity();
        this.velocityX = vel.x;
        this.velocityY = vel.y;
        this.velocityZ = vel.z;
        
        // Camera orientation
        Camera camera = player.getCamera();
        this.cameraYaw = camera.getYaw();
        this.cameraPitch = camera.getPitch();
        
        // Player state
        this.onGround = player.isOnGround();
        this.flightEnabled = player.isFlightEnabled();
        this.isFlying = player.isFlying();
        this.health = 20.0f; // TODO: Add health system to Player
        
        // Serialize inventory
        this.hotbarItems = new ArrayList<>();
        this.mainInventoryItems = new ArrayList<>();
        
        // Convert hotbar items
        for (int i = 0; i < player.getInventory().getHotbarSize(); i++) {
            ItemStack stack = player.getInventory().getHotbarItem(i);
            hotbarItems.add(new SerializableItemStack(stack));
        }
        
        // Convert main inventory items
        for (int i = 0; i < player.getInventory().getMainInventorySize(); i++) {
            ItemStack stack = player.getInventory().getMainInventoryItem(i);
            mainInventoryItems.add(new SerializableItemStack(stack));
        }
        
        this.selectedHotbarSlot = player.getInventory().getSelectedSlot();
        this.gameMode = "SURVIVAL";
        
        // Get world-specific play time from WorldManager
        WorldManager worldManager = WorldManager.getInstance();
        String currentWorldName = worldManager.getCurrentWorldName();
        if (currentWorldName != null) {
            WorldSaveMetadata metadata = worldManager.getWorldMetadata(currentWorldName);
            this.totalPlayTimeMillis = metadata != null ? metadata.getTotalPlayTimeMillis() : 0;
        } else {
            this.totalPlayTimeMillis = 0;
        }
    }

    /**
     * Applies this player data to a Player instance.
     */
    public void applyToPlayer(Player player) {
        // Set position and velocity
        player.setPosition(new Vector3f(positionX, positionY, positionZ));
        player.setVelocity(new Vector3f(velocityX, velocityY, velocityZ));
        
        // Set camera orientation
        player.getCamera().setYaw(cameraYaw);
        player.getCamera().setPitch(cameraPitch);
        
        // Set player state
        player.setOnGround(onGround);
        player.setFlightEnabled(flightEnabled);
        player.setFlying(isFlying);
        
        // Restore inventory
        player.getInventory().clear();
        
        // Restore hotbar items
        for (int i = 0; i < hotbarItems.size() && i < player.getInventory().getHotbarSize(); i++) {
            SerializableItemStack serializable = hotbarItems.get(i);
            ItemStack stack = serializable.toItemStack();
            player.getInventory().setHotbarItem(i, stack);
        }
        
        // Restore main inventory items
        for (int i = 0; i < mainInventoryItems.size() && i < player.getInventory().getMainInventorySize(); i++) {
            SerializableItemStack serializable = mainInventoryItems.get(i);
            ItemStack stack = serializable.toItemStack();
            player.getInventory().setMainInventoryItem(i, stack);
        }
        
        player.getInventory().setSelectedSlot(selectedHotbarSlot);
    }

    // Getters and setters
    public float getPositionX() { return positionX; }
    public void setPositionX(float positionX) { this.positionX = positionX; }
    
    public float getPositionY() { return positionY; }
    public void setPositionY(float positionY) { this.positionY = positionY; }
    
    public float getPositionZ() { return positionZ; }
    public void setPositionZ(float positionZ) { this.positionZ = positionZ; }
    
    public float getVelocityX() { return velocityX; }
    public void setVelocityX(float velocityX) { this.velocityX = velocityX; }
    
    public float getVelocityY() { return velocityY; }
    public void setVelocityY(float velocityY) { this.velocityY = velocityY; }
    
    public float getVelocityZ() { return velocityZ; }
    public void setVelocityZ(float velocityZ) { this.velocityZ = velocityZ; }
    
    public float getCameraYaw() { return cameraYaw; }
    public void setCameraYaw(float cameraYaw) { this.cameraYaw = cameraYaw; }
    
    public float getCameraPitch() { return cameraPitch; }
    public void setCameraPitch(float cameraPitch) { this.cameraPitch = cameraPitch; }
    
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    
    public boolean isFlightEnabled() { return flightEnabled; }
    public void setFlightEnabled(boolean flightEnabled) { this.flightEnabled = flightEnabled; }
    
    public boolean isFlying() { return isFlying; }
    public void setFlying(boolean isFlying) { this.isFlying = isFlying; }
    
    public float getHealth() { return health; }
    public void setHealth(float health) { this.health = health; }
    
    public List<SerializableItemStack> getHotbarItems() { return hotbarItems; }
    public void setHotbarItems(List<SerializableItemStack> hotbarItems) { this.hotbarItems = hotbarItems; }
    
    public List<SerializableItemStack> getMainInventoryItems() { return mainInventoryItems; }
    public void setMainInventoryItems(List<SerializableItemStack> mainInventoryItems) { this.mainInventoryItems = mainInventoryItems; }
    
    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public void setSelectedHotbarSlot(int selectedHotbarSlot) { this.selectedHotbarSlot = selectedHotbarSlot; }
    
    public String getGameMode() { return gameMode; }
    public void setGameMode(String gameMode) { this.gameMode = gameMode; }
    
    public long getTotalPlayTimeMillis() { return totalPlayTimeMillis; }
    public void setTotalPlayTimeMillis(long totalPlayTimeMillis) { this.totalPlayTimeMillis = totalPlayTimeMillis; }
    
    public Vector3f getPosition() {
        return new Vector3f(positionX, positionY, positionZ);
    }
    
    public List<SerializableItemStack> getInventory() {
        List<SerializableItemStack> allItems = new ArrayList<>();
        allItems.addAll(hotbarItems);
        allItems.addAll(mainInventoryItems);
        return allItems;
    }

    /**
     * Serializable representation of an ItemStack.
     */
    public static class SerializableItemStack {
        @JsonProperty("itemId")
        private int itemId;
        
        @JsonProperty("count")
        private int count;
        
        public SerializableItemStack() {}
        
        public SerializableItemStack(ItemStack stack) {
            this.itemId = stack.getItem().getId();
            this.count = stack.getCount();
        }
        
        public ItemStack toItemStack() {
            return new ItemStack(itemId, count);
        }
        
        public int getItemId() { return itemId; }
        public void setItemId(int itemId) { this.itemId = itemId; }
        
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }
}