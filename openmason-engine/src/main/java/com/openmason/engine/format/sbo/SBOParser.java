package com.openmason.engine.format.sbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
 * Parser for Stonebreak Object (.SBO) files.
 *
 * <p>Reads the SBO ZIP container, extracts the manifest and embedded OMO,
 * then delegates OMO parsing to {@link OMOReader}.
 *
 * <p>Validates the SHA-256 checksum of the embedded OMO to ensure integrity.
 */
public class SBOParser {

    private static final Logger logger = LoggerFactory.getLogger(SBOParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OMOReader omoReader = new OMOReader();

    /**
     * Parse an SBO file from disk.
     *
     * @param sboPath path to the .sbo file
     * @return complete parse result
     * @throws IOException if reading or parsing fails
     */
    public SBOParseResult parse(Path sboPath) throws IOException {
        if (!Files.exists(sboPath)) {
            throw new IOException("SBO file does not exist: " + sboPath);
        }

        SBOFormat.Document manifest = null;
        byte[] omoBytes = null;
        byte[] omtBytes = null;
        Map<String, byte[]> rawEntries = new HashMap<>();

        try (InputStream fis = Files.newInputStream(sboPath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                byte[] bytes = readBytes(zis);

                if (SBOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    manifest = parseManifest(bytes);
                    logger.debug("Parsed SBO manifest: {} ({})", manifest.objectName(), manifest.objectId());
                } else if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(entryName)) {
                    omoBytes = bytes;
                } else if (SBOFormat.EMBEDDED_OMT_FILENAME.equals(entryName)) {
                    omtBytes = bytes;
                } else if (entryName.startsWith(SBOFormat.STATES_DIR_PREFIX)) {
                    rawEntries.put(entryName, bytes);
                }

                zis.closeEntry();
            }
        }

        if (manifest == null) {
            throw new IOException("Missing manifest.json in SBO file: " + sboPath);
        }

        Map<String, byte[]> stateOmoBytes = new LinkedHashMap<>();
        Map<String, byte[]> stateOmtBytes = new LinkedHashMap<>();
        Map<String, OMOReader.ReadResult> stateOmoData = new LinkedHashMap<>();

        if (manifest.hasStates()) {
            for (SBOFormat.StateEntry e : manifest.states()) {
                byte[] data;
                if (e.name().equals(manifest.defaultStateName())) {
                    data = e.model() ? omoBytes : omtBytes;
                } else {
                    data = rawEntries.get(e.filename());
                }
                if (data == null) {
                    throw new IOException("State '" + e.name() + "' missing entry "
                            + e.filename() + " in SBO: " + sboPath);
                }
                validateNamedChecksum(e.name(), e.checksum(), data, sboPath);
                if (e.model()) {
                    stateOmoBytes.put(e.name(), data);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                        stateOmoData.put(e.name(), omoReader.read(bais));
                    }
                } else {
                    stateOmtBytes.put(e.name(), data);
                }
            }
        }

        if (manifest.isModelBearing()) {
            if (omoBytes == null) {
                throw new IOException("Manifest declares omoFile but model.omo missing: " + sboPath);
            }
            return parseModelBearing(manifest, omoBytes, sboPath, stateOmoBytes, stateOmtBytes, stateOmoData);
        }

        if (manifest.isTextureOnly()) {
            if (omtBytes == null) {
                throw new IOException("Manifest declares textureFile but texture.omt missing: " + sboPath);
            }
            validateChecksum(manifest, omtBytes, sboPath);
            logger.info("Parsed texture-only SBO: {} ({}) - {} OMT bytes, states={}",
                    manifest.objectName(), manifest.objectId(), omtBytes.length, stateOmtBytes.size());
            return new SBOParseResult(manifest, null, null, null, null, null, omtBytes,
                    stateOmoBytes, stateOmtBytes, stateOmoData);
        }

