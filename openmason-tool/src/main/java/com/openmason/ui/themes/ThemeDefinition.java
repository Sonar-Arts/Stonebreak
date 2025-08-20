package com.openmason.ui.themes;

import imgui.ImVec4;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Theme definition class extracted from ThemeManager inner class.
 * Contains theme data structure, validation, and manipulation methods.
 * Estimated size: ~200 lines (extracted from lines 91-186 of ThemeManager)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThemeDefinition {
    private static final Logger logger = LoggerFactory.getLogger(ThemeDefinition.class);
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name") 
    private String name;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("type")
    private ThemeType type;
    
    @JsonProperty("colors")
    private final Map<Integer, ImVec4> colors = new ConcurrentHashMap<>();
    
    @JsonProperty("style_vars")
    private final Map<Integer, Float> styleVars = new ConcurrentHashMap<>();
    
    @JsonProperty("read_only")
    private boolean readOnly;
    
    /**
     * Theme types
     */
    public enum ThemeType {
        BUILT_IN("Built-in"),
        USER_CUSTOM("Custom"),
        IMPORTED("Imported");
        
        private final String displayName;
        
        ThemeType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() { return displayName; }
    }
    
    // Default constructor for Jackson
    public ThemeDefinition() {}
    
    public ThemeDefinition(String id, String name, String description, ThemeType type) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.readOnly = false;
    }
    
    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ThemeType getType() { return type; }
    public Map<Integer, ImVec4> getColors() { return colors; }
    public Map<Integer, Float> getStyleVars() { return styleVars; }
    public boolean isReadOnly() { return readOnly; }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setType(ThemeType type) { this.type = type; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    
    // Internal setters for Jackson deserialization
    @JsonProperty("colors")
    private void setColors(Map<Integer, ImVec4> colors) {
        this.colors.clear();
        if (colors != null) {
            this.colors.putAll(colors);
        }
    }
    
    @JsonProperty("style_vars")
    private void setStyleVars(Map<Integer, Float> styleVars) {
        this.styleVars.clear();
        if (styleVars != null) {
            this.styleVars.putAll(styleVars);
        }
    }
    
    // Color management
    public void setColor(int colorId, ImVec4 color) {
        if (!readOnly) {
            colors.put(colorId, new ImVec4(color.x, color.y, color.z, color.w));
        } else {
            logger.warn("Cannot modify read-only theme: {}", name);
        }
    }
    
    public void setColor(int colorId, float r, float g, float b, float a) {
        if (!readOnly) {
            colors.put(colorId, new ImVec4(r, g, b, a));
        } else {
            logger.warn("Cannot modify read-only theme: {}", name);
        }
    }
    
    public ImVec4 getColor(int colorId) {
        return colors.get(colorId);
    }
    
    // Style variable management
    public void setStyleVar(int styleVar, float value) {
        if (!readOnly) {
            styleVars.put(styleVar, value);
        } else {
            logger.warn("Cannot modify read-only theme: {}", name);
        }
    }
    
    public Float getStyleVar(int styleVar) {
        return styleVars.get(styleVar);
    }
    
    // Create a deep copy of this theme
    public ThemeDefinition copy() {
        ThemeDefinition copy = new ThemeDefinition(this.id + "_copy", this.name + " (Copy)", this.description, ThemeType.USER_CUSTOM);
        for (Map.Entry<Integer, ImVec4> entry : this.colors.entrySet()) {
            ImVec4 color = entry.getValue();
            copy.setColor(entry.getKey(), color.x, color.y, color.z, color.w);
        }
        for (Map.Entry<Integer, Float> entry : this.styleVars.entrySet()) {
            copy.setStyleVar(entry.getKey(), entry.getValue());
        }
        logger.debug("Created copy of theme: {} -> {}", this.name, copy.name);
        return copy;
    }
    
    // Validation methods
    public boolean isValid() {
        return id != null && !id.trim().isEmpty() && 
               name != null && !name.trim().isEmpty();
    }
    
    public void validate() {
        if (!isValid()) {
            String error = String.format("Invalid theme definition - ID: '%s', Name: '%s'", id, name);
            logger.error(error);
            throw new IllegalStateException(error);
        }
        
        if (colors.isEmpty() && styleVars.isEmpty()) {
            logger.warn("Theme '{}' has no colors or style variables defined", name);
        }
        
        logger.debug("Theme validation passed: {}", name);
    }
    
    // Utility methods
    public int getColorCount() {
        return colors.size();
    }
    
    public int getStyleVarCount() {
        return styleVars.size();
    }
    
    public boolean hasColor(int colorId) {
        return colors.containsKey(colorId);
    }
    
    public boolean hasStyleVar(int styleVar) {
        return styleVars.containsKey(styleVar);
    }
    
    public void clearColors() {
        if (!readOnly) {
            colors.clear();
            logger.debug("Cleared colors for theme: {}", name);
        }
    }
    
    public void clearStyleVars() {
        if (!readOnly) {
            styleVars.clear();
            logger.debug("Cleared style vars for theme: {}", name);
        }
    }
    
    @Override
    public String toString() {
        return name != null ? name : "Unnamed Theme";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ThemeDefinition that = (ThemeDefinition) obj;
        return id != null ? id.equals(that.id) : that.id == null;
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}