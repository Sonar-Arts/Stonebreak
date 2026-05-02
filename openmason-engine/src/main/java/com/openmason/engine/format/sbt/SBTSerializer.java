package com.openmason.engine.format.sbt;

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
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializer for Stonebreak Texture (.SBT) file format.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>{@code manifest.json} - SBT metadata with texture type, checksum, and author signage</li>
 *   <li>{@code texture.omt} - Embedded Open Mason Texture file</li>
 * </ul>
 *
 * <p>Structurally identical to the SBO/SBE serializers — wraps an OMT instead
 * of an OMO.
 */
public class SBTSerializer {

    private static final Logger logger = LoggerFactory.getLogger(SBTSerializer.class);

    private final ObjectMapper objectMapper;

    public SBTSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Exports an SBT file from an existing OMT file.
     *
     * @param params     export parameters containing texture identity and metadata
     * @param omtPath    path to the source .OMT file
     * @param outputPath output file path for the .SBT file
     * @return true if export succeeded
     */
    public boolean export(SBTFormat.ExportParameters params, Path omtPath, String outputPath) {
        if (params == null || !params.isValid()) {
            logger.error("Invalid export parameters: {}",
                    params != null ? params.getValidationError() : "null");
            return false;
        }

        if (omtPath == null || !Files.exists(omtPath)) {
            logger.error("OMT file does not exist: {}", omtPath);
            return false;
        }

        outputPath = SBTFormat.ensureExtension(outputPath);

        try {
            byte[] omtBytes = Files.readAllBytes(omtPath);
            String checksum = computeChecksum(omtBytes);

            SBTFormat.Document document = new SBTFormat.Document(
                    SBTFormat.FORMAT_VERSION,
                    params.getTextureId(),
                    params.getTextureName(),
                    params.getTextureType().getId(),
                    params.getTexturePack(),
                    checksum,
                    params.getAuthor(),
                    params.getDescription().isBlank() ? null : params.getDescription(),
                    Instant.now().toString(),
                    SBTFormat.EMBEDDED_OMT_FILENAME
            );

            Path tempFile = Files.createTempFile("sbt_export_", ".tmp");

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                writeManifest(zos, document);
                writeEntry(zos, SBTFormat.EMBEDDED_OMT_FILENAME, omtBytes);
            }

            Path finalPath = Path.of(outputPath);
            Files.move(tempFile, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            logger.info("Exported .SBT file: {} (checksum={})", outputPath, checksum.substring(0, 12) + "...");
            return true;

        } catch (IOException e) {
            logger.error("Error exporting .SBT file: {}", outputPath, e);
            return false;
        }
    }

    private void writeManifest(ZipOutputStream zos, SBTFormat.Document document) throws IOException {
        ZipEntry manifestEntry = new ZipEntry(SBTFormat.MANIFEST_FILENAME);
        zos.putNextEntry(manifestEntry);

        ManifestDTO dto = new ManifestDTO(document);
        String json = objectMapper.writeValueAsString(dto);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        zos.write(jsonBytes);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote manifest.json ({} bytes)", jsonBytes.length);
    }

    private void writeEntry(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.flush();
        zos.closeEntry();

        logger.debug("Wrote {} ({} bytes)", entryName, data.length);
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBTFormat.CHECKSUM_ALGORITHM);
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Data Transfer Object for JSON serialization of the SBT manifest.
     */
    private static class ManifestDTO {
        public String version;
        public String textureId;
        public String textureName;
        public String textureType;
        public String texturePack;
        public String checksum;
        public String checksumAlgorithm;
        public String author;
        public String description;
        public String createdAt;
        public String omtFile;

        public ManifestDTO(SBTFormat.Document doc) {
            this.version = doc.version();
            this.textureId = doc.textureId();
            this.textureName = doc.textureName();
            this.textureType = doc.textureType();
            this.texturePack = doc.texturePack();
            this.checksum = doc.checksum();
            this.checksumAlgorithm = SBTFormat.CHECKSUM_ALGORITHM;
            this.author = doc.author();
            this.description = doc.description();
            this.createdAt = doc.createdAt();
            this.omtFile = doc.omtFilename();
        }
    }
}
