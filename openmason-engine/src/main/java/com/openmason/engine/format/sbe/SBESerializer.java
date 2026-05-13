package com.openmason.engine.format.sbe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Stonebreak Entity (.SBE) file format.
 *
 * <p>Supports two flows:
 * <ul>
 *   <li>{@link #export(SBEFormat.ExportParameters, Path, String)} — bundle from
 *       scratch, given user parameters and source files on disk.</li>
 *   <li>{@link #exportFromDocument} — re-bundle an existing document with
 *       caller-supplied bytes. Used by the SBE editor's round-trip save.</li>
 * </ul>
 */
public class SBESerializer {

    private static final Logger logger = LoggerFactory.getLogger(SBESerializer.class);

    private final ObjectMapper objectMapper;

    public SBESerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Export an SBE file from a base OMO on disk and a list of state bindings.
     */
    public boolean export(SBEFormat.ExportParameters params, Path omoPath, String outputPath) {
        if (params == null || !params.isValid()) {
            logger.error("Invalid export parameters: {}",
                    params != null ? params.getValidationError() : "null");
            return false;
        }

        if (omoPath == null || !Files.exists(omoPath)) {
            logger.error("OMO file does not exist: {}", omoPath);
            return false;
        }

        outputPath = SBEFormat.ensureExtension(outputPath);

        try {
            byte[] omoBytes = Files.readAllBytes(omoPath);
            String checksum = computeChecksum(omoBytes);

            // Read all per-state inputs into memory so we can build the
            // manifest before opening the output stream.
            List<ResolvedState> resolved = resolveStates(params.getStates());

            List<SBEFormat.StateEntry> stateEntries = new ArrayList<>(resolved.size());
            for (ResolvedState r : resolved) {
                stateEntries.add(r.toEntry());
            }

            SBEFormat.Document document = new SBEFormat.Document(
                    SBEFormat.FORMAT_VERSION,
                    params.getObjectId(),
                    params.getObjectName(),
                    params.getEntityType().getId(),
                    params.getObjectPack(),
                    checksum,
                    params.getAuthor(),
                    params.getDescription().isBlank() ? null : params.getDescription(),
                    Instant.now().toString(),
                    SBEFormat.EMBEDDED_OMO_FILENAME,
                    stateEntries
            );

            writeArchive(outputPath, document, omoBytes, resolved);
            logger.info("Exported .SBE file: {} (checksum={}, states={})",
                    outputPath, checksum.substring(0, 12) + "...", stateEntries.size());
            return true;

        } catch (IOException e) {
            logger.error("Error exporting .SBE file: {}", outputPath, e);
            return false;
        }
    }

    /**
     * Re-bundle an existing document with caller-supplied bytes. Used by the
     * SBE editor to save round-tripped content.
     *
     * @param document         manifest skeleton; checksums are recomputed
     * @param omoBytes         base OMO bytes
     * @param stateAssetBytes  map keyed by ZIP entry filename
     *                         ({@code states/<name>/model.omo} or
     *                         {@code states/<name>/clip.omanim}); every
     *                         non-null asset in the document must have an entry
     * @param outputPath       destination .sbe path
     */
    public boolean exportFromDocument(SBEFormat.Document document,
                                       byte[] omoBytes,
                                       Map<String, byte[]> stateAssetBytes,
                                       String outputPath) {
        if (document == null) {
            logger.error("exportFromDocument: document is null");
            return false;
        }
        if (omoBytes == null || omoBytes.length == 0) {
            logger.error("exportFromDocument: omoBytes is empty");
            return false;
        }
        outputPath = SBEFormat.ensureExtension(outputPath);

        String omoChecksum = computeChecksum(omoBytes);

        try {
            List<ResolvedState> resolved = new ArrayList<>(document.states().size());
            for (SBEFormat.StateEntry entry : document.states()) {
                ResolvedState r = new ResolvedState(entry.name());

                if (entry.hasModelOverride()) {
                    String filename = SBEFormat.stateModelPath(entry.name());
                    byte[] bytes = stateAssetBytes != null ? stateAssetBytes.get(filename) : null;
                    if (bytes == null) {
                        logger.error("exportFromDocument: missing model bytes for state '{}'", entry.name());
                        return false;
                    }
                    r.modelBytes = bytes;
                    r.modelFilename = filename;
                    r.modelChecksum = computeChecksum(bytes);
                }

                if (entry.hasAnimation()) {
                    String filename = SBEFormat.stateClipPath(entry.name());
                    byte[] bytes = stateAssetBytes != null ? stateAssetBytes.get(filename) : null;
                    if (bytes == null) {
                        logger.error("exportFromDocument: missing clip bytes for state '{}'", entry.name());
                        return false;
                    }
                    r.clipBytes = bytes;
                    r.clipFilename = filename;
                    r.clipChecksum = computeChecksum(bytes);
                    OMAMetadata meta = probeOMAMetadata(bytes, Path.of(filename));
                    r.clipMeta = meta;
                }

                resolved.add(r);
            }

            List<SBEFormat.StateEntry> rebuilt = new ArrayList<>(resolved.size());
            for (ResolvedState r : resolved) {
                rebuilt.add(r.toEntry());
            }

            SBEFormat.Document finalDoc = new SBEFormat.Document(
                    SBEFormat.FORMAT_VERSION,
                    document.objectId(),
                    document.objectName(),
                    document.entityType(),
                    document.objectPack(),
                    omoChecksum,
                    document.author(),
                    document.description(),
                    document.createdAt() != null ? document.createdAt() : Instant.now().toString(),
                    SBEFormat.EMBEDDED_OMO_FILENAME,
                    rebuilt
            );

            writeArchive(outputPath, finalDoc, omoBytes, resolved);
            logger.info("Re-bundled .SBE file: {} ({} states)", outputPath, rebuilt.size());
            return true;
        } catch (IOException e) {
            logger.error("Error re-bundling .SBE file: {}", outputPath, e);
            return false;
        }
    }

    // ========================================================================
    // Internals
    // ========================================================================

    /** Signals a recoverable error in the caller-supplied export parameters. */
    public static class SBEExportException extends IOException {
        public SBEExportException(String message) { super(message); }
    }

    /**
     * One state's resolved bytes + metadata, ready to be serialized.
     * Either or both asset slots may be empty.
     */
    private static final class ResolvedState {
        final String name;
        byte[] modelBytes;
        String modelFilename;
        String modelChecksum;
        byte[] clipBytes;
        String clipFilename;
        String clipChecksum;
        OMAMetadata clipMeta;

        ResolvedState(String name) { this.name = name; }

        boolean hasModel() { return modelBytes != null; }
        boolean hasClip() { return clipBytes != null; }

        SBEFormat.StateEntry toEntry() {
            SBEFormat.AssetRef model = hasModel()
                    ? new SBEFormat.AssetRef(modelFilename, modelChecksum)
                    : null;
            SBEFormat.AnimationRef anim = null;
            if (hasClip()) {
                OMAMetadata m = clipMeta != null ? clipMeta : new OMAMetadata();
                String clipName = m.name != null && !m.name.isBlank() ? m.name : name;
                anim = new SBEFormat.AnimationRef(
                        clipFilename, clipChecksum, clipName,
                        m.duration, m.fps, m.loop, m.requiredParts);
            }
            return new SBEFormat.StateEntry(name, model, anim);
        }
    }

    private List<ResolvedState> resolveStates(List<SBEFormat.StateBinding> bindings) throws IOException {
        List<ResolvedState> result = new ArrayList<>();
        if (bindings == null || bindings.isEmpty()) return result;

        Set<String> usedStates = new HashSet<>();

        for (SBEFormat.StateBinding binding : bindings) {
            String rawName = binding.name();
            if (rawName == null || rawName.isBlank()) {
                throw new SBEExportException("Animation state name cannot be blank");
            }
            String name = rawName.trim();
            if (!usedStates.add(name)) {
                throw new SBEExportException("Duplicate state '" + name
                        + "'. Each state must appear exactly once.");
            }

            ResolvedState r = new ResolvedState(name);

            Path modelSource = binding.modelOverrideSource();
            if (modelSource != null) {
                if (!Files.exists(modelSource)) {
                    throw new SBEExportException("Model override file missing for state '"
                            + name + "': " + modelSource);
                }
                r.modelBytes = Files.readAllBytes(modelSource);
                r.modelFilename = SBEFormat.stateModelPath(name);
                r.modelChecksum = computeChecksum(r.modelBytes);
            }

            Path clipSource = binding.clipSource();
            if (clipSource != null) {
                if (!Files.exists(clipSource)) {
                    throw new SBEExportException("Animation clip file missing for state '"
                            + name + "': " + clipSource);
                }
                r.clipBytes = Files.readAllBytes(clipSource);
                r.clipFilename = SBEFormat.stateClipPath(name);
                r.clipChecksum = computeChecksum(r.clipBytes);
                r.clipMeta = probeOMAMetadata(r.clipBytes, clipSource);
            }

            result.add(r);
        }

        return result;
    }

    private void writeArchive(String outputPath,
                              SBEFormat.Document document,
                              byte[] omoBytes,
                              List<ResolvedState> resolved) throws IOException {
        Path tempFile = Files.createTempFile("sbe_export_", ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            writeManifest(zos, document);
            writeEntry(zos, SBEFormat.EMBEDDED_OMO_FILENAME, omoBytes);

            for (ResolvedState r : resolved) {
                if (r.hasModel()) writeEntry(zos, r.modelFilename, r.modelBytes);
                if (r.hasClip()) writeEntry(zos, r.clipFilename, r.clipBytes);
            }
        }
        Files.move(tempFile, Path.of(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeManifest(ZipOutputStream zos, SBEFormat.Document document) throws IOException {
        ZipEntry manifestEntry = new ZipEntry(SBEFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        ManifestDTO dto = new ManifestDTO(document);
        byte[] jsonBytes = objectMapper.writeValueAsString(dto).getBytes(StandardCharsets.UTF_8);
        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();
    }

    private void writeEntry(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.flush();
        zos.closeEntry();
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBEFormat.CHECKSUM_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    // ========================================================================
    // OMA metadata probe
    // ========================================================================

    private OMAMetadata probeOMAMetadata(byte[] omaBytes, Path source) {
        OMAMetadata meta = new OMAMetadata();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(omaBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!"manifest.json".equals(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                byte[] manifestBytes = readAll(zis);
                zis.closeEntry();

                JsonNode root = objectMapper.readTree(manifestBytes);
                if (root.hasNonNull("name")) meta.name = root.get("name").asText();
                if (root.hasNonNull("fps")) meta.fps = (float) root.get("fps").asDouble();
                if (root.hasNonNull("duration")) meta.duration = (float) root.get("duration").asDouble();
                if (root.hasNonNull("loop")) meta.loop = root.get("loop").asBoolean();

                JsonNode parts = root.get("requiredParts");
                if (parts != null && parts.isArray()) {
                    for (JsonNode p : parts) {
                        if (p != null && !p.isNull()) meta.requiredParts.add(p.asText());
                    }
                } else {
                    JsonNode tracks = root.get("tracks");
                    if (tracks != null && tracks.isArray()) {
                        for (JsonNode t : tracks) {
                            JsonNode partId = t.get("partId");
                            if (partId != null && !partId.isNull()) {
                                meta.requiredParts.add(partId.asText());
                            }
                        }
                    }
                }
                return meta;
            }
            logger.warn("No manifest.json in animation archive: {}", source);
        } catch (IOException e) {
            logger.warn("Failed to probe animation manifest in {}: {}", source, e.getMessage());
        }
        return meta;
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static class OMAMetadata {
        String name = null;
        float fps = 30f;
        float duration = 1f;
        boolean loop = true;
        final List<String> requiredParts = new ArrayList<>();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    private static class ManifestDTO {
        public String version;
        public String objectId;
        public String objectName;
        public String entityType;
        public String objectPack;
        public String checksum;
        public String checksumAlgorithm;
        public String author;
        public String description;
        public String createdAt;
        public String omoFile;
        public List<StateEntryDTO> states;

        public ManifestDTO(SBEFormat.Document doc) {
            this.version = doc.version();
            this.objectId = doc.objectId();
            this.objectName = doc.objectName();
            this.entityType = doc.entityType();
            this.objectPack = doc.objectPack();
            this.checksum = doc.checksum();
            this.checksumAlgorithm = SBEFormat.CHECKSUM_ALGORITHM;
            this.author = doc.author();
            this.description = doc.description();
            this.createdAt = doc.createdAt();
            this.omoFile = doc.omoFilename();
            this.states = new ArrayList<>(doc.states().size());
            for (SBEFormat.StateEntry e : doc.states()) {
                this.states.add(new StateEntryDTO(e));
            }
        }
    }

    private static class StateEntryDTO {
        public String name;
        public AssetRefDTO model;
        public AnimationRefDTO animation;

        public StateEntryDTO(SBEFormat.StateEntry e) {
            this.name = e.name();
            this.model = e.hasModelOverride() ? new AssetRefDTO(e.modelOverride()) : null;
            this.animation = e.hasAnimation() ? new AnimationRefDTO(e.animation()) : null;
        }
    }

    private static class AssetRefDTO {
        public String file;
        public String checksum;

        public AssetRefDTO(SBEFormat.AssetRef r) {
            this.file = r.filename();
            this.checksum = r.checksum();
        }
    }

    private static class AnimationRefDTO {
        public String file;
        public String checksum;
        public String clipName;
        public float duration;
        public float fps;
        public boolean loop;
        public List<String> requiredParts;

        public AnimationRefDTO(SBEFormat.AnimationRef a) {
            this.file = a.filename();
            this.checksum = a.checksum();
            this.clipName = a.clipName();
            this.duration = a.duration();
            this.fps = a.fps();
            this.loop = a.loop();
            this.requiredParts = new ArrayList<>(a.requiredParts());
        }
    }
}
