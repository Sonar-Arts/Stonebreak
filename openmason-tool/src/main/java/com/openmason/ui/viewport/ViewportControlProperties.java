package com.openmason.ui.viewport;


/**
 * Properties for UI integration with OpenMason3DViewport.
 * Provides properties that can be used for integration with the viewport's
 * model rendering and camera systems.
 */
public class ViewportControlProperties {

    /**
     * Simple property classes to replace JavaFX properties.
     */
    static class StringProperty {
        private String value;
        public StringProperty(String initial) { this.value = initial; }
        public String get() { return value; }
        public void set(String value) { this.value = value; }
    }

    static class BooleanProperty {
        private boolean value;
        public BooleanProperty(boolean initial) { this.value = initial; }
        public boolean get() { return value; }
        public void set(boolean value) { this.value = value; }
    }

    static class DoubleProperty {
        private double value;
        public DoubleProperty(double initial) { this.value = initial; }
        public double get() { return value; }
        public void set(double value) { this.value = value; }
    }

    static class IntegerProperty {
        private int value;
        public IntegerProperty(int initial) { this.value = initial; }
        public int get() { return value; }
        public void set(int value) { this.value = value; }
    }

    static class LongProperty {
        private long value;
        public LongProperty(long initial) { this.value = initial; }
        public long get() { return value; }
        public void set(long value) { this.value = value; }
    }

    static class ReadOnlyDoubleWrapper {
        private double value;
        private ReadOnlyDoubleProperty readOnlyProperty;
        
        public ReadOnlyDoubleWrapper(double initial) { 
            this.value = initial; 
            this.readOnlyProperty = new ReadOnlyDoubleProperty(initial);
        }
        
        public double get() { return value; }
        
        public void set(double value) { 
            this.value = value; 
            this.readOnlyProperty = new ReadOnlyDoubleProperty(value);
        }
        
        public ReadOnlyDoubleProperty getReadOnlyProperty() { 
            return readOnlyProperty; 
        }
    }

    static class ReadOnlyIntegerWrapper {
        private int value;
        private ReadOnlyIntegerProperty readOnlyProperty;
        
        public ReadOnlyIntegerWrapper(int initial) { 
            this.value = initial; 
            this.readOnlyProperty = new ReadOnlyIntegerProperty(initial);
        }
        
        public int get() { return value; }
        
        public void set(int value) { 
            this.value = value; 
            this.readOnlyProperty = new ReadOnlyIntegerProperty(value);
        }
        
        public ReadOnlyIntegerProperty getReadOnlyProperty() { 
            return readOnlyProperty; 
        }
    }

    static class ReadOnlyLongWrapper {
        private long value;
        private ReadOnlyLongProperty readOnlyProperty;
        
        public ReadOnlyLongWrapper(long initial) { 
            this.value = initial; 
            this.readOnlyProperty = new ReadOnlyLongProperty(initial);
        }
        
        public long get() { return value; }
        
        public void set(long value) { 
            this.value = value; 
            this.readOnlyProperty = new ReadOnlyLongProperty(value);
        }
        
        public ReadOnlyLongProperty getReadOnlyProperty() { 
            return readOnlyProperty; 
        }
    }

    static class ReadOnlyBooleanWrapper {
        private boolean value;
        private ReadOnlyBooleanProperty readOnlyProperty;
        
        public ReadOnlyBooleanWrapper(boolean initial) { 
            this.value = initial; 
            this.readOnlyProperty = new ReadOnlyBooleanProperty(initial);
        }
        
        public boolean get() { return value; }
        
        public void set(boolean value) { 
            this.value = value; 
            this.readOnlyProperty = new ReadOnlyBooleanProperty(value);
        }
        
        public ReadOnlyBooleanProperty getReadOnlyProperty() { 
            return readOnlyProperty; 
        }
    }

    static class ReadOnlyStringWrapper {
        private String value;
        private ReadOnlyStringProperty readOnlyProperty;
        
        public ReadOnlyStringWrapper(String initial) { 
            this.value = initial; 
            this.readOnlyProperty = new ReadOnlyStringProperty(initial);
        }
        
        public String get() { return value; }
        
        public void set(String value) { 
            this.value = value; 
            this.readOnlyProperty = new ReadOnlyStringProperty(value);
        }
        
        public ReadOnlyStringProperty getReadOnlyProperty() { 
            return readOnlyProperty; 
        }
    }

