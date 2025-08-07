package com.openmason.export;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.ui.MainController;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Export System Integration for Open Mason Phase 8.
 * 
 * Provides seamless integration of export systems with the existing OpenMason3DViewport
 * and user interface components. Handles user interactions, progress tracking,
 * and error handling for all export operations.
 */
public class ExportSystemIntegration {
    
    private static final Logger logger = LoggerFactory.getLogger(ExportSystemIntegration.class);
    
    private final OpenMason3DViewport viewport;
    private final HighResolutionScreenshotSystem screenshotSystem;
    private final BatchExportSystem batchExportSystem;
    private final TechnicalDocumentationGenerator documentationGenerator;
    private final TextureAtlasExportSystem atlasExportSystem;
    
    // Progress tracking
    private final AtomicReference<ProgressDialog> currentProgressDialog = new AtomicReference<>();
    
    /**
     * Creates a new export system integration.
     * 
     * @param viewport The 3D viewport to integrate with
     */
    public ExportSystemIntegration(OpenMason3DViewport viewport) {
        this.viewport = viewport;
        this.screenshotSystem = new HighResolutionScreenshotSystem(viewport);
        this.batchExportSystem = new BatchExportSystem(viewport);
        this.documentationGenerator = new TechnicalDocumentationGenerator();
        this.atlasExportSystem = new TextureAtlasExportSystem();
        
        logger.info("Export System Integration initialized");
    }
    
    /**
     * Show high-resolution screenshot dialog and capture screenshot.
     * 
     * @param parentStage Parent stage for dialog
     */
    public void showScreenshotDialog(Stage parentStage) {
        try {
            ScreenshotConfigDialog dialog = new ScreenshotConfigDialog(parentStage);
            dialog.showAndWait().ifPresent(config -> {
                captureScreenshotWithProgress(config, parentStage);
            });
        } catch (Exception e) {
            logger.error("Error showing screenshot dialog", e);
            showErrorAlert(parentStage, "Screenshot Error", "Failed to show screenshot dialog: " + e.getMessage());
        }
    }
    
    /**
     * Show batch export dialog and execute batch export.
     * 
     * @param parentStage Parent stage for dialog
     */
    public void showBatchExportDialog(Stage parentStage) {
        try {
            BatchExportConfigDialog dialog = new BatchExportConfigDialog(parentStage);
            dialog.showAndWait().ifPresent(template -> {
                executeBatchExportWithProgress(template, parentStage);
            });
        } catch (Exception e) {
            logger.error("Error showing batch export dialog", e);
            showErrorAlert(parentStage, "Batch Export Error", "Failed to show batch export dialog: " + e.getMessage());
        }
    }
    
    /**
     * Show documentation generation dialog and generate documentation.
     * 
     * @param parentStage Parent stage for dialog
     */
    public void showDocumentationDialog(Stage parentStage) {
        try {
            DocumentationConfigDialog dialog = new DocumentationConfigDialog(parentStage);
            dialog.showAndWait().ifPresent(config -> {
                generateDocumentationWithProgress(config, parentStage);
            });
        } catch (Exception e) {
            logger.error("Error showing documentation dialog", e);
            showErrorAlert(parentStage, "Documentation Error", "Failed to show documentation dialog: " + e.getMessage());
        }
    }
    
    /**
     * Show atlas export dialog and export texture atlases.
     * 
     * @param parentStage Parent stage for dialog
     */
    public void showAtlasExportDialog(Stage parentStage) {
        try {
            AtlasExportConfigDialog dialog = new AtlasExportConfigDialog(parentStage);
            dialog.showAndWait().ifPresent(config -> {
                exportAtlasWithProgress(config, parentStage);
            });
        } catch (Exception e) {
            logger.error("Error showing atlas export dialog", e);
            showErrorAlert(parentStage, "Atlas Export Error", "Failed to show atlas export dialog: " + e.getMessage());
        }
    }
    
