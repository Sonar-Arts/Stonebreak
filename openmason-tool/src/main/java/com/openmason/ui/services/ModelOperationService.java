package com.openmason.ui.services;

import com.openmason.model.editable.BlockModel;
import com.openmason.model.factory.BlankModelFactory;
import com.openmason.model.io.omo.OMODeserializer;
import com.openmason.model.io.omo.OMOSerializer;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.ViewportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Model operation service.
 */
public class ModelOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ModelOperationService.class);

    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;

    // Editable model support
    private final BlankModelFactory blankModelFactory;
    private final OMOSerializer omoSerializer;
    private final OMODeserializer omoDeserializer;
    private BlockModel currentEditableModel;

    // UI component references
    private ViewportController viewport;
    private com.openmason.ui.properties.PropertyPanelImGui propertiesPanel;

    public ModelOperationService(ModelState modelState, StatusService statusService,
                                 FileDialogService fileDialogService) {
        this.modelState = modelState;
        this.statusService = statusService;
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
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
        logger.debug("Viewport reference set in ModelOperationService");
    }

    /**
     * Sets the properties panel reference for editable models.
     * Called by MainImGuiInterface after properties panel is created.
     *
     * @param propertiesPanel the properties panel instance
     */
    public void setPropertiesPanel(com.openmason.ui.properties.PropertyPanelImGui propertiesPanel) {
        this.propertiesPanel = propertiesPanel;
        logger.debug("Properties panel reference set in ModelOperationService");
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

            // Update properties panel with new model
            if (propertiesPanel != null) {
                propertiesPanel.setEditableModel(currentEditableModel);
            }

            logger.info("Created new blank cube model: {}", currentEditableModel.getName());

        } catch (IOException e) {
            logger.error("Failed to create blank model", e);
            statusService.updateStatus("Error creating model: " + e.getMessage());
            currentEditableModel = null;
        }
    }

    /**
     * Open model with file dialog.
     * Delegates to openOMOModel() to avoid code duplication (DRY).
     */
    public void openModel() {
        openOMOModel();
    }

    /**
     * Save current editable model.
     * If model hasn't been saved before, shows save dialog.
     * Otherwise, saves to existing file path.
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
            logger.warn("No editable model to save as");
            statusService.updateStatus("No model to save");
            return;
        }

        // Show save dialog
        fileDialogService.showSaveOMODialog(this::saveModelToFile);
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
     * Open a .OMO file with file dialog.
     */
    public void openOMOModel() {
        fileDialogService.showOpenOMODialog(this::loadOMOModelFromFile);
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

                // Update properties panel with loaded model
                if (propertiesPanel != null) {
                    propertiesPanel.setEditableModel(currentEditableModel);
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

}
