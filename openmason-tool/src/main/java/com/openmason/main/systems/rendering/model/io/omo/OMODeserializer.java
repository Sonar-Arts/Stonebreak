package com.openmason.main.systems.rendering.model.io.omo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
 *   <li>Loads custom mesh data for subdivided models (v2.0+)</li>
 * </ul>
 *
 * <p>Validation includes:
 * <ul>
 *   <li>Format version compatibility (1.0 and 2.0)</li>
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
 * @since 2.0 Added custom mesh data support
 */
public class OMODeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMODeserializer.class);
    private final ObjectMapper objectMapper;

    // Last loaded mesh data (accessible after load())
    private OMOFormat.MeshData lastLoadedMeshData;

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
     * Loads a BlockModel from a .OMO file.
     * After loading, call getLastLoadedMeshData() to retrieve any custom mesh data.
     *
     * @param filePath path to the .OMO file
     * @return the loaded BlockModel, or null if loading failed
     */
    public BlockModel load(String filePath) {
        // Clear previous mesh data
        lastLoadedMeshData = null;

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

            // Store mesh data for external access
            lastLoadedMeshData = loadedData.meshData();

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
        Path texturePath = null;

        try (FileInputStream fis = new FileInputStream(path.toFile());
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (OMOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    // Read manifest.json (may contain mesh data in v2.0+)
                    ManifestParseResult result = readManifestV2(zis);
                    document = result.document;
                    meshData = result.meshData;
                    logger.debug("Loaded manifest.json (version={})", document.version());

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

        return new LoadedData(document, texturePath, meshData);
    }

    /**
     * Result of parsing manifest.json.
     */
    private record ManifestParseResult(OMOFormat.Document document, OMOFormat.MeshData meshData) {}

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

        return new ManifestParseResult(document, meshData);
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
     * Container for loaded manifest, texture, and mesh data.
     *
     * @param document The parsed manifest document
     * @param texturePath Path to extracted texture file
     * @param meshData Optional custom mesh data (null for standard cube)
     */
    private record LoadedData(OMOFormat.Document document, Path texturePath, OMOFormat.MeshData meshData) {
    }
}
