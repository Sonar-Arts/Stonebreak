package com.openmason.ui.dialogs;

import com.openmason.ui.services.StatusService;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NativeFileDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;

import static org.lwjgl.util.nfd.NativeFileDialog.*;

/**
 * File dialog service for open/save/export operations.
 * Follows Single Responsibility Principle - only handles file dialogs.
 * Follows DRY - eliminates duplicated file chooser code.
 *
 * Uses LWJGL NFD for native file dialogs (PNG files) and Swing for legacy model dialogs.
 */
public class FileDialogService {

    private static final Logger logger = LoggerFactory.getLogger(FileDialogService.class);

    private final StatusService statusService;

    public FileDialogService(StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * Show save model dialog.
     */
    public void showSaveDialog(SaveCallback callback) {
        statusService.updateStatus("Saving model as...");

        try {
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = createFileChooser("Save Model As");

                FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON Model Files (*.json)", "json");
                fileChooser.addChoosableFileFilter(jsonFilter);
                fileChooser.setFileFilter(jsonFilter);

                int result = fileChooser.showSaveDialog(null);

                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = ensureExtension(fileChooser.getSelectedFile(), "json");
                    callback.onSave(selectedFile);
                } else {
                    statusService.updateStatus("Save cancelled");
                }
            });
        } catch (Exception e) {
            logger.error("Error showing save dialog", e);
            statusService.updateStatus("Error opening save dialog: " + e.getMessage());
        }
    }

    /**
     * Show export model dialog.
     */
    public void showExportDialog(ExportCallback callback) {
        statusService.updateStatus("Exporting model...");

        try {
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = createFileChooser("Export Model");

                FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON Model (*.json)", "json");
                fileChooser.addChoosableFileFilter(jsonFilter);
                fileChooser.setFileFilter(jsonFilter);

                int result = fileChooser.showSaveDialog(null);

                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    FileNameExtensionFilter selectedFilter = (FileNameExtensionFilter) fileChooser.getFileFilter();
                    String extension = selectedFilter.getExtensions()[0];

                    selectedFile = ensureExtension(selectedFile, extension);
                    callback.onExport(selectedFile, extension);
                } else {
                    statusService.updateStatus("Export cancelled");
                }
            });
        } catch (Exception e) {
            logger.error("Error showing export dialog", e);
            statusService.updateStatus("Error opening export dialog: " + e.getMessage());
        }
    }

    /**
     * Create configured file chooser.
     */
    private JFileChooser createFileChooser(String title) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(title);
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        return fileChooser;
    }

    /**
     * Ensure file has correct extension.
     */
    private File ensureExtension(File file, String extension) {
        String filePath = file.getAbsolutePath();
        if (!filePath.toLowerCase().endsWith("." + extension)) {
            filePath += "." + extension;
            file = new File(filePath);
        }
        return file;
    }

    /**
     * Callback interface for save operations.
     */
    public interface SaveCallback {
        void onSave(File file);
    }

    /**
     * Callback interface for export operations.
     */
    public interface ExportCallback {
        void onExport(File file, String format);
    }

    /**
     * Show open PNG dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenPNGDialog(OpenCallback callback) {
        statusService.updateStatus("Opening PNG file...");

        try {
            // Initialize NFD
            int initResult = NFD_Init();
            if (initResult != NFD_OKAY) {
                logger.error("Failed to initialize NFD: {}", NFD_GetError());
                statusService.updateStatus("Error: Failed to initialize file dialog");
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Create filter for PNG files
                NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1, stack);
                filters.get(0)
                        .name(stack.UTF8("PNG Image"))
                        .spec(stack.UTF8("png"));

                PointerBuffer outPath = stack.mallocPointer(1);

                // Show open dialog
                int result = NFD_OpenDialog(outPath, filters, (CharSequence) null);

                if (result == NFD_OKAY) {
                    String selectedPath = outPath.getStringUTF8(0);
                    NFD_FreePath(outPath.get(0));
                    logger.info("Selected file: {}", selectedPath);
                    callback.onOpen(selectedPath);
                    statusService.updateStatus("Opened: " + new File(selectedPath).getName());
                } else if (result == NFD_CANCEL) {
                    logger.info("User cancelled file dialog");
                    statusService.updateStatus("Open cancelled");
                } else {
                    logger.error("NFD Error: {}", NFD_GetError());
                    statusService.updateStatus("Error opening file dialog");
                }
            }

            NFD_Quit();

        } catch (Exception e) {
            logger.error("Error showing open PNG dialog", e);
            statusService.updateStatus("Error: " + e.getMessage());
        }
    }

    /**
     * Show save PNG dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSavePNGDialog(SavePNGCallback callback) {
        statusService.updateStatus("Saving PNG file...");

        try {
            // Initialize NFD
            int initResult = NFD_Init();
            if (initResult != NFD_OKAY) {
                logger.error("Failed to initialize NFD: {}", NFD_GetError());
                statusService.updateStatus("Error: Failed to initialize file dialog");
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Create filter for PNG files
                NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1, stack);
                filters.get(0)
                        .name(stack.UTF8("PNG Image"))
                        .spec(stack.UTF8("png"));

                PointerBuffer outPath = stack.mallocPointer(1);

                // Show save dialog
                int result = NFD_SaveDialog(outPath, filters, null, stack.UTF8("texture.png"));

                if (result == NFD_OKAY) {
                    String selectedPath = outPath.getStringUTF8(0);
                    NFD_FreePath(outPath.get(0));

                    // Ensure .png extension
                    if (!selectedPath.toLowerCase().endsWith(".png")) {
                        selectedPath += ".png";
                    }

                    logger.info("Save to file: {}", selectedPath);
                    callback.onSave(selectedPath);
                    statusService.updateStatus("Saved: " + new File(selectedPath).getName());
                } else if (result == NFD_CANCEL) {
                    logger.info("User cancelled save dialog");
                    statusService.updateStatus("Save cancelled");
                } else {
                    logger.error("NFD Error: {}", NFD_GetError());
                    statusService.updateStatus("Error opening save dialog");
                }
            }

            NFD_Quit();

        } catch (Exception e) {
            logger.error("Error showing save PNG dialog", e);
            statusService.updateStatus("Error: " + e.getMessage());
        }
    }

    /**
     * Callback interface for open operations.
     */
    public interface OpenCallback {
        void onOpen(String filePath);
    }

    /**
     * Callback interface for PNG save operations.
     */
    public interface SavePNGCallback {
        void onSave(String filePath);
    }
}
