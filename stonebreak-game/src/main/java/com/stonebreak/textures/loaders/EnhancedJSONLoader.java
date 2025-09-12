package com.stonebreak.textures.loaders;

import com.stonebreak.textures.validation.JSONSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Loads and parses the enhanced JSON format for blocks and items
 */
public class EnhancedJSONLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Represents a block definition from the enhanced JSON format
     */
    public static class BlockDefinition {
        private final int id;
        private final String textureType;
        private final String uniformTexture;
        private final String cubeNetTexture;
        private final Map<String, String> faces;
        
        public BlockDefinition(int id, String textureType, String uniformTexture, String cubeNetTexture, Map<String, String> faces) {
            this.id = id;
            this.textureType = textureType;
            this.uniformTexture = uniformTexture;
            this.cubeNetTexture = cubeNetTexture;
            this.faces = faces != null ? faces : new HashMap<>();
        }
        
        public int getId() { return id; }
        public String getTextureType() { return textureType; }
        public String getUniformTexture() { return uniformTexture; }
        public String getCubeNetTexture() { return cubeNetTexture; }
        public Map<String, String> getFaces() { return faces; }
        
        public boolean isCubeNet() { return "cube_net".equals(textureType); }
        public boolean isCubeCross() { return "cube_cross".equals(textureType); }
        public boolean isUniform() { return "uniform".equals(textureType); }
        
        public String getTopTexture() { return faces.get("top"); }
        public String getBottomTexture() { return faces.get("bottom"); }
        public String getSideTexture() { return faces.get("sides"); }
    }
    
    /**
     * Represents an item definition from the enhanced JSON format
     */
    public static class ItemDefinition {
        private final int id;
        private final int legacyId;
        private final String textureType;
        private final String texture;
        
        public ItemDefinition(int id, int legacyId, String textureType, String texture) {
            this.id = id;
            this.legacyId = legacyId;
            this.textureType = textureType;
            this.texture = texture;
        }
        
        public int getId() { return id; }
        public int getLegacyId() { return legacyId; }
        public String getTextureType() { return textureType; }
        public String getTexture() { return texture; }
    }
    
    /**
     * Loads block definitions from enhanced Block_ids.JSON
     */
    public static Map<String, BlockDefinition> loadBlockDefinitions(File blockIdsFile) throws IOException {
        JsonNode root = objectMapper.readTree(blockIdsFile);
        JsonNode blockIds = root.get("blockIds");
        
        Map<String, BlockDefinition> blocks = new HashMap<>();
        
        Iterator<String> fieldNames = blockIds.fieldNames();
        while (fieldNames.hasNext()) {
            String blockName = fieldNames.next();
            JsonNode blockDef = blockIds.get(blockName);
            
            int id = blockDef.get("id").asInt();
            String textureType = blockDef.get("textureType").asText();
            
            String uniformTexture = null;
            String cubeNetTexture = null;
            Map<String, String> faces = null;
            
            if ("uniform".equals(textureType)) {
                uniformTexture = blockDef.get("texture").asText();
            } else if ("cube_net".equals(textureType)) {
                cubeNetTexture = blockDef.get("cubeNetTexture").asText();
            } else if ("cube_cross".equals(textureType)) {
                faces = new HashMap<>();
                JsonNode facesObj = blockDef.get("faces");
                Iterator<String> faceNames = facesObj.fieldNames();
                while (faceNames.hasNext()) {
                    String faceName = faceNames.next();
                    faces.put(faceName, facesObj.get(faceName).asText());
                }
            }
            
            blocks.put(blockName, new BlockDefinition(id, textureType, uniformTexture, cubeNetTexture, faces));
        }
        
        return blocks;
    }
    
    /**
     * Loads item definitions from enhanced Item_ids.JSON
     */
    public static Map<String, ItemDefinition> loadItemDefinitions(File itemIdsFile) throws IOException {
        JsonNode root = objectMapper.readTree(itemIdsFile);
        JsonNode itemIds = root.get("itemIds");
        
        Map<String, ItemDefinition> items = new HashMap<>();
        
        Iterator<String> fieldNames = itemIds.fieldNames();
        while (fieldNames.hasNext()) {
            String itemName = fieldNames.next();
            JsonNode itemDef = itemIds.get(itemName);
            
            int id = itemDef.get("id").asInt();
            int legacyId = itemDef.has("legacyId") ? itemDef.get("legacyId").asInt() : -1;
            String textureType = itemDef.get("textureType").asText();
            String texture = itemDef.get("texture").asText();
            
            items.put(itemName, new ItemDefinition(id, legacyId, textureType, texture));
        }
        
        return items;
    }
    
    /**
     * Gets schema version from JSON file
     */
    public static String getSchemaVersion(File jsonFile) throws IOException {
        JsonNode root = objectMapper.readTree(jsonFile);
        
        if (root.has("schemaVersion")) {
            return root.get("schemaVersion").asText();
        }
        
        return JSONSchemaValidator.LEGACY_SCHEMA_VERSION;
    }
    
    /**
     * Creates a lookup map from block ID to block name
     */
    public static Map<Integer, String> createBlockIdLookup(Map<String, BlockDefinition> blocks) {
        Map<Integer, String> lookup = new HashMap<>();
        
        for (Map.Entry<String, BlockDefinition> entry : blocks.entrySet()) {
            lookup.put(entry.getValue().getId(), entry.getKey());
        }
        
        return lookup;
    }
    
    /**
     * Creates a lookup map from item ID to item name
     */
    public static Map<Integer, String> createItemIdLookup(Map<String, ItemDefinition> items) {
        Map<Integer, String> lookup = new HashMap<>();
        
        for (Map.Entry<String, ItemDefinition> entry : items.entrySet()) {
            lookup.put(entry.getValue().getId(), entry.getKey());
        }
        
        return lookup;
    }
    
    /**
     * Gets all texture names referenced in block definitions
     */
    public static Set<String> getAllBlockTextures(Map<String, BlockDefinition> blocks) {
        Set<String> textures = new HashSet<>();
        
        for (BlockDefinition block : blocks.values()) {
            if (block.isUniform()) {
                textures.add(block.getUniformTexture());
            } else if (block.isCubeNet()) {
                textures.add(block.getCubeNetTexture());
            } else if (block.isCubeCross()) {
                textures.addAll(block.getFaces().values());
            }
        }
        
        return textures;
    }
    
    /**
     * Gets all texture names referenced in item definitions
     */
    public static Set<String> getAllItemTextures(Map<String, ItemDefinition> items) {
        Set<String> textures = new HashSet<>();
        
        for (ItemDefinition item : items.values()) {
            textures.add(item.getTexture());
        }
        
        return textures;
    }
}