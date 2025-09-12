package com.stonebreak.textures.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Validates JSON schema versions and structure for block and item ID files
 */
public class JSONSchemaValidator {
    
    public static final String CURRENT_SCHEMA_VERSION = "1.1.0";
    public static final String LEGACY_SCHEMA_VERSION = "1.0.0";
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Schema validation result containing version and validity information
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String schemaVersion;
        private final String errorMessage;
        private final boolean needsMigration;
        
        public ValidationResult(boolean valid, String schemaVersion, String errorMessage, boolean needsMigration) {
            this.valid = valid;
            this.schemaVersion = schemaVersion;
            this.errorMessage = errorMessage;
            this.needsMigration = needsMigration;
        }
        
        public boolean isValid() { return valid; }
        public String getSchemaVersion() { return schemaVersion; }
        public String getErrorMessage() { return errorMessage; }
        public boolean needsMigration() { return needsMigration; }
    }
    
    /**
     * Validates Block_ids.JSON file structure and schema version
     */
    public static ValidationResult validateBlockIds(File blockIdsFile) {
        try {
            JsonNode root = objectMapper.readTree(blockIdsFile);
            
            // Detect schema version
            String schemaVersion = detectSchemaVersion(root);
            
            if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
                return validateLegacyBlockIds(root, schemaVersion);
            } else if (CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
                return validateEnhancedBlockIds(root, schemaVersion);
            } else {
                return new ValidationResult(false, schemaVersion, 
                    "Unsupported schema version: " + schemaVersion, false);
            }
            
        } catch (IOException e) {
            return new ValidationResult(false, null, "Failed to read file: " + e.getMessage(), false);
        }
    }
    
    /**
     * Validates Item_ids.JSON file structure and schema version
     */
    public static ValidationResult validateItemIds(File itemIdsFile) {
        try {
            JsonNode root = objectMapper.readTree(itemIdsFile);
            
            // Detect schema version
            String schemaVersion = detectSchemaVersion(root);
            
            if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
                return validateLegacyItemIds(root, schemaVersion);
            } else if (CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
                return validateEnhancedItemIds(root, schemaVersion);
            } else {
                return new ValidationResult(false, schemaVersion, 
                    "Unsupported schema version: " + schemaVersion, false);
            }
            
        } catch (IOException e) {
            return new ValidationResult(false, null, "Failed to read file: " + e.getMessage(), false);
        }
    }
    
    /**
     * Detects schema version from JSON structure
     */
    private static String detectSchemaVersion(JsonNode root) {
        if (root.has("schemaVersion")) {
            return root.get("schemaVersion").asText();
        }
        
        // Legacy format detection - if blockIds/itemIds contains simple string->number mapping
        if (root.has("blockIds")) {
            JsonNode blockIds = root.get("blockIds");
            if (blockIds.size() > 0) {
                JsonNode first = blockIds.elements().next();
                if (first.isNumber()) {
                    return LEGACY_SCHEMA_VERSION;
                }
            }
        }
        
        if (root.has("itemIds")) {
            JsonNode itemIds = root.get("itemIds");
            if (itemIds.size() > 0) {
                JsonNode first = itemIds.elements().next();
                if (first.isNumber()) {
                    return LEGACY_SCHEMA_VERSION;
                }
            }
        }
        
        return CURRENT_SCHEMA_VERSION;
    }
    
    /**
     * Validates legacy format Block_ids.JSON (v1.0)
     */
    private static ValidationResult validateLegacyBlockIds(JsonNode root, String schemaVersion) {
        if (!root.has("blockIds")) {
            return new ValidationResult(false, schemaVersion, "Missing required 'blockIds' field", true);
        }
        
        JsonNode blockIds = root.get("blockIds");
        Set<Integer> usedIds = new HashSet<>();
        
        Iterator<String> fieldNames = blockIds.fieldNames();
        while (fieldNames.hasNext()) {
            String blockName = fieldNames.next();
            JsonNode blockValue = blockIds.get(blockName);
            
            if (!blockValue.isNumber()) {
                return new ValidationResult(false, schemaVersion, 
                    "Block '" + blockName + "' must have numeric ID in legacy format", true);
            }
            
            int blockId = blockValue.asInt();
            
            if (usedIds.contains(blockId)) {
                return new ValidationResult(false, schemaVersion, 
                    "Duplicate block ID: " + blockId, true);
            }
            
            usedIds.add(blockId);
        }
        
        return new ValidationResult(true, schemaVersion, null, true);
    }
    
    /**
     * Validates legacy format Item_ids.JSON (v1.0)
     */
    private static ValidationResult validateLegacyItemIds(JsonNode root, String schemaVersion) {
        if (!root.has("itemIds")) {
            return new ValidationResult(false, schemaVersion, "Missing required 'itemIds' field", true);
        }
        
        JsonNode itemIds = root.get("itemIds");
        Set<Integer> usedIds = new HashSet<>();
        
        Iterator<String> fieldNames = itemIds.fieldNames();
        while (fieldNames.hasNext()) {
            String itemName = fieldNames.next();
            JsonNode itemValue = itemIds.get(itemName);
            
            if (!itemValue.isNumber()) {
                return new ValidationResult(false, schemaVersion, 
                    "Item '" + itemName + "' must have numeric ID in legacy format", true);
            }
            
            int itemId = itemValue.asInt();
            
            if (usedIds.contains(itemId)) {
                return new ValidationResult(false, schemaVersion, 
                    "Duplicate item ID: " + itemId, true);
            }
            
            usedIds.add(itemId);
        }
        
        return new ValidationResult(true, schemaVersion, null, true);
    }
    
    /**
     * Validates enhanced format Block_ids.JSON (v1.1)
     */
    private static ValidationResult validateEnhancedBlockIds(JsonNode root, String schemaVersion) {
        if (!root.has("blockIds")) {
            return new ValidationResult(false, schemaVersion, "Missing required 'blockIds' field", false);
        }
        
        JsonNode blockIds = root.get("blockIds");
        Set<Integer> usedIds = new HashSet<>();
        
        Iterator<String> fieldNames = blockIds.fieldNames();
        while (fieldNames.hasNext()) {
            String blockName = fieldNames.next();
            JsonNode blockDef = blockIds.get(blockName);
            
            if (!blockDef.isObject()) {
                return new ValidationResult(false, schemaVersion, 
                    "Block '" + blockName + "' must be an object in enhanced format", false);
            }
            
            // Validate required fields
            if (!blockDef.has("id")) {
                return new ValidationResult(false, schemaVersion, 
                    "Block '" + blockName + "' missing required 'id' field", false);
            }
            
            if (!blockDef.has("textureType")) {
                return new ValidationResult(false, schemaVersion, 
                    "Block '" + blockName + "' missing required 'textureType' field", false);
            }
            
            int blockId = blockDef.get("id").asInt();
            String textureType = blockDef.get("textureType").asText();
            
            if (usedIds.contains(blockId)) {
                return new ValidationResult(false, schemaVersion, 
                    "Duplicate block ID: " + blockId, false);
            }
            
            if (blockId < 0 || blockId >= 1000) {
                return new ValidationResult(false, schemaVersion, 
                    "Block ID " + blockId + " outside valid range (0-999)", false);
            }
            
            // Validate texture type specific fields
            if ("cube_net".equals(textureType)) {
                if (!blockDef.has("cubeNetTexture")) {
                    return new ValidationResult(false, schemaVersion, 
                        "Block '" + blockName + "' with cube_net textureType must have 'cubeNetTexture' field", false);
                }
            } else if ("cube_cross".equals(textureType)) {
                if (!blockDef.has("faces")) {
                    return new ValidationResult(false, schemaVersion, 
                        "Block '" + blockName + "' with cube_cross textureType must have 'faces' field", false);
                }
            } else if ("uniform".equals(textureType)) {
                if (!blockDef.has("texture")) {
                    return new ValidationResult(false, schemaVersion, 
                        "Block '" + blockName + "' with uniform textureType must have 'texture' field", false);
                }
            } else {
                return new ValidationResult(false, schemaVersion, 
                    "Invalid textureType '" + textureType + "' for block '" + blockName + "' (must be 'uniform', 'cube_net', or 'cube_cross')", false);
            }
            
            usedIds.add(blockId);
        }
        
        return new ValidationResult(true, schemaVersion, null, false);
    }
    
    /**
     * Validates enhanced format Item_ids.JSON (v1.1)
     */
    private static ValidationResult validateEnhancedItemIds(JsonNode root, String schemaVersion) {
        if (!root.has("itemIds")) {
            return new ValidationResult(false, schemaVersion, "Missing required 'itemIds' field", false);
        }
        
        JsonNode itemIds = root.get("itemIds");
        Set<Integer> usedIds = new HashSet<>();
        
        Iterator<String> fieldNames = itemIds.fieldNames();
        while (fieldNames.hasNext()) {
            String itemName = fieldNames.next();
            JsonNode itemDef = itemIds.get(itemName);
            
            if (!itemDef.isObject()) {
                return new ValidationResult(false, schemaVersion, 
                    "Item '" + itemName + "' must be an object in enhanced format", false);
            }
            
            // Validate required fields
            if (!itemDef.has("id")) {
                return new ValidationResult(false, schemaVersion, 
                    "Item '" + itemName + "' missing required 'id' field", false);
            }
            
            if (!itemDef.has("textureType")) {
                return new ValidationResult(false, schemaVersion, 
                    "Item '" + itemName + "' missing required 'textureType' field", false);
            }
            
            if (!itemDef.has("texture")) {
                return new ValidationResult(false, schemaVersion, 
                    "Item '" + itemName + "' missing required 'texture' field", false);
            }
            
            int itemId = itemDef.get("id").asInt();
            String textureType = itemDef.get("textureType").asText();
            
            if (usedIds.contains(itemId)) {
                return new ValidationResult(false, schemaVersion, 
                    "Duplicate item ID: " + itemId, false);
            }
            
            if (itemId < 1000) {
                return new ValidationResult(false, schemaVersion, 
                    "Item ID " + itemId + " must be >= 1000 (reserved range for items)", false);
            }
            
            if (!"flat".equals(textureType)) {
                return new ValidationResult(false, schemaVersion, 
                    "Invalid textureType '" + textureType + "' for item '" + itemName + "' (must be 'flat')", false);
            }
            
            usedIds.add(itemId);
        }
        
        return new ValidationResult(true, schemaVersion, null, false);
    }
    
    /**
     * Validates that block and item IDs don't conflict
     */
    public static ValidationResult validateIdConflicts(File blockIdsFile, File itemIdsFile) {
        try {
            Set<Integer> blockIds = extractBlockIds(blockIdsFile);
            Set<Integer> itemIds = extractItemIds(itemIdsFile);
            
            Set<Integer> conflicts = new HashSet<>(blockIds);
            conflicts.retainAll(itemIds);
            
            if (!conflicts.isEmpty()) {
                return new ValidationResult(false, null, 
                    "ID conflicts between blocks and items: " + conflicts, false);
            }
            
            return new ValidationResult(true, null, null, false);
            
        } catch (Exception e) {
            return new ValidationResult(false, null, 
                "Error checking ID conflicts: " + e.getMessage(), false);
        }
    }
    
    private static Set<Integer> extractBlockIds(File blockIdsFile) throws IOException {
        JsonNode root = objectMapper.readTree(blockIdsFile);
        JsonNode blockIds = root.get("blockIds");
        Set<Integer> ids = new HashSet<>();
        
        Iterator<String> fieldNames = blockIds.fieldNames();
        while (fieldNames.hasNext()) {
            String blockName = fieldNames.next();
            JsonNode blockValue = blockIds.get(blockName);
            
            if (blockValue.isNumber()) {
                // Legacy format
                ids.add(blockValue.asInt());
            } else {
                // Enhanced format
                ids.add(blockValue.get("id").asInt());
            }
        }
        
        return ids;
    }
    
    private static Set<Integer> extractItemIds(File itemIdsFile) throws IOException {
        JsonNode root = objectMapper.readTree(itemIdsFile);
        JsonNode itemIds = root.get("itemIds");
        Set<Integer> ids = new HashSet<>();
        
        Iterator<String> fieldNames = itemIds.fieldNames();
        while (fieldNames.hasNext()) {
            String itemName = fieldNames.next();
            JsonNode itemValue = itemIds.get(itemName);
            
            if (itemValue.isNumber()) {
                // Legacy format
                ids.add(itemValue.asInt());
            } else {
                // Enhanced format
                ids.add(itemValue.get("id").asInt());
            }
        }
        
        return ids;
    }
}