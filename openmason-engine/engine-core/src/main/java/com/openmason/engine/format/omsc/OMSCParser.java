package com.openmason.engine.format.omsc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads {@code .omsc} archives.
 *
 * <p>Absent JSON fields fall back to defaults and the reader never gates on the version
 * number, matching the house style across the other formats.
 */
public class OMSCParser {

    private static final Logger logger = LoggerFactory.getLogger(OMSCParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Manifest plus raw bytes, without decoding the embedded models. */
    public record RawParse(OMSCFormat.Document manifest, Map<String, byte[]> modelBytesById) {
    }

    /** Parse and decode every embedded OMO. */
    public OMSCParseResult parse(Path omscPath) throws IOException {
        RawParse raw = parseRaw(omscPath);

        Map<String, OMOReader.ReadResult> decoded = new LinkedHashMap<>();
        OMOReader reader = new OMOReader();
        for (Map.Entry<String, byte[]> entry : raw.modelBytesById().entrySet()) {
            try (InputStream in = new ByteArrayInputStream(entry.getValue())) {
                decoded.put(entry.getKey(), reader.read(in));
            } catch (Exception e) {
                // A model that will not decode should not sink the whole scene; the host
                // renders a placeholder for it.
                logger.warn("Embedded model '{}' failed to decode: {}", entry.getKey(), e.getMessage());
            }
        }
        return new OMSCParseResult(raw.manifest(), raw.modelBytesById(), decoded);
    }

    /**
     * Parse the manifest and collect embedded bytes without decoding them — enough for
     * re-export or inspection.
     */
    public RawParse parseRaw(Path omscPath) throws IOException {
        OMSCFormat.Document manifest = null;
        Map<String, byte[]> rawEntries = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(omscPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (OMSCFormat.MANIFEST_FILENAME.equals(name)) {
                    manifest = parseManifest(readBytes(zis));
                } else if (name.startsWith(OMSCFormat.MODELS_DIR_PREFIX)
                        && name.endsWith(OMSCFormat.EMBEDDED_MODEL_FILENAME)) {
                    rawEntries.put(name, readBytes(zis));
                }
                zis.closeEntry();
            }
        }

        if (manifest == null) {
            throw new IOException("Not an .OMSC archive (no " + OMSCFormat.MANIFEST_FILENAME + "): " + omscPath);
        }

        Map<String, byte[]> byModelId = new LinkedHashMap<>();
        for (OMSCFormat.ModelRef ref : manifest.models()) {
            byte[] data = rawEntries.get(ref.file());
            if (data == null) {
                // Tolerated, unlike SBO's hard error: an .OMSC has a second resolution
                // channel (the project-relative path), so a missing embedded copy can
                // still produce a fully renderable scene.
                logger.warn("Scene declares model '{}' but the archive has no entry at {}",
                        ref.modelId(), ref.file());
                continue;
            }
            validateChecksum(ref, data, omscPath);
            byModelId.put(ref.modelId(), data);
        }

        return new RawParse(manifest, byModelId);
    }

    private OMSCFormat.Document parseManifest(byte[] json) throws IOException {
        JsonNode root = objectMapper.readTree(json);

        List<OMSCFormat.ModelRef> models = new ArrayList<>();
        JsonNode modelsNode = root.get("models");
        if (modelsNode != null && modelsNode.isArray()) {
            for (JsonNode node : modelsNode) {
                models.add(new OMSCFormat.ModelRef(
                        text(node, "modelId", null),
                        text(node, "path", null),
                        text(node, "sourceName", null),
                        text(node, "file", null),
                        text(node, "checksum", ""),
                        node.has("size") ? node.get("size").asLong() : 0L));
            }
        }

        List<OMSCFormat.InstanceEntry> instances = new ArrayList<>();
        JsonNode instancesNode = root.get("instances");
        if (instancesNode != null && instancesNode.isArray()) {
            for (JsonNode node : instancesNode) {
                instances.add(new OMSCFormat.InstanceEntry(
                        text(node, "id", null),
                        text(node, "name", "Instance"),
                        text(node, "modelId", null),
                        parseTransform(node.get("transform")),
                        !node.has("visible") || node.get("visible").asBoolean(),
                        node.has("locked") && node.get("locked").asBoolean()));
            }
        }

        return new OMSCFormat.Document(
                text(root, "version", OMSCFormat.FORMAT_VERSION),
                text(root, "sceneName", "Untitled Scene"),
                text(root, "author", null),
                text(root, "description", null),
                text(root, "createdAt", null),
                text(root, "lastSavedAt", null),
                models, instances,
                parseCamera(root.get("camera")),
                parseViewport(root.get("viewport")));
    }

    private static OMOFormat.ModelTransform parseTransform(JsonNode node) {
        if (node == null || node.isNull()) {
            return OMOFormat.ModelTransform.identity();
        }
        return new OMOFormat.ModelTransform(
                f(node, "posX", 0), f(node, "posY", 0), f(node, "posZ", 0),
                f(node, "rotX", 0), f(node, "rotY", 0), f(node, "rotZ", 0),
                f(node, "scaleX", 1), f(node, "scaleY", 1), f(node, "scaleZ", 1));
    }

    private static OMSCFormat.CameraState parseCamera(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new OMSCFormat.CameraState(
                text(node, "mode", "ARCBALL"),
                f(node, "distance", 10), f(node, "pitch", 20), f(node, "yaw", 45), f(node, "fov", 45),
                f(node, "targetX", 0), f(node, "targetY", 0), f(node, "targetZ", 0));
    }

    private static OMSCFormat.ViewportState parseViewport(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new OMSCFormat.ViewportState(
                i(node, "viewModeIndex", 0),
                i(node, "renderModeIndex", 0),
                b(node, "gridVisible", true),
                b(node, "axesVisible", true),
                b(node, "unrenderedMode", false),
                b(node, "showVertices", false),
                b(node, "showGizmo", true),
                b(node, "gridSnappingEnabled", false),
                f(node, "gridSnappingIncrement", 0.25f));
    }

    private void validateChecksum(OMSCFormat.ModelRef ref, byte[] data, Path source) {
        if (ref.checksum() == null || ref.checksum().isBlank()) {
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(OMSCFormat.CHECKSUM_ALGORITHM);
            String actual = HexFormat.of().formatHex(digest.digest(data));
            if (!actual.equalsIgnoreCase(ref.checksum())) {
                // Warn only. A mismatch means the archive was edited, not that the bytes
                // are unusable — refusing to open would strand the user's whole scene.
                logger.warn("Checksum mismatch for model '{}' in {} (expected {}, got {})",
                        ref.modelId(), source, ref.checksum(), actual);
            }
        } catch (NoSuchAlgorithmException e) {
            logger.warn("Cannot verify checksums: {}", e.getMessage());
        }
    }

    private static byte[] readBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zis.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static float f(JsonNode node, String field, float fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : (float) value.asDouble();
    }

    private static int i(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt();
    }

    private static boolean b(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean();
    }
}
