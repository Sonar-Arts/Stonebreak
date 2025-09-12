package com.stonebreak.textures.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

/**
 * Handles automatic migration of JSON schema versions from v1.0 to v1.1
 */
public class SchemaMigrator {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    static {
        // Configure ObjectMapper for pretty printing
        objectMapper.writerWithDefaultPrettyPrinter();
    }
    
    /**
     * Migration result containing success status and details
     */
    public static class MigrationResult {
        private final boolean success;
        private final String message;
        private final String backupPath;
        
        public MigrationResult(boolean success, String message, String backupPath) {
            this.success = success;
            this.message = message;
            this.backupPath = backupPath;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getBackupPath() { return backupPath; }
    }
    
    /**
     * Migrates Block_ids.JSON from v1.0 to v1.1 format
     */
    public static MigrationResult migrateBlockIds(File blockIdsFile) {
        try {
            // Create backup
            String backupPath = createBackup(blockIdsFile);
            
            // Read current file
            JsonNode root = objectMapper.readTree(blockIdsFile);
            
            // Detect current version
            String currentVersion = detectSchemaVersion(root);
            
            if (!JSONSchemaValidator.LEGACY_SCHEMA_VERSION.equals(currentVersion)) {
                return new MigrationResult(false, 
                    "File is already in version " + currentVersion + ", migration not needed", backupPath);
            }
            
            // Perform migration
            ObjectNode migratedRoot = objectMapper.createObjectNode();
            migratedRoot.put("schemaVersion", JSONSchemaValidator.CURRENT_SCHEMA_VERSION);
            
            JsonNode blockIds = root.get("blockIds");
            ObjectNode newBlockIds = objectMapper.createObjectNode();
            
            Iterator<String> fieldNames = blockIds.fieldNames();
            while (fieldNames.hasNext()) {
                String blockName = fieldNames.next();
                int blockId = blockIds.get(blockName).asInt();
                
                ObjectNode blockDef = objectMapper.createObjectNode();
                blockDef.put("id", blockId);
                
                // Determine texture type and mapping based on block name
                String textureType = determineBlockTextureType(blockName);
                blockDef.put("textureType", textureType);
                
                if ("cube_net".equals(textureType)) {
                    blockDef.put("cubeNetTexture", determineCubeNetTexture(blockName));
                } else if ("cube_cross".equals(textureType)) {
                    ObjectNode faces = createFaceMapping(blockName);
                    blockDef.set("faces", faces);
                } else {
                    blockDef.put("texture", determineUniformTexture(blockName));
                }
                
                newBlockIds.set(blockName, blockDef);
            }
            
            migratedRoot.set("blockIds", newBlockIds);
            
            // Write migrated file
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(blockIdsFile, migratedRoot);
            
            return new MigrationResult(true, 
                "Successfully migrated Block_ids.JSON from v1.0 to v1.1", backupPath);
            
        } catch (Exception e) {
            return new MigrationResult(false, 
                "Migration failed: " + e.getMessage(), null);
        }
    }
    
    /**
     * Migrates Item_ids.JSON from v1.0 to v1.1 format
     */
    public static MigrationResult migrateItemIds(File itemIdsFile) {
        try {
            // Create backup
            String backupPath = createBackup(itemIdsFile);
            
            // Read current file
            JsonNode root = objectMapper.readTree(itemIdsFile);
            
            // Detect current version
            String currentVersion = detectSchemaVersion(root);
            
            if (!JSONSchemaValidator.LEGACY_SCHEMA_VERSION.equals(currentVersion)) {
                return new MigrationResult(false, 
                    "File is already in version " + currentVersion + ", migration not needed", backupPath);
            }
            
            // Perform migration
            ObjectNode migratedRoot = objectMapper.createObjectNode();
            migratedRoot.put("schemaVersion", JSONSchemaValidator.CURRENT_SCHEMA_VERSION);
            
            JsonNode itemIds = root.get("itemIds");
            ObjectNode newItemIds = objectMapper.createObjectNode();
            
            Iterator<String> fieldNames = itemIds.fieldNames();
            while (fieldNames.hasNext()) {
                String itemName = fieldNames.next();
                int oldItemId = itemIds.get(itemName).asInt();
                
                ObjectNode itemDef = objectMapper.createObjectNode();
                // Offset item IDs to 1000+ range
                itemDef.put("id", 1000 + oldItemId);
                itemDef.put("legacyId", oldItemId);
                itemDef.put("textureType", "flat");
                itemDef.put("texture", determineItemTexture(itemName));
                
                newItemIds.set(itemName, itemDef);
            }
            
            migratedRoot.set("itemIds", newItemIds);
            
            // Write migrated file
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(itemIdsFile, migratedRoot);
            
            return new MigrationResult(true, 
                "Successfully migrated Item_ids.JSON from v1.0 to v1.1", backupPath);
            
        } catch (Exception e) {
            return new MigrationResult(false, 
                "Migration failed: " + e.getMessage(), null);
        }
    }
    
