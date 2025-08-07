package com.openmason.export;

import com.openmason.ui.viewport.OpenMason3DViewport;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-Resolution Screenshot System for Open Mason Phase 8.
 * 
 * Provides professional-grade screenshot capture capabilities with support for:
 * - Up to 4K resolution (3840x2160) rendering
 * - Multiple output formats (PNG, JPG, TIFF)
 * - Quality settings and compression options
 * - Background rendering for large captures
 * - Progress tracking and error handling
 */
public class HighResolutionScreenshotSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(HighResolutionScreenshotSystem.class);
    
    // High-resolution presets
    public enum ResolutionPreset {
        HD_720P(1280, 720, "HD 720p"),
        FULL_HD_1080P(1920, 1080, "Full HD 1080p"),
        QHD_1440P(2560, 1440, "QHD 1440p"),
        UHD_4K(3840, 2160, "4K UHD"),
        CINEMA_4K(4096, 2160, "Cinema 4K"),
        ULTRA_WIDE_1440P(3440, 1440, "Ultra-wide 1440p"),
        CUSTOM(0, 0, "Custom Resolution");
        
        private final int width;
        private final int height;
        private final String displayName;
        
        ResolutionPreset(int width, int height, String displayName) {
            this.width = width;
            this.height = height;
            this.displayName = displayName;
        }
        
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getDisplayName() { return displayName; }
        public double getAspectRatio() { return (double) width / height; }
    }
    
    // Output format options
    public enum OutputFormat {
        PNG("png", "Portable Network Graphics", true, false),
        JPG("jpg", "JPEG Image", false, true),
        JPEG("jpeg", "JPEG Image", false, true),
        TIFF("tiff", "Tagged Image File Format", true, false),
        BMP("bmp", "Bitmap Image", false, false);
        
        private final String extension;
        private final String description;
        private final boolean supportsTransparency;
        private final boolean supportsCompression;
        
        OutputFormat(String extension, String description, boolean supportsTransparency, boolean supportsCompression) {
            this.extension = extension;
            this.description = description;
            this.supportsTransparency = supportsTransparency;
            this.supportsCompression = supportsCompression;
        }
        
        public String getExtension() { return extension; }
        public String getDescription() { return description; }
        public boolean supportsTransparency() { return supportsTransparency; }
        public boolean supportsCompression() { return supportsCompression; }
    }
    
    // Screenshot configuration
    public static class ScreenshotConfig {
        private ResolutionPreset resolutionPreset = ResolutionPreset.FULL_HD_1080P;
        private int customWidth = 1920;
        private int customHeight = 1080;
        private OutputFormat format = OutputFormat.PNG;
        private float quality = 0.95f; // For compressed formats
        private boolean transparentBackground = false;
        private Color backgroundColor = Color.rgb(51, 51, 51); // Default dark gray
        private String outputDirectory = "screenshots";
        private String filename = "screenshot";
        private boolean includeTimestamp = true;
        private boolean includeModelInfo = true;
        private boolean includeCoordinateOverlay = false;
        private double renderScale = 1.0; // For oversampling/antialiasing
        
        // Getters and setters
        public ResolutionPreset getResolutionPreset() { return resolutionPreset; }
        public void setResolutionPreset(ResolutionPreset resolutionPreset) { this.resolutionPreset = resolutionPreset; }
        
        public int getCustomWidth() { return customWidth; }
        public void setCustomWidth(int customWidth) { this.customWidth = customWidth; }
        
        public int getCustomHeight() { return customHeight; }
        public void setCustomHeight(int customHeight) { this.customHeight = customHeight; }
        
        public OutputFormat getFormat() { return format; }
        public void setFormat(OutputFormat format) { this.format = format; }
        
        public float getQuality() { return quality; }
        public void setQuality(float quality) { this.quality = Math.max(0.1f, Math.min(1.0f, quality)); }
        
        public boolean isTransparentBackground() { return transparentBackground; }
        public void setTransparentBackground(boolean transparentBackground) { this.transparentBackground = transparentBackground; }
        
        public Color getBackgroundColor() { return backgroundColor; }
        public void setBackgroundColor(Color backgroundColor) { this.backgroundColor = backgroundColor; }
        
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        
        public boolean isIncludeTimestamp() { return includeTimestamp; }
        public void setIncludeTimestamp(boolean includeTimestamp) { this.includeTimestamp = includeTimestamp; }
        
        public boolean isIncludeModelInfo() { return includeModelInfo; }
        public void setIncludeModelInfo(boolean includeModelInfo) { this.includeModelInfo = includeModelInfo; }
        
        public boolean isIncludeCoordinateOverlay() { return includeCoordinateOverlay; }
        public void setIncludeCoordinateOverlay(boolean includeCoordinateOverlay) { this.includeCoordinateOverlay = includeCoordinateOverlay; }
        
        public double getRenderScale() { return renderScale; }
        public void setRenderScale(double renderScale) { this.renderScale = Math.max(0.5, Math.min(4.0, renderScale)); }
        
        // Utility methods
        public int getActualWidth() {
            return resolutionPreset == ResolutionPreset.CUSTOM ? customWidth : resolutionPreset.getWidth();
        }
        
        public int getActualHeight() {
            return resolutionPreset == ResolutionPreset.CUSTOM ? customHeight : resolutionPreset.getHeight();
        }
        
        public int getRenderWidth() {
            return (int) (getActualWidth() * renderScale);
        }
        
        public int getRenderHeight() {
            return (int) (getActualHeight() * renderScale);
        }
    }
    
    // Progress callback interface
    public interface ScreenshotProgressCallback {
        void onProgress(String stage, int progress, String details);
        void onComplete(File outputFile, long fileSize);
        void onError(String stage, Throwable error);
    }
    
    private OpenMason3DViewport viewport;
    
    /**
     * Creates a new high-resolution screenshot system.
     * 
     * @param viewport The 3D viewport to capture screenshots from
     */
    public HighResolutionScreenshotSystem(OpenMason3DViewport viewport) {
        this.viewport = viewport;
        logger.info("High-Resolution Screenshot System initialized");
    }
    
    /**
     * Capture a high-resolution screenshot asynchronously.
     * 
     * @param config Screenshot configuration
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to the output file
     */
    public CompletableFuture<File> captureScreenshotAsync(ScreenshotConfig config, ScreenshotProgressCallback progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (progressCallback != null) {
                    progressCallback.onProgress("Preparation", 0, "Preparing high-resolution capture");
                }
                
                // Validate configuration
                validateConfig(config);
                
                // Create output directory
                Path outputDir = Paths.get(config.getOutputDirectory());
                Files.createDirectories(outputDir);
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Setup", 10, "Creating rendering canvas");
                }
                
                // Create high-resolution rendering canvas
                Canvas renderCanvas = createHighResolutionCanvas(config);
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Rendering", 25, "Rendering 3D scene at high resolution");
                }
                
                // Render the viewport content at high resolution
                AtomicReference<WritableImage> imageRef = new AtomicReference<>();
                
                // Execute on JavaFX Application Thread
                Platform.runLater(() -> {
                    try {
                        renderHighResolutionContent(renderCanvas, config);
                        
                        // Create snapshot parameters
                        SnapshotParameters snapshotParams = new SnapshotParameters();
                        snapshotParams.setFill(config.isTransparentBackground() ? Color.TRANSPARENT : config.getBackgroundColor());
                        
                        // Apply scaling if needed
                        if (config.getRenderScale() != 1.0) {
                            Scale scale = new Scale(1.0 / config.getRenderScale(), 1.0 / config.getRenderScale());
                            snapshotParams.setTransform(scale);
                        }
                        
                        // Take the snapshot at actual resolution
                        WritableImage image = renderCanvas.snapshot(snapshotParams, 
                            new WritableImage(config.getActualWidth(), config.getActualHeight()));
                        imageRef.set(image);
                        
                    } catch (Exception e) {
                        logger.error("Error during high-resolution rendering", e);
                        throw new RuntimeException("High-resolution rendering failed", e);
                    }
                });
                
                // Wait for JavaFX thread to complete
                while (imageRef.get() == null) {
                    Thread.sleep(50);
                }
                
                WritableImage finalImage = imageRef.get();
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Processing", 75, "Processing captured image");
                }
                
                // Add overlays if requested
                if (config.isIncludeModelInfo() || config.isIncludeCoordinateOverlay()) {
                    finalImage = addImageOverlays(finalImage, config);
                }
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Saving", 90, "Saving image to file");
                }
                
                // Generate filename
                String filename = generateFilename(config);
                File outputFile = new File(outputDir.toFile(), filename);
                
                // Save the image
                saveImage(finalImage, outputFile, config);
                
                long fileSize = outputFile.length();
                logger.info("High-resolution screenshot saved: {} ({}x{}, {} bytes)", 
                    outputFile.getAbsolutePath(), config.getActualWidth(), config.getActualHeight(), fileSize);
                
                if (progressCallback != null) {
                    progressCallback.onComplete(outputFile, fileSize);
                }
                
                return outputFile;
                
            } catch (Exception e) {
                logger.error("Failed to capture high-resolution screenshot", e);
                if (progressCallback != null) {
                    progressCallback.onError("Capture", e);
                }
                throw new RuntimeException("Screenshot capture failed", e);
            }
        });
    }
    
    /**
     * Capture a screenshot synchronously (blocks until complete).
     * 
     * @param config Screenshot configuration
     * @return The output file
     */
    public File captureScreenshot(ScreenshotConfig config) {
        try {
            return captureScreenshotAsync(config, null).get();
        } catch (Exception e) {
            throw new RuntimeException("Synchronous screenshot capture failed", e);
        }
    }
    
    /**
     * Validate screenshot configuration.
     */
    private void validateConfig(ScreenshotConfig config) {
        if (config.getActualWidth() <= 0 || config.getActualHeight() <= 0) {
            throw new IllegalArgumentException("Invalid resolution: " + config.getActualWidth() + "x" + config.getActualHeight());
        }
        
        if (config.getActualWidth() > 8192 || config.getActualHeight() > 8192) {
            logger.warn("Extremely high resolution requested: {}x{} - this may cause memory issues", 
                config.getActualWidth(), config.getActualHeight());
        }
        
        if (config.getRenderScale() > 2.0) {
            logger.warn("High render scale requested: {} - this may cause performance issues", config.getRenderScale());
        }
    }
    
    /**
     * Create a high-resolution canvas for rendering.
     */
    private Canvas createHighResolutionCanvas(ScreenshotConfig config) {
        int renderWidth = config.getRenderWidth();
        int renderHeight = config.getRenderHeight();
        
        Canvas canvas = new Canvas(renderWidth, renderHeight);
        
        // Set high-quality rendering hints
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(true);
        
        logger.debug("Created high-resolution canvas: {}x{} (scale: {})", 
            renderWidth, renderHeight, config.getRenderScale());
        
        return canvas;
    }
    
    /**
     * Render the viewport content at high resolution.
     */
    private void renderHighResolutionContent(Canvas canvas, ScreenshotConfig config) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        
        // Clear canvas with background color
        gc.setFill(config.isTransparentBackground() ? Color.TRANSPARENT : config.getBackgroundColor());
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        
        // Save original viewport dimensions
        double originalWidth = viewport.getWidth();
        double originalHeight = viewport.getHeight();
        
        try {
            // Temporarily resize viewport for high-resolution rendering
            viewport.resize(config.getRenderWidth(), config.getRenderHeight());
            
            // Force a render update
            viewport.requestRender();
            
            // Wait for render to complete
            Thread.sleep(100);
            
            // Copy viewport content to our high-resolution canvas
            // This is a simplified approach - in practice, you might need to
            // directly access the viewport's graphics context or rendering system
            
            // Create a snapshot of the viewport
            WritableImage viewportSnapshot = viewport.snapshot(null, null);
            if (viewportSnapshot != null) {
                gc.drawImage(viewportSnapshot, 0, 0, canvas.getWidth(), canvas.getHeight());
            }
            
        } catch (Exception e) {
            logger.error("Error during high-resolution content rendering", e);
            throw new RuntimeException("Content rendering failed", e);
        } finally {
            // Restore original viewport dimensions
            viewport.resize(originalWidth, originalHeight);
        }
    }
    
    /**
     * Add overlays to the captured image.
     */
    private WritableImage addImageOverlays(WritableImage image, ScreenshotConfig config) {
        // Create a new canvas for overlay processing
        Canvas overlayCanvas = new Canvas(image.getWidth(), image.getHeight());
        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        
        // Draw the original image
        gc.drawImage(image, 0, 0);
        
        // Add model information overlay
        if (config.isIncludeModelInfo() && viewport.getCurrentModel() != null) {
            addModelInfoOverlay(gc, config);
        }
        
        // Add coordinate system overlay
        if (config.isIncludeCoordinateOverlay()) {
            addCoordinateOverlay(gc, config);
        }
        
        // Return the processed image
        return overlayCanvas.snapshot(null, null);
    }
    
    /**
     * Add model information overlay to the image.
     */
    private void addModelInfoOverlay(GraphicsContext gc, ScreenshotConfig config) {
        // Set text style
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(1);
        gc.setFont(javafx.scene.text.Font.font("Arial", 14));
        
        // Get model information
        String modelName = viewport.getCurrentModelName();
        String textureVariant = viewport.getCurrentTextureVariant();
        
        // Position for text (bottom-left corner)
        double x = 20;
        double y = config.getActualHeight() - 60;
        
        // Draw text with outline for better visibility
        String modelText = "Model: " + (modelName != null ? modelName : "Unknown");
        String variantText = "Variant: " + (textureVariant != null ? textureVariant : "Default");
        
        gc.strokeText(modelText, x, y);
        gc.fillText(modelText, x, y);
        
        gc.strokeText(variantText, x, y + 20);
        gc.fillText(variantText, x, y + 20);
        
        // Add timestamp
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        gc.strokeText(timestamp, x, y + 40);
        gc.fillText(timestamp, x, y + 40);
    }
    
    /**
     * Add coordinate system overlay to the image.
     */
    private void addCoordinateOverlay(GraphicsContext gc, ScreenshotConfig config) {
        // Draw coordinate grid overlay
        gc.setStroke(Color.rgb(255, 255, 255, 0.3)); // Semi-transparent white
        gc.setLineWidth(0.5);
        
        int gridSpacing = 50;
        int width = config.getActualWidth();
        int height = config.getActualHeight();
        
        // Vertical grid lines
        for (int x = gridSpacing; x < width; x += gridSpacing) {
            gc.strokeLine(x, 0, x, height);
        }
        
        // Horizontal grid lines
        for (int y = gridSpacing; y < height; y += gridSpacing) {
            gc.strokeLine(0, y, width, y);
        }
        
        // Add coordinate labels
        gc.setFill(Color.rgb(255, 255, 255, 0.7));
        gc.setFont(javafx.scene.text.Font.font("Arial", 10));
        
        // Corner coordinates
        gc.fillText("(0,0)", 5, 15);
        gc.fillText("(" + width + ",0)", width - 50, 15);
        gc.fillText("(0," + height + ")", 5, height - 5);
        gc.fillText("(" + width + "," + height + ")", width - 70, height - 5);
    }
    
    /**
     * Generate filename for the screenshot.
     */
    private String generateFilename(ScreenshotConfig config) {
        StringBuilder filename = new StringBuilder(config.getFilename());
        
        if (config.isIncludeTimestamp()) {
            String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("_yyyyMMdd_HHmmss"));
            filename.append(timestamp);
        }
        
        // Add resolution info
        filename.append("_").append(config.getActualWidth()).append("x").append(config.getActualHeight());
        
        // Add model info if available
        if (config.isIncludeModelInfo() && viewport.getCurrentModelName() != null) {
            String modelName = viewport.getCurrentModelName().replaceAll("[^a-zA-Z0-9]", "_");
            filename.append("_").append(modelName);
            
            if (viewport.getCurrentTextureVariant() != null) {
                String variant = viewport.getCurrentTextureVariant().replaceAll("[^a-zA-Z0-9]", "_");
                filename.append("_").append(variant);
            }
        }
        
        filename.append(".").append(config.getFormat().getExtension());
        
        return filename.toString();
    }
    
    /**
     * Save the image to file with the specified format and quality.
     */
    private void saveImage(WritableImage image, File outputFile, ScreenshotConfig config) throws IOException {
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
        
        switch (config.getFormat()) {
            case PNG:
                ImageIO.write(bufferedImage, "png", outputFile);
                break;
                
            case JPG:
            case JPEG:
                // Convert to RGB if needed (JPEG doesn't support transparency)
                if (bufferedImage.getType() != BufferedImage.TYPE_INT_RGB) {
                    BufferedImage rgbImage = new BufferedImage(
                        bufferedImage.getWidth(), bufferedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                    rgbImage.getGraphics().drawImage(bufferedImage, 0, 0, null);
                    bufferedImage = rgbImage;
                }
                
                // Set JPEG quality if supported
                javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                javax.imageio.ImageWriteParam writeParam = writer.getDefaultWriteParam();
                if (writeParam.canWriteCompressed()) {
                    writeParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                    writeParam.setCompressionQuality(config.getQuality());
                }
                
                try (javax.imageio.stream.ImageOutputStream output = ImageIO.createImageOutputStream(outputFile)) {
                    writer.setOutput(output);
                    writer.write(null, new javax.imageio.IIOImage(bufferedImage, null, null), writeParam);
                }
                writer.dispose();
                break;
                
            case TIFF:
                ImageIO.write(bufferedImage, "tiff", outputFile);
                break;
                
            case BMP:
                ImageIO.write(bufferedImage, "bmp", outputFile);
                break;
                
            default:
                throw new IllegalArgumentException("Unsupported format: " + config.getFormat());
        }
        
        logger.debug("Image saved: {} ({} bytes)", outputFile.getAbsolutePath(), outputFile.length());
    }
    
    /**
     * Get memory requirements estimate for a screenshot configuration.
     * 
     * @param config Screenshot configuration
     * @return Estimated memory usage in bytes
     */
    public long estimateMemoryRequirement(ScreenshotConfig config) {
        long pixels = (long) config.getRenderWidth() * config.getRenderHeight();
        long bytesPerPixel = 4; // RGBA
        long baseMemory = pixels * bytesPerPixel;
        
        // Factor in additional memory for processing
        long processingOverhead = baseMemory / 2;
        
        return baseMemory + processingOverhead;
    }
    
    /**
     * Check if the system has enough memory for the screenshot.
     * 
     * @param config Screenshot configuration
     * @return true if memory is sufficient
     */
    public boolean checkMemoryRequirements(ScreenshotConfig config) {
        long required = estimateMemoryRequirement(config);
        long available = Runtime.getRuntime().maxMemory() - 
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        
        boolean sufficient = available > required * 1.5; // 50% safety margin
        
        if (!sufficient) {
            logger.warn("Insufficient memory for screenshot: required={} MB, available={} MB", 
                required / (1024 * 1024), available / (1024 * 1024));
        }
        
        return sufficient;
    }
}