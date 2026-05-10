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
import java.util.HexFormat;
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

        // First pass: extract manifest and embedded asset (OMO XOR OMT) from the SBO ZIP
        try (InputStream fis = Files.newInputStream(sboPath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (SBOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    byte[] jsonBytes = readBytes(zis);
                    manifest = parseManifest(jsonBytes);
                    logger.debug("Parsed SBO manifest: {} ({})", manifest.objectName(), manifest.objectId());

                } else if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(entryName)) {
                    omoBytes = readBytes(zis);
                    logger.debug("Extracted embedded OMO: {} bytes", omoBytes.length);

                } else if (SBOFormat.EMBEDDED_OMT_FILENAME.equals(entryName)) {
                    omtBytes = readBytes(zis);
                    logger.debug("Extracted embedded OMT: {} bytes", omtBytes.length);
                }

                zis.closeEntry();
            }
        }

        if (manifest == null) {
            throw new IOException("Missing manifest.json in SBO file: " + sboPath);
        }

        if (manifest.isModelBearing()) {
            if (omoBytes == null) {
                throw new IOException("Manifest declares omoFile but model.omo missing: " + sboPath);
            }
            return parseModelBearing(manifest, omoBytes, sboPath);
        }

        if (manifest.isTextureOnly()) {
            if (omtBytes == null) {
                throw new IOException("Manifest declares textureFile but texture.omt missing: " + sboPath);
            }
            validateChecksum(manifest, omtBytes, sboPath);
            logger.info("Parsed texture-only SBO: {} ({}) - {} OMT bytes",
                    manifest.objectName(), manifest.objectId(), omtBytes.length);
            return new SBOParseResult(manifest, null, null, null, null, null, omtBytes);
        }

        throw new IOException("SBO manifest declares neither omoFile nor textureFile: " + sboPath);
    }

    private SBOParseResult parseModelBearing(SBOFormat.Document manifest, byte[] omoBytes, Path sboPath) throws IOException {
        validateChecksum(manifest, omoBytes, sboPath);

        OMOReader.ReadResult omoResult;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(omoBytes)) {
            omoResult = omoReader.read(bais);
        }

        logger.info("Parsed SBO: {} ({}) - {} vertices, {} materials",
                manifest.objectName(), manifest.objectId(),
                omoResult.meshData() != null ? omoResult.meshData().getVertexCount() : 0,
                omoResult.materials().size());

        return new SBOParseResult(
                manifest,
                omoResult.document(),
                omoResult.meshData(),
                omoResult.faceMappings(),
                omoResult.materials(),
                omoResult.defaultTextureBytes(),
                null
        );
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
                gameProperties
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
