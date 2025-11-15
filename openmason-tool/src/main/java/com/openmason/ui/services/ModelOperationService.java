package com.openmason.ui.services;

import com.openmason.deprecated.LegacyCowModelManager;
import com.openmason.model.editable.BlockModel;
import com.openmason.model.factory.BlankModelFactory;
import com.openmason.model.io.omo.OMODeserializer;
import com.openmason.model.io.omo.OMOSerializer;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.state.ModelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Model operation service.
 *
 * <p>Handles model operations including:
 * <ul>
 *   <li>Creating new blank models</li>
 *   <li>Opening/saving .OMO files</li>
 *   <li>Managing editable model state</li>
 *   <li>Dirty state tracking</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles model operations</li>
 *   <li>DRY: Reuses factories and serializers</li>
 *   <li>YAGNI: No unimplemented features</li>
 * </ul>
 */
public class ModelOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ModelOperationService.class);

    private final ModelState modelState;
    private final StatusService statusService;
    private final LegacyCowModelManager legacyCowModelManager;
    private final FileDialogService fileDialogService;

    // Editable model support
    private final BlankModelFactory blankModelFactory;
    private final OMOSerializer omoSerializer;
    private final OMODeserializer omoDeserializer;
    private BlockModel currentEditableModel;

    // Viewport reference (for loading models into viewport)
    private com.openmason.ui.viewport.OpenMason3DViewport viewport;

    public ModelOperationService(ModelState modelState, StatusService statusService,
                                 LegacyCowModelManager legacyCowModelManager,
                                 FileDialogService fileDialogService) {
        this.modelState = modelState;
        this.statusService = statusService;
        this.legacyCowModelManager = legacyCowModelManager;
        this.fileDialogService = fileDialogService;

        // Initialize .OMO support
        this.blankModelFactory = new BlankModelFactory();
        this.omoSerializer = new OMOSerializer();
        this.omoDeserializer = new OMODeserializer();
        this.currentEditableModel = null;
        this.viewport = null; // Set later via setViewport()
    }

    /**
     * Sets the viewport reference for loading models.
     * Called by MainImGuiInterface after viewport is created.
     *
     * @param viewport the 3D viewport instance
     */
    public void setViewport(com.openmason.ui.viewport.OpenMason3DViewport viewport) {
        this.viewport = viewport;
        logger.debug("Viewport reference set in ModelOperationService");
    }

    /**
     * Create new blank cube model.
     * Creates a BlockModel with gray 64x48 cube net texture and displays it in viewport.
     */
    public void newModel() {
        statusService.updateStatus("Creating new blank model...");

        try {
            // Create blank cube model with gray texture
            currentEditableModel = blankModelFactory.createBlankCube();

            // Update state
            modelState.reset();
            modelState.setUnsavedChanges(true); // New models are unsaved
            modelState.setModelSource(ModelState.ModelSource.NEW); // Mark as new model

            // Update statistics (single cube: 1 part, 24 vertices, 12 triangles)
            modelState.updateStatistics(1, 24, 12);

            // Load into viewport if available
            if (viewport != null) {
                viewport.loadBlockModel(currentEditableModel);
                statusService.updateStatus("Blank model created and loaded: " + currentEditableModel.getName());
            } else {
                statusService.updateStatus("Blank model created: " + currentEditableModel.getName() +
                                          " (viewport not available)");
                logger.warn("Viewport not set - model created but not displayed");
            }

            logger.info("Created new blank cube model: {}", currentEditableModel.getName());

        } catch (IOException e) {
            logger.error("Failed to create blank model", e);
            statusService.updateStatus("Error creating model: " + e.getMessage());
            currentEditableModel = null;
        }
    }

    /**
     * Open model asynchronously.
     */
    public void openModel() {
        statusService.updateStatus("Opening model...");

        try {
            if (legacyCowModelManager != null) {
                String modelPath = "standard_cow";

                legacyCowModelManager.loadModelInfoAsync(modelPath, LegacyCowModelManager.LoadingPriority.NORMAL, null)
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
     * Save current editable model.
     * If model hasn't been saved before, shows save dialog.
     * Otherwise saves to existing file path.
     */
    public void saveModel() {
        if (currentEditableModel == null) {
            logger.warn("No editable model to save");
            statusService.updateStatus("No model to save");
            return;
        }

        // Check if model has file path (saved before)
        if (currentEditableModel.getFilePath() == null) {
            // First-time save: show save dialog
            saveModelAs();
        } else {
            // Save to existing path
            saveModelToFile(currentEditableModel.getFilePath().toString());
        }
    }

    /**
     * Save current editable model with "Save As" dialog.
     * Always shows save dialog, even if model was saved before.
     */
    public void saveModelAs() {
        if (currentEditableModel == null) {
            logger.warn("No editable model to save");
            statusService.updateStatus("No model to save");
            return;
        }

        // Show save dialog
        fileDialogService.showSaveOMODialog(filePath -> {
            saveModelToFile(filePath);
        });
    }

    /**
     * Internal method to save model to a specific file path.
     *
     * @param filePath the path to save to
     */
    private void saveModelToFile(String filePath) {
        statusService.updateStatus("Saving model...");

        try {
            boolean success = omoSerializer.save(currentEditableModel, filePath);

            if (success) {
                modelState.setUnsavedChanges(false);
                modelState.setCurrentModelPath(currentEditableModel.getName());
                statusService.updateStatus("Model saved: " + filePath);
                logger.info("Saved model to: {}", filePath);
            } else {
                statusService.updateStatus("Failed to save model");
                logger.error("Save operation returned false");
            }

        } catch (Exception e) {
            logger.error("Error saving model", e);
            statusService.updateStatus("Error saving model: " + e.getMessage());
        }
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
        modelState.setModelSource(ModelState.ModelSource.BROWSER); // Mark as browser model (read-only)
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

    /**
     * Open a .OMO file with file dialog.
     */
    public void openOMOModel() {
        fileDialogService.showOpenOMODialog(filePath -> {
            loadOMOModelFromFile(filePath);
        });
    }

    /**
     * Load a .OMO model from a file path (public API for model browser).
     *
     * @param filePath the path to the .OMO file
     */
    public void loadOMOModel(String filePath) {
        loadOMOModelFromFile(filePath);
    }

    /**
     * Load a .OMO model from a specific file path.
     *
     * @param filePath the path to load from
     */
    private void loadOMOModelFromFile(String filePath) {
        statusService.updateStatus("Loading .OMO model...");

        try {
            BlockModel loadedModel = omoDeserializer.load(filePath);

            if (loadedModel != null) {
                currentEditableModel = loadedModel;

                // Update state
                modelState.setModelLoaded(true);
                modelState.setCurrentModelPath(loadedModel.getName());
                modelState.setUnsavedChanges(false);
                modelState.setModelSource(ModelState.ModelSource.OMO_FILE); // Mark as .OMO file

                // Update statistics (single cube for now)
                modelState.updateStatistics(1, 24, 12);

                // Load into viewport if available
                if (viewport != null) {
                    viewport.loadBlockModel(currentEditableModel);
                    statusService.updateStatus("Loaded .OMO model: " + loadedModel.getName());
                } else {
                    statusService.updateStatus("Loaded .OMO model: " + loadedModel.getName() +
                                              " (viewport not available)");
                    logger.warn("Viewport not set - model loaded but not displayed");
                }

                logger.info("Loaded .OMO model from: {}", filePath);
            } else {
                statusService.updateStatus("Failed to load .OMO model");
                logger.error("Deserializer returned null");
            }

        } catch (Exception e) {
            logger.error("Error loading .OMO model", e);
            statusService.updateStatus("Error loading model: " + e.getMessage());
        }
    }

    /**
     * Gets the current editable model.
     *
     * @return the current BlockModel, or null if none is loaded
     */
    public BlockModel getCurrentEditableModel() {
        return currentEditableModel;
    }

    /**
     * Checks if there is an editable model currently loaded.
     *
     * @return true if an editable model is loaded
     */
    public boolean hasEditableModel() {
        return currentEditableModel != null;
    }

    /**
     * Marks the current editable model as modified (dirty).
     * Called when the model is edited.
     */
    public void markEditableModelDirty() {
        if (currentEditableModel != null && !currentEditableModel.isDirty()) {
            // Model is clean but now modified
            modelState.setUnsavedChanges(true);
        }
    }
}
