package com.openmason.engine.format.sbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Stonebreak Object (.SBO) file format.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>{@code manifest.json} - SBO metadata with object identity, checksum, and author signage</li>
 *   <li>{@code model.omo} - Embedded Open Mason Object file (complete model + texture)</li>
 *   <li>{@code texture.omt} - Embedded Open Mason Texture file</li>
 * </ul>
 *
 * <p>The serializer computes a SHA-256 checksum of the OMO file bytes for integrity
 * verification. The OMO and OMT files are embedded as-is without re-encoding.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .SBO file writing</li>
 *   <li>DRY: Reuses existing .OMO and .OMT files (no re-encoding)</li>
 *   <li>KISS: Simple ZIP creation with JSON manifest + embedded files</li>
 * </ul>
 *
 * @since 1.0
 */
public class SBOSerializer {

    private static final Logger logger = LoggerFactory.getLogger(SBOSerializer.class);

    private final ObjectMapper objectMapper;

    public SBOSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Exports an SBO file from an existing OMO file.
     *
     * <p>The OMO file must exist and contain a valid model. The OMO already
     * contains its texture data internally (embedded .omt), so no separate
     * texture file is needed.
     *
     * @param params     export parameters containing object identity and metadata
     * @param omoPath    path to the source .OMO file
     * @param outputPath output file path for the .SBO file
     * @return true if export succeeded
     */
    public boolean export(SBOFormat.ExportParameters params, Path omoPath, String outputPath) {
        if (params == null || !params.isValid()) {
            logger.error("Invalid export parameters: {}",
                    params != null ? params.getValidationError() : "null");
            return false;
        }

        if (omoPath == null || !Files.exists(omoPath)) {
            logger.error("OMO file does not exist: {}", omoPath);
            return false;
        }

        outputPath = SBOFormat.ensureExtension(outputPath);

        try {
            byte[] defaultBytes = Files.readAllBytes(omoPath);
            return writeSbo(params, outputPath, defaultBytes, /* model */ true);
        } catch (IOException e) {
            logger.error("Error exporting .SBO file: {}", outputPath, e);
            return false;
        }
    }

    /**
     * Exports a texture-only SBO (1.2+) wrapping an existing .OMT file.
     *
     * <p>Used for sprite items and other assets that have no 3D model. The
     * resulting SBO carries {@code textureFile=texture.omt} (no {@code omoFile})
     * and is consumed game-side by the item voxelizer.
     *
     * @param params     export parameters; objectType is typically ITEM
     * @param omtPath    path to the source .OMT file
     * @param outputPath output file path for the .SBO file
     * @return true if export succeeded
     */
    public boolean exportTexture(SBOFormat.ExportParameters params, Path omtPath, String outputPath) {
        if (params == null || !params.isValid()) {
            logger.error("Invalid export parameters: {}",
                    params != null ? params.getValidationError() : "null");
            return false;
        }

        if (omtPath == null || !Files.exists(omtPath)) {
            logger.error("OMT file does not exist: {}", omtPath);
            return false;
        }

        outputPath = SBOFormat.ensureExtension(outputPath);

        try {
            byte[] defaultBytes = Files.readAllBytes(omtPath);
            return writeSbo(params, outputPath, defaultBytes, /* model */ false);
        } catch (IOException e) {
            logger.error("Error exporting texture-only .SBO file: {}", outputPath, e);
            return false;
        }
    }

    /**
     * Variant of {@link #exportTexture(SBOFormat.ExportParameters, Path, String)} that
     * accepts raw OMT bytes (for in-memory generation, no temp file required).
     *
     * @param params    export parameters
     * @param omtBytes  raw OMT archive bytes to embed
     * @param outputPath output file path for the .SBO file
     * @return true if export succeeded
     */
    public boolean exportTextureBytes(SBOFormat.ExportParameters params, byte[] omtBytes, String outputPath) {
        if (params == null || !params.isValid()) {
            logger.error("Invalid export parameters: {}",
                    params != null ? params.getValidationError() : "null");
            return false;
        }
        if (omtBytes == null || omtBytes.length == 0) {
            logger.error("OMT bytes are empty");
            return false;
        }

        outputPath = SBOFormat.ensureExtension(outputPath);

        try {
            return writeSbo(params, outputPath, omtBytes, /* model */ false);
        } catch (IOException e) {
            logger.error("Error exporting texture-only .SBO file: {}", outputPath, e);
            return false;
        }
    }

