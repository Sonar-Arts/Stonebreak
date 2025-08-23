package com.stonebreak.mobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob structure definition and loader for organized asset management.
 */
public class MobStructure {
    
    public static class MobDirectories {
        private String directory;
        
        public String getDirectory() {
            return directory;
        }
        
        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }
    
    public static class MobStructureDefinition {
        private String name;
        private String id;
        private MobDirectories model;
        private MobDirectories textures;
        private MobDirectories sounds;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public MobDirectories getModel() { return model; }
        public void setModel(MobDirectories model) { this.model = model; }
        
        public MobDirectories getTextures() { return textures; }
        public void setTextures(MobDirectories textures) { this.textures = textures; }
        
        public MobDirectories getSounds() { return sounds; }
        public void setSounds(MobDirectories sounds) { this.sounds = sounds; }
    }
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, MobStructureDefinition> cachedStructures = new ConcurrentHashMap<>();
    
    /**
     * Load mob structure definition for a specific mob type.
     */
    public static MobStructureDefinition getMobStructure(String mobType) {
        // Check cache first
        MobStructureDefinition cached = cachedStructures.get(mobType);
        if (cached != null) {
            return cached;
        }
        
        // Load from JSON file
        String filePath = "mobs/" + mobType + "/Mob_structure.JSON";
        
        try (InputStream inputStream = MobStructure.class.getClassLoader().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                System.err.println("[MobStructure] Could not find mob structure file: " + filePath);
                return null;
            }
            
            MobStructureDefinition structure = objectMapper.readValue(inputStream, MobStructureDefinition.class);
            cachedStructures.put(mobType, structure);
            
            return structure;
            
        } catch (IOException e) {
            System.err.println("[MobStructure] Failed to load mob structure for " + mobType + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get texture directory path for a specific mob type.
     */
    public static String getTextureDirectory(String mobType) {
        MobStructureDefinition structure = getMobStructure(mobType);
        if (structure != null && structure.getTextures() != null) {
            return structure.getTextures().getDirectory();
        }
        return "mobs/" + mobType + "/Textures/"; // fallback
    }
    
    /**
     * Get model directory path for a specific mob type.
     */
    public static String getModelDirectory(String mobType) {
        MobStructureDefinition structure = getMobStructure(mobType);
        if (structure != null && structure.getModel() != null) {
            return structure.getModel().getDirectory();
        }
        return "mobs/" + mobType + "/Models/"; // fallback
    }
    
    /**
     * Get sounds directory path for a specific mob type.
     */
    public static String getSoundsDirectory(String mobType) {
        MobStructureDefinition structure = getMobStructure(mobType);
        if (structure != null && structure.getSounds() != null) {
            return structure.getSounds().getDirectory();
        }
        return "mobs/" + mobType + "/Sounds/"; // fallback
    }
    
    /**
     * Clear cache (useful for development/testing).
     */
    public static void clearCache() {
        cachedStructures.clear();
    }
}