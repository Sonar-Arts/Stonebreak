package com.stonebreak.textures;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class CowTextureDefinition {
    
    @JsonProperty("cowVariants")
    private Map<String, CowVariant> cowVariants;
    
    @JsonProperty("textureAtlas")
    private TextureAtlas textureAtlas;
    
    public Map<String, CowVariant> getCowVariants() {
        return cowVariants;
    }
    
    public void setCowVariants(Map<String, CowVariant> cowVariants) {
        this.cowVariants = cowVariants;
    }
    
    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
    
    public void setTextureAtlas(TextureAtlas textureAtlas) {
        this.textureAtlas = textureAtlas;
    }
    
    public static class CowVariant {
        @JsonProperty("displayName")
        private String displayName;
        
        @JsonProperty("faceMappings")
        private Map<String, AtlasCoordinate> faceMappings;
        
        @JsonProperty("baseColors")
        private BaseColors baseColors;
        
        public String getDisplayName() {
            return displayName;
        }
        
        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
        
        public Map<String, AtlasCoordinate> getFaceMappings() {
            return faceMappings;
        }
        
        public void setFaceMappings(Map<String, AtlasCoordinate> faceMappings) {
            this.faceMappings = faceMappings;
        }
        
        public BaseColors getBaseColors() {
            return baseColors;
        }
        
        public void setBaseColors(BaseColors baseColors) {
            this.baseColors = baseColors;
        }
    }
    
    public static class BaseColors {
        @JsonProperty("primary")
        private String primary;
        
        @JsonProperty("secondary")
        private String secondary;
        
        @JsonProperty("accent")
        private String accent;
        
        public String getPrimary() {
            return primary;
        }
        
        public void setPrimary(String primary) {
            this.primary = primary;
        }
        
        public String getSecondary() {
            return secondary;
        }
        
        public void setSecondary(String secondary) {
            this.secondary = secondary;
        }
        
        public String getAccent() {
            return accent;
        }
        
        public void setAccent(String accent) {
            this.accent = accent;
        }
    }
    
    public static class AtlasCoordinate {
        @JsonProperty("atlasX")
        private int atlasX;
        
        @JsonProperty("atlasY")
        private int atlasY;
        
        public int getAtlasX() {
            return atlasX;
        }
        
        public void setAtlasX(int atlasX) {
            this.atlasX = atlasX;
        }
        
        public int getAtlasY() {
            return atlasY;
        }
        
        public void setAtlasY(int atlasY) {
            this.atlasY = atlasY;
        }
    }
    
    public static class TextureAtlas {
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height")
        private int height;
        
        @JsonProperty("gridSize")
        private int gridSize;
        
        @JsonProperty("file")
        private String file;
        
        public int getWidth() {
            return width;
        }
        
        public void setWidth(int width) {
            this.width = width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public void setHeight(int height) {
            this.height = height;
        }
        
        public int getGridSize() {
            return gridSize;
        }
        
        public void setGridSize(int gridSize) {
            this.gridSize = gridSize;
        }
        
        public String getFile() {
            return file;
        }
        
        public void setFile(String file) {
            this.file = file;
        }
    }
}