package com.openmason.engine.format.oma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only parser for Open Mason Animation ({@code .omanim}) archives.
 *
 * <p>Produces an immutable {@link ParsedAnimClip} suitable for runtime keyframe
 * sampling — see {@link AnimSampler}. The archive layout is documented on
 * {@link OMAFormat}: a {@code manifest.json} plus one {@code track_<uuid>.json}
 * per animated part.
 */
public class OMAReader {

    private static final Logger logger = LoggerFactory.getLogger(OMAReader.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Thrown when the {@code .omanim} archive is structurally invalid. */
    public static class OMAParseException extends IOException {
        public OMAParseException(String message) { super(message); }
        public OMAParseException(String message, Throwable cause) { super(message, cause); }
    }

    /** Read a clip from raw {@code .omanim} archive bytes. */
    public ParsedAnimClip read(byte[] omaBytes) throws IOException {
        if (omaBytes == null) {
            throw new OMAParseException("OMA byte array is null");
        }
        try (InputStream in = new ByteArrayInputStream(omaBytes)) {
            return read(in);
        }
    }

    /** Read a clip from an {@code .omanim} archive stream. */
    public ParsedAnimClip read(InputStream omaStream) throws IOException {
        byte[] manifestBytes = null;
        Map<String, byte[]> entries = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(omaStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] data = readAll(zis);
                zis.closeEntry();
                if (OMAFormat.MANIFEST_FILENAME.equals(entry.getName())) {
                    manifestBytes = data;
                } else {
                    entries.put(entry.getName(), data);
                }
            }
        }

        if (manifestBytes == null) {
            throw new OMAParseException("Missing " + OMAFormat.MANIFEST_FILENAME + " in OMA archive");
        }
        return parse(manifestBytes, entries);
    }

    private ParsedAnimClip parse(byte[] manifestBytes, Map<String, byte[]> entries) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestBytes);
        } catch (IOException e) {
            throw new OMAParseException("Malformed OMA manifest.json", e);
        }

        String name = textOrDefault(root, "name", "Unnamed");
        float fps = floatOrDefault(root, "fps", 30f);
        float duration = floatOrDefault(root, "duration", 0f);
        boolean loop = root.hasNonNull("loop") && root.get("loop").asBoolean();

        List<ParsedAnimTrack> tracks = new ArrayList<>();
        JsonNode tracksNode = root.get("tracks");
        if (tracksNode != null && tracksNode.isArray()) {
            for (JsonNode trackRef : tracksNode) {
                String partId = textOrDefault(trackRef, "partId", null);
                String partName = textOrDefault(trackRef, "partName", partId);
                String dataFile = textOrDefault(trackRef, "dataFile", null);
                if (partId == null || dataFile == null) {
                    throw new OMAParseException("Track reference missing partId/dataFile");
                }
                byte[] trackBytes = entries.get(dataFile);
                if (trackBytes == null) {
                    throw new OMAParseException("Missing track data file: " + dataFile);
                }
                tracks.add(parseTrack(partId, partName, trackBytes));
            }
        }

        AnimLayerMeta layer = parseLayer(root.get("layer"));

        logger.info("Parsed OMA clip '{}' ({} tracks, duration={}s, fps={}, loop={}, layer={})",
                name, tracks.size(), duration, fps, loop, layer.type());
        return new ParsedAnimClip(name, fps, duration, loop, tracks, layer);
    }

    /**
     * Parse the optional v1.1 {@code layer} object. Absent (all v1.0 files)
     * or malformed nodes fall back to a full-body BASE layer.
     */
    private static AnimLayerMeta parseLayer(JsonNode layerNode) {
        if (layerNode == null || !layerNode.isObject()) {
            return AnimLayerMeta.base();
        }
        AnimLayerMeta.LayerType type =
                AnimLayerMeta.LayerType.fromString(textOrDefault(layerNode, "type", null));
        List<String> maskParts = new ArrayList<>();
        JsonNode maskNode = layerNode.get("maskParts");
        if (maskNode != null && maskNode.isArray()) {
            for (JsonNode part : maskNode) {
                if (part != null && !part.isNull() && !part.asText().isBlank()) {
                    maskParts.add(part.asText());
                }
            }
        }
        float fadeIn = floatOrDefault(layerNode, "fadeInSeconds", AnimLayerMeta.DEFAULT_FADE_SECONDS);
        float fadeOut = floatOrDefault(layerNode, "fadeOutSeconds", AnimLayerMeta.DEFAULT_FADE_SECONDS);
        int priority = layerNode.hasNonNull("priority") ? layerNode.get("priority").asInt(0) : 0;
        return new AnimLayerMeta(type, maskParts, fadeIn, fadeOut, priority);
    }

    private ParsedAnimTrack parseTrack(String partId, String partName, byte[] trackBytes)
            throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(trackBytes);
        } catch (IOException e) {
            throw new OMAParseException("Malformed track data for part " + partId, e);
        }

        List<ParsedKeyframe> keyframes = new ArrayList<>();
        JsonNode kfNode = root.get("keyframes");
        if (kfNode != null && kfNode.isArray()) {
            for (JsonNode kf : kfNode) {
                keyframes.add(new ParsedKeyframe(
                        floatOrDefault(kf, "time", 0f),
                        vec3(kf.get("position"), 0f),
                        vec3(kf.get("rotation"), 0f),
                        vec3(kf.get("scale"), 1f),
                        textOrDefault(kf, "easing", "LINEAR")
                ));
            }
        }
        return new ParsedAnimTrack(partId, partName, keyframes);
    }

    private static Vector3f vec3(JsonNode arrayNode, float fallback) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.size() < 3) {
            return new Vector3f(fallback, fallback, fallback);
        }
        return new Vector3f(
                (float) arrayNode.get(0).asDouble(),
                (float) arrayNode.get(1).asDouble(),
                (float) arrayNode.get(2).asDouble()
        );
    }

    private static String textOrDefault(JsonNode parent, String field, String fallback) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) return fallback;
        return node.asText();
    }

    private static float floatOrDefault(JsonNode parent, String field, float fallback) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) return fallback;
        return (float) node.asDouble();
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