    /**
     * Creates a timestamped backup of the original file
     */
    private static String createBackup(File originalFile) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupFileName = originalFile.getName().replace(".JSON", "_backup_" + timestamp + ".JSON");
        File backupFile = new File(originalFile.getParent(), backupFileName);
        
        Files.copy(originalFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        
        return backupFile.getAbsolutePath();
    }
    
    /**
     * Detects schema version from JSON structure
     */
    private static String detectSchemaVersion(JsonNode root) {
        if (root.has("schemaVersion")) {
            return root.get("schemaVersion").asText();
        }
        
        return JSONSchemaValidator.LEGACY_SCHEMA_VERSION;
    }
    
    /**
     * Determines appropriate texture type for a block based on its name
     */
    private static String determineBlockTextureType(String blockName) {
        // Extract just the block type name (remove namespace)
        String blockType = blockName.substring(blockName.indexOf(':') + 1);
        
        // Blocks that use cube net format (64x48 cube cross layout in single file)
        if (blockType.equals("grass") || blockType.equals("workbench") || 
            blockType.contains("wood") || blockType.contains("log")) {
            return "cube_net";
        }
        
        // All other blocks use uniform texture
        return "uniform";
    }
    
    /**
     * Determines cube net texture name for cube_net texture type blocks
     */
    private static String determineCubeNetTexture(String blockName) {
        String blockType = blockName.substring(blockName.indexOf(':') + 1);
        
        // Handle special cases
        switch (blockType) {
            case "workbench":
                return "workbench_custom_texture";
            default:
                return blockType + "_texture";
        }
    }
    
    /**
     * Creates face mapping for cube_cross texture type blocks
     */
    private static ObjectNode createFaceMapping(String blockName) {
        String blockType = blockName.substring(blockName.indexOf(':') + 1);
        ObjectNode faces = objectMapper.createObjectNode();
        
        switch (blockType) {
            case "grass":
                faces.put("top", "grass_block_texture_top");
                faces.put("bottom", "dirt_block_texture");
                faces.put("sides", "grass_block_texture_side");
                break;
            case "workbench":
                faces.put("top", "workbench_custom_texture_top");
                faces.put("bottom", "wood_planks_custom_texture");
                faces.put("sides", "workbench_custom_texture_side");
                break;
            case "wood":
                faces.put("top", "wood_texture_top");
                faces.put("bottom", "wood_texture_top");
                faces.put("sides", "wood_texture_side");
                break;
            case "pine_wood":
                faces.put("top", "pine_wood_texture_top");
                faces.put("bottom", "pine_wood_texture_top");
                faces.put("sides", "pine_wood_texture_side");
                break;
            case "elm_wood_log":
                faces.put("top", "elm_wood_log_texture_top");
                faces.put("bottom", "elm_wood_log_texture_top");
                faces.put("sides", "elm_wood_log_texture_side");
                break;
            default:
                // Default to uniform mapping
                String textureName = blockType.replace("_", "_") + "_texture";
                faces.put("top", textureName);
                faces.put("bottom", textureName);
                faces.put("sides", textureName);
                break;
        }
        
        return faces;
    }
    
    /**
     * Determines texture name for uniform texture type blocks
     */
    private static String determineUniformTexture(String blockName) {
        String blockType = blockName.substring(blockName.indexOf(':') + 1);
        
        // Handle special cases
        switch (blockType) {
            case "wood_planks":
                return "wood_planks_custom_texture";
            case "pine_wood_planks":
                return "pine_wood_planks_custom_texture";
            case "elm_wood_planks":
                return "elm_wood_planks_custom_texture";
            case "water":
                return "water_temp_texture";
            default:
                return blockType + "_texture";
        }
    }
    
    /**
     * Determines texture name for items
     */
    private static String determineItemTexture(String itemName) {
        String itemType = itemName.substring(itemName.indexOf(':') + 1);
        return itemType + "_texture";
    }
    
    /**
     * Rolls back migration by restoring from backup
     */
    public static boolean rollbackMigration(String backupPath, File targetFile) {
        try {
            File backupFile = new File(backupPath);
            if (!backupFile.exists()) {
                return false;
            }
            
            Files.copy(backupFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
            
        } catch (IOException e) {
            return false;
        }
    }
}