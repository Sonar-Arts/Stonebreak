package com.openmason.ui.components.textureCreator.io;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles drag-and-drop operations for PNG and .OMT files in the texture editor.
 *
 * Features:
 * - Imports PNG files as new layers
 * - Imports .OMT files (flattened to a single layer)
 * - Validates file formats and dimensions
 * - Integrates with existing LayerManager
 */
public class DragDropHandler {
    private static final Logger logger = LoggerFactory.getLogger(DragDropHandler.class);

    private final TextureImporter textureImporter;
    private final OMTDeserializer omtDeserializer;

    public DragDropHandler() {
        this.textureImporter = new TextureImporter();
        this.omtDeserializer = new OMTDeserializer();
    }

    /**
     * Processes dropped PNG files and adds them as layers to the LayerManager.
     * Note: .OMT files are handled separately via dialog interaction in TextureCreatorImGui.
     *
     * @param filePaths Array of PNG file paths that were dropped
     * @param layerManager The LayerManager to add layers to
     * @return List of error messages (empty if all successful)
     */
    public List<String> processDroppedPNGFiles(String[] filePaths, LayerManager layerManager) {
        List<String> errors = new ArrayList<>();

        if (filePaths == null || filePaths.length == 0) {
            return errors;
        }

        for (String filePath : filePaths) {
            try {
                Path path = Paths.get(filePath);
                String fileName = path.getFileName().toString();
                String extension = getFileExtension(fileName).toLowerCase();

                if (extension.equals("png")) {
                    importPNGAsLayer(filePath, fileName, layerManager);
                } else {
                    logger.warn("Skipping non-PNG file in processDroppedPNGFiles: {}", fileName);
                }
            } catch (Exception e) {
                String errorMsg = "Failed to import " + new File(filePath).getName() + ": " + e.getMessage();
                logger.error(errorMsg, e);
                errors.add(errorMsg);
            }
        }

        return errors;
    }

    /**
     * Imports a PNG file as a new layer.
     */
    private void importPNGAsLayer(String filePath, String fileName, LayerManager layerManager) throws Exception {
        logger.info("Importing PNG file as layer: {}", fileName);

        // Load PNG using existing TextureImporter
        PixelCanvas canvas = textureImporter.importFromPNG(filePath);

        if (canvas == null) {
            throw new Exception("Failed to load PNG file");
        }

        // Validate dimensions match canvas size
        int canvasWidth = layerManager.getCanvasWidth();
        int canvasHeight = layerManager.getCanvasHeight();

        if (canvas.getWidth() != canvasWidth || canvas.getHeight() != canvasHeight) {
            throw new Exception(String.format(
                "Image dimensions (%dx%d) don't match canvas size (%dx%d)",
                canvas.getWidth(), canvas.getHeight(), canvasWidth, canvasHeight
            ));
        }

        // Create layer name from filename (remove extension)
        String layerName = fileName.substring(0, fileName.lastIndexOf('.'));

        // Create new layer with imported canvas
        Layer newLayer = new Layer(layerName, canvas, true, 1.0f);

        // Add layer to manager at the end
        layerManager.addLayerAt(layerManager.getLayerCount(), newLayer);

        logger.info("Successfully imported PNG as layer: {}", layerName);
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
     * Validates if a file is a supported format for drag-drop.
     */
    public static boolean isSupportedFile(String filePath) {
        String extension = getFileExtensionStatic(filePath).toLowerCase();
        return extension.equals("png") || extension.equals("omt");
    }

    /**
     * Static version of getFileExtension for validation.
     */
    private static String getFileExtensionStatic(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1);
    }
}
