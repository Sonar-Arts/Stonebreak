package com.openmason.engine.format.sbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.format.sound.SoundJson;
import com.openmason.engine.format.sound.SoundSpec;
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

        List<byte[]> stateClipBytes;

        if (params.isStatesEnabled()) {
            // Resolve all state assets to (entry, bytes, checksum). The default
            // state's bytes must equal defaultBytes; if a separate file is given
            // we trust the caller's path and re-read.
            defaultStateName = params.getDefaultStateName();
            stateEntries = new ArrayList<>(params.getStates().size());
            stateBytes = new ArrayList<>(params.getStates().size());
            stateClipBytes = new ArrayList<>(params.getStates().size());

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

                SBOFormat.AnimationRef animRef = null;
                byte[] clipBytes = null;
                if (spec.hasClip()) {
                    if (!model) {
                        logger.error("State '{}' declares an animation clip but the SBO is texture-only",
                                spec.name());
                        return false;
                    }
                    Path clipSrc = Path.of(spec.clipSourcePath());
                    if (!Files.exists(clipSrc)) {
                        logger.error("State '{}' clip file does not exist: {}", spec.name(), clipSrc);
                        return false;
                    }
                    clipBytes = Files.readAllBytes(clipSrc);
                    animRef = buildAnimationRef(spec.name(), clipBytes, spec.loopMode(), clipSrc.toString());
                }

                stateEntries.add(new SBOFormat.StateEntry(
                        spec.name(), entryName, model, computeChecksum(bytes), animRef));
                stateBytes.add(bytes);
                stateClipBytes.add(clipBytes);
            }
        } else {
            stateEntries = Collections.emptyList();
            stateBytes = Collections.emptyList();
            stateClipBytes = Collections.emptyList();
        }

        ResolvedSounds sounds;
        try {
            sounds = resolveSoundSpecs(params.getSounds());
        } catch (IOException e) {
            logger.error("Failed to resolve sound specs: {}", e.getMessage());
            return false;
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
                defaultStateName,
                params.getRecipes(),
                params.getSmeltingRecipes(),
                params.getFuel(),
                sounds.data()
        );

        Path tempFile = Files.createTempFile("sbo_export_", ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            writeManifest(zos, document);
            writeEntry(zos, legacyEntry, defaultBytes);

            for (int i = 0; i < stateEntries.size(); i++) {
                SBOFormat.StateEntry e = stateEntries.get(i);
                if (!e.name().equals(defaultStateName)) { // legacy entry already written
                    writeEntry(zos, e.filename(), stateBytes.get(i));
                }
                // Clips always live under states/<name>/ — including the
                // default state's (there is no legacy clip entry).
                if (e.hasAnimation()) {
                    writeEntry(zos, e.animation().filename(), stateClipBytes.get(i));
                }
            }

            writeSoundEntries(zos, sounds);
        }

        Files.move(tempFile, Path.of(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        logger.info("Exported .SBO file: {} (states={}, payload={})",
                outputPath, stateEntries.size(), model ? "OMO" : "OMT");
        return true;
    }

    /**
     * Re-pack an SBO from a fully-built {@link SBOFormat.Document} plus the
     * already-extracted embedded asset bytes. Used by the in-app SBO editor
     * to save edits to an existing file without re-encoding the OMO/OMT.
     *
     * <p>The default-state bytes are re-checksummed; the manifest's checksum
     * field on the supplied document is replaced with the recomputed value
     * before writing.
     *
     * @param document        edited manifest (any field except payload kind may differ)
     * @param defaultBytes    bytes of the default-state asset (omo or omt)
     * @param stateBytesByName per-state asset bytes keyed by state name; used when
     *                        {@code document.hasStates()}. Each non-default state
     *                        must have an entry; the default state's bytes are taken
     *                        from {@code defaultBytes}. May be empty/null for stateless SBOs.
     * @param outputPath      destination .sbo path (overwrites if exists)
     * @return true on success
     */
    public boolean exportFromDocument(SBOFormat.Document document,
                                       byte[] defaultBytes,
                                       java.util.Map<String, byte[]> stateBytesByName,
                                       String outputPath) {
        return exportFromDocument(document, defaultBytes, stateBytesByName, null, outputPath);
    }

    /**
     * Variant of {@link #exportFromDocument(SBOFormat.Document, byte[], java.util.Map, String)}
     * that also carries per-state animation clip bytes (1.6+). Every state entry
     * with an {@link SBOFormat.AnimationRef} must have its raw {@code .omanim}
     * bytes in {@code stateClipBytesByName}; the ref's file/checksum/metadata are
     * recomputed from the bytes on save while the ref's <em>loop</em> flag is
     * preserved (it is an authored choice, not clip-derived).
     *
     * @param stateClipBytesByName per-state clip bytes keyed by state name; may be
     *                             null/empty when no state carries a clip
     */
    public boolean exportFromDocument(SBOFormat.Document document,
                                       byte[] defaultBytes,
                                       java.util.Map<String, byte[]> stateBytesByName,
                                       java.util.Map<String, byte[]> stateClipBytesByName,
                                       String outputPath) {
        return exportFromDocument(document, defaultBytes, stateBytesByName,
                stateClipBytesByName, null, outputPath);
    }

    /**
     * Variant that also carries embedded sound sample bytes (1.7+), keyed by
     * ZIP entry filename ({@code sounds/…}). Every embedded {@code SoundDef}
     * on the document must have its bytes present; the def's checksum is
     * recomputed from the bytes on save. Resource-referenced defs need no
     * bytes. Callers round-tripping a parsed SBO pass
     * {@code RawParse.soundBytes()} straight through.
     *
     * @param soundBytesByFilename embedded sound bytes keyed by entry filename;
     *                             may be null/empty when no def embeds audio
     */
    public boolean exportFromDocument(SBOFormat.Document document,
                                       byte[] defaultBytes,
                                       java.util.Map<String, byte[]> stateBytesByName,
                                       java.util.Map<String, byte[]> stateClipBytesByName,
                                       java.util.Map<String, byte[]> soundBytesByFilename,
                                       String outputPath) {
        if (document == null) {
            logger.error("exportFromDocument: document is null");
            return false;
        }
        if (defaultBytes == null || defaultBytes.length == 0) {
            logger.error("exportFromDocument: defaultBytes is empty");
            return false;
        }

        boolean model = document.isModelBearing();
        String legacyEntry = model ? SBOFormat.EMBEDDED_OMO_FILENAME : SBOFormat.EMBEDDED_OMT_FILENAME;
        String legacyChecksum = computeChecksum(defaultBytes);

        // Rebuild state entries with recomputed checksums (state filenames preserved).
        java.util.List<SBOFormat.StateEntry> rebuiltStates = Collections.emptyList();
        java.util.Map<String, byte[]> resolvedStateBytes = new java.util.LinkedHashMap<>();
        java.util.Map<String, byte[]> resolvedClipBytes = new java.util.LinkedHashMap<>();
        if (document.hasStates()) {
            rebuiltStates = new ArrayList<>(document.states().size());
            for (SBOFormat.StateEntry e : document.states()) {
                byte[] bytes;
                if (e.name().equals(document.defaultStateName())) {
                    bytes = defaultBytes;
                } else {
                    bytes = stateBytesByName != null ? stateBytesByName.get(e.name()) : null;
                    if (bytes == null) {
                        logger.error("exportFromDocument: missing bytes for state '{}'", e.name());
                        return false;
                    }
                }
                resolvedStateBytes.put(e.name(), bytes);

                SBOFormat.AnimationRef animRef = null;
                if (e.hasAnimation()) {
                    byte[] clipBytes = stateClipBytesByName != null
                            ? stateClipBytesByName.get(e.name()) : null;
                    if (clipBytes == null) {
                        logger.error("exportFromDocument: missing clip bytes for state '{}'", e.name());
                        return false;
                    }
                    resolvedClipBytes.put(e.name(), clipBytes);
                    // Re-probe metadata from the bytes but keep the entry's
                    // loop flag — it is an authored choice, not clip-derived.
                    SBOFormat.LoopMode preserved = e.animation().loop()
                            ? SBOFormat.LoopMode.LOOP : SBOFormat.LoopMode.ONCE;
                    animRef = buildAnimationRef(e.name(), clipBytes, preserved,
                            "state '" + e.name() + "'");
                }

                rebuiltStates.add(new SBOFormat.StateEntry(
                        e.name(),
                        SBOFormat.STATES_DIR_PREFIX + e.name() + "/" + legacyEntry,
                        model,
                        computeChecksum(bytes),
                        animRef
                ));
            }
        }

        ResolvedSounds rebuiltSounds =
                rebuildSounds(document.sounds(), soundBytesByFilename);
        if (rebuiltSounds == null) {
            return false; // missing embedded bytes; already logged
        }

        SBOFormat.Document finalDoc = new SBOFormat.Document(
                SBOFormat.FORMAT_VERSION,
                document.objectId(),
                document.objectName(),
                document.objectType(),
                document.objectPack(),
                legacyChecksum,
                document.author(),
                document.description(),
                document.createdAt() != null ? document.createdAt() : Instant.now().toString(),
                model ? legacyEntry : null,
                model ? null : legacyEntry,
                document.gameProperties(),
                rebuiltStates,
                rebuiltStates.isEmpty() ? null : document.defaultStateName(),
                document.recipes(),
                document.smeltingRecipes(),
                document.fuel(),
                rebuiltSounds.data()
        );

        outputPath = SBOFormat.ensureExtension(outputPath);
        try {
            Path tempFile = Files.createTempFile("sbo_save_", ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                writeManifest(zos, finalDoc);
                writeEntry(zos, legacyEntry, defaultBytes);

                for (SBOFormat.StateEntry e : rebuiltStates) {
                    if (!e.name().equals(finalDoc.defaultStateName())) {
                        writeEntry(zos, e.filename(), resolvedStateBytes.get(e.name()));
                    }
                    if (e.hasAnimation()) {
                        writeEntry(zos, e.animation().filename(), resolvedClipBytes.get(e.name()));
                    }
                }

                writeSoundEntries(zos, rebuiltSounds);
            }
            Files.move(tempFile, Path.of(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Saved .SBO file: {} (states={}, recipes={})",
                    outputPath, rebuiltStates.size(),
                    finalDoc.hasRecipes() ? finalDoc.recipes().shaped().size() : 0);
            return true;
        } catch (IOException e) {
            logger.error("Error saving .SBO file: {}", outputPath, e);
            return false;
        }
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
     * Sound defs plus the embedded bytes for each (aligned by index; a null
     * bytes slot means the def references a classpath resource instead).
     */
    private record ResolvedSounds(SoundData data, List<byte[]> embeddedBytes) {
        static final ResolvedSounds NONE = new ResolvedSounds(null, List.of());
    }

    /**
     * Resolve export-time {@link SoundSpec}s: read each embedded source file
     * from disk (checksumming it and assigning a {@code sounds/<event>_<n>}
     * entry path), and pass resource references through untouched.
     */
    private ResolvedSounds resolveSoundSpecs(List<SoundSpec> specs) throws IOException {
        if (specs == null || specs.isEmpty()) {
            return ResolvedSounds.NONE;
        }
        List<SoundDef> defs = new ArrayList<>(specs.size());
        List<byte[]> bytesList = new ArrayList<>(specs.size());
        java.util.Map<String, Integer> perEventIndex = new java.util.HashMap<>();
        for (SoundSpec spec : specs) {
            if (spec.embeds()) {
                Path src = Path.of(spec.sourcePath());
                if (!Files.exists(src)) {
                    throw new IOException("Sound source file for event '" + spec.event()
                            + "' does not exist: " + src);
                }
                byte[] bytes = Files.readAllBytes(src);
                int index = perEventIndex.merge(spec.event(), 1, Integer::sum) - 1;
                String entry = SBOFormat.soundEntryPath(spec.event(), index, extensionOf(src));
                defs.add(new SoundDef(spec.event(), entry, computeChecksum(bytes), null,
                        spec.volume(), spec.pitchMin(), spec.pitchMax(), spec.variation()));
                bytesList.add(bytes);
            } else {
                defs.add(new SoundDef(spec.event(), null, null, spec.resourcePath(),
                        spec.volume(), spec.pitchMin(), spec.pitchMax(), spec.variation()));
                bytesList.add(null);
            }
        }
        return new ResolvedSounds(new SoundData(defs), bytesList);
    }

    /**
     * Rebuild a document's sound defs for a round-trip save: embedded defs
     * keep their entry filename but get their checksum recomputed from the
     * supplied bytes; resource references pass through. Returns null (after
     * logging) when an embedded def's bytes are missing.
     */
    private ResolvedSounds rebuildSounds(SoundData sounds,
                                         java.util.Map<String, byte[]> soundBytesByFilename) {
        if (sounds == null || sounds.isEmpty()) {
            return ResolvedSounds.NONE;
        }
        List<SoundDef> defs = new ArrayList<>(sounds.sounds().size());
        List<byte[]> bytesList = new ArrayList<>(sounds.sounds().size());
        for (SoundDef def : sounds.sounds()) {
            if (def.isEmbedded()) {
                byte[] bytes = soundBytesByFilename != null
                        ? soundBytesByFilename.get(def.filename()) : null;
                if (bytes == null) {
                    logger.error("exportFromDocument: missing bytes for embedded sound '{}'",
                            def.filename());
                    return null;
                }
                defs.add(new SoundDef(def.event(), def.filename(), computeChecksum(bytes),
                        null, def.volume(), def.pitchMin(), def.pitchMax(), def.variation()));
                bytesList.add(bytes);
            } else {
                defs.add(def);
                bytesList.add(null);
            }
        }
        return new ResolvedSounds(new SoundData(defs), bytesList);
    }

    /** Write the embedded sound samples of a resolved set (resource refs skip). */
    private void writeSoundEntries(ZipOutputStream zos, ResolvedSounds sounds) throws IOException {
        if (sounds.data() == null) return;
        List<SoundDef> defs = sounds.data().sounds();
        for (int i = 0; i < defs.size(); i++) {
            byte[] bytes = sounds.embeddedBytes().get(i);
            if (bytes != null) {
                writeEntry(zos, defs.get(i).filename(), bytes);
            }
        }
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "wav";
    }

    /**
     * Build an {@link SBOFormat.AnimationRef} for a state's clip: checksum the
     * raw bytes, probe the clip's inner manifest for name/fps/duration/loop/
     * requiredParts, and resolve the loop flag through {@code loopMode}.
     *
     * <p>Mirrors the SBE serializer's OMA probe — the clip is embedded
     * verbatim, never re-encoded; only its metadata is snapshotted into the
     * SBO manifest so the game can schedule clips without unpacking tracks.
     */
    private SBOFormat.AnimationRef buildAnimationRef(String stateName, byte[] clipBytes,
                                                     SBOFormat.LoopMode loopMode, String sourceLabel) {
        String name = null;
        float fps = 30f;
        float duration = 1f;
        boolean clipLoop = true;
        List<String> requiredParts = new ArrayList<>();

        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(clipBytes))) {
            ZipEntry entry;
            boolean found = false;
            while ((entry = zis.getNextEntry()) != null) {
                if (!"manifest.json".equals(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
                zis.closeEntry();

                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(out.toByteArray());
                if (root.hasNonNull("name")) name = root.get("name").asText();
                if (root.hasNonNull("fps")) fps = (float) root.get("fps").asDouble();
                if (root.hasNonNull("duration")) duration = (float) root.get("duration").asDouble();
                if (root.hasNonNull("loop")) clipLoop = root.get("loop").asBoolean();

                com.fasterxml.jackson.databind.JsonNode parts = root.get("requiredParts");
                if (parts != null && parts.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode p : parts) {
                        if (p != null && !p.isNull()) requiredParts.add(p.asText());
                    }
                } else {
                    com.fasterxml.jackson.databind.JsonNode tracks = root.get("tracks");
                    if (tracks != null && tracks.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode t : tracks) {
                            com.fasterxml.jackson.databind.JsonNode partId = t.get("partId");
                            if (partId != null && !partId.isNull()) requiredParts.add(partId.asText());
                        }
                    }
                }
                found = true;
                break;
            }
            if (!found) {
                logger.warn("No manifest.json in animation clip for {} — using defaults", sourceLabel);
            }
        } catch (IOException e) {
            logger.warn("Failed to probe animation clip for {}: {}", sourceLabel, e.getMessage());
        }

        return new SBOFormat.AnimationRef(
                SBOFormat.stateClipPath(stateName),
                computeChecksum(clipBytes),
                name != null ? name : stateName,
                duration,
                fps,
                loopMode.resolve(clipLoop),
                requiredParts
        );
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
        public RecipeDataDTO recipes;
        public SmeltingRecipeDataDTO smeltingRecipes;
        public FuelDataDTO fuel;
        public List<SoundJson.SoundDefDTO> sounds;

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
            this.recipes = doc.hasRecipes() ? new RecipeDataDTO(doc.recipes()) : null;
            this.smeltingRecipes = doc.hasSmeltingRecipes()
                    ? new SmeltingRecipeDataDTO(doc.smeltingRecipes())
                    : null;
            this.fuel = doc.hasFuel() ? new FuelDataDTO(doc.fuel()) : null;
            this.sounds = SoundJson.toDto(doc.sounds());
        }
    }

    private static class SmeltingRecipeDataDTO {
        public List<SmeltingRecipeEntryDTO> recipes;

        public SmeltingRecipeDataDTO(SBOFormat.SmeltingRecipeData data) {
            this.recipes = new ArrayList<>(data.recipes().size());
            for (SBOFormat.SmeltingRecipeEntry e : data.recipes()) {
                this.recipes.add(new SmeltingRecipeEntryDTO(e));
            }
        }
    }

    private static class SmeltingRecipeEntryDTO {
        public String input;
        public int outputCount;

        public SmeltingRecipeEntryDTO(SBOFormat.SmeltingRecipeEntry e) {
            this.input = e.inputObjectId();
            this.outputCount = e.outputCount();
        }
    }

    private static class FuelDataDTO {
        public int burnTicks;

        public FuelDataDTO(SBOFormat.FuelData f) {
            this.burnTicks = f.burnTicks();
        }
    }

    private static class RecipeDataDTO {
        public List<ShapedRecipeDTO> shaped;

        public RecipeDataDTO(SBOFormat.RecipeData data) {
            this.shaped = new ArrayList<>(data.shaped().size());
            for (SBOFormat.ShapedRecipe r : data.shaped()) {
                this.shaped.add(new ShapedRecipeDTO(r));
            }
        }
    }

    private static class ShapedRecipeDTO {
        public int width;
        public int height;
        public List<String> pattern;
        public int outputCount;

        public ShapedRecipeDTO(SBOFormat.ShapedRecipe r) {
            this.width = r.width();
            this.height = r.height();
            this.pattern = r.pattern();
            this.outputCount = r.outputCount();
        }
    }

    private static class StateEntryDTO {
        public String name;
        public String file;
        public boolean model;
        public String checksum;
        public AnimationRefDTO animation;

        public StateEntryDTO(SBOFormat.StateEntry e) {
            this.name = e.name();
            this.file = e.filename();
            this.model = e.model();
            this.checksum = e.checksum();
            this.animation = e.hasAnimation() ? new AnimationRefDTO(e.animation()) : null;
        }
    }

    /** DTO mirror of {@link SBOFormat.AnimationRef} (1.6+). */
    private static class AnimationRefDTO {
        public String file;
        public String checksum;
        public String clipName;
        public float duration;
        public float fps;
        public boolean loop;
        public List<String> requiredParts;

        public AnimationRefDTO(SBOFormat.AnimationRef a) {
            this.file = a.filename();
            this.checksum = a.checksum();
            this.clipName = a.clipName();
            this.duration = a.duration();
            this.fps = a.fps();
            this.loop = a.loop();
            this.requiredParts = new ArrayList<>(a.requiredParts());
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
