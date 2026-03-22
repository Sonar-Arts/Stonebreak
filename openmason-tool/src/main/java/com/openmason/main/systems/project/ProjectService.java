package com.openmason.main.systems.project;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.viewport.ViewportCamera;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.state.RenderingMode;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Orchestrates project-level save/load operations.
 * Extracts state from live objects into an OMP document on save,
 * and restores state from a document into live objects on load.
 */
public class ProjectService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final OMPSerializer serializer;
    private final OMPDeserializer deserializer;

    private String currentProjectPath;
    private String currentProjectName;
    private String createdAt;
    private boolean dirty;

    public ProjectService() {
        this.serializer = new OMPSerializer();
        this.deserializer = new OMPDeserializer();
    }

    /**
     * Extract current session state into an OMP document.
     */
    public OMPFormat.Document extractState(ViewportController viewport, ModelState modelState,
                                            UIVisibilityState uiState, String projectName) {
        ViewportCamera camera = viewport.getCamera();
        ViewportUIState viewportUIState = viewport.getViewportUIState();
        TransformState transformState = viewport.getTransformState();
        RenderingState renderingState = viewport.getRenderingState();

        // Camera state
        OMPFormat.CameraState cameraState = new OMPFormat.CameraState(
                camera.getCameraMode().name(),
                camera.getDistance(),
                camera.getPitch(),
                camera.getYaw(),
                camera.getFov()
        );

        // Viewport state
        OMPFormat.ViewportState viewportState = new OMPFormat.ViewportState(
                viewportUIState.getCurrentViewModeIndex().get(),
                viewportUIState.getCurrentRenderModeIndex().get(),
                viewportUIState.getGridVisible().get(),
                viewportUIState.getAxesVisible().get(),
                viewportUIState.getWireframeMode().get(),
                viewportUIState.getShowVertices().get(),
                viewportUIState.getShowGizmo().get(),
                viewportUIState.getGridSnappingEnabled().get(),
                viewportUIState.getGridSnappingIncrement().get()
        );

        // Transform state
        OMPFormat.TransformData transformData = new OMPFormat.TransformData(
                transformState.getPositionX(),
                transformState.getPositionY(),
                transformState.getPositionZ(),
                transformState.getRotationX(),
                transformState.getRotationY(),
                transformState.getRotationZ(),
                transformState.getScaleX(),
                transformState.getScaleY(),
                transformState.getScaleZ(),
                transformState.isGizmoEnabled()
        );

        // Model reference with relative path (use actual .OMO file path, not model name)
        String modelFilePath = resolveModelPathForSave(modelState.getCurrentOMOFilePath());
        OMPFormat.ModelReference modelRef = new OMPFormat.ModelReference(
                renderingState.getMode().name(),
                renderingState.getCurrentModelName(),
                renderingState.getCurrentTextureVariant(),
                renderingState.getSelectedBlock() != null ? renderingState.getSelectedBlock().name() : null,
                renderingState.getSelectedItem() != null ? renderingState.getSelectedItem().name() : null,
                modelState.getModelSource().name(),
                modelFilePath
        );

        // UI state
        OMPFormat.UIState ui = new OMPFormat.UIState(
                uiState.getShowModelBrowser().get(),
                uiState.getShowPropertyPanel().get(),
                uiState.getShowToolbar().get()
        );

        String now = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        return new OMPFormat.Document(
                OMPFormat.FORMAT_VERSION,
                projectName != null ? projectName : "Untitled Project",
                createdAt != null ? createdAt : now,
                now,
                cameraState,
                viewportState,
                transformData,
                modelRef,
                ui
        );
    }

    /**
     * Restore an OMP document into live objects.
     */
    public void restoreState(OMPFormat.Document document, ViewportController viewport,
                              ModelState modelState, UIVisibilityState uiState,
                              ModelOperationService modelOperations) {
        // Restore camera
        if (document.camera() != null) {
            ViewportCamera camera = viewport.getCamera();
            try {
                ViewportCamera.CameraMode mode = ViewportCamera.CameraMode.valueOf(document.camera().mode());
                camera.setCameraMode(mode);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown camera mode '{}', keeping current", document.camera().mode());
            }
            camera.setDistance(document.camera().distance());
            camera.setPitch(document.camera().pitch());
            camera.setYaw(document.camera().yaw());
            camera.setFov(document.camera().fov());
        }

        // Restore viewport settings
        if (document.viewport() != null) {
            ViewportUIState viewportUIState = viewport.getViewportUIState();
            viewportUIState.getCurrentViewModeIndex().set(document.viewport().viewModeIndex());
            viewportUIState.getCurrentRenderModeIndex().set(document.viewport().renderModeIndex());
            viewportUIState.getGridVisible().set(document.viewport().gridVisible());
            viewportUIState.getAxesVisible().set(document.viewport().axesVisible());
            viewportUIState.getWireframeMode().set(document.viewport().wireframeMode());
            viewportUIState.getShowVertices().set(document.viewport().showVertices());
            viewportUIState.getShowGizmo().set(document.viewport().showGizmo());
            viewportUIState.getGridSnappingEnabled().set(document.viewport().gridSnappingEnabled());
            viewportUIState.getGridSnappingIncrement().set(document.viewport().gridSnappingIncrement());
        }

        // Restore UI visibility
        if (document.ui() != null) {
            uiState.getShowModelBrowser().set(document.ui().showModelBrowser());
            uiState.getShowPropertyPanel().set(document.ui().showPropertyPanel());
            uiState.getShowToolbar().set(document.ui().showToolbar());
        }

        // Restore model reference BEFORE transform, because model loading
        // resets the transform position (ContentTypeManager.switchToModel
        // calls transformState.resetPosition())
        if (document.model() != null) {
            restoreModel(document.model(), viewport, modelState, modelOperations);
        }

        // Restore transform AFTER model loading so it isn't overwritten
        if (document.transform() != null) {
            TransformState transformState = viewport.getTransformState();
            transformState.setPosition(
                    document.transform().positionX(),
                    document.transform().positionY(),
                    document.transform().positionZ()
            );
            transformState.setRotation(
                    document.transform().rotationX(),
                    document.transform().rotationY(),
                    document.transform().rotationZ()
            );
            transformState.setScale(
                    document.transform().scaleX(),
                    document.transform().scaleY(),
                    document.transform().scaleZ()
            );
            transformState.setGizmoEnabled(document.transform().gizmoEnabled());
        }

        logger.info("Project state restored: {}", document.projectName());
    }

    /**
     * Restore the model from a project reference.
     * Loads the .OMO file if it exists; otherwise restores metadata only.
     */
    private void restoreModel(OMPFormat.ModelReference modelRef, ViewportController viewport,
                               ModelState modelState, ModelOperationService modelOperations) {
        // Restore rendering mode metadata
        RenderingState renderingState = viewport.getRenderingState();
        if (modelRef.textureVariant() != null) {
            renderingState.setCurrentTextureVariant(modelRef.textureVariant());
        }

        // Attempt to load model file
        if (modelRef.modelFilePath() != null && !modelRef.modelFilePath().isBlank()) {
            String absolutePath = resolveModelPathForLoad(modelRef.modelFilePath());

            if (absolutePath != null && new File(absolutePath).exists()) {
                try {
                    ModelState.ModelSource source = ModelState.ModelSource.valueOf(modelRef.modelSource());
                    if (source == ModelState.ModelSource.OMO_FILE || source == ModelState.ModelSource.NEW) {
                        modelOperations.loadOMOModel(absolutePath);
                        logger.info("Model loaded from project: {}", absolutePath);
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown model source '{}', skipping model load", modelRef.modelSource());
                }
            } else {
                logger.warn("Referenced model file not found: {} (resolved: {}). " +
                        "Camera and viewport state restored, but model is missing.",
                        modelRef.modelFilePath(), absolutePath);
            }
        }

        // Restore block/item selection if applicable
        try {
            RenderingMode mode = RenderingMode.valueOf(modelRef.renderingMode());
            if (mode == RenderingMode.BLOCK && modelRef.selectedBlockType() != null) {
                BlockType blockType = BlockType.valueOf(modelRef.selectedBlockType());
                viewport.setSelectedBlock(blockType);
            } else if (mode == RenderingMode.ITEM && modelRef.selectedItemType() != null) {
                ItemType itemType = ItemType.valueOf(modelRef.selectedItemType());
                viewport.setSelectedItem(itemType);
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to restore rendering mode/selection: {}", e.getMessage());
        }
    }

    /**
     * Save project to the current path.
     *
     * @return true if saved successfully
     */
    public boolean saveProject(ViewportController viewport, ModelState modelState,
                                UIVisibilityState uiState) {
        if (currentProjectPath == null || currentProjectPath.isBlank()) {
            logger.warn("No current project path set. Use saveProjectAs() instead.");
            return false;
        }

        OMPFormat.Document document = extractState(viewport, modelState, uiState, currentProjectName);
        boolean success = serializer.save(document, currentProjectPath);
        if (success) {
            dirty = false;
        }
        return success;
    }

    /**
     * Save project to a new path (Save As).
     *
     * @return true if saved successfully
     */
    public boolean saveProjectAs(String filePath, ViewportController viewport,
                                  ModelState modelState, UIVisibilityState uiState,
                                  String projectName) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }

        this.currentProjectPath = OMPFormat.ensureExtension(filePath);
        this.currentProjectName = projectName != null ? projectName : deriveProjectName(filePath);
        this.createdAt = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        OMPFormat.Document document = extractState(viewport, modelState, uiState, currentProjectName);
        boolean success = serializer.save(document, currentProjectPath);
        if (success) {
            dirty = false;
            logger.info("Project saved as: {}", currentProjectPath);
        }
        return success;
    }

    /**
     * Open a project from a file path.
     *
     * @return true if loaded successfully
     */
    public boolean openProject(String filePath, ViewportController viewport,
                                ModelState modelState, UIVisibilityState uiState,
                                ModelOperationService modelOperations) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }

        OMPFormat.Document document = deserializer.load(filePath);
        if (document == null) {
            logger.error("Failed to load project: {}", filePath);
            return false;
        }

        this.currentProjectPath = filePath;
        this.currentProjectName = document.projectName();
        this.createdAt = document.createdAt();

        restoreState(document, viewport, modelState, uiState, modelOperations);
        dirty = false;

        logger.info("Project opened: {} ({})", currentProjectName, filePath);
        return true;
    }

    /**
     * Resolve a model file path for saving (make relative to .OMP location).
     */
    private String resolveModelPathForSave(String modelPath) {
        if (modelPath == null || modelPath.isBlank() || currentProjectPath == null) {
            return modelPath;
        }

        try {
            Path ompParent = Path.of(currentProjectPath).getParent();
            if (ompParent == null) {
                return modelPath;
            }
            Path modelAbsolute = Path.of(modelPath).toAbsolutePath();
            return ompParent.toAbsolutePath().relativize(modelAbsolute).toString();
        } catch (IllegalArgumentException e) {
            // Cross-drive on Windows or other relativization failure — use absolute
            logger.debug("Cannot relativize model path, using absolute: {}", e.getMessage());
            return modelPath;
        }
    }

    /**
     * Resolve a model file path for loading (resolve relative to .OMP location).
     */
    private String resolveModelPathForLoad(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return null;
        }

        Path modelPathObj = Path.of(modelPath);

        // If already absolute, use as-is
        if (modelPathObj.isAbsolute()) {
            return modelPathObj.toString();
        }

        // Resolve relative to .OMP file location
        if (currentProjectPath != null) {
            Path ompParent = Path.of(currentProjectPath).getParent();
            if (ompParent != null) {
                return ompParent.resolve(modelPathObj).toAbsolutePath().toString();
            }
        }

        return modelPath;
    }

    /**
     * Derive a project name from a file path.
     */
    private String deriveProjectName(String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        if (fileName.toLowerCase().endsWith(OMPFormat.FILE_EXTENSION)) {
            return fileName.substring(0, fileName.length() - OMPFormat.FILE_EXTENSION.length());
        }
        return fileName;
    }

    // Accessors

    public boolean hasUnsavedChanges() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public String getCurrentProjectPath() {
        return currentProjectPath;
    }

    public String getCurrentProjectName() {
        return currentProjectName;
    }

    public boolean hasCurrentProject() {
        return currentProjectPath != null && !currentProjectPath.isBlank();
    }

    /**
     * Clear all project state (no active project).
     * Called when transitioning to a blank/new editor session.
     */
    public void clearCurrentProject() {
        this.currentProjectPath = null;
        this.currentProjectName = null;
        this.createdAt = null;
        this.dirty = false;
        logger.debug("Project state cleared");
    }
}
