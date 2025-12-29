package com.openmason.main.systems.rendering.model.io.omo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
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
 *   <li>manifest.json - Model metadata, geometry, and optional custom mesh data</li>
 *   <li>texture.omt - Embedded texture file</li>
 * </ul>
 *
 * <p>The serializer handles:
 * <ul>
 *   <li>Building manifest from BlockModel</li>
 *   <li>Embedding .OMT texture file</li>
 *   <li>Writing ZIP archive</li>
 *   <li>Atomic file operations (write to temp, then move)</li>
 *   <li>Custom mesh data for subdivided models (v2.0+)</li>
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
 * @since 2.0 Added custom mesh data support
 */
public class OMOSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMOSerializer.class);
    private final ObjectMapper objectMapper;

    // Optional mesh data for saving subdivided models
    private OMOFormat.MeshData pendingMeshData;

    /**
     * Creates a new .OMO serializer with JSON support.
     */
    public OMOSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty-print JSON
    }

    /**
     * Set custom mesh data to be saved with the next model.
     * Call this before save() to include mesh data in the .OMO file.
     *
     * @param meshData The mesh data to save, or null to save standard cube
     */
    public void setMeshData(OMOFormat.MeshData meshData) {
        this.pendingMeshData = meshData;
    }

    /**
     * Clear any pending mesh data.
     */
    public void clearMeshData() {
        this.pendingMeshData = null;
    }

    /**
     * Saves a BlockModel to a .OMO file with custom mesh data.
     * Convenience method that sets mesh data and saves in one call.
     *
     * @param model the model to save, must not be null
     * @param filePath output file path, will be given .omo extension if missing
     * @param meshData custom mesh data, or null for standard cube
     * @return true if save succeeded
     */
    public boolean save(BlockModel model, String filePath, OMOFormat.MeshData meshData) {
        setMeshData(meshData);
        return save(model, filePath);
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
        // Build manifest document (v1.1 with optional mesh data)
        OMOFormat.ExtendedDocument document = buildExtendedDocument(model);

        // Create manifest entry
        ZipEntry manifestEntry = new ZipEntry(OMOFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(new ExtendedManifestDTO(document));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        // Write JSON to ZIP
        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();

        // Clear pending mesh data after save
        pendingMeshData = null;

        logger.debug("Wrote manifest.json ({} bytes, hasMesh={})", jsonBytes.length, document.hasCustomMesh());
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
     * Builds an extended OMO document from a BlockModel.
     *
     * @param model the source model
     * @return the OMO document structure with optional mesh data
     */
    private OMOFormat.ExtendedDocument buildExtendedDocument(BlockModel model) {
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

        return new OMOFormat.ExtendedDocument(
            OMOFormat.FORMAT_VERSION,
            model.getName(),
            model.getModelType(),
            geometryData,
            OMOFormat.DEFAULT_TEXTURE_FILENAME,
            pendingMeshData  // May be null for standard cube
        );
    }

    /**
     * Data Transfer Object for JSON serialization of manifest (v1.1+).
     * Jackson serializes public fields automatically.
     *
     * <p>Note: Texture format auto-detected from .OMT dimensions at load time.
     */
    private static class ExtendedManifestDTO {
        public String version;
        public String objectName;
        public String modelType;
        public GeometryDTO geometry;
        public String textureFile;
        public MeshDataDTO mesh;  // Optional, null for standard cube

        public ExtendedManifestDTO(OMOFormat.ExtendedDocument document) {
            this.version = document.version();
            this.objectName = document.objectName();
            this.modelType = document.modelType();
            this.geometry = new GeometryDTO(document.geometry());
            this.textureFile = document.textureFile();
            this.mesh = document.mesh() != null ? new MeshDataDTO(document.mesh()) : null;
        }
    }

    /**
     * DTO for custom mesh data (v1.1+).
     * Contains all vertex, index, and UV data for edited/subdivided models.
     */
    private static class MeshDataDTO {
        public float[] vertices;      // x,y,z interleaved
        public float[] texCoords;     // u,v interleaved
        public int[] indices;         // Triangle indices
        public int[] triangleToFaceId; // Face mapping
        public String uvMode;         // "FLAT" or "CUBE_NET"

        public MeshDataDTO(OMOFormat.MeshData meshData) {
            this.vertices = meshData.vertices();
            this.texCoords = meshData.texCoords();
            this.indices = meshData.indices();
            this.triangleToFaceId = meshData.triangleToFaceId();
            this.uvMode = meshData.uvMode();
        }
    }

    private static class GeometryDTO {
        public int width;
        public int height;
        public int depth;
        public PositionDTO position;

        public GeometryDTO(OMOFormat.GeometryData geometryData) {
            this.width = geometryData.width();
            this.height = geometryData.height();
            this.depth = geometryData.depth();
            this.position = new PositionDTO(geometryData.position());
        }
    }

    private static class PositionDTO {
        public double x;
        public double y;
        public double z;

        public PositionDTO(OMOFormat.Position position) {
            this.x = position.x();
            this.y = position.y();
            this.z = position.z();
        }
    }
}
