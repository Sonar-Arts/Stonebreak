package com.openmason.ui.viewport;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * JavaFX properties for UI integration with OpenMason3DViewport.
 * Provides observable properties that can be bound to UI controls for seamless
 * integration with the viewport's model rendering and camera systems.
 */
public class ViewportControlProperties {
    
    // Model loading and texture properties
    private final StringProperty currentModelName = new SimpleStringProperty("standard_cow");
    private final StringProperty currentTextureVariant = new SimpleStringProperty("default");
    private final BooleanProperty modelLoadingInProgress = new SimpleBooleanProperty(false);
    private final StringProperty modelLoadingStatus = new SimpleStringProperty("Ready");
    private final DoubleProperty modelLoadingProgress = new SimpleDoubleProperty(0.0);
    
    // Texture variant options
    private final ObservableList<String> availableTextureVariants = FXCollections.observableArrayList(
        "default", "angus", "highland", "jersey"
    );
    
    // Rendering mode properties
    private final BooleanProperty wireframeMode = new SimpleBooleanProperty(false);
    private final BooleanProperty gridVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty axesVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty lightingEnabled = new SimpleBooleanProperty(true);
    
    // Camera control properties
    private final BooleanProperty cameraControlsEnabled = new SimpleBooleanProperty(true);
    private final StringProperty cameraMode = new SimpleStringProperty("arcball");
    private final DoubleProperty cameraDistance = new SimpleDoubleProperty(5.0);
    private final DoubleProperty cameraPitch = new SimpleDoubleProperty(20.0);
    private final DoubleProperty cameraYaw = new SimpleDoubleProperty(45.0);
    
    // Performance properties
    private final BooleanProperty performanceOverlayEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty adaptiveQualityEnabled = new SimpleBooleanProperty(true);
    private final IntegerProperty msaaLevel = new SimpleIntegerProperty(2);
    private final DoubleProperty renderScale = new SimpleDoubleProperty(1.0);
    
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
    private final StringProperty lastErrorMessage = new SimpleStringProperty("");
    private final LongProperty errorCount = new SimpleLongProperty(0);
    private final BooleanProperty hasErrors = new SimpleBooleanProperty(false);
    
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