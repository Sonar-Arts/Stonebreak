package com.openmason.ui.components.textureCreator.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImageWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Open Mason Texture (.OMT) file format.
 *
 * Creates a ZIP-based container with:
 * - manifest.json: Metadata about canvas and layers
 * - layer_N.png: PNG image for each layer
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only handles .OMT file writing
 * - Uses TextureExporter pattern for PNG encoding
 *
 * @author Open Mason Team
 */
public class OMTSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMTSerializer.class);
    private final ObjectMapper objectMapper;

    /**
     * Create serializer with JSON support.
     */
    public OMTSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty-print JSON
    }

    /**
     * Save LayerManager state to .OMT file.
     *
     * @param layerManager layer manager to save
     * @param filePath output file path
     * @return true if save succeeded
     */
    public boolean save(LayerManager layerManager, String filePath) {
        if (layerManager == null) {
            logger.error("Cannot save null layer manager");
            return false;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return false;
        }

        // Ensure .omt extension
        filePath = OMTFormat.ensureExtension(filePath);

        try {
            // Build OMT document structure
            OMTFormat.Document document = buildDocument(layerManager);

            // Write to ZIP archive
            try (FileOutputStream fos = new FileOutputStream(filePath);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Write manifest.json
                writeManifest(zos, document);

                // Write layer PNG files
                List<Layer> layers = layerManager.getLayers();
                for (int i = 0; i < layers.size(); i++) {
                    Layer layer = layers.get(i);
                    String layerFilename = OMTFormat.generateLayerFilename(i);
                    writeLayerPNG(zos, layer, layerFilename);
                }
            }

            logger.info("Saved .OMT file: {}", filePath);
            return true;

        } catch (IOException e) {
            logger.error("Error saving .OMT file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Build OMT document structure from LayerManager.
     *
     * @param layerManager source layer manager
     * @return OMT document
     */
    private OMTFormat.Document buildDocument(LayerManager layerManager) {
        // Get canvas dimensions from first layer
        Layer firstLayer = layerManager.getLayer(0);
        int width = firstLayer.getCanvas().getWidth();
        int height = firstLayer.getCanvas().getHeight();
        OMTFormat.CanvasSize canvasSize = new OMTFormat.CanvasSize(width, height);

        // Build layer info list
        List<OMTFormat.LayerInfo> layerInfos = new ArrayList<>();
        List<Layer> layers = layerManager.getLayers();
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            String layerFilename = OMTFormat.generateLayerFilename(i);
            OMTFormat.LayerInfo layerInfo = new OMTFormat.LayerInfo(
                    layer.getName(),
                    layer.isVisible(),
                    layer.getOpacity(),
                    layerFilename
            );
            layerInfos.add(layerInfo);
        }

        // Get active layer index
        int activeLayerIndex = layerManager.getActiveLayerIndex();

        // Build document
        return new OMTFormat.Document(
                OMTFormat.FORMAT_VERSION,
                canvasSize,
                layerInfos,
                activeLayerIndex
        );
    }

    /**
     * Write manifest.json to ZIP archive.
     *
     * @param zos ZIP output stream
     * @param document OMT document
     * @throws IOException if write fails
     */
    private void writeManifest(ZipOutputStream zos, OMTFormat.Document document) throws IOException {
        // Create JSON entry
        ZipEntry manifestEntry = new ZipEntry(OMTFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        // Serialize document to JSON
        String json = objectMapper.writeValueAsString(new ManifestDTO(document));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        // Write JSON to ZIP
        zos.write(jsonBytes);
        zos.flush(); // Ensure data is written to stream
        zos.closeEntry();

        logger.debug("Wrote manifest.json ({} bytes)", jsonBytes.length);
    }

    /**
     * Write layer PNG to ZIP archive.
     *
     * @param zos ZIP output stream
     * @param layer layer to write
     * @param filename filename in ZIP
     * @throws IOException if write fails
     */
    private void writeLayerPNG(ZipOutputStream zos, Layer layer, String filename) throws IOException {
        // Create PNG entry
        ZipEntry layerEntry = new ZipEntry(filename);
        zos.putNextEntry(layerEntry);

        // Encode layer pixels to PNG
        byte[] pngData = encodePNG(layer);
        if (pngData == null) {
            throw new IOException("Failed to encode PNG for layer: " + layer.getName());
        }

        // Write PNG to ZIP
        zos.write(pngData);
        zos.flush(); // Ensure data is written to stream
        zos.closeEntry();

        logger.debug("Wrote {} ({} bytes)", filename, pngData.length);
    }

    /**
     * Encode layer pixels to PNG format.
     *
     * @param layer layer to encode
     * @return PNG data as byte array, or null if failed
     */
    private byte[] encodePNG(Layer layer) {
        try {
            // Get pixel data
            int width = layer.getCanvas().getWidth();
            int height = layer.getCanvas().getHeight();
            byte[] pixelBytes = layer.getCanvas().getPixelsAsRGBABytes();

            // Create ByteBuffer for STB
            ByteBuffer buffer = BufferUtils.createByteBuffer(pixelBytes.length);
            buffer.put(pixelBytes);
            buffer.flip();

            // Write PNG to memory using STB
            // Note: STB can write to memory via callback, but for simplicity we'll use temp file
            File tempFile = File.createTempFile("omt_layer_", ".png");
            tempFile.deleteOnExit();

            int stride = width * 4; // 4 bytes per pixel (RGBA)
            boolean success = STBImageWrite.stbi_write_png(
                    tempFile.getAbsolutePath(),
                    width,
                    height,
                    4,
                    buffer,
                    stride
            );

            if (!success) {
                logger.error("STB PNG encoding failed");
                return null;
            }

            // Read PNG data from temp file
            byte[] pngData = readFileToByteArray(tempFile);
            tempFile.delete();

            return pngData;

        } catch (Exception e) {
            logger.error("Error encoding PNG", e);
            return null;
        }
    }

    /**
     * Read file contents to byte array.
     *
     * @param file file to read
     * @return file contents
     * @throws IOException if read fails
     */
    private byte[] readFileToByteArray(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }

            return baos.toByteArray();
        }
    }

    /**
     * Validate file path for writing.
     *
     * @param filePath file path to validate
     * @return true if path is valid and writable
     */
    public boolean validateFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        try {
            File file = new File(OMTFormat.ensureExtension(filePath));
            File parent = file.getParentFile();

            // Check if parent directory exists or can be created
            if (parent != null && !parent.exists()) {
                return parent.mkdirs();
            }

            return true;
        } catch (Exception e) {
            logger.warn("Invalid file path: {}", filePath, e);
            return false;
        }
    }

    /**
     * Data Transfer Object for JSON serialization of manifest.
     * Jackson serializes public fields or getters automatically.
     */
    private static class ManifestDTO {
        public String version;
        public CanvasSizeDTO canvasSize;
        public List<LayerInfoDTO> layers;
        public int activeLayerIndex;

        public ManifestDTO(OMTFormat.Document document) {
            this.version = document.getVersion();
            this.canvasSize = new CanvasSizeDTO(document.getCanvasSize());
            this.layers = new ArrayList<>();
            for (OMTFormat.LayerInfo layerInfo : document.getLayers()) {
                this.layers.add(new LayerInfoDTO(layerInfo));
            }
            this.activeLayerIndex = document.getActiveLayerIndex();
        }
    }

    private static class CanvasSizeDTO {
        public int width;
        public int height;

        public CanvasSizeDTO(OMTFormat.CanvasSize canvasSize) {
            this.width = canvasSize.getWidth();
            this.height = canvasSize.getHeight();
        }
    }

    private static class LayerInfoDTO {
        public String name;
        public boolean visible;
        public float opacity;
        public String dataFile;

        public LayerInfoDTO(OMTFormat.LayerInfo layerInfo) {
            this.name = layerInfo.getName();
            this.visible = layerInfo.isVisible();
            this.opacity = layerInfo.getOpacity();
            this.dataFile = layerInfo.getDataFile();
        }
    }
}
