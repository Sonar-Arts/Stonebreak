package com.openmason.ui.viewport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages properties and bindings for the 3D viewport (ImGui-compatible).
 * 
 * Responsible for:
 * - Property creation and management without JavaFX dependencies
 * - Property change listeners and callbacks
 * - Property validation and constraints
 * - Property state persistence and restoration
 */
public class ViewportPropertyManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportPropertyManager.class);
    
    // Property storage
    private String currentModelName = "";
    private String currentTextureVariant = "default";
    
    // Visualization properties
    private boolean wireframeMode = false;
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    
    // Debug properties
    private boolean debugMode = false;
    private boolean coordinateAxesVisible = false;
    private boolean partLabelsVisible = false;
    
    // Performance properties
    private boolean performanceOverlayEnabled = false;
    private boolean adaptiveQualityEnabled = true;
    
    // Camera properties
    private boolean cameraControlsEnabled = true;
    
    // Property change callbacks
    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> propertyCallbacks = new HashMap<>();
    
    /**
     * Simple property wrapper for string values.
     */
    public static class StringProperty {
        private String value;
        private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        
        public StringProperty(String initialValue) {
            this.value = initialValue;
        }
        
        public String get() { return value; }
        
        public void set(String newValue) {
            String oldValue = this.value;
            this.value = newValue;
            notifyListeners(oldValue, newValue);
        }
        
        public void addListener(TriConsumer<Object, String, String> listener) {
            listeners.add(newVal -> listener.accept(null, value, newVal));
        }
        
        private void notifyListeners(String oldValue, String newValue) {
            for (Consumer<String> listener : listeners) {
                try {
                    listener.accept(newValue);
                } catch (Exception e) {
                    // Log but don't fail
                }
            }
        }
    }
    
    /**
     * Simple property wrapper for boolean values.
     */
    public static class BooleanProperty {
        private boolean value;
        private final CopyOnWriteArrayList<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
        
        public BooleanProperty(boolean initialValue) {
            this.value = initialValue;
        }
        
        public boolean get() { return value; }
        
        public void set(boolean newValue) {
            boolean oldValue = this.value;
            this.value = newValue;
            notifyListeners(oldValue, newValue);
        }
        
        public void addListener(TriConsumer<Object, Boolean, Boolean> listener) {
            listeners.add(newVal -> listener.accept(null, value, newVal));
        }
        
        private void notifyListeners(boolean oldValue, boolean newValue) {
            for (Consumer<Boolean> listener : listeners) {
                try {
                    listener.accept(newValue);
                } catch (Exception e) {
                    // Log but don't fail
                }
            }
        }
    }
    
    /**
     * Functional interface for triple argument consumers.
     */
    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
    
    // Property objects for compatibility
    private final StringProperty currentModelNameProperty = new StringProperty("");
    private final StringProperty currentTextureVariantProperty = new StringProperty("default");
    private final BooleanProperty wireframeModeProperty = new BooleanProperty(false);
    private final BooleanProperty gridVisibleProperty = new BooleanProperty(true);
    private final BooleanProperty axesVisibleProperty = new BooleanProperty(true);
    private final BooleanProperty debugModeProperty = new BooleanProperty(false);
    private final BooleanProperty coordinateAxesVisibleProperty = new BooleanProperty(false);
    private final BooleanProperty partLabelsVisibleProperty = new BooleanProperty(false);
    private final BooleanProperty performanceOverlayEnabledProperty = new BooleanProperty(false);
    private final BooleanProperty adaptiveQualityEnabledProperty = new BooleanProperty(true);
    private final BooleanProperty cameraControlsEnabledProperty = new BooleanProperty(true);
    
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
        currentModelNameProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("currentModelName", oldVal, newVal));
            
        currentTextureVariantProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("currentTextureVariant", oldVal, newVal));
        
        // Visualization properties
        wireframeModeProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("wireframeMode", oldVal, newVal));
            
        gridVisibleProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("gridVisible", oldVal, newVal));
            
        axesVisibleProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("axesVisible", oldVal, newVal));
        
        // Debug properties
        debugModeProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("debugMode", oldVal, newVal));
            
        coordinateAxesVisibleProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("coordinateAxesVisible", oldVal, newVal));
            
        partLabelsVisibleProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("partLabelsVisible", oldVal, newVal));
        
        // Performance properties
        performanceOverlayEnabledProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("performanceOverlayEnabled", oldVal, newVal));
            
        adaptiveQualityEnabledProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("adaptiveQualityEnabled", oldVal, newVal));
        
        // Camera properties
        cameraControlsEnabledProperty.addListener((obs, oldVal, newVal) -> 
            notifyPropertyChange("cameraControlsEnabled", oldVal, newVal));
        
        logger.debug("Property listeners configured");
    }
    
    /**
     * Notify registered callbacks about property changes.
     */
    private void notifyPropertyChange(String propertyName, Object oldValue, Object newValue) {
        CopyOnWriteArrayList<Consumer<Object>> callbacks = propertyCallbacks.get(propertyName);
        if (callbacks != null) {
            for (Consumer<Object> callback : callbacks) {
                try {
                    callback.accept(newValue);
                    logger.trace("Property change notification: {}={} (was: {})", 
                        propertyName, newValue, oldValue);
                } catch (Exception e) {
                    logger.warn("Failed to notify property change for: " + propertyName, e);
                }
            }
        }
    }
    
    /**
     * Register a callback for property changes.
     */
    public void registerPropertyCallback(String propertyName, Consumer<Object> callback) {
        if (propertyName != null && callback != null) {
            propertyCallbacks.computeIfAbsent(propertyName, k -> new CopyOnWriteArrayList<>()).add(callback);
            logger.debug("Registered callback for property: {}", propertyName);
        }
    }
    
    /**
     * Unregister a property callback.
     */
    public void unregisterPropertyCallback(String propertyName, Consumer<Object> callback) {
        CopyOnWriteArrayList<Consumer<Object>> callbacks = propertyCallbacks.get(propertyName);
        if (callbacks != null) {
            callbacks.remove(callback);
            if (callbacks.isEmpty()) {
                propertyCallbacks.remove(propertyName);
            }
            logger.debug("Unregistered callback for property: {}", propertyName);
        }
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
        state.put("currentModelName", currentModelName);
        state.put("currentTextureVariant", currentTextureVariant);
        
        // Visualization properties
        state.put("wireframeMode", wireframeMode);
        state.put("gridVisible", gridVisible);
        state.put("axesVisible", axesVisible);
        
        // Debug properties
        state.put("debugMode", debugMode);
        state.put("coordinateAxesVisible", coordinateAxesVisible);
        state.put("partLabelsVisible", partLabelsVisible);
        
        // Performance properties
        state.put("performanceOverlayEnabled", performanceOverlayEnabled);
        state.put("adaptiveQualityEnabled", adaptiveQualityEnabled);
        
        // Camera properties
        state.put("cameraControlsEnabled", cameraControlsEnabled);
        
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
                    setCurrentModelName(modelName);
                }
            }
            
            if (state.containsKey("currentTextureVariant")) {
                String variant = (String) state.get("currentTextureVariant");
                if (validateProperty("currentTextureVariant", variant)) {
                    setCurrentTextureVariant(variant);
                }
            }
            
            // Visualization properties
            if (state.containsKey("wireframeMode")) {
                setWireframeMode((Boolean) state.get("wireframeMode"));
            }
            
            if (state.containsKey("gridVisible")) {
                setGridVisible((Boolean) state.get("gridVisible"));
            }
            
            if (state.containsKey("axesVisible")) {
                setAxesVisible((Boolean) state.get("axesVisible"));
            }
            
            // Debug properties
            if (state.containsKey("debugMode")) {
                setDebugMode((Boolean) state.get("debugMode"));
            }
            
            if (state.containsKey("coordinateAxesVisible")) {
                setCoordinateAxesVisible((Boolean) state.get("coordinateAxesVisible"));
            }
            
            if (state.containsKey("partLabelsVisible")) {
                setPartLabelsVisible((Boolean) state.get("partLabelsVisible"));
            }
            
            // Performance properties
            if (state.containsKey("performanceOverlayEnabled")) {
                setPerformanceOverlayEnabled((Boolean) state.get("performanceOverlayEnabled"));
            }
            
            if (state.containsKey("adaptiveQualityEnabled")) {
                setAdaptiveQualityEnabled((Boolean) state.get("adaptiveQualityEnabled"));
            }
            
            // Camera properties
            if (state.containsKey("cameraControlsEnabled")) {
                setCameraControlsEnabled((Boolean) state.get("cameraControlsEnabled"));
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
        setCurrentModelName("");
        setCurrentTextureVariant("default");
        setWireframeMode(false);
        setGridVisible(true);
        setAxesVisible(true);
        setDebugMode(false);
        setCoordinateAxesVisible(false);
        setPartLabelsVisible(false);
        setPerformanceOverlayEnabled(false);
        setAdaptiveQualityEnabled(true);
        setCameraControlsEnabled(true);
        
        logger.debug("All properties reset to default values");
    }
    
    // Model and Texture Properties
    public StringProperty currentModelNameProperty() { return currentModelNameProperty; }
    public String getCurrentModelName() { return currentModelName; }
    public void setCurrentModelName(String modelName) { 
        if (validateProperty("currentModelName", modelName)) {
            this.currentModelName = modelName;
            currentModelNameProperty.set(modelName);
        }
    }
    
    public StringProperty currentTextureVariantProperty() { return currentTextureVariantProperty; }
    public String getCurrentTextureVariant() { return currentTextureVariant; }
    public void setCurrentTextureVariant(String variant) { 
        if (validateProperty("currentTextureVariant", variant)) {
            this.currentTextureVariant = variant;
            currentTextureVariantProperty.set(variant);
        }
    }
    
    // Visualization Properties
    public BooleanProperty wireframeModeProperty() { return wireframeModeProperty; }
    public boolean isWireframeMode() { return wireframeMode; }
    public void setWireframeMode(boolean enabled) { 
        this.wireframeMode = enabled;
        wireframeModeProperty.set(enabled);
    }
    
    public BooleanProperty gridVisibleProperty() { return gridVisibleProperty; }
    public boolean isGridVisible() { return gridVisible; }
    public void setGridVisible(boolean visible) { 
        this.gridVisible = visible;
        gridVisibleProperty.set(visible);
    }
    
    public BooleanProperty axesVisibleProperty() { return axesVisibleProperty; }
    public boolean isAxesVisible() { return axesVisible; }
    public void setAxesVisible(boolean visible) { 
        this.axesVisible = visible;
        axesVisibleProperty.set(visible);
    }
    
    // Debug Properties
    public BooleanProperty debugModeProperty() { return debugModeProperty; }
    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean enabled) { 
        this.debugMode = enabled;
        debugModeProperty.set(enabled);
    }
    
    public BooleanProperty coordinateAxesVisibleProperty() { return coordinateAxesVisibleProperty; }
    public boolean isCoordinateAxesVisible() { return coordinateAxesVisible; }
    public void setCoordinateAxesVisible(boolean visible) { 
        this.coordinateAxesVisible = visible;
        coordinateAxesVisibleProperty.set(visible);
    }
    
    public BooleanProperty partLabelsVisibleProperty() { return partLabelsVisibleProperty; }
    public boolean isPartLabelsVisible() { return partLabelsVisible; }
    public void setPartLabelsVisible(boolean visible) { 
        this.partLabelsVisible = visible;
        partLabelsVisibleProperty.set(visible);
    }
    
    // Performance Properties
    public BooleanProperty performanceOverlayEnabledProperty() { return performanceOverlayEnabledProperty; }
    public boolean isPerformanceOverlayEnabled() { return performanceOverlayEnabled; }
    public void setPerformanceOverlayEnabled(boolean enabled) { 
        this.performanceOverlayEnabled = enabled;
        performanceOverlayEnabledProperty.set(enabled);
    }
    
    public BooleanProperty adaptiveQualityEnabledProperty() { return adaptiveQualityEnabledProperty; }
    public boolean isAdaptiveQualityEnabled() { return adaptiveQualityEnabled; }
    public void setAdaptiveQualityEnabled(boolean enabled) { 
        this.adaptiveQualityEnabled = enabled;
        adaptiveQualityEnabledProperty.set(enabled);
    }
    
    // Camera Properties
    public BooleanProperty cameraControlsEnabledProperty() { return cameraControlsEnabledProperty; }
    public boolean areCameraControlsEnabled() { return cameraControlsEnabled; }
    public void setCameraControlsEnabled(boolean enabled) { 
        this.cameraControlsEnabled = enabled;
        cameraControlsEnabledProperty.set(enabled);
    }
    
    /**
     * Get property manager state as a formatted string for debugging.
     */
    public String getPropertyStateString() {
        return String.format("Properties: Model='%s', Variant='%s', Wireframe=%s, Grid=%s, Axes=%s, Debug=%s",
            getCurrentModelName(), getCurrentTextureVariant(), isWireframeMode(), 
            isGridVisible(), isAxesVisible(), isDebugMode());
    }
}