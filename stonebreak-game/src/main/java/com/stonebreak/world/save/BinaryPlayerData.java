package com.stonebreak.world.save;

import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.items.Inventory;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Binary player data format for the new save system.
 * Replaces JSON player data with efficient binary storage.
 * 
 * Binary Format:
 * - Header (32 bytes): Magic number, version, data size, timestamp
 * - Position Data (24 bytes): Position (12) + Velocity (12)
 * - Camera Data (8 bytes): Yaw (4) + Pitch (4)
 * - Player State (16 bytes): Health, flags, game mode, selected slot
 * - Inventory Data (variable): Hotbar + main inventory items
 */
public class BinaryPlayerData {
    
    /** Magic number for player data files */
    private static final int MAGIC_NUMBER = 0x504C5952; // "PLYR" in ASCII
    
    /** Current player data format version */
    private static final int FORMAT_VERSION = 1;
    
    /** Size of fixed header */
    private static final int HEADER_SIZE = 32;
    
    /** Maximum game mode string length */
    private static final int MAX_GAME_MODE_LENGTH = 32;
    
    // Position and physics
    private Vector3f position;
    private Vector3f velocity;
    
    // Camera orientation
    private float cameraYaw;
    private float cameraPitch;
    
    // Player state
    private boolean onGround;
    private boolean flightEnabled;
    private boolean isFlying;
    private float health;
    
    // Inventory data
    private ItemStack[] hotbarItems;
    private ItemStack[] mainInventoryItems;
    private int selectedHotbarSlot;
    
    // Game state
    private String gameMode;
    private long totalPlayTimeMillis;
    private long lastSaved;
    
    /**
     * Create empty player data with defaults.
     */
    public BinaryPlayerData() {
        this.position = new Vector3f(0, 64, 0);
        this.velocity = new Vector3f(0, 0, 0);
        this.cameraYaw = 0.0f;
        this.cameraPitch = 0.0f;
        this.onGround = true;
        this.flightEnabled = false;
        this.isFlying = false;
        this.health = 20.0f;
        this.hotbarItems = new ItemStack[9]; // Standard hotbar size
        this.mainInventoryItems = new ItemStack[27]; // Standard inventory size
        this.selectedHotbarSlot = 0;
        this.gameMode = "SURVIVAL";
        this.totalPlayTimeMillis = 0;
        this.lastSaved = System.currentTimeMillis();
    }
    
    /**
     * Create player data from a Player instance.
     * @param player Player to serialize
     */
    public BinaryPlayerData(Player player) {
        this();
        
        // Position and velocity
        this.position = new Vector3f(player.getPosition());
        this.velocity = new Vector3f(player.getVelocity());
        
        // Camera orientation
        this.cameraYaw = player.getCamera().getYaw();
        this.cameraPitch = player.getCamera().getPitch();
        
        // Player state
        this.onGround = player.isOnGround();
        this.flightEnabled = player.isFlightEnabled();
        this.isFlying = player.isFlying();
        this.health = player.getHealth();
        
        // Inventory
        if (player.getInventory() != null) {
            Inventory inventory = player.getInventory();
            this.selectedHotbarSlot = inventory.getSelectedSlot();
            
            // Copy hotbar items
            for (int i = 0; i < 9 && i < hotbarItems.length; i++) {
                ItemStack item = inventory.getHotbarItem(i);
                this.hotbarItems[i] = item != null ? item.copy() : null;
            }
            
            // Copy main inventory items
            for (int i = 0; i < 27 && i < mainInventoryItems.length; i++) {
                ItemStack item = inventory.getMainInventoryItem(i);
                this.mainInventoryItems[i] = item != null ? item.copy() : null;
            }
        }
        
        this.lastSaved = System.currentTimeMillis();
    }
    