    static class ReadOnlyDoubleProperty {
        private double value;
        public ReadOnlyDoubleProperty(double initial) { this.value = initial; }
        public double get() { return value; }
    }

    static class ReadOnlyIntegerProperty {
        private int value;
        public ReadOnlyIntegerProperty(int initial) { this.value = initial; }
        public int get() { return value; }
    }

    static class ReadOnlyLongProperty {
        private long value;
        public ReadOnlyLongProperty(long initial) { this.value = initial; }
        public long get() { return value; }
    }

    static class ReadOnlyBooleanProperty {
        private boolean value;
        public ReadOnlyBooleanProperty(boolean initial) { this.value = initial; }
        public boolean get() { return value; }
    }

    static class ReadOnlyStringProperty {
        private String value;
        public ReadOnlyStringProperty(String initial) { this.value = initial; }
        public String get() { return value; }
    }

    static class ObservableList<T> extends java.util.ArrayList<T> {
        public ObservableList(java.util.Collection<? extends T> items) { super(items); }
    }

    static class FXCollections {
        @SafeVarargs
        public static <T> ObservableList<T> observableArrayList(T... items) {
            return new ObservableList<>(java.util.Arrays.asList(items));
        }
    }
    
    // Model loading and texture properties
    private final StringProperty currentModelName = new StringProperty("standard_cow");
    private final StringProperty currentTextureVariant = new StringProperty("default");
    private final BooleanProperty modelLoadingInProgress = new BooleanProperty(false);
    private final StringProperty modelLoadingStatus = new StringProperty("Ready");
    private final DoubleProperty modelLoadingProgress = new DoubleProperty(0.0);
    
    // Texture variant options
    private final ObservableList<String> availableTextureVariants = FXCollections.observableArrayList(
        "default", "angus", "highland", "jersey"
    );
    
    // Rendering mode properties
    private final BooleanProperty wireframeMode = new BooleanProperty(false);
    private final BooleanProperty gridVisible = new BooleanProperty(true);
    private final BooleanProperty axesVisible = new BooleanProperty(true);
    private final BooleanProperty lightingEnabled = new BooleanProperty(true);
    
    // Camera control properties
    private final BooleanProperty cameraControlsEnabled = new BooleanProperty(true);
    private final StringProperty cameraMode = new StringProperty("arcball");
    private final DoubleProperty cameraDistance = new DoubleProperty(5.0);
    private final DoubleProperty cameraPitch = new DoubleProperty(20.0);
    private final DoubleProperty cameraYaw = new DoubleProperty(45.0);
    
    // Performance properties
    private final BooleanProperty performanceOverlayEnabled = new BooleanProperty(false);
    private final BooleanProperty adaptiveQualityEnabled = new BooleanProperty(true);
    private final IntegerProperty msaaLevel = new IntegerProperty(2);
    private final DoubleProperty renderScale = new DoubleProperty(1.0);
    
    // Statistics properties (read-only)
    private final ReadOnlyDoubleWrapper currentFPS = new ReadOnlyDoubleWrapper(0.0);
    private final ReadOnlyIntegerWrapper triangleCount = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyLongWrapper memoryUsage = new ReadOnlyLongWrapper(0);
    private final ReadOnlyBooleanWrapper renderingActive = new ReadOnlyBooleanWrapper(false);
    
    // Model information properties (read-only)
    private final ReadOnlyStringWrapper modelVariantName = new ReadOnlyStringWrapper("");
    private final ReadOnlyIntegerWrapper bodyPartCount = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper faceMappingCount = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyBooleanWrapper modelPrepared = new ReadOnlyBooleanWrapper(false);
    
    // Error handling properties
    private final StringProperty lastErrorMessage = new StringProperty("");
    private final LongProperty errorCount = new LongProperty(0);
    private final BooleanProperty hasErrors = new BooleanProperty(false);
    
    // === PROPERTY ACCESSORS ===
    
    // Model loading properties
    public StringProperty currentModelNameProperty() { return currentModelName; }
    public String getCurrentModelName() { return currentModelName.get(); }
    public void setCurrentModelName(String name) { currentModelName.set(name); }
    
    public StringProperty currentTextureVariantProperty() { return currentTextureVariant; }
    public String getCurrentTextureVariant() { return currentTextureVariant.get(); }
    public void setCurrentTextureVariant(String variant) { currentTextureVariant.set(variant); }
    
