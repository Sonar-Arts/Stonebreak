package com.openmason.ui.textureCreator.io;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.layers.Layer;
import com.openmason.ui.textureCreator.layers.LayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Handles drag-and-drop operations for PNG and .OMT files in the texture editor.
 */
public class DragDropHandler {
    private static final Logger logger = LoggerFactory.getLogger(DragDropHandler.class);

    private final TextureImporter textureImporter;
    private final OMTDeserializer omtDeserializer;
    private Rectangle lastImportedBounds; // Bounds of the last successfully imported image

    public DragDropHandler() {
        this.textureImporter = new TextureImporter();
        this.omtDeserializer = new OMTDeserializer();
        this.lastImportedBounds = null;
    }

    /**
     * Processes dropped PNG files and adds them as layers to the LayerManager.
     */
    public int processDroppedPNGFiles(String[] filePaths, LayerManager layerManager) {
        int successCount = 0;
        lastImportedBounds = null; // Clear previous import bounds

        if (filePaths == null) {
            return successCount;
        }

        for (String filePath : filePaths) {
            try {
                Path path = Paths.get(filePath);
                String fileName = path.getFileName().toString();
                String extension = getFileExtension(fileName).toLowerCase();

                if (extension.equals("png")) {
                    if (importPNGAsLayer(filePath, fileName, layerManager)) {
                        successCount++;
                    }
                } else {
                    logger.warn("Skipping non-PNG file in processDroppedPNGFiles: {}", fileName);
                }
            } catch (Exception e) {
                String errorMsg = "Failed to import " + new File(filePath).getName() + ": " + e.getMessage();
                logger.error(errorMsg, e);
            }
        }

        return successCount;
    }

    /**
     * Imports a PNG file as a new layer.
     * Supports images of any size - centers smaller images, crops larger ones.
     *
     * @return true if import was successful, false otherwise
     */
    private boolean importPNGAsLayer(String filePath, String fileName, LayerManager layerManager) {
        try {
            logger.info("Importing PNG file as layer: {}", fileName);

            // Load PNG using existing TextureImporter
            PixelCanvas importedCanvas = textureImporter.importFromPNG(filePath);

            if (importedCanvas == null) {
                logger.error("Failed to load PNG file: {}", fileName);
                return false;
            }

            int canvasWidth = layerManager.getCanvasWidth();
            int canvasHeight = layerManager.getCanvasHeight();
            int importedWidth = importedCanvas.getWidth();
            int importedHeight = importedCanvas.getHeight();

            // Create new canvas matching the project's canvas size
            PixelCanvas targetCanvas = new PixelCanvas(canvasWidth, canvasHeight);

            // Center the imported image on the target canvas
            int offsetX = (canvasWidth - importedWidth) / 2;
            int offsetY = (canvasHeight - importedHeight) / 2;

            // Copy pixels from imported canvas to target canvas
            for (int y = 0; y < importedHeight; y++) {
                for (int x = 0; x < importedWidth; x++) {
                    int targetX = x + offsetX;
                    int targetY = y + offsetY;

                    // Only copy if within bounds
                    if (targetX >= 0 && targetX < canvasWidth && targetY >= 0 && targetY < canvasHeight) {
                        int pixel = importedCanvas.getPixel(x, y);
                        targetCanvas.setPixel(targetX, targetY, pixel);
                    }
                }
            }

            // Create layer name from filename (remove extension)
            String layerName = fileName.substring(0, fileName.lastIndexOf('.'));

            // Create new layer with target canvas
            Layer newLayer = new Layer(layerName, targetCanvas, true, 1.0f);

            // Add layer to manager at the end
            layerManager.addLayerAt(layerManager.getLayerCount(), newLayer);

            // Store the bounds of the imported image for auto-selection
            lastImportedBounds = new Rectangle(offsetX, offsetY, importedWidth, importedHeight);

            logger.info("Successfully imported PNG as layer: {} ({}x{} centered on {}x{} canvas)",
                layerName, importedWidth, importedHeight, canvasWidth, canvasHeight);
            return true;

        } catch (Exception e) {
            logger.error("Failed to import PNG as layer: {}", fileName, e);
            return false;
        }
    }

