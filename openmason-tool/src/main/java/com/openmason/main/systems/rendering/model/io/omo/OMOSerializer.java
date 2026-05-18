package com.openmason.main.systems.rendering.model.io.omo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Open Mason Object (.OMO) file format.
 *
 * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current version saves legacy texture data
 * (raw texCoords + uvMode string). Future versions will support per-face texture atlas
 * coordinates, transformations, and flexible mapping modes. Will require texture creator upgrade.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>manifest.json - Model metadata, geometry, and mesh data</li>
 *   <li>texture.omt - Embedded texture file</li>
 * </ul>
 *
 * <p>The serializer handles:
 * <ul>
 *   <li>Building manifest from BlockModel</li>
 *   <li>Embedding .OMT texture file</li>
 *   <li>Writing ZIP archive</li>
 *   <li>Atomic file operations (write to temp, then move)</li>
 *   <li>Mesh data for all models (v2.0+) - makes .omo files self-contained</li>
 * </ul>
 *
 * <p><strong>Important:</strong> All .omo files should include mesh data to be self-contained.
 * Files without mesh data are considered legacy and require generation fallback.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .OMO file writing</li>
 *   <li>DRY: Reuses existing .OMT files (no re-encoding)</li>
 *   <li>KISS: Simple ZIP creation with JSON manifest</li>
 *   <li>Self-Contained: Always includes full mesh topology</li>
 * </ul>
 *
 * @since 1.0
 * @since 2.0 Added mesh data support
 * @since 2.1 Mesh data now always saved for self-contained files
 */