    public BooleanProperty modelLoadingInProgressProperty() { return modelLoadingInProgress; }
    public boolean isModelLoadingInProgress() { return modelLoadingInProgress.get(); }
    public void setModelLoadingInProgress(boolean loading) { modelLoadingInProgress.set(loading); }
    
    public StringProperty modelLoadingStatusProperty() { return modelLoadingStatus; }
    public String getModelLoadingStatus() { return modelLoadingStatus.get(); }
    public void setModelLoadingStatus(String status) { modelLoadingStatus.set(status); }
    
    public DoubleProperty modelLoadingProgressProperty() { return modelLoadingProgress; }
    public double getModelLoadingProgress() { return modelLoadingProgress.get(); }
    public void setModelLoadingProgress(double progress) { modelLoadingProgress.set(progress); }
    
    public ObservableList<String> getAvailableTextureVariants() { return availableTextureVariants; }
    
    // Rendering mode properties
    public BooleanProperty wireframeModeProperty() { return wireframeMode; }
    public boolean isWireframeMode() { return wireframeMode.get(); }
    public void setWireframeMode(boolean enabled) { wireframeMode.set(enabled); }
    
    public BooleanProperty gridVisibleProperty() { return gridVisible; }
    public boolean isGridVisible() { return gridVisible.get(); }
    public void setGridVisible(boolean visible) { gridVisible.set(visible); }
    
    public BooleanProperty axesVisibleProperty() { return axesVisible; }
    public boolean isAxesVisible() { return axesVisible.get(); }
    public void setAxesVisible(boolean visible) { axesVisible.set(visible); }
    
    public BooleanProperty lightingEnabledProperty() { return lightingEnabled; }
    public boolean isLightingEnabled() { return lightingEnabled.get(); }
    public void setLightingEnabled(boolean enabled) { lightingEnabled.set(enabled); }
    
    // Camera control properties
    public BooleanProperty cameraControlsEnabledProperty() { return cameraControlsEnabled; }
    public boolean areCameraControlsEnabled() { return cameraControlsEnabled.get(); }
    public void setCameraControlsEnabled(boolean enabled) { cameraControlsEnabled.set(enabled); }
    
    public StringProperty cameraModeProperty() { return cameraMode; }
    public String getCameraMode() { return cameraMode.get(); }
    public void setCameraMode(String mode) { cameraMode.set(mode); }
    
    public DoubleProperty cameraDistanceProperty() { return cameraDistance; }
    public double getCameraDistance() { return cameraDistance.get(); }
    public void setCameraDistance(double distance) { cameraDistance.set(distance); }
    
    public DoubleProperty cameraPitchProperty() { return cameraPitch; }
    public double getCameraPitch() { return cameraPitch.get(); }
    public void setCameraPitch(double pitch) { cameraPitch.set(pitch); }
    
    public DoubleProperty cameraYawProperty() { return cameraYaw; }
    public double getCameraYaw() { return cameraYaw.get(); }
    public void setCameraYaw(double yaw) { cameraYaw.set(yaw); }
    
    // Performance properties
    public BooleanProperty performanceOverlayEnabledProperty() { return performanceOverlayEnabled; }
    public boolean isPerformanceOverlayEnabled() { return performanceOverlayEnabled.get(); }
    public void setPerformanceOverlayEnabled(boolean enabled) { performanceOverlayEnabled.set(enabled); }
    
    public BooleanProperty adaptiveQualityEnabledProperty() { return adaptiveQualityEnabled; }
    public boolean isAdaptiveQualityEnabled() { return adaptiveQualityEnabled.get(); }
    public void setAdaptiveQualityEnabled(boolean enabled) { adaptiveQualityEnabled.set(enabled); }
    
    public IntegerProperty msaaLevelProperty() { return msaaLevel; }
    public int getMsaaLevel() { return msaaLevel.get(); }
    public void setMsaaLevel(int level) { msaaLevel.set(level); }
    
    public DoubleProperty renderScaleProperty() { return renderScale; }
    public double getRenderScale() { return renderScale.get(); }
    public void setRenderScale(double scale) { renderScale.set(scale); }
    
    // Statistics properties (read-only)
    public ReadOnlyDoubleProperty currentFPSProperty() { return currentFPS.getReadOnlyProperty(); }
    public double getCurrentFPS() { return currentFPS.get(); }
    void updateCurrentFPS(double fps) { currentFPS.set(fps); }
    
    public ReadOnlyIntegerProperty triangleCountProperty() { return triangleCount.getReadOnlyProperty(); }
    public int getTriangleCount() { return triangleCount.get(); }
    void updateTriangleCount(int count) { triangleCount.set(count); }
    