    /**
     * Serialize this player data to binary format.
     * @return Binary representation
     * @throws IOException if serialization fails
     */
    public byte[] serialize() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096); // Initial allocation
        
        // Write header
        buffer.putInt(MAGIC_NUMBER);
        buffer.putInt(FORMAT_VERSION);
        buffer.putLong(lastSaved);
        buffer.putLong(totalPlayTimeMillis);
        buffer.putInt(0); // Data size placeholder
        
        int dataStartPosition = buffer.position();
        
        // Write position data (24 bytes)
        buffer.putFloat(position.x);
        buffer.putFloat(position.y);
        buffer.putFloat(position.z);
        buffer.putFloat(velocity.x);
        buffer.putFloat(velocity.y);
        buffer.putFloat(velocity.z);
        
        // Write camera data (8 bytes)
        buffer.putFloat(cameraYaw);
        buffer.putFloat(cameraPitch);
        
        // Write player state (16 bytes)
        buffer.putFloat(health);
        buffer.putInt(selectedHotbarSlot);
        
        // Pack boolean flags into a single byte
        byte flags = 0;
        if (onGround) flags |= 0x01;
        if (flightEnabled) flags |= 0x02;
        if (isFlying) flags |= 0x04;
        buffer.put(flags);
        
        // Padding to maintain alignment
        buffer.put((byte) 0); // Reserved byte
        buffer.putShort((short) 0); // Reserved short
        
        // Write game mode
        writeString(buffer, gameMode != null ? gameMode : "SURVIVAL");
        
        // Write inventory data
        writeInventory(buffer, hotbarItems, mainInventoryItems);
        
        // Update data size in header
        int dataSize = buffer.position() - dataStartPosition;
        buffer.putInt(20, dataSize); // Position 20 is where data size is stored
        
        // Return trimmed array
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Deserialize player data from binary format.
     * @param data Binary player data
     * @return Deserialized player data
     * @throws IOException if deserialization fails
     */
    public static BinaryPlayerData deserialize(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("Invalid player data: too small");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        // Read and validate header
        int magic = buffer.getInt();
        if (magic != MAGIC_NUMBER) {
            throw new IOException("Invalid player data: bad magic number");
        }
        
        int version = buffer.getInt();
        if (version > FORMAT_VERSION) {
            throw new IOException("Unsupported player data version: " + version);
        }
        
        BinaryPlayerData playerData = new BinaryPlayerData();
        
        // Read header data
        playerData.lastSaved = buffer.getLong();
        playerData.totalPlayTimeMillis = buffer.getLong();
        int dataSize = buffer.getInt();
        
        // Validate data size
        if (buffer.remaining() != dataSize) {
            throw new IOException("Invalid player data: data size mismatch");
        }
        
        // Read position data
        playerData.position = new Vector3f(
            buffer.getFloat(), // x
            buffer.getFloat(), // y
            buffer.getFloat()  // z
        );
        
        playerData.velocity = new Vector3f(
            buffer.getFloat(), // x
            buffer.getFloat(), // y
            buffer.getFloat()  // z
        );
        
        // Read camera data
        playerData.cameraYaw = buffer.getFloat();
        playerData.cameraPitch = buffer.getFloat();
        
        // Read player state
        playerData.health = buffer.getFloat();
        playerData.selectedHotbarSlot = buffer.getInt();
        
        // Read boolean flags
        byte flags = buffer.get();
        playerData.onGround = (flags & 0x01) != 0;
        playerData.flightEnabled = (flags & 0x02) != 0;
        playerData.isFlying = (flags & 0x04) != 0;
        
        // Skip reserved bytes
        buffer.get(); // Reserved byte
        buffer.getShort(); // Reserved short
        
        // Read game mode
        playerData.gameMode = readString(buffer);
        
        // Read inventory data
        readInventory(buffer, playerData);
        
        return playerData;
    }
    
    /**
     * Apply this player data to a Player instance.
     * @param player Player to update
     */
    public void applyToPlayer(Player player) {
        // Position and velocity
        player.setPosition(new Vector3f(position.x, position.y, position.z));
        player.setVelocity(new Vector3f(velocity.x, velocity.y, velocity.z));
        
        // Camera orientation
        player.getCamera().setYaw(cameraYaw);
        player.getCamera().setPitch(cameraPitch);
        
        // Player state
        player.setOnGround(onGround);
        player.setFlightEnabled(flightEnabled);
        player.setFlying(isFlying);
        player.setHealth(health);
        
        // Inventory
        if (player.getInventory() != null) {
            Inventory inventory = player.getInventory();
            inventory.setSelectedSlot(selectedHotbarSlot);
            
            // Set hotbar items
            for (int i = 0; i < hotbarItems.length && i < 9; i++) {
                inventory.setHotbarItem(i, hotbarItems[i]);
            }
            
            // Set main inventory items
            for (int i = 0; i < mainInventoryItems.length && i < 27; i++) {
                inventory.setMainInventoryItem(i, mainInventoryItems[i]);
            }
        }
    }
    
    /**
     * Save player data to a file.
     * @param worldPath World directory path
     * @throws IOException if saving fails
     */
    public void saveToFile(String worldPath) throws IOException {
        Path playerDataPath = Paths.get(worldPath, "player.dat");
        byte[] data = serialize();
        
        // Atomic write using temporary file
        Path tempPath = Paths.get(worldPath, "player.dat.tmp");
        Files.write(tempPath, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tempPath, playerDataPath);
    }
    
    /**
     * Load player data from a file.
     * @param worldPath World directory path
     * @return Loaded player data
     * @throws IOException if loading fails
     */
    public static BinaryPlayerData loadFromFile(String worldPath) throws IOException {
        Path playerDataPath = Paths.get(worldPath, "player.dat");
        if (!Files.exists(playerDataPath)) {
            throw new IOException("Player data not found: " + playerDataPath);
        }
        
        byte[] data = Files.readAllBytes(playerDataPath);
        return deserialize(data);
    }
    
    /**
     * Check if player data exists.
     * @param worldPath World directory path
     * @return True if player data exists
     */
    public static boolean exists(String worldPath) {
        Path playerDataPath = Paths.get(worldPath, "player.dat");
        return Files.exists(playerDataPath);
    }
    
    /**
     * Write inventory data to buffer.
     * @param buffer ByteBuffer to write to
     * @param hotbar Hotbar items
     * @param mainInventory Main inventory items
     */
    private void writeInventory(ByteBuffer buffer, ItemStack[] hotbar, ItemStack[] mainInventory) {
        // Write hotbar
        buffer.putInt(hotbar.length);
        for (ItemStack item : hotbar) {
            if (item != null && !item.isEmpty()) {
                buffer.put((byte) 1); // Has item
                buffer.putInt(item.getItem().getId());
                buffer.putInt(item.getCount());
            } else {
                buffer.put((byte) 0); // No item
            }
        }
        
        // Write main inventory
        buffer.putInt(mainInventory.length);
        for (ItemStack item : mainInventory) {
            if (item != null && !item.isEmpty()) {
                buffer.put((byte) 1); // Has item
                buffer.putInt(item.getItem().getId());
                buffer.putInt(item.getCount());
            } else {
                buffer.put((byte) 0); // No item
            }
        }
    }
    
    /**
     * Read inventory data from buffer.
     * @param buffer ByteBuffer to read from
     * @param playerData Player data to populate
     */
    private static void readInventory(ByteBuffer buffer, BinaryPlayerData playerData) {
        // Read hotbar
        int hotbarSize = buffer.getInt();
        playerData.hotbarItems = new ItemStack[hotbarSize];
        for (int i = 0; i < hotbarSize; i++) {
            byte hasItem = buffer.get();
            if (hasItem != 0) {
                int itemId = buffer.getInt();
                int count = buffer.getInt();
                // Note: Would need to implement ItemStack creation from ID
                // playerData.hotbarItems[i] = new ItemStack(Item.getById(itemId), count);
            }
        }
        
        // Read main inventory
        int mainSize = buffer.getInt();
        playerData.mainInventoryItems = new ItemStack[mainSize];
        for (int i = 0; i < mainSize; i++) {
            byte hasItem = buffer.get();
            if (hasItem != 0) {
                int itemId = buffer.getInt();
                int count = buffer.getInt();
                // Note: Would need to implement ItemStack creation from ID
                // playerData.mainInventoryItems[i] = new ItemStack(Item.getById(itemId), count);
            }
        }
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
    
    public Vector3f getPosition() {
        return new Vector3f(position);
    }
    
    public void setPosition(Vector3f position) {
        this.position.set(position);
    }
    
    public void setPosition(float x, float y, float z) {
        this.position.set(x, y, z);
    }
    
    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }
    
    public void setVelocity(Vector3f velocity) {
        this.velocity.set(velocity);
    }
    
    public void setVelocity(float x, float y, float z) {
        this.velocity.set(x, y, z);
    }
    
    public float getCameraYaw() {
        return cameraYaw;
    }
    
    public void setCameraYaw(float cameraYaw) {
        this.cameraYaw = cameraYaw;
    }
    
    public float getCameraPitch() {
        return cameraPitch;
    }
    
    public void setCameraPitch(float cameraPitch) {
        this.cameraPitch = cameraPitch;
    }
    
    public boolean isOnGround() {
        return onGround;
    }
    
    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }
    
    public boolean isFlightEnabled() {
        return flightEnabled;
    }
    
    public void setFlightEnabled(boolean flightEnabled) {
        this.flightEnabled = flightEnabled;
    }
    
    public boolean isFlying() {
        return isFlying;
    }
    
    public void setFlying(boolean flying) {
        isFlying = flying;
    }
    
    public float getHealth() {
        return health;
    }
    
    public void setHealth(float health) {
        this.health = health;
    }
    
    public int getSelectedHotbarSlot() {
        return selectedHotbarSlot;
    }
    
    public void setSelectedHotbarSlot(int selectedHotbarSlot) {
        this.selectedHotbarSlot = selectedHotbarSlot;
    }
    
    public String getGameMode() {
        return gameMode;
    }
    
    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }
    
    public long getTotalPlayTimeMillis() {
        return totalPlayTimeMillis;
    }
    
    public void setTotalPlayTimeMillis(long totalPlayTimeMillis) {
        this.totalPlayTimeMillis = totalPlayTimeMillis;
    }
    
    public long getLastSaved() {
        return lastSaved;
    }
    
    public void setLastSaved(long lastSaved) {
        this.lastSaved = lastSaved;
    }
    
    @Override
    public String toString() {
        return String.format(
            "PlayerData[pos=(%.1f,%.1f,%.1f), health=%.1f, gameMode=%s, played=%dms]",
            position.x, position.y, position.z, health, gameMode, totalPlayTimeMillis
        );
    }
}