package com.openmason.export;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.ui.viewport.Camera;
import com.openmason.texture.TextureManager;
import com.openmason.model.ModelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Batch Export System for Open Mason Phase 8.
 * 
 * Provides automated export capabilities for generating comprehensive documentation:
 * - Documentation images for all cow variants and view angles
 * - Batch processing with progress tracking
 * - Automated naming and organization
 * - Error handling and recovery
 * - Configurable export templates
 */
public class BatchExportSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(BatchExportSystem.class);
    
    private final OpenMason3DViewport viewport;
    private final HighResolutionScreenshotSystem screenshotSystem;
    private final ExecutorService executorService;
    
    // Predefined camera angles for documentation
    public enum CameraAngle {
        FRONT("Front View", 0.0f, 0.0f),
        BACK("Back View", 180.0f, 0.0f),
        LEFT("Left View", -90.0f, 0.0f),
        RIGHT("Right View", 90.0f, 0.0f),
        TOP("Top View", 0.0f, 90.0f),
        BOTTOM("Bottom View", 0.0f, -90.0f),
        FRONT_TOP("Front-Top View", 0.0f, 30.0f),
        BACK_TOP("Back-Top View", 180.0f, 30.0f),
        ISOMETRIC("Isometric View", 45.0f, 35.264f),
        THREE_QUARTER_FRONT("3/4 Front View", 45.0f, 15.0f),
        THREE_QUARTER_BACK("3/4 Back View", 135.0f, 15.0f);
        
        private final String displayName;
        private final float azimuth;
        private final float elevation;
        
        CameraAngle(String displayName, float azimuth, float elevation) {
            this.displayName = displayName;
            this.azimuth = azimuth;
            this.elevation = elevation;
        }
        
        public String getDisplayName() { return displayName; }
        public float getAzimuth() { return azimuth; }
        public float getElevation() { return elevation; }
        public String getFilenameSuffix() { return name().toLowerCase().replace("_", "-"); }
    }
    
    // Export template configurations
    public static class ExportTemplate {
        private String name;
        private String description;
        private List<String> modelNames;
        private List<String> textureVariants;
        private List<CameraAngle> cameraAngles;
        private HighResolutionScreenshotSystem.ScreenshotConfig screenshotConfig;
        private boolean includeWireframe;
        private boolean includeGrid;
        private boolean includeAxes;
        private String outputSubdirectory;
        
        public ExportTemplate(String name) {
            this.name = name;
            this.modelNames = new ArrayList<>();
            this.textureVariants = new ArrayList<>();
            this.cameraAngles = new ArrayList<>();
            this.screenshotConfig = new HighResolutionScreenshotSystem.ScreenshotConfig();
            this.includeWireframe = false;
            this.includeGrid = false;
            this.includeAxes = false;
            this.outputSubdirectory = name.toLowerCase().replace(" ", "_");
        }
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public List<String> getModelNames() { return modelNames; }
        public void setModelNames(List<String> modelNames) { this.modelNames = modelNames; }
        
        public List<String> getTextureVariants() { return textureVariants; }
        public void setTextureVariants(List<String> textureVariants) { this.textureVariants = textureVariants; }
        
        public List<CameraAngle> getCameraAngles() { return cameraAngles; }
        public void setCameraAngles(List<CameraAngle> cameraAngles) { this.cameraAngles = cameraAngles; }
        
        public HighResolutionScreenshotSystem.ScreenshotConfig getScreenshotConfig() { return screenshotConfig; }
        public void setScreenshotConfig(HighResolutionScreenshotSystem.ScreenshotConfig screenshotConfig) { 
            this.screenshotConfig = screenshotConfig; 
        }
        
        public boolean isIncludeWireframe() { return includeWireframe; }
        public void setIncludeWireframe(boolean includeWireframe) { this.includeWireframe = includeWireframe; }
        
        public boolean isIncludeGrid() { return includeGrid; }
        public void setIncludeGrid(boolean includeGrid) { this.includeGrid = includeGrid; }
        
        public boolean isIncludeAxes() { return includeAxes; }
        public void setIncludeAxes(boolean includeAxes) { this.includeAxes = includeAxes; }
        
        public String getOutputSubdirectory() { return outputSubdirectory; }
        public void setOutputSubdirectory(String outputSubdirectory) { this.outputSubdirectory = outputSubdirectory; }
        
        // Utility methods
        public int getTotalExportCount() {
            int count = modelNames.size() * textureVariants.size() * cameraAngles.size();
            if (includeWireframe) count *= 2; // Solid + wireframe
            return count;
        }
    }
    
    // Export progress tracking
    public static class ExportProgress {
        private final String templateName;
        private final int totalTasks;
        private final AtomicInteger completedTasks = new AtomicInteger(0);
        private final AtomicInteger failedTasks = new AtomicInteger(0);
        private final AtomicReference<String> currentTask = new AtomicReference<>("Initializing");
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        private final long startTime = System.currentTimeMillis();
        
        public ExportProgress(String templateName, int totalTasks) {
            this.templateName = templateName;
            this.totalTasks = totalTasks;
        }
        
        public String getTemplateName() { return templateName; }
        public int getTotalTasks() { return totalTasks; }
        public int getCompletedTasks() { return completedTasks.get(); }
        public int getFailedTasks() { return failedTasks.get(); }
        public String getCurrentTask() { return currentTask.get(); }
        public List<String> getErrors() { return new ArrayList<>(errors); }
        public long getElapsedTime() { return System.currentTimeMillis() - startTime; }
        
        public double getProgressPercentage() {
            return totalTasks > 0 ? (double) (completedTasks.get() + failedTasks.get()) / totalTasks * 100.0 : 0.0;
        }
        
        public boolean isComplete() {
            return (completedTasks.get() + failedTasks.get()) >= totalTasks;
        }
        
        void taskCompleted() { completedTasks.incrementAndGet(); }
        void taskFailed(String error) { 
            failedTasks.incrementAndGet(); 
            errors.add(error);
        }
        void setCurrentTask(String task) { currentTask.set(task); }
    }
    
    // Progress callback interface
    public interface BatchExportProgressCallback {
        void onProgressUpdate(ExportProgress progress);
        void onTaskCompleted(String taskName, File outputFile);
        void onTaskFailed(String taskName, Throwable error);
        void onBatchCompleted(ExportProgress finalProgress, List<File> outputFiles);
        void onBatchFailed(ExportProgress finalProgress, Throwable error);
    }
    
    /**
     * Creates a new batch export system.
     * 
     * @param viewport The 3D viewport to use for rendering
     */
    public BatchExportSystem(OpenMason3DViewport viewport) {
        this.viewport = viewport;
        this.screenshotSystem = new HighResolutionScreenshotSystem(viewport);
        this.executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "BatchExport-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        logger.info("Batch Export System initialized");
    }
    
    /**
     * Execute a batch export using the specified template.
     * 
     * @param template Export template configuration
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to the list of generated files
     */
    public CompletableFuture<List<File>> executeBatchExport(ExportTemplate template, 
                                                           BatchExportProgressCallback progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            ExportProgress progress = new ExportProgress(template.getName(), template.getTotalExportCount());
            List<File> outputFiles = Collections.synchronizedList(new ArrayList<>());
            
            try {
                logger.info("Starting batch export: {} ({} tasks)", template.getName(), progress.getTotalTasks());
                
                if (progressCallback != null) {
                    progressCallback.onProgressUpdate(progress);
                }
                
                // Create output directory structure
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path baseOutputDir = Paths.get("exports", template.getOutputSubdirectory() + "_" + timestamp);
                Files.createDirectories(baseOutputDir);
                
                progress.setCurrentTask("Setting up export directory");
                
                // Store original viewport settings
                ViewportState originalState = saveViewportState();
                
                try {
                    // Process each combination
                    for (String modelName : template.getModelNames()) {
                        for (String variant : template.getTextureVariants()) {
                            for (CameraAngle angle : template.getCameraAngles()) {
                                
                                // Export solid version
                                exportSingleConfiguration(template, baseOutputDir, modelName, variant, 
                                    angle, false, progress, progressCallback, outputFiles);
                                
                                // Export wireframe version if requested
                                if (template.isIncludeWireframe()) {
                                    exportSingleConfiguration(template, baseOutputDir, modelName, variant, 
                                        angle, true, progress, progressCallback, outputFiles);
                                }
                            }
                        }
                    }
                    
                } finally {
                    // Restore original viewport settings
                    restoreViewportState(originalState);
                }
                
                logger.info("Batch export completed: {} ({}/{} successful)", 
                    template.getName(), progress.getCompletedTasks(), progress.getTotalTasks());
                
                if (progressCallback != null) {
                    progressCallback.onBatchCompleted(progress, outputFiles);
                }
                
                return outputFiles;
                
            } catch (Exception e) {
                logger.error("Batch export failed: {}", template.getName(), e);
                if (progressCallback != null) {
                    progressCallback.onBatchFailed(progress, e);
                }
                throw new RuntimeException("Batch export failed", e);
            }
        }, executorService);
    }
    
    /**
     * Export a single model/variant/angle configuration.
     */
    private void exportSingleConfiguration(ExportTemplate template, Path baseOutputDir, 
                                         String modelName, String variant, CameraAngle angle, 
                                         boolean wireframe, ExportProgress progress,
                                         BatchExportProgressCallback progressCallback,
                                         List<File> outputFiles) {
        
        String configName = String.format("%s_%s_%s%s", 
            modelName, variant, angle.getFilenameSuffix(), wireframe ? "_wireframe" : "");
        
        progress.setCurrentTask("Rendering " + configName);
        if (progressCallback != null) {
            progressCallback.onProgressUpdate(progress);
        }
        
        try {
            // Load model and set variant
            viewport.loadModel(modelName);
            viewport.setCurrentTextureVariant(variant);
            
            // Wait for model to load
            Thread.sleep(500);
            
            // Set camera angle
            Camera camera = viewport.getCamera();
            if (camera != null) {
                camera.setOrientation(angle.getAzimuth(), angle.getElevation());
                camera.setDistance(5.0f); // Standard viewing distance
            }
            
            // Configure viewport settings
            viewport.setWireframeMode(wireframe);
            viewport.setGridVisible(template.isIncludeGrid());
            viewport.setAxesVisible(template.isIncludeAxes());
            
            // Wait for settings to apply
            Thread.sleep(200);
            
            // Configure screenshot settings
            HighResolutionScreenshotSystem.ScreenshotConfig screenshotConfig = 
                new HighResolutionScreenshotSystem.ScreenshotConfig();
            copyScreenshotConfig(template.getScreenshotConfig(), screenshotConfig);
            
            // Set output directory and filename
            screenshotConfig.setOutputDirectory(baseOutputDir.toString());
            screenshotConfig.setFilename(configName);
            screenshotConfig.setIncludeTimestamp(false); // Template handles naming
            screenshotConfig.setIncludeModelInfo(true);
            
            // Capture screenshot
            File outputFile = screenshotSystem.captureScreenshot(screenshotConfig);
            outputFiles.add(outputFile);
            
            progress.taskCompleted();
            
            if (progressCallback != null) {
                progressCallback.onTaskCompleted(configName, outputFile);
                progressCallback.onProgressUpdate(progress);
            }
            
            logger.debug("Exported: {}", configName);
            
        } catch (Exception e) {
            String errorMsg = "Failed to export " + configName + ": " + e.getMessage();
            progress.taskFailed(errorMsg);
            
            if (progressCallback != null) {
                progressCallback.onTaskFailed(configName, e);
                progressCallback.onProgressUpdate(progress);
            }
            
            logger.error("Export failed: {}", configName, e);
        }
    }
    
    /**
     * Copy screenshot configuration settings.
     */
    private void copyScreenshotConfig(HighResolutionScreenshotSystem.ScreenshotConfig source,
                                     HighResolutionScreenshotSystem.ScreenshotConfig target) {
        target.setResolutionPreset(source.getResolutionPreset());
        target.setCustomWidth(source.getCustomWidth());
        target.setCustomHeight(source.getCustomHeight());
        target.setFormat(source.getFormat());
        target.setQuality(source.getQuality());
        target.setTransparentBackground(source.isTransparentBackground());
        target.setBackgroundColor(source.getBackgroundColor());
        target.setRenderScale(source.getRenderScale());
    }
    
    // Viewport state management
    private static class ViewportState {
        final String currentModel;
        final String currentVariant;
        final boolean wireframeMode;
        final boolean gridVisible;
        final boolean axesVisible;
        final float cameraAzimuth;
        final float cameraElevation;
        final float cameraDistance;
        
        ViewportState(String currentModel, String currentVariant, boolean wireframeMode,
                     boolean gridVisible, boolean axesVisible, float cameraAzimuth,
                     float cameraElevation, float cameraDistance) {
            this.currentModel = currentModel;
            this.currentVariant = currentVariant;
            this.wireframeMode = wireframeMode;
            this.gridVisible = gridVisible;
            this.axesVisible = axesVisible;
            this.cameraAzimuth = cameraAzimuth;
            this.cameraElevation = cameraElevation;
            this.cameraDistance = cameraDistance;
        }
    }
    
    private ViewportState saveViewportState() {
        Camera camera = viewport.getCamera();
        return new ViewportState(
            viewport.getCurrentModelName(),
            viewport.getCurrentTextureVariant(),
            viewport.isWireframeMode(),
            viewport.isGridVisible(),
            viewport.isAxesVisible(),
            camera != null ? camera.getAzimuth() : 0.0f,
            camera != null ? camera.getElevation() : 0.0f,
            camera != null ? camera.getDistance() : 5.0f
        );
    }
    
    private void restoreViewportState(ViewportState state) {
        try {
            if (state.currentModel != null) {
                viewport.loadModel(state.currentModel);
            }
            if (state.currentVariant != null) {
                viewport.setCurrentTextureVariant(state.currentVariant);
            }
            
            viewport.setWireframeMode(state.wireframeMode);
            viewport.setGridVisible(state.gridVisible);
            viewport.setAxesVisible(state.axesVisible);
            
            Camera camera = viewport.getCamera();
            if (camera != null) {
                camera.setOrientation(state.cameraAzimuth, state.cameraElevation);
                camera.setDistance(state.cameraDistance);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to fully restore viewport state", e);
        }
    }
    
    /**
     * Create a comprehensive documentation template for all cow variants.
     * 
     * @return Pre-configured export template
     */
    public static ExportTemplate createComprehensiveDocumentationTemplate() {
        ExportTemplate template = new ExportTemplate("Comprehensive Documentation");
        template.setDescription("Complete documentation for all cow models and variants");
        
        // Add all available models
        List<String> availableModels = ModelManager.getAvailableModels();
        template.setModelNames(availableModels);
        
        // Add all available texture variants
        List<String> availableVariants = TextureManager.getAvailableVariants();
        template.setTextureVariants(availableVariants);
        
        // Add comprehensive camera angles
        template.setCameraAngles(Arrays.asList(
            CameraAngle.FRONT,
            CameraAngle.BACK,
            CameraAngle.LEFT,
            CameraAngle.RIGHT,
            CameraAngle.ISOMETRIC,
            CameraAngle.THREE_QUARTER_FRONT,
            CameraAngle.THREE_QUARTER_BACK
        ));
        
        // Configure high-quality screenshots
        HighResolutionScreenshotSystem.ScreenshotConfig config = template.getScreenshotConfig();
        config.setResolutionPreset(HighResolutionScreenshotSystem.ResolutionPreset.FULL_HD_1080P);
        config.setFormat(HighResolutionScreenshotSystem.OutputFormat.PNG);
        config.setIncludeModelInfo(true);
        config.setTransparentBackground(false);
        config.setRenderScale(1.5); // High quality with oversampling
        
        template.setIncludeWireframe(true);
        template.setIncludeGrid(false);
        template.setIncludeAxes(false);
        
        return template;
    }
    
    /**
     * Create a quick preview template for rapid iteration.
     * 
     * @return Pre-configured export template
     */
    public static ExportTemplate createQuickPreviewTemplate() {
        ExportTemplate template = new ExportTemplate("Quick Preview");
        template.setDescription("Quick preview images for rapid iteration");
        
        // Limited selection for speed - model mapping will handle baked variant selection
        template.setModelNames(Arrays.asList("standard_cow"));
        template.setTextureVariants(Arrays.asList("default", "angus"));
        template.setCameraAngles(Arrays.asList(CameraAngle.ISOMETRIC, CameraAngle.FRONT));
        
        // Configure for speed
        HighResolutionScreenshotSystem.ScreenshotConfig config = template.getScreenshotConfig();
        config.setResolutionPreset(HighResolutionScreenshotSystem.ResolutionPreset.HD_720P);
        config.setFormat(HighResolutionScreenshotSystem.OutputFormat.JPG);
        config.setQuality(0.8f);
        config.setIncludeModelInfo(false);
        
        template.setIncludeWireframe(false);
        template.setIncludeGrid(false);
        template.setIncludeAxes(false);
        
        return template;
    }
    
    /**
     * Create a high-quality presentation template.
     * 
     * @return Pre-configured export template
     */
    public static ExportTemplate createPresentationTemplate() {
        ExportTemplate template = new ExportTemplate("Presentation Quality");
        template.setDescription("High-quality images for presentations and marketing");
        
        // Model mapping will handle baked variant selection
        template.setModelNames(Arrays.asList("standard_cow"));
        template.setTextureVariants(TextureManager.getAvailableVariants());
        template.setCameraAngles(Arrays.asList(
            CameraAngle.ISOMETRIC,
            CameraAngle.THREE_QUARTER_FRONT
        ));
        
        // Configure for maximum quality
        HighResolutionScreenshotSystem.ScreenshotConfig config = template.getScreenshotConfig();
        config.setResolutionPreset(HighResolutionScreenshotSystem.ResolutionPreset.UHD_4K);
        config.setFormat(HighResolutionScreenshotSystem.OutputFormat.PNG);
        config.setIncludeModelInfo(false);
        config.setTransparentBackground(true);
        config.setRenderScale(2.0); // Maximum quality oversampling
        
        template.setIncludeWireframe(false);
        template.setIncludeGrid(false);
        template.setIncludeAxes(false);
        
        return template;
    }
    
    /**
     * Get estimated processing time for a template.
     * 
     * @param template Export template
     * @return Estimated time in seconds
     */
    public long estimateProcessingTime(ExportTemplate template) {
        // Base time per screenshot (in seconds)
        double baseTimePerScreenshot = 2.0;
        
        // Resolution factor
        int pixels = template.getScreenshotConfig().getActualWidth() * 
                    template.getScreenshotConfig().getActualHeight();
        double resolutionFactor = pixels / (1920.0 * 1080.0); // Relative to 1080p
        
        // Render scale factor
        double scaleFactor = Math.pow(template.getScreenshotConfig().getRenderScale(), 2);
        
        // Total processing time
        double totalTime = template.getTotalExportCount() * baseTimePerScreenshot * 
                          resolutionFactor * scaleFactor;
        
        return Math.round(totalTime);
    }
    
    /**
     * Shutdown the batch export system.
     */
    public void shutdown() {
        logger.info("Shutting down Batch Export System");
        executorService.shutdown();
    }
}