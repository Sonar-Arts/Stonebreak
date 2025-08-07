package com.openmason.ui.viewport;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Manages JavaFX properties and bindings for the 3D viewport.
 * 
 * Responsible for:
 * - JavaFX property creation and management
 * - Property change listeners and callbacks
 * - Property binding coordination
 * - Property validation and constraints
 * - Property state persistence and restoration
 */
public class ViewportPropertyManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportPropertyManager.class);
    
    // Model and texture properties
    private final StringProperty currentModelName = new SimpleStringProperty("");
    private final StringProperty currentTextureVariant = new SimpleStringProperty("default");
    
    // Visualization properties
    private final BooleanProperty wireframeMode = new SimpleBooleanProperty(false);
    private final BooleanProperty gridVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty axesVisible = new SimpleBooleanProperty(true);
    
    // Debug properties
    private final BooleanProperty debugMode = new SimpleBooleanProperty(false);
    private final BooleanProperty coordinateAxesVisible = new SimpleBooleanProperty(false);
    private final BooleanProperty partLabelsVisible = new SimpleBooleanProperty(false);
    
    // Performance properties
    private final BooleanProperty performanceOverlayEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty adaptiveQualityEnabled = new SimpleBooleanProperty(true);
    
    // Camera properties
    private final BooleanProperty cameraControlsEnabled = new SimpleBooleanProperty(true);
    
    // Property change callbacks
    private final Map<String, Consumer<Object>> propertyCallbacks = new HashMap<>();
    
    /**
     * Initialize property manager and set up listeners.
     */
    public void initialize() {
        setupPropertyListeners();
        logger.debug("ViewportPropertyManager initialized");
    }
    
    /**
     * Set up property change listeners.
     */
    private void setupPropertyListeners() {
        // Model and texture properties
        currentModelName.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("currentModelName", oldVal, newVal));
            
        currentTextureVariant.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("currentTextureVariant", oldVal, newVal));
        
        // Visualization properties
        wireframeMode.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("wireframeMode", oldVal, newVal));
            
        gridVisible.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("gridVisible", oldVal, newVal));
            
        axesVisible.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("axesVisible", oldVal, newVal));
        
        // Debug properties
        debugMode.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("debugMode", oldVal, newVal));
            
        coordinateAxesVisible.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("coordinateAxesVisible", oldVal, newVal));
            
        partLabelsVisible.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("partLabelsVisible", oldVal, newVal));
        
        // Performance properties
        performanceOverlayEnabled.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("performanceOverlayEnabled", oldVal, newVal));
            
        adaptiveQualityEnabled.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("adaptiveQualityEnabled", oldVal, newVal));
        
        // Camera properties
        cameraControlsEnabled.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("cameraControlsEnabled", oldVal, newVal));
        
        logger.debug("Property listeners configured");
    }
    
    /**
     * Notify registered callbacks about property changes.
     */
    private void notifyPropertyChange(String propertyName, Object oldValue, Object newValue) {
        Consumer<Object> callback = propertyCallbacks.get(propertyName);
        if (callback != null) {
            try {
                callback.accept(newValue);
                logger.trace("Property change notification: {}={} (was: {})", 
                    propertyName, newValue, oldValue);
            } catch (Exception e) {
                logger.warn("Failed to notify property change for: " + propertyName, e);
            }
        }
    }
    
    /**
     * Register a callback for property changes.
     */
    public void registerPropertyCallback(String propertyName, Consumer<Object> callback) {
        if (propertyName != null && callback != null) {
            propertyCallbacks.put(propertyName, callback);
            logger.debug("Registered callback for property: {}", propertyName);
        }
    }
    
    /**
     * Unregister a property callback.
     */
    public void unregisterPropertyCallback(String propertyName) {
        propertyCallbacks.remove(propertyName);
        logger.debug("Unregistered callback for property: {}", propertyName);
    }
    
    /**
     * Validate property values and apply constraints.
     */
    public boolean validateProperty(String propertyName, Object value) {
        switch (propertyName) {
            case "currentTextureVariant":
                return validateTextureVariant((String) value);
            case "currentModelName":
                return validateModelName((String) value);
            default:
                return true; // Allow all other values
        }
    }
    
    /**
     * Validate texture variant value.
     */
    private boolean validateTextureVariant(String variant) {
        if (variant == null) return false;
        
        // Define allowed texture variants
        String[] allowedVariants = {"default", "angus", "highland", "jersey"};
        
        for (String allowed : allowedVariants) {
            if (allowed.equals(variant)) {
                return true;
            }
        }
        
        logger.warn("Invalid texture variant: {}", variant);
        return false;
    }
    
    /**
     * Validate model name value.
     */
    private boolean validateModelName(String modelName) {
        // Allow empty string and null (for clearing)
        if (modelName == null || modelName.isEmpty()) {
            return true;
        }
        
        // Basic validation - no special characters that might cause issues
        return modelName.matches("[a-zA-Z0-9_-]+");
    }
    
    /**
     * Get property state as a map.
     */
    public Map<String, Object> getPropertyState() {
        Map<String, Object> state = new HashMap<>();
        
        // Model and texture properties
        state.put("currentModelName", currentModelName.get());
        state.put("currentTextureVariant", currentTextureVariant.get());
        
        // Visualization properties
        state.put("wireframeMode", wireframeMode.get());
        state.put("gridVisible", gridVisible.get());
        state.put("axesVisible", axesVisible.get());
        
        // Debug properties
        state.put("debugMode", debugMode.get());
        state.put("coordinateAxesVisible", coordinateAxesVisible.get());
        state.put("partLabelsVisible", partLabelsVisible.get());
        
        // Performance properties
        state.put("performanceOverlayEnabled", performanceOverlayEnabled.get());
        state.put("adaptiveQualityEnabled", adaptiveQualityEnabled.get());
        
        // Camera properties
        state.put("cameraControlsEnabled", cameraControlsEnabled.get());
        
        return state;
    }
    
    /**
     * Restore property state from a map.
     */
    public void restorePropertyState(Map<String, Object> state) {
        if (state == null) return;
        
        try {
            // Model and texture properties
            if (state.containsKey("currentModelName")) {
                String modelName = (String) state.get("currentModelName");
                if (validateProperty("currentModelName", modelName)) {
                    currentModelName.set(modelName);
                }
            }
            
            if (state.containsKey("currentTextureVariant")) {
                String variant = (String) state.get("currentTextureVariant");
                if (validateProperty("currentTextureVariant", variant)) {
                    currentTextureVariant.set(variant);
                }
            }
            
            // Visualization properties
            if (state.containsKey("wireframeMode")) {
                wireframeMode.set((Boolean) state.get("wireframeMode"));
            }
            
            if (state.containsKey("gridVisible")) {
                gridVisible.set((Boolean) state.get("gridVisible"));
            }
            
            if (state.containsKey("axesVisible")) {
                axesVisible.set((Boolean) state.get("axesVisible"));
            }
            
            // Debug properties
            if (state.containsKey("debugMode")) {
                debugMode.set((Boolean) state.get("debugMode"));
            }
            
            if (state.containsKey("coordinateAxesVisible")) {
                coordinateAxesVisible.set((Boolean) state.get("coordinateAxesVisible"));
            }
            
            if (state.containsKey("partLabelsVisible")) {
                partLabelsVisible.set((Boolean) state.get("partLabelsVisible"));
            }
            
            // Performance properties
            if (state.containsKey("performanceOverlayEnabled")) {
                performanceOverlayEnabled.set((Boolean) state.get("performanceOverlayEnabled"));
            }
            
            if (state.containsKey("adaptiveQualityEnabled")) {
                adaptiveQualityEnabled.set((Boolean) state.get("adaptiveQualityEnabled"));
            }
            
            // Camera properties
            if (state.containsKey("cameraControlsEnabled")) {
                cameraControlsEnabled.set((Boolean) state.get("cameraControlsEnabled"));
            }
            
            logger.debug("Property state restored from {} properties", state.size());
            
        } catch (Exception e) {
            logger.error("Failed to restore property state", e);
        }
    }
    
    /**
     * Reset all properties to their default values.
     */
    public void resetToDefaults() {
        currentModelName.set("");
        currentTextureVariant.set("default");
        wireframeMode.set(false);
        gridVisible.set(true);
        axesVisible.set(true);
        debugMode.set(false);
        coordinateAxesVisible.set(false);
        partLabelsVisible.set(false);
        performanceOverlayEnabled.set(false);
        adaptiveQualityEnabled.set(true);
        cameraControlsEnabled.set(true);
        
        logger.debug("All properties reset to default values");
    }
    
    // Model and Texture Properties
    public StringProperty currentModelNameProperty() { return currentModelName; }
    public String getCurrentModelName() { return currentModelName.get(); }
    public void setCurrentModelName(String modelName) { 
        if (validateProperty("currentModelName", modelName)) {
            currentModelName.set(modelName); 
        }
    }
    
    public StringProperty currentTextureVariantProperty() { return currentTextureVariant; }
    public String getCurrentTextureVariant() { return currentTextureVariant.get(); }
    public void setCurrentTextureVariant(String variant) { 
        if (validateProperty("currentTextureVariant", variant)) {
            currentTextureVariant.set(variant); 
        }
    }
    
    // Visualization Properties
    public BooleanProperty wireframeModeProperty() { return wireframeMode; }
    public boolean isWireframeMode() { return wireframeMode.get(); }
    public void setWireframeMode(boolean enabled) { wireframeMode.set(enabled); }
    
    public BooleanProperty gridVisibleProperty() { return gridVisible; }
    public boolean isGridVisible() { return gridVisible.get(); }
    public void setGridVisible(boolean visible) { gridVisible.set(visible); }
    
    public BooleanProperty axesVisibleProperty() { return axesVisible; }
    public boolean isAxesVisible() { return axesVisible.get(); }
    public void setAxesVisible(boolean visible) { axesVisible.set(visible); }
    
    // Debug Properties
    public BooleanProperty debugModeProperty() { return debugMode; }
    public boolean isDebugMode() { return debugMode.get(); }
    public void setDebugMode(boolean enabled) { debugMode.set(enabled); }
    
    public BooleanProperty coordinateAxesVisibleProperty() { return coordinateAxesVisible; }
    public boolean isCoordinateAxesVisible() { return coordinateAxesVisible.get(); }
    public void setCoordinateAxesVisible(boolean visible) { coordinateAxesVisible.set(visible); }
    
    public BooleanProperty partLabelsVisibleProperty() { return partLabelsVisible; }
    public boolean isPartLabelsVisible() { return partLabelsVisible.get(); }
    public void setPartLabelsVisible(boolean visible) { partLabelsVisible.set(visible); }
    
    // Performance Properties
    public BooleanProperty performanceOverlayEnabledProperty() { return performanceOverlayEnabled; }
    public boolean isPerformanceOverlayEnabled() { return performanceOverlayEnabled.get(); }
    public void setPerformanceOverlayEnabled(boolean enabled) { performanceOverlayEnabled.set(enabled); }
    
    public BooleanProperty adaptiveQualityEnabledProperty() { return adaptiveQualityEnabled; }
    public boolean isAdaptiveQualityEnabled() { return adaptiveQualityEnabled.get(); }
    public void setAdaptiveQualityEnabled(boolean enabled) { adaptiveQualityEnabled.set(enabled); }
    
    // Camera Properties
    public BooleanProperty cameraControlsEnabledProperty() { return cameraControlsEnabled; }
    public boolean areCameraControlsEnabled() { return cameraControlsEnabled.get(); }
    public void setCameraControlsEnabled(boolean enabled) { cameraControlsEnabled.set(enabled); }
    
    /**
     * Get property manager state as a formatted string for debugging.
     */
    public String getPropertyStateString() {
        return String.format("Properties: Model='%s', Variant='%s', Wireframe=%s, Grid=%s, Axes=%s, Debug=%s",
            getCurrentModelName(), getCurrentTextureVariant(), isWireframeMode(), 
            isGridVisible(), isAxesVisible(), isDebugMode());
    }
}