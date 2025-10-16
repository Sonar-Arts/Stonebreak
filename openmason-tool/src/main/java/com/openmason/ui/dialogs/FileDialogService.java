package com.openmason.ui.dialogs;

import com.openmason.ui.services.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;

/**
 * File dialog service for open/save/export operations.
 * Follows Single Responsibility Principle - only handles file dialogs.
 * Follows DRY - eliminates duplicated file chooser code.
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
}
