package com.openmason.main.systems.rendering.model.io.omo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deserializer for Open Mason Object (.OMO) file format.
 *
 * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current version loads legacy texture data
 * (raw texCoords + uvMode string). Future versions will support per-face texture atlas
 * coordinates, transformations, and flexible mapping modes. Will require texture creator upgrade.
 *
 * <p>Reads ZIP-based .OMO files and reconstructs BlockModel instances.
 * The deserializer:
 * <ul>
 *   <li>Reads and validates manifest.json</li>
 *   <li>Extracts embedded .OMT texture file</li>
 *   <li>Reconstructs model geometry</li>
 *   <li>Creates BlockModel with loaded data</li>
 *   <li>Loads mesh data for all models (v2.1+)</li>
 * </ul>
 *
 * <p><strong>Loading behavior:</strong>
 * <ul>
 *   <li>Modern files (v2.1+): Include mesh data - self-contained, no generation needed</li>
 *   <li>Legacy files (v1.0-2.0): No mesh data - require generation fallback (see ModelOperationService)</li>
 * </ul>
 *
 * <p>Validation includes:
 * <ul>
 *   <li>Format version compatibility (1.0, 2.0, and 2.1)</li>
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
 *   <li>Backward Compatible: Supports legacy files without mesh data</li>
 * </ul>
 *
 * @since 1.0
 * @since 2.0 Added mesh data support
 * @since 2.1 Mesh data expected for all new files
 */
