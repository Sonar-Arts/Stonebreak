package com.openmason.ui.textureCreator.rendering;

import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.ui.textureCreator.dialogs.ImportPNGDialog;
import com.openmason.ui.textureCreator.dialogs.NewTextureDialog;
import com.openmason.ui.textureCreator.dialogs.OMTImportDialog;
import com.openmason.ui.textureCreator.io.DragDropHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes dialog results and confirmations.
 * Follows Single Responsibility Principle - only handles dialog result processing.
 *
 * @author Open Mason Team
 */
public class DialogProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DialogProcessor.class);

    private final TextureCreatorController controller;
    private final FileOperationsCoordinator fileOperations;
    private final DragDropHandler dragDropHandler;
    private final NewTextureDialog newTextureDialog;
    private final ImportPNGDialog importPNGDialog;
    private final OMTImportDialog omtImportDialog;

    /**
     * Create dialog processor.
     */
    public DialogProcessor(TextureCreatorController controller,
                          FileOperationsCoordinator fileOperations,
                          DragDropHandler dragDropHandler,
                          NewTextureDialog newTextureDialog,
                          ImportPNGDialog importPNGDialog,
                          OMTImportDialog omtImportDialog) {
        this.controller = controller;
        this.fileOperations = fileOperations;
        this.dragDropHandler = dragDropHandler;
        this.newTextureDialog = newTextureDialog;
        this.importPNGDialog = importPNGDialog;
        this.omtImportDialog = omtImportDialog;
    }

    /**
     * Process all dialog results.
     * Call this once per frame to check for confirmed dialog selections.
     */
    public void processAll() {
        processNewTextureDialog();
        processImportPNGDialog();
        processOMTImportDialog();
    }

    /**
     * Process new texture dialog confirmation.
     */
    private void processNewTextureDialog() {
        TextureCreatorState.CanvasSize selectedSize = newTextureDialog.getSelectedCanvasSize();
        if (selectedSize != null) {
            controller.newTexture(selectedSize);
        }
    }

    /**
     * Process import PNG dialog confirmation.
     */
    private void processImportPNGDialog() {
        TextureCreatorState.CanvasSize importSize = importPNGDialog.getSelectedCanvasSize();
        if (importSize != null) {
            fileOperations.processPendingPNGImport(importSize);
        }
    }

    /**
     * Process OMT import dialog confirmation.
     */
    private void processOMTImportDialog() {
        OMTImportDialog.ImportMode omtChoice = omtImportDialog.getConfirmedChoice();
        if (omtChoice == OMTImportDialog.ImportMode.NONE) {
            return;
        }

        String omtFilePath = omtImportDialog.getPendingFilePath();
        if (omtFilePath == null) {
            return;
        }

        try {
            if (omtChoice == OMTImportDialog.ImportMode.FLATTEN) {
                dragDropHandler.importOMTFlattened(omtFilePath, controller.getLayerManager());
                logger.info("Successfully imported and flattened .OMT file: {}", omtFilePath);
            } else if (omtChoice == OMTImportDialog.ImportMode.IMPORT_ALL) {
                dragDropHandler.importOMTAllLayers(omtFilePath, controller.getLayerManager());
                logger.info("Successfully imported all layers from .OMT file: {}", omtFilePath);
            }
            controller.notifyLayerModified();
        } catch (Exception e) {
            logger.error("Failed to import .OMT file: {}", omtFilePath, e);
        }
    }
}