    /**
     * Unified write path. When {@code params.isStatesEnabled()} is false,
     * writes a single-asset SBO (1.2-style: just the legacy entry). When
     * states are enabled, writes the legacy entry from the default state's
     * source (so 1.2 readers still load) plus per-state entries under
     * {@code states/<name>/}, and records the full {@code states[]} list in
     * the manifest.
     *
     * @param params       export parameters
     * @param outputPath   final SBO path
     * @param defaultBytes bytes of the default-state asset (already loaded)
     * @param model        true for OMO payload, false for OMT payload
     */
    private boolean writeSbo(SBOFormat.ExportParameters params,
                             String outputPath,
                             byte[] defaultBytes,
                             boolean model) throws IOException {
        String legacyEntry = model ? SBOFormat.EMBEDDED_OMO_FILENAME : SBOFormat.EMBEDDED_OMT_FILENAME;
        String legacyChecksum = computeChecksum(defaultBytes);

        List<SBOFormat.StateEntry> stateEntries;
        List<byte[]> stateBytes;
        String defaultStateName = null;

        if (params.isStatesEnabled()) {
            // Resolve all state assets to (entry, bytes, checksum). The default
            // state's bytes must equal defaultBytes; if a separate file is given
            // we trust the caller's path and re-read.
            defaultStateName = params.getDefaultStateName();
            stateEntries = new ArrayList<>(params.getStates().size());
            stateBytes = new ArrayList<>(params.getStates().size());

            for (SBOFormat.StateSpec spec : params.getStates()) {
                String entryName = SBOFormat.STATES_DIR_PREFIX + spec.name() + "/" + legacyEntry;
                byte[] bytes;
                if (spec.name().equals(defaultStateName)) {
                    bytes = defaultBytes;
                } else {
                    Path src = Path.of(spec.sourcePath());
                    if (!Files.exists(src)) {
                        logger.error("State '{}' source file does not exist: {}", spec.name(), src);
                        return false;
                    }
                    bytes = Files.readAllBytes(src);
                }
                stateEntries.add(new SBOFormat.StateEntry(spec.name(), entryName, model, computeChecksum(bytes)));
                stateBytes.add(bytes);
            }
        } else {
            stateEntries = Collections.emptyList();
            stateBytes = Collections.emptyList();
        }

        SBOFormat.Document document = new SBOFormat.Document(
                SBOFormat.FORMAT_VERSION,
                params.getObjectId(),
                params.getObjectName(),
                params.getObjectType().getId(),
                params.getObjectPack(),
                legacyChecksum,
                params.getAuthor(),
                params.getDescription().isBlank() ? null : params.getDescription(),
                Instant.now().toString(),
                model ? legacyEntry : null,
                model ? null : legacyEntry,
                params.getGameProperties(),
                stateEntries,
                defaultStateName
        );

        Path tempFile = Files.createTempFile("sbo_export_", ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            writeManifest(zos, document);
            writeEntry(zos, legacyEntry, defaultBytes);

            for (int i = 0; i < stateEntries.size(); i++) {
                SBOFormat.StateEntry e = stateEntries.get(i);
                if (e.name().equals(defaultStateName)) continue; // legacy entry already written
                writeEntry(zos, e.filename(), stateBytes.get(i));
            }
        }

        Files.move(tempFile, Path.of(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        logger.info("Exported .SBO file: {} (states={}, payload={})",
                outputPath, stateEntries.size(), model ? "OMO" : "OMT");
        return true;
    }

    /**
     * Writes the manifest.json entry to the ZIP archive.
     */
    private void writeManifest(ZipOutputStream zos, SBOFormat.Document document) throws IOException {
        ZipEntry manifestEntry = new ZipEntry(SBOFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        ManifestDTO dto = new ManifestDTO(document);
        String json = objectMapper.writeValueAsString(dto);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote manifest.json ({} bytes)", jsonBytes.length);
    }

    /**
     * Writes a raw byte array as a ZIP entry.
     */
    private void writeEntry(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote {} ({} bytes)", entryName, data.length);
    }

    /**
     * Computes a SHA-256 checksum of the given byte array.
     *
     * @param data the data to checksum
     * @return hex-encoded checksum string
     */
    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBOFormat.CHECKSUM_ALGORITHM);
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Data Transfer Object for JSON serialization of the SBO manifest.
     * Jackson serializes public fields automatically.
     */
    private static class ManifestDTO {
        public String version;
        public String objectId;
        public String objectName;
        public String objectType;
        public String objectPack;
        public String checksum;
        public String checksumAlgorithm;
        public String author;
        public String description;
        public String createdAt;
        public String omoFile;
        public String textureFile;
        public GamePropertiesDTO gameProperties;
        public List<StateEntryDTO> states;
        public String defaultState;

        public ManifestDTO(SBOFormat.Document doc) {
            this.version = doc.version();
            this.objectId = doc.objectId();
            this.objectName = doc.objectName();
            this.objectType = doc.objectType();
            this.objectPack = doc.objectPack();
            this.checksum = doc.checksum();
            this.checksumAlgorithm = SBOFormat.CHECKSUM_ALGORITHM;
            this.author = doc.author();
            this.description = doc.description();
            this.createdAt = doc.createdAt();
            this.omoFile = doc.omoFilename();
            this.textureFile = doc.textureFilename();
            this.gameProperties = doc.gameProperties() != null
                    ? new GamePropertiesDTO(doc.gameProperties())
                    : null;
            if (doc.hasStates()) {
                this.states = new ArrayList<>();
                for (SBOFormat.StateEntry e : doc.states()) {
                    this.states.add(new StateEntryDTO(e));
                }
                this.defaultState = doc.defaultStateName();
            } else {
                this.states = null;
                this.defaultState = null;
            }
        }
    }

    private static class StateEntryDTO {
        public String name;
        public String file;
        public boolean model;
        public String checksum;

        public StateEntryDTO(SBOFormat.StateEntry e) {
            this.name = e.name();
            this.file = e.filename();
            this.model = e.model();
            this.checksum = e.checksum();
        }
    }

    /**
     * DTO mirror of {@link SBOFormat.GameProperties} for Jackson serialization.
     * Float.POSITIVE_INFINITY hardness serializes as the string "Infinity" via
     * Jackson; the parser reads it back via {@code asDouble()} which handles it.
     */
    private static class GamePropertiesDTO {
        public int numericId;
        public float hardness;
        public boolean solid;
        public boolean breakable;
        public int atlasX;
        public int atlasY;
        public String renderLayer;
        public boolean transparent;
        public boolean flower;
        public boolean stackable;
        public int maxStackSize;
        public String category;
        public boolean placeable;

        public GamePropertiesDTO(SBOFormat.GameProperties gp) {
            this.numericId = gp.numericId();
            this.hardness = gp.hardness();
            this.solid = gp.solid();
            this.breakable = gp.breakable();
            this.atlasX = gp.atlasX();
            this.atlasY = gp.atlasY();
            this.renderLayer = gp.renderLayer();
            this.transparent = gp.transparent();
            this.flower = gp.flower();
            this.stackable = gp.stackable();
            this.maxStackSize = gp.maxStackSize();
            this.category = gp.category();
            this.placeable = gp.placeable();
        }
    }
}