public class OMOSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMOSerializer.class);
    private final ObjectMapper objectMapper;

    // Optional mesh data for saving subdivided models
    private OMOFormat.MeshData pendingMeshData;

    // Optional face texture data for per-face material persistence (v1.2+)
    private OMOFormat.FaceTextureData pendingFaceTextureData;
    private Map<Integer, byte[]> pendingMaterialTexturePNGs;

    // Optional part entries for multi-part models (v1.3+)
    private List<OMOFormat.PartEntry> pendingParts;

    // Optional model-level transform (v1.4+)
    private OMOFormat.ModelTransform pendingModelTransform;

    // Optional bone skeleton entries (v1.6+)
    private List<OMOFormat.BoneEntry> pendingBones;

    /**
     * Creates a new .OMO serializer with JSON support.
     */
    public OMOSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty-print JSON
    }

    /**
     * Set mesh data to be saved with the next model.
     * Call this before save() to include mesh data in the .OMO file.
     *
     * <p><strong>Note:</strong> Mesh data should ALWAYS be provided to make .omo files self-contained.
     * Files without mesh data require legacy generation fallback.
     *
     * @param meshData The mesh data to save (should not be null for new files)
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
     * Set face texture data to be saved with the next model (v1.2+).
     *
     * @param faceTextureData face mapping and material metadata
     * @param materialPNGs    material textures as PNG byte arrays, keyed by materialId
     */
    public void setFaceTextureData(OMOFormat.FaceTextureData faceTextureData,
                                    Map<Integer, byte[]> materialPNGs) {
        this.pendingFaceTextureData = faceTextureData;
        this.pendingMaterialTexturePNGs = materialPNGs;
    }

    /**
     * Set model part entries to be saved with the next model (v1.3+).
     * Each entry contains the part's identity, transform, mesh range, and per-part geometry.
     *
     * @param parts List of part entries, or null for single-part models
     */
    public void setPartEntries(List<OMOFormat.PartEntry> parts) {
        this.pendingParts = parts;
    }

    /**
     * Set model-level transform to be saved with the next model (v1.4+).
     *
     * @param transform the model-level position, rotation, and scale
     */
    public void setModelTransform(OMOFormat.ModelTransform transform) {
        this.pendingModelTransform = transform;
    }

    /**
     * Set the bone skeleton entries to be saved with the next model (v1.6+).
     * Null or empty means the file carries no skeleton (backward-compatible).
     */
    public void setBoneEntries(List<OMOFormat.BoneEntry> bones) {
        this.pendingBones = bones;
    }

    /**
     * Saves a BlockModel to a .OMO file with mesh data.
     * Convenience method that sets mesh data and saves in one call.
     *
     * <p><strong>Note:</strong> Mesh data should ALWAYS be provided to make .omo files self-contained.
     * Files without mesh data require legacy generation fallback.
     *
     * @param model the model to save, must not be null
     * @param filePath output file path, will be given .omo extension if missing
     * @param meshData mesh data to save (should not be null for new files)
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

                // Write per-face material texture PNGs (v1.2+)
                writeMaterialTextures(zos);
            }

            // Clear pending data after all ZIP entries are written
            pendingMeshData = null;
            pendingFaceTextureData = null;
            pendingMaterialTexturePNGs = null;
            pendingParts = null;
            pendingModelTransform = null;
            pendingBones = null;

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

        // Serialize to JSON (pass pending data explicitly since DTO is static)
        String json = objectMapper.writeValueAsString(
                new ExtendedManifestDTO(document, pendingParts, pendingModelTransform, pendingBones));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        // Write JSON to ZIP
        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote manifest.json ({} bytes, hasMesh={}, hasFaceTextures={})",
            jsonBytes.length, document.hasCustomMesh(), document.hasFaceTextures());
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
            pendingMeshData,      // May be null for standard cube
            pendingFaceTextureData // May be null if no per-face textures
        );
    }

    /**
     * Write per-face material texture PNGs to the ZIP archive (v1.2+).
     */
    private void writeMaterialTextures(ZipOutputStream zos) throws IOException {
        if (pendingMaterialTexturePNGs == null || pendingMaterialTexturePNGs.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, byte[]> entry : pendingMaterialTexturePNGs.entrySet()) {
            String filename = "material_" + entry.getKey() + ".png";
            ZipEntry zipEntry = new ZipEntry(filename);
            zos.putNextEntry(zipEntry);
            zos.write(entry.getValue());
            zos.flush();
            zos.closeEntry();
            logger.debug("Wrote {} ({} bytes)", filename, entry.getValue().length);
        }
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
        public FaceTextureDataDTO faceTextures; // Optional, null for pre-1.2 files
        public List<PartEntryDTO> parts; // Optional, null for single-part models
        public ModelTransformDTO modelTransform; // Optional, null for identity (v1.4+)
        public List<BoneEntryDTO> bones; // Optional, null or empty for files without a skeleton (v1.6+)

        public ExtendedManifestDTO(OMOFormat.ExtendedDocument document,
                                   List<OMOFormat.PartEntry> partEntries,
                                   OMOFormat.ModelTransform modelTransform,
                                   List<OMOFormat.BoneEntry> boneEntries) {
            this.version = document.version();
            this.objectName = document.objectName();
            this.modelType = document.modelType();
            this.geometry = new GeometryDTO(document.geometry());
            this.textureFile = document.textureFile();
            this.mesh = document.mesh() != null ? new MeshDataDTO(document.mesh()) : null;
            this.faceTextures = document.faceTextures() != null
                    ? new FaceTextureDataDTO(document.faceTextures()) : null;
            this.parts = partEntries != null && !partEntries.isEmpty()
                    ? partEntries.stream().map(PartEntryDTO::new).toList() : null;
            this.modelTransform = modelTransform != null && !modelTransform.isIdentity()
                    ? new ModelTransformDTO(modelTransform) : null;
            this.bones = boneEntries != null && !boneEntries.isEmpty()
                    ? boneEntries.stream().map(BoneEntryDTO::new).toList() : null;
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

    /**
     * DTO for per-face texture data (v1.2+).
     */
    private static class FaceTextureDataDTO {
        public List<FaceMappingEntryDTO> mappings;
        public List<MaterialEntryDTO> materials;

        public FaceTextureDataDTO(OMOFormat.FaceTextureData data) {
            this.mappings = data.mappings().stream()
                    .map(FaceMappingEntryDTO::new)
                    .toList();
            this.materials = data.materials().stream()
                    .map(MaterialEntryDTO::new)
                    .toList();
        }
    }

    private static class FaceMappingEntryDTO {
        public int faceId;
        public int materialId;
        public float u0;
        public float v0;
        public float u1;
        public float v1;
        public int uvRotationDegrees;
        public boolean autoResize;

        public FaceMappingEntryDTO(OMOFormat.FaceMappingEntry entry) {
            this.faceId = entry.faceId();
            this.materialId = entry.materialId();
            this.u0 = entry.u0();
            this.v0 = entry.v0();
            this.u1 = entry.u1();
            this.v1 = entry.v1();
            this.uvRotationDegrees = entry.uvRotationDegrees();
            this.autoResize = entry.autoResize();
        }
    }

    private static class MaterialEntryDTO {
        public int materialId;
        public String name;
        public String textureFile;
        public String renderLayer;
        public boolean emissive;
        public int tintColor;

        public MaterialEntryDTO(OMOFormat.MaterialEntry entry) {
            this.materialId = entry.materialId();
            this.name = entry.name();
            this.textureFile = entry.textureFile();
            this.renderLayer = entry.renderLayer();
            this.emissive = entry.emissive();
            this.tintColor = entry.tintColor();
        }
    }

    /**
     * DTO for model-level transform (v1.4+).
     */
    private static class ModelTransformDTO {
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        public float scaleX, scaleY, scaleZ;

        public ModelTransformDTO(OMOFormat.ModelTransform t) {
            this.posX = t.posX();
            this.posY = t.posY();
            this.posZ = t.posZ();
            this.rotX = t.rotX();
            this.rotY = t.rotY();
            this.rotZ = t.rotZ();
            this.scaleX = t.scaleX();
            this.scaleY = t.scaleY();
            this.scaleZ = t.scaleZ();
        }
    }

    /**
     * DTO for model part entries (v1.3+).
     * Geometry is sliced from the combined mesh using vertex/index/face ranges.
     */
    private static class PartEntryDTO {
        public String id;
        public String name;
        public float originX, originY, originZ;
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        public float scaleX, scaleY, scaleZ;
        public int vertexStart, vertexCount;
        public int indexStart, indexCount;
        public int faceStart, faceCount;
        public boolean visible, locked;
        // v1.5+: parent part ID for skeletal hierarchy. Omitted (null) on root parts.
        public String parentId;

        public PartEntryDTO(OMOFormat.PartEntry entry) {
            this.id = entry.id();
            this.name = entry.name();
            this.originX = entry.originX();
            this.originY = entry.originY();
            this.originZ = entry.originZ();
            this.posX = entry.posX();
            this.posY = entry.posY();
            this.posZ = entry.posZ();
            this.rotX = entry.rotX();
            this.rotY = entry.rotY();
            this.rotZ = entry.rotZ();
            this.scaleX = entry.scaleX();
            this.scaleY = entry.scaleY();
            this.scaleZ = entry.scaleZ();
            this.vertexStart = entry.vertexStart();
            this.vertexCount = entry.vertexCount();
            this.indexStart = entry.indexStart();
            this.indexCount = entry.indexCount();
            this.faceStart = entry.faceStart();
            this.faceCount = entry.faceCount();
            this.visible = entry.visible();
            this.locked = entry.locked();
            this.parentId = entry.parentId();
            this.boneId = entry.boneId();
        }

        // v1.6+: bone this part is attached to. Omitted (null) when the part is unbound.
        public String boneId;
    }

    /**
     * DTO for bone entries (v1.6+).
     * Bones are pure transform nodes; mesh attachment happens via {@link PartEntryDTO#boneId}.
     */
    private static class BoneEntryDTO {
        public String id;
        public String name;
        public String parentBoneId;
        public float originX, originY, originZ;
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        // Tail offset in bone-local space (post-rotation). Absent in pre-endpoint files;
        // the deserializer defaults missing values to 0 for backward compatibility.
        public float endpointX, endpointY, endpointZ;

        public BoneEntryDTO(OMOFormat.BoneEntry entry) {
            this.id = entry.id();
            this.name = entry.name();
            this.parentBoneId = entry.parentBoneId();
            this.originX = entry.originX();
            this.originY = entry.originY();
            this.originZ = entry.originZ();
            this.posX = entry.posX();
            this.posY = entry.posY();
            this.posZ = entry.posZ();
            this.rotX = entry.rotX();
            this.rotY = entry.rotY();
            this.rotZ = entry.rotZ();
            this.endpointX = entry.endpointX();
            this.endpointY = entry.endpointY();
            this.endpointZ = entry.endpointZ();
        }
    }
}