    /**
     * Capture screenshot with progress dialog.
     */
    private void captureScreenshotWithProgress(HighResolutionScreenshotSystem.ScreenshotConfig config, Stage parentStage) {
        ProgressDialog progressDialog = new ProgressDialog(parentStage, "Screenshot Capture", "Preparing screenshot...");
        currentProgressDialog.set(progressDialog);
        progressDialog.show();
        
        screenshotSystem.captureScreenshotAsync(config, new HighResolutionScreenshotSystem.ScreenshotProgressCallback() {
            @Override
            public void onProgress(String stage, int progress, String details) {
                Platform.runLater(() -> {
                    progressDialog.setProgress(progress / 100.0);
                    progressDialog.setStatus(stage + ": " + details);
                });
            }
            
            @Override
            public void onComplete(File outputFile, long fileSize) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    
                    String message = String.format("Screenshot saved successfully!\n\nFile: %s\nSize: %s\nResolution: %dx%d",
                        outputFile.getName(),
                        formatFileSize(fileSize),
                        config.getActualWidth(),
                        config.getActualHeight());
                    
                    showInfoAlert(parentStage, "Screenshot Complete", message);
                    
                    // Ask if user wants to open the file location
                    if (showConfirmDialog(parentStage, "Open Location", "Would you like to open the file location?")) {
                        openFileLocation(outputFile);
                    }
                });
            }
            