        throw new IOException("SBO manifest declares neither omoFile nor textureFile: " + sboPath);
    }

    private SBOParseResult parseModelBearing(SBOFormat.Document manifest, byte[] omoBytes, Path sboPath,
                                              Map<String, byte[]> stateOmoBytes,
                                              Map<String, byte[]> stateOmtBytes,
                                              Map<String, OMOReader.ReadResult> stateOmoData) throws IOException {
        validateChecksum(manifest, omoBytes, sboPath);

        OMOReader.ReadResult omoResult;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(omoBytes)) {
            omoResult = omoReader.read(bais);
        }

        logger.info("Parsed SBO: {} ({}) - {} vertices, {} materials, states={}",
                manifest.objectName(), manifest.objectId(),
                omoResult.meshData() != null ? omoResult.meshData().getVertexCount() : 0,
                omoResult.materials().size(),
                stateOmoBytes.size());

        return new SBOParseResult(
                manifest,
                omoResult.document(),
                omoResult.meshData(),
                omoResult.faceMappings(),
                omoResult.materials(),
                omoResult.defaultTextureBytes(),
                null,
                stateOmoBytes,
                stateOmtBytes,
                stateOmoData
        );
    }

    private void validateNamedChecksum(String label, String expected, byte[] data, Path sboPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBOFormat.CHECKSUM_ALGORITHM);
            String computed = HexFormat.of().formatHex(digest.digest(data));
            if (!computed.equalsIgnoreCase(expected)) {
                logger.warn("SBO state '{}' checksum mismatch in {}: expected={}, computed={}",
                        label, sboPath.getFileName(), expected, computed);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.warn("Cannot validate checksum for state '{}': {} not available",
                    label, SBOFormat.CHECKSUM_ALGORITHM);
        }
    }

    private SBOFormat.Document parseManifest(byte[] jsonBytes) throws IOException {
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        var root = objectMapper.readTree(json);

        SBOFormat.GameProperties gameProperties = null;
        if (root.has("gameProperties") && !root.get("gameProperties").isNull()) {
            var gp = root.get("gameProperties");
            gameProperties = new SBOFormat.GameProperties(
                    gp.has("numericId") ? gp.get("numericId").asInt() : -1,
                    gp.has("hardness") ? (float) gp.get("hardness").asDouble() : 1.0f,
                    !gp.has("solid") || gp.get("solid").asBoolean(),
                    !gp.has("breakable") || gp.get("breakable").asBoolean(),
                    gp.has("atlasX") ? gp.get("atlasX").asInt() : -1,
                    gp.has("atlasY") ? gp.get("atlasY").asInt() : -1,
                    gp.has("renderLayer") ? gp.get("renderLayer").asText() : null,
                    gp.has("transparent") && gp.get("transparent").asBoolean(),
                    gp.has("flower") && gp.get("flower").asBoolean(),
                    gp.has("stackable") && gp.get("stackable").asBoolean(),
                    gp.has("maxStackSize") ? gp.get("maxStackSize").asInt() : 64,
                    gp.has("category") ? gp.get("category").asText() : null,
                    !gp.has("placeable") || gp.get("placeable").asBoolean()
            );
        }

        String omoFile = root.has("omoFile") && !root.get("omoFile").isNull()
                ? nullIfBlank(root.get("omoFile").asText()) : null;
        String textureFile = root.has("textureFile") && !root.get("textureFile").isNull()
                ? nullIfBlank(root.get("textureFile").asText()) : null;

        List<SBOFormat.StateEntry> states = Collections.emptyList();
        String defaultState = null;
        if (root.has("states") && root.get("states").isArray() && !root.get("states").isEmpty()) {
            states = new ArrayList<>();
            for (var node : root.get("states")) {
                String name = node.get("name").asText();
                String file = node.get("file").asText();
                boolean model = node.has("model") && node.get("model").asBoolean();
                String stateChecksum = node.has("checksum") ? node.get("checksum").asText() : "";
                states.add(new SBOFormat.StateEntry(name, file, model, stateChecksum));
            }
            defaultState = root.has("defaultState") && !root.get("defaultState").isNull()
                    ? root.get("defaultState").asText() : null;
        }

        return new SBOFormat.Document(
                root.get("version").asText(),
                root.get("objectId").asText(),
                root.get("objectName").asText(),
                root.get("objectType").asText(),
                root.get("objectPack").asText(),
                root.get("checksum").asText(),
                root.get("author").asText(),
                root.has("description") ? root.get("description").asText() : null,
                root.has("createdAt") ? root.get("createdAt").asText() : null,
                omoFile,
                textureFile,
                gameProperties,
                states,
                defaultState
        );
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void validateChecksum(SBOFormat.Document manifest, byte[] omoBytes, Path sboPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBOFormat.CHECKSUM_ALGORITHM);
            String computed = HexFormat.of().formatHex(digest.digest(omoBytes));
            if (!computed.equalsIgnoreCase(manifest.checksum())) {
                logger.warn("SBO checksum mismatch in {}: expected={}, computed={}",
                        sboPath.getFileName(), manifest.checksum(), computed);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.warn("Cannot validate SBO checksum: {} not available", SBOFormat.CHECKSUM_ALGORITHM);
        }
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
