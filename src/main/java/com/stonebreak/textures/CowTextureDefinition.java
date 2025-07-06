package com.stonebreak.textures;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
        @JsonProperty("baseColors")
        private BaseColors baseColors;
        
        @JsonProperty("bodyParts")
        private Map<String, BodyPart> bodyParts;
        
        public BaseColors getBaseColors() {
            return baseColors;
        }
        
        public void setBaseColors(BaseColors baseColors) {
            this.baseColors = baseColors;
        }
        
        public Map<String, BodyPart> getBodyParts() {
            return bodyParts;
        }
        
        public void setBodyParts(Map<String, BodyPart> bodyParts) {
            this.bodyParts = bodyParts;
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
    
    public static class BodyPart {
        @JsonProperty("uvMapping")
        private Map<String, UVCoordinate> uvMapping;
        
        @JsonProperty("patterns")
        private List<Pattern> patterns;
        
        public Map<String, UVCoordinate> getUvMapping() {
            return uvMapping;
        }
        
        public void setUvMapping(Map<String, UVCoordinate> uvMapping) {
            this.uvMapping = uvMapping;
        }
        
        public List<Pattern> getPatterns() {
            return patterns;
        }
        
        public void setPatterns(List<Pattern> patterns) {
            this.patterns = patterns;
        }
    }
    
    public static class UVCoordinate {
        @JsonProperty("u")
        private int u;
        
        @JsonProperty("v")
        private int v;
        
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height")
        private int height;
        
        public int getU() {
            return u;
        }
        
        public void setU(int u) {
            this.u = u;
        }
        
        public int getV() {
            return v;
        }
        
        public void setV(int v) {
            this.v = v;
        }
        
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
    }
    
    public static class Pattern {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("density")
        private double density;
        
        @JsonProperty("size")
        private int size;
        
        @JsonProperty("color")
        private String color;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public double getDensity() {
            return density;
        }
        
        public void setDensity(double density) {
            this.density = density;
        }
        
        public int getSize() {
            return size;
        }
        
        public void setSize(int size) {
            this.size = size;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class TextureAtlas {
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height")
        private int height;
        
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
        
        public String getFile() {
            return file;
        }
        
        public void setFile(String file) {
            this.file = file;
        }
    }
}