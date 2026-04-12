package com.openmason.main.systems.stateHandling;

/**
 * Centralized model state management.
 * Follows Single Responsibility Principle - only manages model-related state.
 */
public class ModelState {

    /**
     * Represents the source of the currently loaded model.
     */
    public enum ModelSource {
        NONE,           // No model loaded
        NEW,            // Created via "New Model"
        OMO_FILE,       // Loaded from .OMO file
        BROWSER         // Loaded from Model Browser (read-only)
    }

    private boolean modelLoaded = false;
    private String currentModelPath = "";
    private String currentOMOFilePath = "";
    private boolean unsavedChanges = false;
    private ModelSource modelSource = ModelSource.NONE;

    // Model Statistics
    private int partCount = 0;
    private int vertexCount = 0;
    private int triangleCount = 0;

    // Model Part Selection
    private String selectedPart = "None";
    private String partCoordinates = "0, 0, 0";

    // Getters and Setters

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    public void setModelLoaded(boolean modelLoaded) {
        this.modelLoaded = modelLoaded;
    }

    public String getCurrentModelPath() {
        return currentModelPath;
    }

    public void setCurrentModelPath(String currentModelPath) {
        this.currentModelPath = currentModelPath;
    }

    /**
     * Get the file system path to the current .OMO file.
     * Empty string if no .OMO file is loaded (e.g. new model or browser model).
     */
    public String getCurrentOMOFilePath() {
        return currentOMOFilePath;
    }

    public void setCurrentOMOFilePath(String path) {
        this.currentOMOFilePath = path != null ? path : "";
    }

    public boolean hasUnsavedChanges() {
        return unsavedChanges;
    }

    public void setUnsavedChanges(boolean unsavedChanges) {
        this.unsavedChanges = unsavedChanges;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    public ModelSource getModelSource() {
        return modelSource;
    }

    public void setModelSource(ModelSource modelSource) {
        this.modelSource = modelSource;
    }

    /**
     * Check if the current model can be saved to .OMO format.
     * Only NEW and OMO_FILE models can be saved.
     * BROWSER models are read-only references.
     */
    public boolean canSaveModel() {
        return modelSource == ModelSource.NEW || modelSource == ModelSource.OMO_FILE;
    }

    /**
     * Check if the current model has a backing .OMO file on disk.
     * This is the actual prerequisite for Stonebreak exports (SBO/SBE),
     * which read the OMO bytes from disk — independent of {@link ModelSource},
     * since a project-loaded model may end up with a non-OMO source tag
     * while still having a valid on-disk path.
     */
    public boolean hasOMOFile() {
        return currentOMOFilePath != null && !currentOMOFilePath.isBlank();
    }

    /**
     * Reset model state to initial values.
     */
    public void reset() {
        modelLoaded = false;
        currentModelPath = "";
        currentOMOFilePath = "";
        unsavedChanges = false;
        modelSource = ModelSource.NONE;
        partCount = 0;
        vertexCount = 0;
        triangleCount = 0;
        selectedPart = "None";
        partCoordinates = "0, 0, 0";
    }

    /**
     * Update model statistics.
     */
    public void updateStatistics(int partCount, int vertexCount, int triangleCount) {
        this.partCount = partCount;
        this.vertexCount = vertexCount;
        this.triangleCount = triangleCount;
    }
}
