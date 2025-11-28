package com.openmason.ui.textureCreator.coordinators;

import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.textureCreator.imports.ImportAction;
import com.openmason.ui.textureCreator.imports.ImportStrategyResolver;
import com.openmason.ui.dialogs.FileDialogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates all file operations (open, save, import, export).
 */
public class FileOperationsCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(FileOperationsCoordinator.class);

    private final FileDialogService fileDialogService;
    private final TextureCreatorController controller;
    private final TextureCreatorState state;
    private final ImportStrategyResolver importResolver;

    // Track pending import for dialog flow
    private String pendingImportPath;

    /**
     * Create file operations coordinator.
     *
     * @param fileDialogService service for showing file dialogs
     * @param controller texture creator controller
     * @param state texture creator state
     * @param importResolver strategy resolver for imports
     */
    public FileOperationsCoordinator(FileDialogService fileDialogService,
                                     TextureCreatorController controller,
                                     TextureCreatorState state,
                                     ImportStrategyResolver importResolver) {
        this.fileDialogService = fileDialogService;
        this.controller = controller;
        this.state = state;
        this.importResolver = importResolver;
    }

    /**
     * Open project file (.OMT).
     */
    public void openProject() {
        fileDialogService.showOpenOMTDialog(filePath -> {
            logger.info("Open project: {}", filePath);
            try {
                boolean success = controller.loadProject(filePath);
                if (success) {
                    logger.info("Open project succeeded: {}", filePath);
                } else {
                    logger.error("Open project failed: {}", filePath);
                }
            } catch (Exception e) {
                logger.error("Open project failed with exception: {}", filePath, e);
            }
        });
    }

    /**
     * Save project to current file path.
     * If no file path exists, delegates to saveProjectAs().
     */
    public void saveProject() {
        if (state.hasFilePath() && state.isProjectFile()) {
            // Save directly to existing file
            String filePath = state.getCurrentFilePath();
            logger.info("Saving project to: {}", filePath);

            try {
                boolean success = controller.saveProject(filePath);
                if (success) {
                    logger.info("Successfully saved project");
                } else {
                    logger.error("Failed to save project");
                }
            } catch (Exception e) {
                logger.error("Failed to save project: {}", filePath, e);
            }
        } else {
            // Show Save As dialog
            saveProjectAs();
        }
    }

    /**
     * Save project as (always shows dialog).
     */
    public void saveProjectAs() {
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Save project as: {}", filePath);
            try {
                boolean success = controller.saveProject(filePath);
                if (success) {
                    logger.info("Save project as succeeded: {}", filePath);
                } else {
                    logger.error("Save project as failed: {}", filePath);
                }
            } catch (Exception e) {
                logger.error("Save project as failed with exception: {}", filePath, e);
            }
        });
    }

    /**
     * Import PNG with intelligent dimension detection.
     * - Detects PNG dimensions
     * - Auto-imports if exact match (16x16 or 64x48)
     * - Shows dialog for other sizes
     *
     * @param importPNGDialog dialog to show for non-standard sizes
     */
    public void importPNG(ImportPNGDialog importPNGDialog) {
        fileDialogService.showOpenPNGDialog(filePath -> {
            logger.info("Detecting PNG dimensions: {}", filePath);

            // Detect PNG dimensions
            int[] dimensions = controller.getImporter().getPNGDimensions(filePath);

            if (dimensions == null) {
                logger.error("Failed to detect PNG dimensions: {}", filePath);
                return;
            }

            int width = dimensions[0];
            int height = dimensions[1];

            logger.debug("PNG dimensions detected: {}x{}", width, height);

            // Use strategy resolver to determine import action
            ImportAction action = importResolver.resolveForPNG(filePath, width, height);

            if (action.isAutoImport()) {
                // Exact match - auto-import without dialog
                logger.info("Exact match detected, auto-importing to {} canvas",
                    action.getTargetSize().getDisplayName());

                boolean success = controller.importTexture(filePath, action.getTargetSize());
                if (success) {
                    logger.info("Successfully auto-imported PNG: {}", filePath);
                } else {
                    logger.error("Failed to auto-import PNG: {}", filePath);
                }
            } else if (action.isShowDialog()) {
                // Non-matching size - show dialog for user to choose target
                logger.info("Non-standard size ({}x{}), showing import dialog",
                    action.getSourceWidth(), action.getSourceHeight());
                pendingImportPath = filePath;
                importPNGDialog.show(action.getSourceWidth(), action.getSourceHeight());
            } else if (action.isReject()) {
                logger.error("Import rejected: {}", action.getRejectReason());
            }
        });
    }

    /**
     * Process confirmed PNG import from dialog.
     * Call this after dialog confirms a canvas size selection.
     *
     * @param selectedSize the canvas size selected by user
     */
    public void processPendingPNGImport(TextureCreatorState.CanvasSize selectedSize) {
        if (pendingImportPath == null || selectedSize == null) {
            return;
        }

        String filePath = pendingImportPath;
        pendingImportPath = null; // Clear pending path

        logger.info("Processing pending PNG import: {} to {} canvas",
            filePath, selectedSize.getDisplayName());

        boolean success = controller.importTexture(filePath, selectedSize);
        if (success) {
            logger.info("Successfully imported PNG: {}", filePath);
        } else {
            logger.error("Failed to import PNG: {}", filePath);
        }

    }

    /**
     * Export with format selection dialog.
     */
    public void export() {
        // This is handled by showing ExportFormatDialog in the UI
        // The dialog will then call exportPNG() or exportOMT()
        logger.debug("Export format dialog should be shown");
    }

    /**
     * Export as PNG (flattened image).
     */
    public void exportPNG() {
        fileDialogService.showSavePNGDialog(filePath -> {
            logger.info("Export as PNG: {}", filePath);
            try {
                boolean success = controller.exportTexture(filePath);
                if (success) {
                    logger.info("Export as PNG succeeded: {}", filePath);
                } else {
                    logger.error("Export as PNG failed: {}", filePath);
                }
            } catch (Exception e) {
                logger.error("Export as PNG failed with exception: {}", filePath, e);
            }
        });
    }

    /**
     * Export as OMT (project copy).
     */
    public void exportOMT() {
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Export as OMT: {}", filePath);
            try {
                boolean success = controller.saveProject(filePath);
                if (success) {
                    logger.info("Export as OMT succeeded: {}", filePath);
                } else {
                    logger.error("Export as OMT failed: {}", filePath);
                }
            } catch (Exception e) {
                logger.error("Export as OMT failed with exception: {}", filePath, e);
            }
        });
    }

}
