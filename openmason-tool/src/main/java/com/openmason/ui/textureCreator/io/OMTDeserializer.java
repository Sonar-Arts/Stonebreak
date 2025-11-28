package com.openmason.ui.textureCreator.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.layers.Layer;
import com.openmason.ui.textureCreator.layers.LayerManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deserializer for Open Mason Texture (.OMT) file format.
 */
public class OMTDeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMTDeserializer.class);
    private final ObjectMapper objectMapper;

    /**
     * Create deserializer with JSON support.
     */
    public OMTDeserializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Load .OMT file into LayerManager.
     *
     * @param filePath input file path
     * @return loaded layer manager, or null if failed
     */
    public LayerManager load(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return null;
        }

        if (OMTFormat.hasValidExtension(filePath)) {
            logger.error("File does not have .omt extension: {}", filePath);
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("File does not exist: {}", filePath);
            return null;
        }

        try {
            // Extract ZIP contents to memory
            OMTArchive archive = extractArchive(file);
            if (archive == null) {
                logger.error("Failed to extract .OMT archive: {}", filePath);
                return null;
            }

            // Parse manifest
            OMTFormat.Document document = parseManifest(archive.manifestData);
            if (document == null) {
                logger.error("Failed to parse manifest.json: {}", filePath);
                return null;
            }

            // Validate format version
            if (!OMTFormat.FORMAT_VERSION.equals(document.version())) {
                logger.warn("Unsupported .OMT format version: {} (expected {})",
                        document.version(), OMTFormat.FORMAT_VERSION);
                // Continue anyway - might be compatible
            }

            // Build LayerManager
            LayerManager layerManager = buildLayerManager(document, archive);
            if (layerManager == null) {
                logger.error("Failed to build LayerManager: {}", filePath);
                return null;
            }

            logger.info("Loaded .OMT file: {}", filePath);
            return layerManager;

        } catch (IOException e) {
            logger.error("Error loading .OMT file: {}", filePath, e);
            return null;
        }
    }

    /**
     * Extract ZIP archive contents to memory.
     *
     * @param file ZIP file to extract
     * @return archive contents, or null if failed
     * @throws IOException if read fails
     */
    private OMTArchive extractArchive(File file) throws IOException {
        OMTArchive archive = new OMTArchive();

        try (FileInputStream fis = new FileInputStream(file);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Read entry data
                byte[] entryData = readEntryData(zis);

                // Store based on entry type
                if (OMTFormat.MANIFEST_FILENAME.equals(entryName)) {
                    archive.manifestData = entryData;
                    logger.debug("Extracted manifest.json ({} bytes)", entryData.length);
                } else if (entryName.startsWith(OMTFormat.LAYER_FILENAME_PREFIX)
                        && entryName.endsWith(OMTFormat.LAYER_FILENAME_SUFFIX)) {
                    archive.layerData.put(entryName, entryData);
                    logger.debug("Extracted {} ({} bytes)", entryName, entryData.length);
                } else {
                    logger.warn("Ignoring unknown entry in .OMT archive: {}", entryName);
                }

                zis.closeEntry();
            }
        }

        // Validate archive
        if (archive.manifestData == null) {
            logger.error("Missing manifest.json in .OMT archive");
            return null;
        }

        return archive;
    }

    /**
     * Read ZIP entry data to byte array.
     *
     * @param zis ZIP input stream positioned at entry
     * @return entry data
     * @throws IOException if read fails
     */
    private byte[] readEntryData(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    /**
     * Parse manifest.json data.
     *
     * @param manifestData JSON data as bytes
     * @return parsed document, or null if failed
     */
    private OMTFormat.Document parseManifest(byte[] manifestData) {
        try {
            // Parse JSON to DTO
            ManifestDTO dto = objectMapper.readValue(manifestData, ManifestDTO.class);

            // Convert DTO to domain model
            OMTFormat.CanvasSize canvasSize = new OMTFormat.CanvasSize(
                    dto.canvasSize.width,
                    dto.canvasSize.height
            );

            List<OMTFormat.LayerInfo> layers = new ArrayList<>();
            for (LayerInfoDTO layerDTO : dto.layers) {
                OMTFormat.LayerInfo layerInfo = new OMTFormat.LayerInfo(
                        layerDTO.name,
                        layerDTO.visible,
                        layerDTO.opacity,
                        layerDTO.dataFile
                );
                layers.add(layerInfo);
            }

            return new OMTFormat.Document(
                    dto.version,
                    canvasSize,
                    layers,
                    dto.activeLayerIndex
            );

        } catch (Exception e) {
            logger.error("Error parsing manifest.json", e);
            return null;
        }
    }

    /**
     * Build LayerManager from document and archive data.
     *
     * @param document parsed document
     * @param archive extracted archive data
     * @return layer manager, or null if failed
     */
    private LayerManager buildLayerManager(OMTFormat.Document document, OMTArchive archive) {
        // Create layer manager with canvas dimensions
        int width = document.canvasSize().width();
        int height = document.canvasSize().height();
        LayerManager layerManager = new LayerManager(width, height);

        // Load each layer from the file
        List<OMTFormat.LayerInfo> layerInfos = document.layers();
        for (int i = 0; i < layerInfos.size(); i++) {
            OMTFormat.LayerInfo layerInfo = layerInfos.get(i);

            // Get layer PNG data
            byte[] pngData = archive.layerData.get(layerInfo.dataFile());
            if (pngData == null) {
                logger.error("Missing layer data file: {}", layerInfo.dataFile());
                return null;
            }

            // Decode PNG to PixelCanvas
            PixelCanvas canvas = decodePNG(pngData, width, height);
            if (canvas == null) {
                logger.error("Failed to decode PNG for layer: {}", layerInfo.name());
                return null;
            }

            // Create layer with loaded data
            Layer layer = new Layer(
                    layerInfo.name(),
                    canvas,
                    layerInfo.visible(),
                    layerInfo.opacity()
            );

            // Add layers after the default "Background" layer
            // They will be added at indices 1, 2, 3, etc.
            layerManager.addLayerAt(i + 1, layer);
        }

        // Now remove the default "Background" layer at index 0
        // (Safe to do now since we have other layers)
        layerManager.removeLayer(0);

        // Set active layer (no need to adjust index since we added then removed)
        layerManager.setActiveLayer(document.activeLayerIndex());

        return layerManager;
    }

    /**
     * Decode PNG data to PixelCanvas.
     *
     * @param pngData PNG file data
     * @param expectedWidth expected canvas width
     * @param expectedHeight expected canvas height
     * @return decoded canvas, or null if failed
     */
    private PixelCanvas decodePNG(byte[] pngData, int expectedWidth, int expectedHeight) {
        try {
            // Create ByteBuffer from PNG data
            ByteBuffer pngBuffer = BufferUtils.createByteBuffer(pngData.length);
            pngBuffer.put(pngData);
            pngBuffer.flip();

            // Prepare buffers for image info
            IntBuffer width = BufferUtils.createIntBuffer(1);
            IntBuffer height = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);

            // Load image data (request RGBA format)
            ByteBuffer imageData = STBImage.stbi_load_from_memory(pngBuffer, width, height, channels, 4);

            if (imageData == null) {
                logger.error("STB failed to decode PNG: {}", STBImage.stbi_failure_reason());
                return null;
            }

            int imageWidth = width.get(0);
            int imageHeight = height.get(0);

            // Validate dimensions
            if (imageWidth != expectedWidth || imageHeight != expectedHeight) {
                logger.error("PNG dimensions mismatch: got {}x{}, expected {}x{}",
                        imageWidth, imageHeight, expectedWidth, expectedHeight);
                STBImage.stbi_image_free(imageData);
                return null;
            }

            // Create canvas and copy pixel data
            PixelCanvas canvas = new PixelCanvas(imageWidth, imageHeight);
            for (int y = 0; y < imageHeight; y++) {
                for (int x = 0; x < imageWidth; x++) {
                    int index = (y * imageWidth + x) * 4; // 4 bytes per pixel (RGBA)

                    // Read RGBA values (unsigned bytes)
                    int r = imageData.get(index) & 0xFF;
                    int g = imageData.get(index + 1) & 0xFF;
                    int b = imageData.get(index + 2) & 0xFF;
                    int a = imageData.get(index + 3) & 0xFF;

                    // Pack into color int
                    int color = PixelCanvas.packRGBA(r, g, b, a);
                    canvas.setPixel(x, y, color);
                }
            }

            // Free image data
            STBImage.stbi_image_free(imageData);

            return canvas;

        } catch (Exception e) {
            logger.error("Error decoding PNG", e);
            return null;
        }
    }

    /**
     * Validate input file path.
     *
     * @param filePath file path to validate
     * @return true if path exists and is a .OMT file
     */
    public boolean validateFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        if (OMTFormat.hasValidExtension(filePath)) {
            return false;
        }

        File file = new File(filePath);
        return file.exists() && file.isFile();
    }

    /**
     * In-memory representation of extracted .OMT archive.
     */
    private static class OMTArchive {
        byte[] manifestData;
        Map<String, byte[]> layerData = new HashMap<>();
    }

    /**
     * Data Transfer Objects for JSON deserialization.
     * Jackson deserializes to public fields automatically.
     */
    private static class ManifestDTO {
        public String version;
        public CanvasSizeDTO canvasSize;
        public List<LayerInfoDTO> layers;
        public int activeLayerIndex;
    }

    private static class CanvasSizeDTO {
        public int width;
        public int height;
    }

    private static class LayerInfoDTO {
        public String name;
        public boolean visible;
        public float opacity;
        public String dataFile;
    }
}
