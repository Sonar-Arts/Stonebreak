package com.openmason.main.systems.menus.textureCreator.coordinators;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import com.openmason.main.systems.menus.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.main.systems.menus.textureCreator.imports.ImportAction;
import com.openmason.main.systems.menus.textureCreator.imports.ImportStrategyResolver;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
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

    /** The file dialog service backing this coordinator's save/open dialogs. */
    public FileDialogService getFileDialogService() {
        return fileDialogService;
    }

    /**
     * Open project file (.OMT) as a document tab (or focus its tab when it is already open).
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
     * Save the active texture to its .OMT, or ask where (Save As) when it has none.
     */
    public void saveProject() {
        if (!controller.hasOpenDocument()) {
            return;
        }
        saveDocumentNow(controller.getActiveDocument());
    }

    /**
     * Save the active texture as a new .OMT (always shows the dialog).
     */
    public void saveProjectAs() {
        if (!controller.hasOpenDocument()) {
            return;
        }
        saveDocumentAs(controller.getActiveDocument());
    }

    /**
     * Save {@code document} in place when it has a .OMT, else through Save As. The file dialog is
     * modal, so on return the outcome is known.
     *
     * @return true when the document is clean afterwards (false: the save failed or the user
     *         backed out of the dialog)
     */
    public boolean saveDocumentNow(TextureDocument document) {
        String path = document.projectPath();
        if (path == null) {
            saveDocumentAs(document);
            return !document.hasUnsavedChanges();
        }
        logger.info("Saving texture {} to: {}", document.id(), path);
        try {
            if (!controller.saveDocument(document, path)) {
                logger.error("Failed to save texture {}", document.id());
            }
        } catch (Exception e) {
            logger.error("Failed to save texture {}: {}", document.id(), path, e);
        }
        return !document.hasUnsavedChanges();
    }

    /** Save As for one document (the one active when the command started, whatever is active later). */
    private void saveDocumentAs(TextureDocument document) {
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Save texture {} as: {}", document.id(), filePath);
            try {
                if (!controller.saveDocument(document, filePath)) {
                    logger.error("Save project as failed: {}", filePath);
                }
            } catch (Exception e) {
                logger.error("Save project as failed with exception: {}", filePath, e);
            }
        });
    }

    /**
     * Import PNG with intelligent dimension detection.
     * <ul>
     *   <li>If face-region editing is active, the PNG is resized to fit the face
     *       and stamped onto the active canvas (respecting the face mask).</li>
     *   <li>Otherwise, detects PNG dimensions and auto-imports (or shows a dialog) into a new
     *       document tab.</li>
     * </ul>
     *
     * @param importPNGDialog dialog to show for non-standard sizes
     */
    public void importPNG(ImportPNGDialog importPNGDialog) {
        fileDialogService.showOpenPNGDialog(filePath -> {
            // Face-region mode: import directly into the face region
            if (controller.isFaceRegionActive()) {
                logger.info("Face region active — importing PNG to face: {}", filePath);
                boolean success = controller.importTextureToFaceRegion(filePath);
                if (success) {
                    logger.info("Successfully imported PNG to face region: {}", filePath);
                } else {
                    logger.error("Failed to import PNG to face region: {}", filePath);
                }
                return;
            }

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
        TextureDocument document = controller.getActiveDocument();
        fileDialogService.showSavePNGDialog(filePath -> {
            logger.info("Export as PNG: {}", filePath);
            try {
                boolean success = controller.exportDocument(document, filePath);
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
        TextureDocument document = controller.getActiveDocument();
        fileDialogService.showSaveOMTDialog(filePath -> {
            logger.info("Export as OMT: {}", filePath);
            try {
                boolean success = controller.saveDocument(document, filePath);
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