public class OMODeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMODeserializer.class);
    private final ObjectMapper objectMapper;

    // Last loaded mesh data (accessible after load())
    private OMOFormat.MeshData lastLoadedMeshData;

    // Last loaded part entries (v1.3+)
    private List<OMOFormat.PartEntry> lastLoadedPartEntries;

    // Last loaded model transform (v1.4+)
    private OMOFormat.ModelTransform lastLoadedModelTransform;

    // Last loaded bone skeleton (v1.6+)
    private List<OMOFormat.BoneEntry> lastLoadedBoneEntries;

    // Last loaded face texture data (v1.2+, accessible after load())
    private OMOFormat.FaceTextureData lastLoadedFaceTextureData;
    private Map<String, byte[]> lastLoadedMaterialTextures;

    /**
     * Creates a new .OMO deserializer with JSON support.
     */
    public OMODeserializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get the mesh data from the last successful load() call.
     * Returns null if no mesh data was present (v1.0 file or standard cube).
     *
     * @return the loaded mesh data, or null
     */
    public OMOFormat.MeshData getLastLoadedMeshData() {
        return lastLoadedMeshData;
    }

    /**
     * Get face texture data from the last successful load() call.
     * Returns null if no face texture data was present (pre-1.2 file).
     *
     * @return the loaded face texture data, or null
     */
    public OMOFormat.FaceTextureData getLastLoadedFaceTextureData() {
        return lastLoadedFaceTextureData;
    }

    /**
     * Get material texture PNGs from the last successful load() call.
     * Keys are ZIP entry names (e.g. "material_1.png"), values are PNG bytes.
     *
     * @return map of material textures, or null if none were present
     */
    public Map<String, byte[]> getLastLoadedMaterialTextures() {
        return lastLoadedMaterialTextures;
    }

    /**
     * Get model part entries from the last successful load() call (v1.3+).
     * Returns null if no part entries were present (single-part model).
     *
     * @return list of part entries, or null
     */
    public List<OMOFormat.PartEntry> getLastLoadedPartEntries() {
        return lastLoadedPartEntries;
    }

    /**
     * Get model-level transform from the last successful load() call (v1.4+).
     * Returns null if no model transform was present (pre-1.4 file or identity transform).
     *
     * @return the model transform, or null
     */
    public OMOFormat.ModelTransform getLastLoadedModelTransform() {
        return lastLoadedModelTransform;
    }

    /**
     * Get bone skeleton entries from the last successful load() call (v1.6+).
     * Returns null for pre-1.6 files or files without a skeleton.
     */
    public List<OMOFormat.BoneEntry> getLastLoadedBoneEntries() {
        return lastLoadedBoneEntries;
    }

    /**
     * Loads a BlockModel from a .OMO file.
     * After loading, call getLastLoadedMeshData() to retrieve any custom mesh data.
     *
     * @param filePath path to the .OMO file
     * @return the loaded BlockModel, or null if loading failed
     */
    public BlockModel load(String filePath) {
        // Clear previous data
        lastLoadedMeshData = null;
        lastLoadedFaceTextureData = null;
        lastLoadedMaterialTextures = null;
        lastLoadedPartEntries = null;
        lastLoadedModelTransform = null;
        lastLoadedBoneEntries = null;

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

            // Store mesh data, face texture data, part entries, and model transform for external access
            lastLoadedMeshData = loadedData.meshData();
            lastLoadedFaceTextureData = loadedData.faceTextureData();
            lastLoadedMaterialTextures = loadedData.materialTextures();
            lastLoadedPartEntries = loadedData.partEntries();
            lastLoadedModelTransform = loadedData.modelTransform();
            lastLoadedBoneEntries = loadedData.boneEntries();

            // Build BlockModel from loaded data
            BlockModel model = buildModel(loadedData);

            // Set file path and mark clean (just loaded)
            model.setFilePath(path);
            model.markClean();

            logger.info("Loaded .OMO file: {} (version={}, hasMesh={})",
                    filePath, loadedData.document().version(), lastLoadedMeshData != null);
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
     * @return loaded data containing manifest, texture path, and optional mesh data
     * @throws IOException if reading fails
     */
    private LoadedData loadFromZip(Path path) throws IOException {
        OMOFormat.Document document = null;
        OMOFormat.MeshData meshData = null;
        OMOFormat.FaceTextureData faceTextureData = null;
        List<OMOFormat.PartEntry> partEntries = null;
        OMOFormat.ModelTransform modelTransform = null;
        List<OMOFormat.BoneEntry> boneEntries = null;
        Path texturePath = null;
        Map<String, byte[]> materialTextures = new HashMap<>();

        try (FileInputStream fis = new FileInputStream(path.toFile());
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (OMOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    // Read manifest.json (may contain mesh data, face textures, and parts)
                    ManifestParseResult result = readManifestV2(zis);
                    document = result.document;
                    meshData = result.meshData;
                    faceTextureData = result.faceTextureData;
                    partEntries = result.partEntries;
                    modelTransform = result.modelTransform;
                    boneEntries = result.boneEntries;
                    logger.debug("Loaded manifest.json (version={})", document.version());

                } else if (OMOFormat.DEFAULT_TEXTURE_FILENAME.equals(entryName)) {
                    // Extract texture.omt to temporary file
                    texturePath = extractTexture(zis);
                    logger.debug("Extracted texture.omt to: {}", texturePath);

                } else if (entryName.startsWith("material_") && entryName.endsWith(".png")) {
                    // Store material texture PNG bytes (v1.2+)
                    byte[] pngBytes = readStreamToByteArray(zis);
                    materialTextures.put(entryName, pngBytes);
                    logger.debug("Loaded material texture: {} ({} bytes)", entryName, pngBytes.length);
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

        return new LoadedData(document, texturePath, meshData, faceTextureData,
                              materialTextures.isEmpty() ? null : materialTextures,
                              partEntries, modelTransform, boneEntries);
    }

    /**
     * Result of parsing manifest.json.
     */
    private record ManifestParseResult(OMOFormat.Document document, OMOFormat.MeshData meshData,
                                       OMOFormat.FaceTextureData faceTextureData,
                                       List<OMOFormat.PartEntry> partEntries,
                                       OMOFormat.ModelTransform modelTransform,
                                       List<OMOFormat.BoneEntry> boneEntries) {}

    /**
     * Reads and parses manifest.json from ZIP stream.
     * Supports both v1.0 and v2.0 formats.
     *
     * @param zis ZIP input stream positioned at manifest entry
     * @return parsed result containing document and optional mesh data
     * @throws IOException if read or parse fails
     */
    private ManifestParseResult readManifestV2(ZipInputStream zis) throws IOException {
        // Read JSON from stream
        byte[] jsonBytes = readStreamToByteArray(zis);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        // Parse JSON
        JsonNode root = objectMapper.readTree(json);

        // Validate version
        String version = root.get("version").asText();
        if (!isVersionSupported(version)) {
            throw new IOException("Unsupported format version: " + version +
                    " (supported: " + OMOFormat.MIN_SUPPORTED_VERSION + " to " + OMOFormat.FORMAT_VERSION + ")");
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

        OMOFormat.Document document = new OMOFormat.Document(version, objectName, modelType,
                                     geometryData, textureFile);

        // Parse mesh data if present (v2.0+)
        OMOFormat.MeshData meshData = null;
        JsonNode meshNode = root.get("mesh");
        if (meshNode != null && !meshNode.isNull()) {
            meshData = parseMeshData(meshNode);
            logger.debug("Loaded custom mesh data: {} vertices, {} triangles",
                    meshData.getVertexCount(), meshData.getTriangleCount());
        }

        // Parse face texture data if present (v1.2+)
        OMOFormat.FaceTextureData faceTextureData = null;
        JsonNode faceTexturesNode = root.get("faceTextures");
        if (faceTexturesNode != null && !faceTexturesNode.isNull()) {
            faceTextureData = parseFaceTextureData(faceTexturesNode);
            logger.debug("Loaded face texture data: {} mappings, {} materials",
                    faceTextureData.mappings().size(), faceTextureData.materials().size());
        }

        // Parse part entries if present (v1.3+)
        List<OMOFormat.PartEntry> partEntries = null;
        JsonNode partsNode = root.get("parts");
        if (partsNode != null && partsNode.isArray() && !partsNode.isEmpty()) {
            partEntries = parsePartEntries(partsNode);
            logger.debug("Loaded {} part entries", partEntries.size());
        }

        // Parse model-level transform if present (v1.4+)
        OMOFormat.ModelTransform modelTransform = null;
        JsonNode transformNode = root.get("modelTransform");
        if (transformNode != null && !transformNode.isNull()) {
            modelTransform = new OMOFormat.ModelTransform(
                    (float) transformNode.path("posX").asDouble(0),
                    (float) transformNode.path("posY").asDouble(0),
                    (float) transformNode.path("posZ").asDouble(0),
                    (float) transformNode.path("rotX").asDouble(0),
                    (float) transformNode.path("rotY").asDouble(0),
                    (float) transformNode.path("rotZ").asDouble(0),
                    (float) transformNode.path("scaleX").asDouble(1),
                    (float) transformNode.path("scaleY").asDouble(1),
                    (float) transformNode.path("scaleZ").asDouble(1)
            );
            logger.debug("Loaded model transform: pos=({},{},{}), rot=({},{},{}), scale=({},{},{})",
                    modelTransform.posX(), modelTransform.posY(), modelTransform.posZ(),
                    modelTransform.rotX(), modelTransform.rotY(), modelTransform.rotZ(),
                    modelTransform.scaleX(), modelTransform.scaleY(), modelTransform.scaleZ());
        }

        // Parse bone entries if present (v1.6+)
        List<OMOFormat.BoneEntry> boneEntries = null;
        JsonNode bonesNode = root.get("bones");
        if (bonesNode != null && bonesNode.isArray() && !bonesNode.isEmpty()) {
            boneEntries = parseBoneEntries(bonesNode);
            logger.debug("Loaded {} bone entries", boneEntries.size());
        }

        return new ManifestParseResult(document, meshData, faceTextureData, partEntries, modelTransform, boneEntries);
    }

    /**
     * Parse bone entries from JSON array (v1.6+).
     * Skips entries that are missing a stable id.
     */
    private List<OMOFormat.BoneEntry> parseBoneEntries(JsonNode bonesNode) {
        List<OMOFormat.BoneEntry> entries = new ArrayList<>();
        for (JsonNode b : bonesNode) {
            String id = b.has("id") ? b.get("id").asText() : null;
            if (id == null || id.isBlank()) {
                continue;
            }
            // Endpoint defaults to (0,0,0) when absent — pre-endpoint files round-trip
            // as zero-length bones (tail == head), matching their original visual.
            entries.add(new OMOFormat.BoneEntry(
                    id,
                    b.has("name") ? b.get("name").asText() : "Unnamed Bone",
                    (b.has("parentBoneId") && !b.get("parentBoneId").isNull())
                            ? b.get("parentBoneId").asText() : null,
                    b.has("originX") ? (float) b.get("originX").asDouble() : 0,
                    b.has("originY") ? (float) b.get("originY").asDouble() : 0,
                    b.has("originZ") ? (float) b.get("originZ").asDouble() : 0,
                    b.has("posX") ? (float) b.get("posX").asDouble() : 0,
                    b.has("posY") ? (float) b.get("posY").asDouble() : 0,
                    b.has("posZ") ? (float) b.get("posZ").asDouble() : 0,
                    b.has("rotX") ? (float) b.get("rotX").asDouble() : 0,
                    b.has("rotY") ? (float) b.get("rotY").asDouble() : 0,
                    b.has("rotZ") ? (float) b.get("rotZ").asDouble() : 0,
                    b.has("endpointX") ? (float) b.get("endpointX").asDouble() : 0,
                    b.has("endpointY") ? (float) b.get("endpointY").asDouble() : 0,
                    b.has("endpointZ") ? (float) b.get("endpointZ").asDouble() : 0
            ));
        }
        return entries;
    }

    /**
     * Check if a format version is supported.
     *
     * @param version the version string to check
     * @return true if supported
     */
    private boolean isVersionSupported(String version) {
        if (version == null) return false;
        try {
            double v = Double.parseDouble(version);
            double minV = Double.parseDouble(OMOFormat.MIN_SUPPORTED_VERSION);
            double maxV = Double.parseDouble(OMOFormat.FORMAT_VERSION);
            return v >= minV && v <= maxV;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parse mesh data from JSON node.
     *
     * @param meshNode the mesh JSON node
     * @return parsed MeshData
     */
    private OMOFormat.MeshData parseMeshData(JsonNode meshNode) {
        // Parse vertices
        float[] vertices = null;
        JsonNode verticesNode = meshNode.get("vertices");
        if (verticesNode != null && verticesNode.isArray()) {
            vertices = new float[verticesNode.size()];
            for (int i = 0; i < verticesNode.size(); i++) {
                vertices[i] = (float) verticesNode.get(i).asDouble();
            }
        }

        // Parse texCoords
        float[] texCoords = null;
        JsonNode texCoordsNode = meshNode.get("texCoords");
        if (texCoordsNode != null && texCoordsNode.isArray()) {
            texCoords = new float[texCoordsNode.size()];
            for (int i = 0; i < texCoordsNode.size(); i++) {
                texCoords[i] = (float) texCoordsNode.get(i).asDouble();
            }
        }

        // Parse indices
        int[] indices = null;
        JsonNode indicesNode = meshNode.get("indices");
        if (indicesNode != null && indicesNode.isArray()) {
            indices = new int[indicesNode.size()];
            for (int i = 0; i < indicesNode.size(); i++) {
                indices[i] = indicesNode.get(i).asInt();
            }
        }

        // Parse triangleToFaceId
        int[] triangleToFaceId = null;
        JsonNode faceIdNode = meshNode.get("triangleToFaceId");
        if (faceIdNode != null && faceIdNode.isArray()) {
            triangleToFaceId = new int[faceIdNode.size()];
            for (int i = 0; i < faceIdNode.size(); i++) {
                triangleToFaceId[i] = faceIdNode.get(i).asInt();
            }
        }

        // Parse uvMode
        String uvMode = null;
        JsonNode uvModeNode = meshNode.get("uvMode");
        if (uvModeNode != null && !uvModeNode.isNull()) {
            uvMode = uvModeNode.asText();
        }

        return new OMOFormat.MeshData(vertices, texCoords, indices, triangleToFaceId, uvMode);
    }

    /**
     * Parse face texture data from JSON node (v1.2+).
     *
     * @param node the faceTextures JSON node
     * @return parsed FaceTextureData
     */
    private OMOFormat.FaceTextureData parseFaceTextureData(JsonNode node) {
        List<OMOFormat.FaceMappingEntry> mappings = new ArrayList<>();
        List<OMOFormat.MaterialEntry> materials = new ArrayList<>();

        JsonNode mappingsNode = node.get("mappings");
        if (mappingsNode != null && mappingsNode.isArray()) {
            for (JsonNode m : mappingsNode) {
                mappings.add(new OMOFormat.FaceMappingEntry(
                        m.get("faceId").asInt(),
                        m.get("materialId").asInt(),
                        (float) m.get("u0").asDouble(),
                        (float) m.get("v0").asDouble(),
                        (float) m.get("u1").asDouble(),
                        (float) m.get("v1").asDouble(),
                        m.get("uvRotationDegrees").asInt(),
                        !m.has("autoResize") || m.get("autoResize").asBoolean()
                ));
            }
        }

        JsonNode materialsNode = node.get("materials");
        if (materialsNode != null && materialsNode.isArray()) {
            for (JsonNode m : materialsNode) {
                materials.add(new OMOFormat.MaterialEntry(
                        m.get("materialId").asInt(),
                        m.get("name").asText(),
                        m.get("textureFile").asText(),
                        m.get("renderLayer").asText(),
                        m.has("emissive") && m.get("emissive").asBoolean(),
                        m.has("tintColor") ? m.get("tintColor").asInt() : 0xFFFFFFFF
                ));
            }
        }

        return new OMOFormat.FaceTextureData(mappings, materials);
    }

    /**
     * Parse part entries from JSON array (v1.3+).
     */
    private List<OMOFormat.PartEntry> parsePartEntries(JsonNode partsNode) {
        List<OMOFormat.PartEntry> entries = new ArrayList<>();
        for (JsonNode p : partsNode) {
            String id = p.has("id") ? p.get("id").asText() : null;
            if (id == null || id.isBlank()) {
                continue;
            }
            entries.add(new OMOFormat.PartEntry(
                    id,
                    p.has("name") ? p.get("name").asText() : "Unnamed Part",
                    p.has("originX") ? (float) p.get("originX").asDouble() : 0,
                    p.has("originY") ? (float) p.get("originY").asDouble() : 0,
                    p.has("originZ") ? (float) p.get("originZ").asDouble() : 0,
                    p.has("posX") ? (float) p.get("posX").asDouble() : 0,
                    p.has("posY") ? (float) p.get("posY").asDouble() : 0,
                    p.has("posZ") ? (float) p.get("posZ").asDouble() : 0,
                    p.has("rotX") ? (float) p.get("rotX").asDouble() : 0,
                    p.has("rotY") ? (float) p.get("rotY").asDouble() : 0,
                    p.has("rotZ") ? (float) p.get("rotZ").asDouble() : 0,
                    p.has("scaleX") ? (float) p.get("scaleX").asDouble() : 1,
                    p.has("scaleY") ? (float) p.get("scaleY").asDouble() : 1,
                    p.has("scaleZ") ? (float) p.get("scaleZ").asDouble() : 1,
                    p.has("vertexStart") ? p.get("vertexStart").asInt() : 0,
                    p.has("vertexCount") ? p.get("vertexCount").asInt() : 0,
                    p.has("indexStart") ? p.get("indexStart").asInt() : 0,
                    p.has("indexCount") ? p.get("indexCount").asInt() : 0,
                    p.has("faceStart") ? p.get("faceStart").asInt() : 0,
                    p.has("faceCount") ? p.get("faceCount").asInt() : 0,
                    !p.has("visible") || p.get("visible").asBoolean(true),
                    p.has("locked") && p.get("locked").asBoolean(false),
                    // v1.5+: parentId; absent in older files → root part
                    (p.has("parentId") && !p.get("parentId").isNull())
                            ? p.get("parentId").asText() : null,
                    // v1.6+: boneId; absent in older files → part is unbound
                    (p.has("boneId") && !p.get("boneId").isNull())
                            ? p.get("boneId").asText() : null
            ));
        }
        return entries;
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
        OMOFormat.GeometryData geometryData = document.geometry();
        OMOFormat.Position position = geometryData.position();

        // Create geometry
        ModelGeometry geometry = new CubeGeometry(
            geometryData.width(),
            geometryData.height(),
            geometryData.depth(),
            position.x(),
            position.y(),
            position.z()
        );

        // Create model
        // Note: Texture format (cube net vs flat) is auto-detected from .OMT dimensions at load time

        return new BlockModel(
            document.objectName(),
            geometry,
            data.texturePath
        );
    }

    /**
     * Container for loaded manifest, texture, mesh, and face texture data.
     *
     * @param document The parsed manifest document
     * @param texturePath Path to extracted texture file
     * @param meshData Optional custom mesh data (null for standard cube)
     * @param faceTextureData Optional per-face texture data (null for pre-1.2 files)
     * @param materialTextures Optional material texture PNGs (null if none)
     */
    private record LoadedData(OMOFormat.Document document, Path texturePath,
                              OMOFormat.MeshData meshData,
                              OMOFormat.FaceTextureData faceTextureData,
                              Map<String, byte[]> materialTextures,
                              List<OMOFormat.PartEntry> partEntries,
                              OMOFormat.ModelTransform modelTransform,
                              List<OMOFormat.BoneEntry> boneEntries) {
    }
}
