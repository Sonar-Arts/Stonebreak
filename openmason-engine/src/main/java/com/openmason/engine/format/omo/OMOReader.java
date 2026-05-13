package com.openmason.engine.format.omo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only OMO parser for the engine.
 * Produces {@link ParsedMeshData} and material data — no editor dependencies.
 *
 * <p>Unlike the editor's OMODeserializer (which produces editable BlockModel),
 * this reader produces immutable engine-level data structures suitable for
 * rendering and texture extraction.
 */
public class OMOReader {

    private static final Logger logger = LoggerFactory.getLogger(OMOReader.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Result of reading an OMO file/stream.
     */
    public record ReadResult(
            OMOFormat.Document document,
            ParsedMeshData meshData,
            List<ParsedFaceMapping> faceMappings,
            List<ParsedMaterialData> materials,
            byte[] defaultTextureBytes
    ) {}

    /**
     * Read an OMO file from a stream (e.g. extracted from an SBO ZIP).
     *
     * @param omoStream input stream containing the OMO ZIP data
     * @return parsed result
     * @throws IOException if reading or parsing fails
     */
    public ReadResult read(InputStream omoStream) throws IOException {
        OMOFormat.Document document = null;
        ParsedMeshData meshData = null;
        List<ParsedFaceMapping> faceMappings = new ArrayList<>();
        List<OMOFormat.MaterialEntry> materialEntries = new ArrayList<>();
        Map<String, byte[]> materialTextures = new HashMap<>();
        byte[] defaultTextureBytes = null;

        try (ZipInputStream zis = new ZipInputStream(omoStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (OMOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    byte[] jsonBytes = readBytes(zis);
                    ManifestResult result = parseManifest(jsonBytes);
                    document = result.document;
                    meshData = result.meshData;
                    faceMappings = result.faceMappings;
                    materialEntries = result.materialEntries;

                } else if (OMOFormat.DEFAULT_TEXTURE_FILENAME.equals(entryName)) {
                    defaultTextureBytes = readBytes(zis);

                } else if (entryName.startsWith("material_") && entryName.endsWith(".png")) {
                    materialTextures.put(entryName, readBytes(zis));
                }

                zis.closeEntry();
            }
        }

        if (document == null) {
            throw new IOException("Missing manifest.json in OMO file");
        }

        // Build ParsedMaterialData by joining materialEntries with their PNG bytes
        List<ParsedMaterialData> materials = new ArrayList<>();
        for (OMOFormat.MaterialEntry me : materialEntries) {
            byte[] png = materialTextures.get(me.textureFile());
            materials.add(new ParsedMaterialData(
                    me.materialId(), me.name(), me.textureFile(),
                    png, me.renderLayer(), me.emissive(), me.tintColor()
            ));
        }

        return new ReadResult(document, meshData, faceMappings, materials, defaultTextureBytes);
    }

    private record ManifestResult(
            OMOFormat.Document document,
            ParsedMeshData meshData,
            List<ParsedFaceMapping> faceMappings,
            List<OMOFormat.MaterialEntry> materialEntries
    ) {}

    private ManifestResult parseManifest(byte[] jsonBytes) throws IOException {
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(json);

        String version = root.get("version").asText();
        String objectName = root.get("objectName").asText();
        String modelType = root.get("modelType").asText();
        String textureFile = root.get("textureFile").asText();

        JsonNode geometryNode = root.get("geometry");
        int width = geometryNode.get("width").asInt();
        int height = geometryNode.get("height").asInt();
        int depth = geometryNode.get("depth").asInt();
        JsonNode posNode = geometryNode.get("position");
        OMOFormat.Position position = new OMOFormat.Position(
                posNode.get("x").asDouble(),
                posNode.get("y").asDouble(),
                posNode.get("z").asDouble()
        );

        OMOFormat.Document document = new OMOFormat.Document(
                version, objectName, modelType,
                new OMOFormat.GeometryData(width, height, depth, position),
                textureFile
        );

        // Parse mesh data
        ParsedMeshData meshData = null;
        JsonNode meshNode = root.get("mesh");
        if (meshNode != null && !meshNode.isNull()) {
            meshData = parseMeshData(meshNode);
        }

        // Parse face mappings
        List<ParsedFaceMapping> faceMappings = new ArrayList<>();
        List<OMOFormat.MaterialEntry> materialEntries = new ArrayList<>();
        JsonNode faceTexturesNode = root.get("faceTextures");
        if (faceTexturesNode != null && !faceTexturesNode.isNull()) {
            JsonNode mappingsNode = faceTexturesNode.get("mappings");
            if (mappingsNode != null && mappingsNode.isArray()) {
                for (JsonNode m : mappingsNode) {
                    faceMappings.add(new ParsedFaceMapping(
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
            JsonNode materialsNode = faceTexturesNode.get("materials");
            if (materialsNode != null && materialsNode.isArray()) {
                for (JsonNode m : materialsNode) {
                    materialEntries.add(new OMOFormat.MaterialEntry(
                            m.get("materialId").asInt(),
                            m.get("name").asText(),
                            m.get("textureFile").asText(),
                            m.get("renderLayer").asText(),
                            m.has("emissive") && m.get("emissive").asBoolean(),
                            m.has("tintColor") ? m.get("tintColor").asInt() : 0xFFFFFFFF
                    ));
                }
            }
        }

        return new ManifestResult(document, meshData, faceMappings, materialEntries);
    }

    private ParsedMeshData parseMeshData(JsonNode meshNode) {
        float[] vertices = parseFloatArray(meshNode.get("vertices"));
        float[] texCoords = parseFloatArray(meshNode.get("texCoords"));
        int[] indices = parseIntArray(meshNode.get("indices"));
        int[] triangleToFaceId = parseIntArray(meshNode.get("triangleToFaceId"));
        String uvMode = meshNode.has("uvMode") ? meshNode.get("uvMode").asText() : null;
        return new ParsedMeshData(vertices, texCoords, indices, triangleToFaceId, uvMode);
    }

    private float[] parseFloatArray(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    private int[] parseIntArray(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        int[] arr = new int[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = node.get(i).asInt();
        }
        return arr;
    }

    private byte[] readBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }
}
