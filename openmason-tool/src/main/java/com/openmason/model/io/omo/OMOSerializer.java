package com.openmason.model.io.omo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.model.editable.BlockModel;
import com.openmason.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Open Mason Object (.OMO) file format.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>manifest.json - Model metadata and geometry</li>
 *   <li>texture.omt - Embedded texture file</li>
 * </ul>
 *
 * <p>The serializer handles:
 * <ul>
 *   <li>Building manifest from BlockModel</li>
 *   <li>Embedding .OMT texture file</li>
 *   <li>Writing ZIP archive</li>
 *   <li>Atomic file operations (write to temp, then move)</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .OMO file writing</li>
 *   <li>DRY: Reuses existing .OMT files (no re-encoding)</li>
 *   <li>KISS: Simple ZIP creation with JSON manifest</li>
 * </ul>
 *
 * @since 1.0
 */
public class OMOSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMOSerializer.class);
    private final ObjectMapper objectMapper;

    /**
     * Creates a new .OMO serializer with JSON support.
     */
    public OMOSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty-print JSON
    }

    /**
     * Saves a BlockModel to a .OMO file.
     *
     * <p>The save operation is atomic - writes to a temporary file first,
     * then moves to the final location to prevent corruption on failure.
     *
     * @param model the model to save, must not be null
     * @param filePath output file path, will be given .omo extension if missing
     * @return true if save succeeded
     */
    public boolean save(BlockModel model, String filePath) {
        if (model == null) {
            logger.error("Cannot save null model");
            return false;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return false;
        }

        // Ensure .omo extension
        filePath = OMOFormat.ensureExtension(filePath);

        // Validate texture exists
        Path texturePath = model.getTexturePath();
        if (texturePath == null || !Files.exists(texturePath)) {
            logger.error("Model has no texture or texture file does not exist: {}", texturePath);
            return false;
        }

        try {
            // Write to temporary file first (atomic operation)
            Path tempFile = Files.createTempFile("omo_save_", ".tmp");

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Write manifest.json
                writeManifest(zos, model);

                // Embed texture.omt file
                embedTexture(zos, texturePath);
            }

            // Move temp file to final location (atomic on most filesystems)
            Path finalPath = Path.of(filePath);
            Files.move(tempFile, finalPath,
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Update model state
            model.setFilePath(finalPath);
            model.markClean();

            logger.info("Saved .OMO file: {}", filePath);
            return true;

        } catch (IOException e) {
            logger.error("Error saving .OMO file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Writes the manifest.json entry to the ZIP archive.
     *
     * @param zos ZIP output stream
     * @param model the model to serialize
     * @throws IOException if write fails
     */
    private void writeManifest(ZipOutputStream zos, BlockModel model) throws IOException {
        // Build manifest document
        OMOFormat.Document document = buildDocument(model);

        // Create manifest entry
        ZipEntry manifestEntry = new ZipEntry(OMOFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(new ManifestDTO(document));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        // Write JSON to ZIP
        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote manifest.json ({} bytes)", jsonBytes.length);
    }

    /**
     * Embeds the texture .OMT file into the ZIP archive.
     *
     * @param zos ZIP output stream
     * @param texturePath path to the .OMT file to embed
     * @throws IOException if read or write fails
     */
    private void embedTexture(ZipOutputStream zos, Path texturePath) throws IOException {
        // Create texture entry
        ZipEntry textureEntry = new ZipEntry(OMOFormat.DEFAULT_TEXTURE_FILENAME);
        zos.putNextEntry(textureEntry);

        // Copy .OMT file contents to ZIP
        byte[] textureData = Files.readAllBytes(texturePath);
        zos.write(textureData);
        zos.flush();
        zos.closeEntry();

        logger.debug("Embedded texture.omt ({} bytes)", textureData.length);
    }

    /**
     * Builds an OMO document from a BlockModel.
     *
     * @param model the source model
     * @return the OMO document structure
     */
    private OMOFormat.Document buildDocument(BlockModel model) {
        ModelGeometry geometry = model.getGeometry();

        OMOFormat.Position position = new OMOFormat.Position(
            geometry.getX(),
            geometry.getY(),
            geometry.getZ()
        );

        OMOFormat.GeometryData geometryData = new OMOFormat.GeometryData(
            geometry.getWidth(),
            geometry.getHeight(),
            geometry.getDepth(),
            position
        );

        return new OMOFormat.Document(
            OMOFormat.FORMAT_VERSION,
            model.getName(),
            model.getModelType(),
            geometryData,
            OMOFormat.DEFAULT_TEXTURE_FILENAME
        );
    }

    /**
     * Validates that a file path is writable.
     *
     * @param filePath the path to validate
     * @return true if the path is valid and writable
     */
    public boolean validateFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        try {
            File file = new File(OMOFormat.ensureExtension(filePath));
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
     * Jackson serializes public fields automatically.
     */
    private static class ManifestDTO {
        public String version;
        public String objectName;
        public String modelType;
        public GeometryDTO geometry;
        public String textureFile;

        public ManifestDTO(OMOFormat.Document document) {
            this.version = document.getVersion();
            this.objectName = document.getObjectName();
            this.modelType = document.getModelType();
            this.geometry = new GeometryDTO(document.getGeometry());
            this.textureFile = document.getTextureFile();
        }
    }

    private static class GeometryDTO {
        public int width;
        public int height;
        public int depth;
        public PositionDTO position;

        public GeometryDTO(OMOFormat.GeometryData geometryData) {
            this.width = geometryData.getWidth();
            this.height = geometryData.getHeight();
            this.depth = geometryData.getDepth();
            this.position = new PositionDTO(geometryData.getPosition());
        }
    }

    private static class PositionDTO {
        public double x;
        public double y;
        public double z;

        public PositionDTO(OMOFormat.Position position) {
            this.x = position.getX();
            this.y = position.getY();
            this.z = position.getZ();
        }
    }
}