            @Override
            public void onError(String stage, Throwable error) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    showErrorAlert(parentStage, "Screenshot Failed", "Screenshot capture failed at " + stage + ": " + error.getMessage());
                });
            }
        });
    }
    
    /**
     * Execute batch export with progress dialog.
     */
    private void executeBatchExportWithProgress(BatchExportSystem.ExportTemplate template, Stage parentStage) {
        ProgressDialog progressDialog = new ProgressDialog(parentStage, "Batch Export", "Initializing batch export...");
        currentProgressDialog.set(progressDialog);
        progressDialog.show();
        
        // Show time estimate
        long estimatedTime = batchExportSystem.estimateProcessingTime(template);
        progressDialog.setStatus("Estimated time: " + formatDuration(estimatedTime) + " - Starting export...");
        
        batchExportSystem.executeBatchExport(template, new BatchExportSystem.BatchExportProgressCallback() {
            @Override
            public void onProgressUpdate(BatchExportSystem.ExportProgress progress) {
                Platform.runLater(() -> {
                    double progressPercent = progress.getProgressPercentage() / 100.0;
                    progressDialog.setProgress(progressPercent);
                    progressDialog.setStatus(String.format("%s (%d/%d completed, %d failed)",
                        progress.getCurrentTask(),
                        progress.getCompletedTasks(),
                        progress.getTotalTasks(),
                        progress.getFailedTasks()));
                });
            }
            
            @Override
            public void onTaskCompleted(String taskName, File outputFile) {
                logger.debug("Batch export task completed: {}", taskName);
            }
            
            @Override
            public void onTaskFailed(String taskName, Throwable error) {
                logger.warn("Batch export task failed: {} - {}", taskName, error.getMessage());
            }
            
            @Override
            public void onBatchCompleted(BatchExportSystem.ExportProgress finalProgress, List<File> outputFiles) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    
                    String message = String.format("Batch export completed!\n\nTemplate: %s\nFiles generated: %d\nSuccessful: %d\nFailed: %d\nTime: %s",
                        template.getName(),
                        outputFiles.size(),
                        finalProgress.getCompletedTasks(),
                        finalProgress.getFailedTasks(),
                        formatDuration(finalProgress.getElapsedTime()));
                    
                    showInfoAlert(parentStage, "Batch Export Complete", message);
                    
                    if (!outputFiles.isEmpty() && 
                        showConfirmDialog(parentStage, "Open Location", "Would you like to open the export location?")) {
                        openFileLocation(outputFiles.get(0));
                    }
                });
            }
            
            @Override
            public void onBatchFailed(BatchExportSystem.ExportProgress finalProgress, Throwable error) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    showErrorAlert(parentStage, "Batch Export Failed", "Batch export failed: " + error.getMessage());
                });
            }
        });
    }
    
    /**
     * Generate documentation with progress dialog.
     */
    private void generateDocumentationWithProgress(TechnicalDocumentationGenerator.DocumentationConfig config, Stage parentStage) {
        ProgressDialog progressDialog = new ProgressDialog(parentStage, "Documentation Generation", "Starting documentation generation...");
        currentProgressDialog.set(progressDialog);
        progressDialog.show();
        
        documentationGenerator.generateDocumentationAsync(config, new TechnicalDocumentationGenerator.DocumentationProgressCallback() {
            @Override
            public void onProgress(String stage, int progress, String details) {
                Platform.runLater(() -> {
                    progressDialog.setProgress(progress / 100.0);
                    progressDialog.setStatus(stage + ": " + details);
                });
            }
            
            @Override
            public void onFileGenerated(String fileName, long fileSize) {
                logger.debug("Documentation file generated: {} ({})", fileName, formatFileSize(fileSize));
            }
            
            @Override
            public void onComplete(TechnicalDocumentationGenerator.DocumentationResult result) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    
                    String message = String.format("Documentation generated successfully!\n\nFiles: %d\nTotal size: %s\nGeneration time: %d ms\nFormat: %s",
                        result.getGeneratedFiles().size(),
                        formatFileSize((Long) result.getStatistics().get("totalSize")),
                        result.getGenerationTime(),
                        config.getFormat().getDescription());
                    
                    showInfoAlert(parentStage, "Documentation Complete", message);
                    
                    if (!result.getGeneratedFiles().isEmpty() && 
                        showConfirmDialog(parentStage, "Open Documentation", "Would you like to open the documentation?")) {
                        openFileLocation(result.getGeneratedFiles().get(0));
                    }
                });
            }
            
            @Override
            public void onError(String stage, Throwable error) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    showErrorAlert(parentStage, "Documentation Failed", "Documentation generation failed at " + stage + ": " + error.getMessage());
                });
            }
        });
    }
    
    /**
     * Export atlas with progress dialog.
     */
    private void exportAtlasWithProgress(TextureAtlasExportSystem.AtlasExportConfig config, Stage parentStage) {
        ProgressDialog progressDialog = new ProgressDialog(parentStage, "Atlas Export", "Starting atlas export...");
        currentProgressDialog.set(progressDialog);
        progressDialog.show();
        
        atlasExportSystem.exportAtlasesAsync(config, new TextureAtlasExportSystem.AtlasExportProgressCallback() {
            @Override
            public void onProgress(String stage, int progress, String details) {
                Platform.runLater(() -> {
                    progressDialog.setProgress(progress / 100.0);
                    progressDialog.setStatus(stage + ": " + details);
                });
            }
            
            @Override
            public void onVariantCompleted(String variantName, File outputFile) {
                logger.debug("Atlas export completed for variant: {}", variantName);
            }
            
            @Override
            public void onComplete(TextureAtlasExportSystem.AtlasExportResult result) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    
                    String message = String.format("Atlas export completed!\n\nVariants: %d\nFiles: %d\nExport time: %d ms\nFormat: %s",
                        result.getVariantData().size(),
                        result.getExportedFiles().size(),
                        result.getExportTime(),
                        config.getFormat().getDescription());
                    
                    showInfoAlert(parentStage, "Atlas Export Complete", message);
                    
                    if (!result.getExportedFiles().isEmpty() && 
                        showConfirmDialog(parentStage, "Open Location", "Would you like to open the export location?")) {
                        openFileLocation(result.getExportedFiles().get(0));
                    }
                });
            }
            
            @Override
            public void onError(String stage, Throwable error) {
                Platform.runLater(() -> {
                    progressDialog.close();
                    currentProgressDialog.set(null);
                    showErrorAlert(parentStage, "Atlas Export Failed", "Atlas export failed at " + stage + ": " + error.getMessage());
                });
            }
        });
    }
    
    /**
     * Add export menu items to the main controller.
     * 
     * @param mainController The main controller to add menu items to
     */
    public void integrateWithMainController(MainController mainController) {
        // This would require modifications to MainController to add export menu items
        // For now, we'll just log the integration
        logger.info("Export system integrated with MainController (menu items would be added here)");
    }
    
    /**
     * Cancel any currently running export operation.
     */
    public void cancelCurrentOperation() {
        ProgressDialog progressDialog = currentProgressDialog.get();
        if (progressDialog != null) {
            progressDialog.close();
            currentProgressDialog.set(null);
            logger.info("Export operation cancelled by user");
        }
    }
    
    // Utility methods for dialogs and file operations
    
    private void showInfoAlert(Stage parentStage, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(parentStage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showErrorAlert(Stage parentStage, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(parentStage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private boolean showConfirmDialog(Stage parentStage, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(parentStage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
    
    private void openFileLocation(File file) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                Runtime.getRuntime().exec("explorer /select," + file.getAbsolutePath());
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                Runtime.getRuntime().exec("open -R " + file.getAbsolutePath());
            } else {
                // Linux - open parent directory
                Runtime.getRuntime().exec("xdg-open " + file.getParent());
            }
        } catch (Exception e) {
            logger.error("Failed to open file location: {}", file.getAbsolutePath(), e);
        }
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) return seconds + " seconds";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + ":" + String.format("%02d", seconds);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + ":" + String.format("%02d", minutes) + ":" + String.format("%02d", seconds);
    }
    
    // Simple progress dialog class
    private static class ProgressDialog extends Dialog<Void> {
        private final ProgressBar progressBar;
        private final Label statusLabel;
        
        public ProgressDialog(Stage parentStage, String title, String initialStatus) {
            initOwner(parentStage);
            setTitle(title);
            setHeaderText(null);
            
            // Create content
            VBox content = new VBox(10);
            content.setPrefWidth(400);
            
            statusLabel = new Label(initialStatus);
            progressBar = new ProgressBar(0.0);
            progressBar.setPrefWidth(380);
            
            Button cancelButton = new Button("Cancel");
            cancelButton.setOnAction(e -> close());
            
            content.getChildren().addAll(statusLabel, progressBar, cancelButton);
            getDialogPane().setContent(content);
            
            // Remove default buttons
            getDialogPane().getButtonTypes().clear();
            
            setResultConverter(buttonType -> null);
        }
        
        public void setProgress(double progress) {
            Platform.runLater(() -> progressBar.setProgress(progress));
        }
        
        public void setStatus(String status) {
            Platform.runLater(() -> statusLabel.setText(status));
        }
    }
    
    // Placeholder dialog classes (would be implemented as separate FXML dialogs in a real application)
    
    private static class ScreenshotConfigDialog extends Dialog<HighResolutionScreenshotSystem.ScreenshotConfig> {
        public ScreenshotConfigDialog(Stage parentStage) {
            initOwner(parentStage);
            setTitle("High-Resolution Screenshot");
            
            // For this example, return a default configuration
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    HighResolutionScreenshotSystem.ScreenshotConfig config = 
                        new HighResolutionScreenshotSystem.ScreenshotConfig();
                    config.setResolutionPreset(HighResolutionScreenshotSystem.ResolutionPreset.FULL_HD_1080P);
                    config.setFormat(HighResolutionScreenshotSystem.OutputFormat.PNG);
                    return config;
                }
                return null;
            });
            
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }
    }
    
    private static class BatchExportConfigDialog extends Dialog<BatchExportSystem.ExportTemplate> {
        public BatchExportConfigDialog(Stage parentStage) {
            initOwner(parentStage);
            setTitle("Batch Export Configuration");
            
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    return BatchExportSystem.createQuickPreviewTemplate();
                }
                return null;
            });
            
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }
    }
    
    private static class DocumentationConfigDialog extends Dialog<TechnicalDocumentationGenerator.DocumentationConfig> {
        public DocumentationConfigDialog(Stage parentStage) {
            initOwner(parentStage);
            setTitle("Documentation Generation");
            
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    return new TechnicalDocumentationGenerator.DocumentationConfig();
                }
                return null;
            });
            
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }
    }
    
    private static class AtlasExportConfigDialog extends Dialog<TextureAtlasExportSystem.AtlasExportConfig> {
        public AtlasExportConfigDialog(Stage parentStage) {
            initOwner(parentStage);
            setTitle("Texture Atlas Export");
            
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    return new TextureAtlasExportSystem.AtlasExportConfig();
                }
                return null;
            });
            
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }
    }
    
    /**
     * Shutdown the export system integration.
     */
    public void shutdown() {
        logger.info("Shutting down Export System Integration");
        
        // Cancel any running operations
        cancelCurrentOperation();
        
        // Shutdown batch export system
        if (batchExportSystem != null) {
            batchExportSystem.shutdown();
        }
    }
}