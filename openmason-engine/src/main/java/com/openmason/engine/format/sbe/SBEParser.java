package com.openmason.engine.format.sbe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reader for Stonebreak Entity (.SBE) archives.
 *
 * <p>Counterpart to {@link SBESerializer}. Two parse modes:
 * <ul>
 *   <li>{@link #parse(Path)} — full validation, returns parsed bytes for
 *       runtime consumption ({@link ParsedSBE}).</li>
 *   <li>{@link #parseRaw(Path)} — preserves raw bytes by ZIP entry name so the
 *       editor can round-trip an SBE without decoding every asset.</li>
 * </ul>
 */
public class SBEParser {

    private static final Logger logger = LoggerFactory.getLogger(SBEParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Aggregate result of a full parse. Keys for {@code stateModelBytes} and
     * {@code stateClipBytes} are the state names from the manifest; values are
     * the raw bytes of the corresponding embedded blob. Iteration order
     * matches the manifest.
     */
    public record ParsedSBE(
            SBEFormat.Document document,
            byte[] omoBytes,
            Map<String, byte[]> stateModelBytes,
            Map<String, byte[]> stateClipBytes
    ) {
        public ParsedSBE {
            stateModelBytes = stateModelBytes == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(stateModelBytes);
            stateClipBytes = stateClipBytes == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(stateClipBytes);
        }

        public byte[] modelFor(String state) { return stateModelBytes.get(state); }
        public byte[] clipFor(String state) { return stateClipBytes.get(state); }
    }

    /**
     * Editor-oriented parse result. Holds the manifest, the base OMO bytes, and
     * a flat map keyed by ZIP entry filename. The editor uses the filename keys
     * to look bytes up via {@link SBEFormat#stateModelPath(String)} /
     * {@link SBEFormat#stateClipPath(String)}, and the same map is fed back to
     * {@link SBESerializer#exportFromDocument} on save.
     */
    public record RawParse(
            SBEFormat.Document manifest,
            byte[] omoBytes,
            Map<String, byte[]> stateAssetBytes
    ) {
        public RawParse {
            stateAssetBytes = stateAssetBytes == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(stateAssetBytes);
        }
    }

    /** Thrown when the SBE archive is structurally invalid or a checksum mismatches. */
    public static class SBEParseException extends IOException {
        public SBEParseException(String message) { super(message); }
        public SBEParseException(String message, Throwable cause) { super(message, cause); }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public ParsedSBE parse(Path sbePath) throws IOException {
        if (sbePath == null || !Files.exists(sbePath)) {
            throw new SBEParseException("SBE file does not exist: " + sbePath);
        }
        try (InputStream in = new FileInputStream(sbePath.toFile())) {
            return parse(in);
        }
    }

    public ParsedSBE parse(InputStream sbeStream) throws IOException {
        ArchiveContents archive = readArchive(sbeStream);
        SBEFormat.Document document = readManifest(archive.manifestBytes);

        byte[] omoBytes = archive.entries.get(document.omoFilename());
        if (omoBytes == null) {
            throw new SBEParseException("Manifest references missing OMO entry: " + document.omoFilename());
        }
        verifyChecksum(document.omoFilename(), omoBytes, document.checksum());

        Map<String, byte[]> modelBytes = new LinkedHashMap<>();
        Map<String, byte[]> clipBytes = new LinkedHashMap<>();
        for (SBEFormat.StateEntry state : document.states()) {
            if (state.hasModelOverride()) {
                byte[] bytes = archive.entries.get(state.modelOverride().filename());
                if (bytes == null) {
                    throw new SBEParseException("Missing model override entry: "
                            + state.modelOverride().filename());
                }
                verifyChecksum(state.modelOverride().filename(), bytes,
                        state.modelOverride().checksum());
                modelBytes.put(state.name(), bytes);
            }
            if (state.hasAnimation()) {
                byte[] bytes = archive.entries.get(state.animation().filename());
                if (bytes == null) {
                    throw new SBEParseException("Missing animation clip entry: "
                            + state.animation().filename());
                }
                verifyChecksum(state.animation().filename(), bytes,
                        state.animation().checksum());
                clipBytes.put(state.name(), bytes);
            }
        }

        logger.info("Parsed SBE: objectId={} states={}",
                document.objectId(), document.states().size());
        return new ParsedSBE(document, omoBytes, modelBytes, clipBytes);
    }

    /**
     * Editor-oriented parse: returns raw bytes keyed by ZIP entry filename
     * (so the same map can be passed back to {@link SBESerializer#exportFromDocument}).
     * Checksums are <em>not</em> verified here — the editor may be inspecting a
     * file authored by hand and we don't want to block editing on a stale digest.
     */
    public RawParse parseRaw(Path sbePath) throws IOException {
        if (sbePath == null || !Files.exists(sbePath)) {
            throw new SBEParseException("SBE file does not exist: " + sbePath);
        }
        try (InputStream in = new FileInputStream(sbePath.toFile())) {
            ArchiveContents archive = readArchive(in);
            SBEFormat.Document document = readManifest(archive.manifestBytes);

            byte[] omoBytes = archive.entries.get(document.omoFilename());
            if (omoBytes == null) {
                throw new SBEParseException("Manifest references missing OMO entry: "
                        + document.omoFilename());
            }

            Map<String, byte[]> stateAssets = new LinkedHashMap<>();
            for (SBEFormat.StateEntry state : document.states()) {
                if (state.hasModelOverride()) {
                    String filename = state.modelOverride().filename();
                    byte[] bytes = archive.entries.get(filename);
                    if (bytes != null) stateAssets.put(filename, bytes);
                }
                if (state.hasAnimation()) {
                    String filename = state.animation().filename();
                    byte[] bytes = archive.entries.get(filename);
                    if (bytes != null) stateAssets.put(filename, bytes);
                }
            }

            return new RawParse(document, omoBytes, stateAssets);
        }
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private record ArchiveContents(byte[] manifestBytes, Map<String, byte[]> entries) {}

    private ArchiveContents readArchive(InputStream sbeStream) throws IOException {
        byte[] manifestBytes = null;
        Map<String, byte[]> entries = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(sbeStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] data = readAll(zis);
                zis.closeEntry();

                if (SBEFormat.MANIFEST_FILENAME.equals(entry.getName())) {
                    manifestBytes = data;
                } else {
                    entries.put(entry.getName(), data);
                }
            }
        }

        if (manifestBytes == null) {
            throw new SBEParseException("Missing " + SBEFormat.MANIFEST_FILENAME + " in SBE archive");
        }
        return new ArchiveContents(manifestBytes, entries);
    }

    private SBEFormat.Document readManifest(byte[] manifestBytes) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestBytes);
        } catch (IOException e) {
            throw new SBEParseException("Malformed manifest.json", e);
        }

        String version = textOrNull(root, "version");
        String objectId = textOrNull(root, "objectId");
        String objectName = textOrNull(root, "objectName");
        String entityType = textOrNull(root, "entityType");
        String objectPack = textOrNull(root, "objectPack");
        String checksum = textOrNull(root, "checksum");
        String author = textOrNull(root, "author");
        String description = textOrNull(root, "description");
        String createdAt = textOrNull(root, "createdAt");
        String omoFile = textOrNull(root, "omoFile");

        if (omoFile == null) omoFile = SBEFormat.EMBEDDED_OMO_FILENAME;

        List<SBEFormat.StateEntry> states = parseStates(root);

        try {
            return new SBEFormat.Document(
                    version != null ? version : SBEFormat.FORMAT_VERSION,
                    objectId, objectName,
                    entityType != null ? entityType : SBEFormat.EntityType.OTHER.getId(),
                    objectPack, checksum, author, description, createdAt,
                    omoFile, states
            );
        } catch (NullPointerException e) {
            throw new SBEParseException("Manifest is missing a required field: " + e.getMessage(), e);
        }
    }

    private List<SBEFormat.StateEntry> parseStates(JsonNode root) throws SBEParseException {
        List<SBEFormat.StateEntry> states = new ArrayList<>();
        JsonNode statesNode = root.get("states");
        if (statesNode != null && statesNode.isArray()) {
            for (JsonNode node : statesNode) {
                String name = textOrNull(node, "name");
                if (name == null || name.isBlank()) {
                    throw new SBEParseException("State entry missing 'name'");
                }
                SBEFormat.AssetRef model = parseAssetRef(node.get("model"));
                SBEFormat.AnimationRef anim = parseAnimationRef(node.get("animation"));
                states.add(new SBEFormat.StateEntry(name, model, anim));
            }
        }
        return states;
    }

    private SBEFormat.AssetRef parseAssetRef(JsonNode node) throws SBEParseException {
        if (node == null || node.isNull()) return null;
        String file = textOrNull(node, "file");
        String checksum = textOrNull(node, "checksum");
        if (file == null || checksum == null) {
            throw new SBEParseException("Asset reference missing file/checksum");
        }
        return new SBEFormat.AssetRef(file, checksum);
    }

    private SBEFormat.AnimationRef parseAnimationRef(JsonNode node) throws SBEParseException {
        if (node == null || node.isNull()) return null;
        String file = textOrNull(node, "file");
        String checksum = textOrNull(node, "checksum");
        String clipName = textOrNull(node, "clipName");
        if (file == null || checksum == null) {
            throw new SBEParseException("Animation reference missing file/checksum");
        }
        float duration = node.hasNonNull("duration") ? (float) node.get("duration").asDouble() : 0f;
        float fps = node.hasNonNull("fps") ? (float) node.get("fps").asDouble() : 30f;
        boolean loop = node.hasNonNull("loop") && node.get("loop").asBoolean();
        List<String> requiredParts = new ArrayList<>();
        JsonNode partsNode = node.get("requiredParts");
        if (partsNode != null && partsNode.isArray()) {
            for (JsonNode p : partsNode) {
                if (p != null && !p.isNull()) requiredParts.add(p.asText());
            }
        }
        if (clipName == null) clipName = "";
        return new SBEFormat.AnimationRef(file, checksum, clipName, duration, fps, loop, requiredParts);
    }

    private void verifyChecksum(String entryName, byte[] data, String expected) throws SBEParseException {
        String actual = computeChecksum(data);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new SBEParseException(
                    "Checksum mismatch for " + entryName
                            + ": expected " + expected + " got " + actual);
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) return null;
        return node.asText();
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBEFormat.CHECKSUM_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
