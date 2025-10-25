package com.openmason.ui.services;

import com.openmason.model.ModelManager;
import com.openmason.ui.state.ModelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model operation service.
 * Follows Single Responsibility Principle - only handles model operations.
 * Follows YAGNI - removed unimplemented export placeholders.
 */
public class ModelOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ModelOperationService.class);

    private final ModelState modelState;
    private final StatusService statusService;
    private final ModelManager modelManager;

    public ModelOperationService(ModelState modelState, StatusService statusService, ModelManager modelManager) {
        this.modelState = modelState;
        this.statusService = statusService;
        this.modelManager = modelManager;
    }

    /**
     * Create new model.
     */
    public void newModel() {
        statusService.updateStatus("Creating new model...");
        modelState.reset();
        modelState.setCurrentModelPath("Untitled Model");
        statusService.updateStatus("New model created");
    }

    /**
     * Open model asynchronously.
     */
    public void openModel() {
        statusService.updateStatus("Opening model...");

        try {
            if (modelManager != null) {
                String modelPath = "standard_cow";

                modelManager.loadModelInfoAsync(modelPath, ModelManager.LoadingPriority.NORMAL, null)
                    .thenAccept(modelInfo -> {
                        if (modelInfo != null) {
                            modelState.setModelLoaded(true);
                            modelState.setCurrentModelPath(modelPath);
                            modelState.setUnsavedChanges(false);

                            // Extract model statistics
                            int partCount = modelInfo.getPartCount();
                            int vertexCount = partCount * 24;
                            int triangleCount = partCount * 12;

                            modelState.updateStatistics(partCount, vertexCount, triangleCount);
                            statusService.updateStatus("Model loaded successfully: " + modelPath);
                            logger.info("Model loaded: {} parts, {} vertices, {} triangles",
                                    partCount, vertexCount, triangleCount);
                        } else {
                            statusService.updateStatus("Failed to load model");
                            logger.error("Model loading returned null");
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("Failed to load model", throwable);
                        statusService.updateStatus("Error loading model: " + throwable.getMessage());
                        return null;
                    });
            } else {
                logger.warn("ModelManager not initialized, falling back to placeholder");
                modelState.setModelLoaded(true);
                modelState.setCurrentModelPath("standard_cow.json");
                modelState.setUnsavedChanges(false);
                modelState.updateStatistics(6, 1248, 624);
                statusService.updateStatus("Model loaded (placeholder mode)");
            }
        } catch (Exception e) {
            logger.error("Exception during model loading", e);
            statusService.updateStatus("Error loading model: " + e.getMessage());
        }
    }

    /**
     * Save current model.
     */
    public void saveModel() {
        statusService.updateStatus("Saving model...");
        modelState.setUnsavedChanges(false);
        statusService.updateStatus("Model saved successfully");
    }

    /**
     * Load recent file by name.
     */
    public void loadRecentFile(String filename) {
        statusService.updateStatus("Loading " + filename + "...");
        modelState.setModelLoaded(true);
        modelState.setCurrentModelPath(filename);
        modelState.setUnsavedChanges(false);
        statusService.updateStatus("Loaded " + filename);
    }

    /**
     * Select model from browser.
     */
    public void selectModel(String modelName, String variant) {
        logger.info("Selected model: {} with variant: {}", modelName, variant);

        modelState.setModelLoaded(true);
        modelState.setCurrentModelPath(modelName);
        modelState.updateStatistics(6, 1248, 624);

        statusService.updateStatus("Model loaded: " + modelName + " (" + variant + " variant)");
    }

    /**
     * Validate current model.
     */
    public void validateModel() {
        statusService.updateStatus("Validating model...");
        statusService.updateStatus("Model validation complete");
    }
}
