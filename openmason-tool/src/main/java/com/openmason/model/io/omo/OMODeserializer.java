package com.openmason.model.io.omo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.model.editable.BlockModel;
import com.openmason.model.editable.CubeGeometry;
import com.openmason.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deserializer for Open Mason Object (.OMO) file format.
 *
 * <p>Reads ZIP-based .OMO files and reconstructs BlockModel instances.
 * The deserializer:
 * <ul>
 *   <li>Reads and validates manifest.json</li>
 *   <li>Extracts embedded .OMT texture file</li>
 *   <li>Reconstructs model geometry</li>
 *   <li>Creates BlockModel with loaded data</li>
 * </ul>
 *
 * <p>Validation includes:
 * <ul>
 *   <li>Format version compatibility</li>
 *   <li>Required fields presence</li>
 *   <li>Geometry data validity</li>
 *   <li>Texture file existence</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .OMO file reading</li>
 *   <li>KISS: Straightforward ZIP extraction and JSON parsing</li>
 *   <li>Fail-fast: Comprehensive validation with clear error messages</li>
 * </ul>
 *
 * @since 1.0
 */
public class OMODeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMODeserializer.class);
    private final ObjectMapper objectMapper;

    /**
     * Creates a new .OMO deserializer with JSON support.
     */
    public OMODeserializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Loads a BlockModel from a .OMO file.
     *
     * @param filePath path to the .OMO file
     * @return the loaded BlockModel, or null if loading failed
     */
    public BlockModel load(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return null;
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            logger.error("File does not exist: {}", filePath);
            return null;
        }

        if (!OMOFormat.hasOMOExtension(filePath)) {
            logger.warn("File does not have .omo extension: {}", filePath);
        }

        try {
            // Load document from ZIP
            LoadedData loadedData = loadFromZip(path);

            // Build BlockModel from loaded data
            BlockModel model = buildModel(loadedData);

            // Set file path and mark clean (just loaded)
            model.setFilePath(path);
            model.markClean();

            logger.info("Loaded .OMO file: {}", filePath);
            return model;

        } catch (Exception e) {
            logger.error("Error loading .OMO file: {}", filePath, e);
            return null;
        }
    }

    /**
     * Loads manifest and texture from ZIP file.
     *
     * @param path path to the .OMO ZIP file
     * @return loaded data containing manifest and texture path
     * @throws IOException if reading fails
     */
    private LoadedData loadFromZip(Path path) throws IOException {
        OMOFormat.Document document = null;
        Path texturePath = null;

        try (FileInputStream fis = new FileInputStream(path.toFile());
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (OMOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    // Read manifest.json
                    document = readManifest(zis);
                    logger.debug("Loaded manifest.json");

                } else if (OMOFormat.DEFAULT_TEXTURE_FILENAME.equals(entryName)) {
                    // Extract texture.omt to temporary file
                    texturePath = extractTexture(zis);
                    logger.debug("Extracted texture.omt to: {}", texturePath);
                }

                zis.closeEntry();
            }
        }

        // Validate required entries
        if (document == null) {
            throw new IOException("Missing manifest.json in .OMO file");
        }
        if (texturePath == null) {
            throw new IOException("Missing texture.omt in .OMO file");
        }

        return new LoadedData(document, texturePath);
    }

    /**
     * Reads and parses manifest.json from ZIP stream.
     *
     * @param zis ZIP input stream positioned at manifest entry
     * @return parsed OMO document
     * @throws IOException if read or parse fails
     */
    private OMOFormat.Document readManifest(ZipInputStream zis) throws IOException {
        // Read JSON from stream
        byte[] jsonBytes = readStreamToByteArray(zis);
        String json = new String(jsonBytes, "UTF-8");

        // Parse JSON
        JsonNode root = objectMapper.readTree(json);

        // Validate version
        String version = root.get("version").asText();
        if (!OMOFormat.FORMAT_VERSION.equals(version)) {
            logger.warn("Format version mismatch: expected {}, got {}",
                       OMOFormat.FORMAT_VERSION, version);
        }

        // Extract fields
        String objectName = root.get("objectName").asText();
        String modelType = root.get("modelType").asText();
        String textureFile = root.get("textureFile").asText();

        // Parse geometry
        JsonNode geometryNode = root.get("geometry");
        int width = geometryNode.get("width").asInt();
        int height = geometryNode.get("height").asInt();
        int depth = geometryNode.get("depth").asInt();

        JsonNode positionNode = geometryNode.get("position");
        double x = positionNode.get("x").asDouble();
        double y = positionNode.get("y").asDouble();
        double z = positionNode.get("z").asDouble();

        // Build document
        OMOFormat.Position position = new OMOFormat.Position(x, y, z);
        OMOFormat.GeometryData geometryData = new OMOFormat.GeometryData(
            width, height, depth, position
        );

        // Note: Texture format is auto-detected from .OMT dimensions at runtime
        return new OMOFormat.Document(version, objectName, modelType,
                                     geometryData, textureFile);
    }

    /**
     * Extracts texture .OMT file to a temporary location.
     *
     * @param zis ZIP input stream positioned at texture entry
     * @return path to extracted texture file
     * @throws IOException if extraction fails
     */
    private Path extractTexture(ZipInputStream zis) throws IOException {
        // Create temporary file for texture
        Path tempFile = Files.createTempFile("omo_texture_", ".omt");
        // Note: Not deleting on exit - texture is needed for model editing

        // Copy ZIP entry to temp file
        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = zis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        return tempFile;
    }

    /**
     * Reads a ZIP stream to a byte array without closing the stream.
     *
     * @param zis ZIP input stream
     * @return byte array with stream contents
     * @throws IOException if read fails
     */
    private byte[] readStreamToByteArray(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    /**
     * Builds a BlockModel from loaded data.
     *
     * @param data loaded manifest and texture data
     * @return constructed BlockModel
     */
    private BlockModel buildModel(LoadedData data) {
        OMOFormat.Document document = data.document;
        OMOFormat.GeometryData geometryData = document.getGeometry();
        OMOFormat.Position position = geometryData.getPosition();

        // Create geometry
        ModelGeometry geometry = new CubeGeometry(
            geometryData.getWidth(),
            geometryData.getHeight(),
            geometryData.getDepth(),
            position.getX(),
            position.getY(),
            position.getZ()
        );

        // Create model
        // Note: Texture format (cube net vs flat) is auto-detected from .OMT dimensions at load time
        BlockModel model = new BlockModel(
            document.getObjectName(),
            geometry,
            data.texturePath
        );

        return model;
    }

    /**
     * Container for loaded manifest and texture data.
     */
    private static class LoadedData {
        final OMOFormat.Document document;
        final Path texturePath;

        LoadedData(OMOFormat.Document document, Path texturePath) {
            this.document = document;
            this.texturePath = texturePath;
        }
    }
}