    public ReadOnlyLongProperty memoryUsageProperty() { return memoryUsage.getReadOnlyProperty(); }
    public long getMemoryUsage() { return memoryUsage.get(); }
    void updateMemoryUsage(long usage) { memoryUsage.set(usage); }
    
    public ReadOnlyBooleanProperty renderingActiveProperty() { return renderingActive.getReadOnlyProperty(); }
    public boolean isRenderingActive() { return renderingActive.get(); }
    void updateRenderingActive(boolean active) { renderingActive.set(active); }
    
    // Model information properties (read-only)
    public ReadOnlyStringProperty modelVariantNameProperty() { return modelVariantName.getReadOnlyProperty(); }
    public String getModelVariantName() { return modelVariantName.get(); }
    void updateModelVariantName(String name) { modelVariantName.set(name); }
    
    public ReadOnlyIntegerProperty bodyPartCountProperty() { return bodyPartCount.getReadOnlyProperty(); }
    public int getBodyPartCount() { return bodyPartCount.get(); }
    void updateBodyPartCount(int count) { bodyPartCount.set(count); }
    
    public ReadOnlyIntegerProperty faceMappingCountProperty() { return faceMappingCount.getReadOnlyProperty(); }
    public int getFaceMappingCount() { return faceMappingCount.get(); }
    void updateFaceMappingCount(int count) { faceMappingCount.set(count); }
    
    public ReadOnlyBooleanProperty modelPreparedProperty() { return modelPrepared.getReadOnlyProperty(); }
    public boolean isModelPrepared() { return modelPrepared.get(); }
    void updateModelPrepared(boolean prepared) { modelPrepared.set(prepared); }
    
    // Error handling properties
    public StringProperty lastErrorMessageProperty() { return lastErrorMessage; }
    public String getLastErrorMessage() { return lastErrorMessage.get(); }
    public void setLastErrorMessage(String message) { 
        lastErrorMessage.set(message);
        hasErrors.set(message != null && !message.isEmpty());
    }
    
    public LongProperty errorCountProperty() { return errorCount; }
    public long getErrorCount() { return errorCount.get(); }
    public void incrementErrorCount() { errorCount.set(errorCount.get() + 1); }
    
    public BooleanProperty hasErrorsProperty() { return hasErrors; }
    public boolean hasErrors() { return hasErrors.get(); }
    
    // === UTILITY METHODS ===
    
    /**
     * Updates all model-related properties from a ModelInfo object.
     */
    public void updateFromModelInfo(ModelManagementMethods.ModelInfo info) {
        if (info != null) {
            updateModelVariantName(info.variantName);
            setCurrentTextureVariant(info.textureVariant);
            updateBodyPartCount(info.bodyPartCount);
            updateFaceMappingCount(info.faceMappingCount);
            updateModelPrepared(info.isPrepared);
        } else {
            updateModelVariantName("");
            updateBodyPartCount(0);
            updateFaceMappingCount(0);
            updateModelPrepared(false);
        }
    }
    
    /**
     * Resets all properties to their default values.
     */
    public void resetToDefaults() {
        setCurrentModelName("standard_cow");
        setCurrentTextureVariant("default");
        setModelLoadingInProgress(false);
        setModelLoadingStatus("Ready");
        setModelLoadingProgress(0.0);
        
        setWireframeMode(false);
        setGridVisible(true);
        setAxesVisible(true);
        setLightingEnabled(true);
        
        setCameraControlsEnabled(true);
        setCameraMode("arcball");
        setCameraDistance(5.0);
        setCameraPitch(20.0);
        setCameraYaw(45.0);
        
        setPerformanceOverlayEnabled(false);
        setAdaptiveQualityEnabled(true);
        setMsaaLevel(2);
        setRenderScale(1.0);
        
        setLastErrorMessage("");
        errorCount.set(0);
    }
    
    /**
     * Creates a summary string of current property values for debugging.
     */
    public String getPropertiesSummary() {
        return String.format(
            "ViewportProperties{model='%s', variant='%s', wireframe=%b, grid=%b, axes=%b, fps=%.1f, triangles=%d, errors=%d}",
            getCurrentModelName(), getCurrentTextureVariant(), isWireframeMode(), 
            isGridVisible(), isAxesVisible(), getCurrentFPS(), getTriangleCount(), getErrorCount()
        );
    }
}