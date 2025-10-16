package com.openmason.ui.state;

/**
 * Centralized model state management.
 * Follows Single Responsibility Principle - only manages model-related state.
 */
public class ModelState {

    private boolean modelLoaded = false;
    private String currentModelPath = "";
    private boolean unsavedChanges = false;

    // Model Statistics
    private int partCount = 0;
    private int vertexCount = 0;
    private int triangleCount = 0;
    private int textureVariantCount = 4;

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

    public boolean hasUnsavedChanges() {
        return unsavedChanges;
    }

    public void setUnsavedChanges(boolean unsavedChanges) {
        this.unsavedChanges = unsavedChanges;
    }

    public int getPartCount() {
        return partCount;
    }

    public void setPartCount(int partCount) {
        this.partCount = partCount;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    public int getTriangleCount() {
        return triangleCount;
    }

    public void setTriangleCount(int triangleCount) {
        this.triangleCount = triangleCount;
    }

    public int getTextureVariantCount() {
        return textureVariantCount;
    }

    public void setTextureVariantCount(int textureVariantCount) {
        this.textureVariantCount = textureVariantCount;
    }

    public String getSelectedPart() {
        return selectedPart;
    }

    public void setSelectedPart(String selectedPart) {
        this.selectedPart = selectedPart;
    }

    public String getPartCoordinates() {
        return partCoordinates;
    }

    public void setPartCoordinates(String partCoordinates) {
        this.partCoordinates = partCoordinates;
    }

    /**
     * Reset model state to initial values.
     */
    public void reset() {
        modelLoaded = false;
        currentModelPath = "";
        unsavedChanges = false;
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