    /**
     * Imports an .OMT file and flattens it to a single layer.
     */
    public void importOMTFlattened(String filePath, LayerManager layerManager) throws Exception {
        String fileName = new File(filePath).getName();
        logger.info("Importing .OMT file as flattened layer: {}", fileName);

        // Load .OMT file using existing deserializer
        LayerManager importedLayerManager = omtDeserializer.load(filePath);

        if (importedLayerManager == null) {
            throw new Exception("Failed to load .OMT file");
        }

        // Validate dimensions match canvas size
        int canvasWidth = layerManager.getCanvasWidth();
        int canvasHeight = layerManager.getCanvasHeight();

        if (importedLayerManager.getCanvasWidth() != canvasWidth ||
            importedLayerManager.getCanvasHeight() != canvasHeight) {
            throw new Exception(String.format(
                ".OMT dimensions (%dx%d) don't match canvas size (%dx%d)",
                importedLayerManager.getCanvasWidth(),
                importedLayerManager.getCanvasHeight(),
                canvasWidth, canvasHeight
            ));
        }

        // Flatten all layers into a single canvas
        PixelCanvas flattenedCanvas = flattenLayers(importedLayerManager);

        // Create layer name from filename (remove extension)
        String layerName = fileName.substring(0, fileName.lastIndexOf('.'));

        // Create new layer with flattened canvas
        Layer newLayer = new Layer(layerName, flattenedCanvas, true, 1.0f);

        // Add layer to manager at the end
        layerManager.addLayerAt(layerManager.getLayerCount(), newLayer);

        logger.info("Successfully imported .OMT as flattened layer: {}", layerName);
    }

    /**
     * Imports all layers from an .OMT file individually.
     * Preserves layer names, visibility, and opacity settings.
     */
    public void importOMTAllLayers(String filePath, LayerManager layerManager) throws Exception {
        String fileName = new File(filePath).getName();
        logger.info("Importing all layers from .OMT file: {}", fileName);

        // Load .OMT file using existing deserializer
        LayerManager importedLayerManager = omtDeserializer.load(filePath);

        if (importedLayerManager == null) {
            throw new Exception("Failed to load .OMT file");
        }

        // Validate dimensions match canvas size
        int canvasWidth = layerManager.getCanvasWidth();
        int canvasHeight = layerManager.getCanvasHeight();

        if (importedLayerManager.getCanvasWidth() != canvasWidth ||
            importedLayerManager.getCanvasHeight() != canvasHeight) {
            throw new Exception(String.format(
                ".OMT dimensions (%dx%d) don't match canvas size (%dx%d)",
                importedLayerManager.getCanvasWidth(),
                importedLayerManager.getCanvasHeight(),
                canvasWidth, canvasHeight
            ));
        }

        // Import each layer individually
        List<Layer> layers = importedLayerManager.getLayers();
        int importedCount = 0;

        for (Layer layer : layers) {
            // Add each layer to the manager, preserving all properties
            layerManager.addLayerAt(layerManager.getLayerCount(), layer);
            importedCount++;
        }

        logger.info("Successfully imported {} layer(s) from .OMT file: {}", importedCount, fileName);
    }

    /**
     * Flattens all visible layers in a LayerManager into a single PixelCanvas.
     * Uses the LayerManager's built-in compositing method.
     */
    private PixelCanvas flattenLayers(LayerManager layerManager) {
        // Use LayerManager's built-in compositing (already implements proper alpha blending)
        return layerManager.compositeLayersToCanvas();
    }

    /**
     * Extracts file extension from filename.
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1);
    }

    /**
     * Gets the bounds of the last successfully imported image.
     * Used for auto-selecting the imported content.
     *
     * @return Rectangle representing the bounds of the last imported image, or null if no image was imported
     */
    public Rectangle getLastImportedBounds() {
        return lastImportedBounds;
    }
}
