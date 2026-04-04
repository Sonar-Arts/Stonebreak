package com.openmason.main.systems.rendering.model.io.sbe;

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
 * Serializer for Stonebreak Entity (.SBE) file format.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>{@code manifest.json} - SBE metadata with entity type, checksum, and author signage</li>
 *   <li>{@code model.omo} - Embedded Open Mason Object file</li>
 *   <li>{@code texture.omt} - Embedded Open Mason Texture file</li>
 * </ul>
 *
 * <p>Structurally identical to the SBO serializer with the addition of
 * entity-specific metadata. Will be expanded with further entity data
 * in future versions.
 *
 * @since 1.0
 */
public class SBESerializer {

    private static final Logger logger = LoggerFactory.getLogger(SBESerializer.class);

    private final ObjectMapper objectMapper;

    public SBESerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Exports an SBE file from an existing OMO file.
     *
     * <p>The OMO file already contains its texture data internally (embedded .omt),
     * so no separate texture file is needed.
     *
     * @param params     export parameters containing entity identity and metadata
     * @param omoPath    path to the source .OMO file
     * @param outputPath output file path for the .SBE file
     * @return true if export succeeded
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
                    SBEFormat.EMBEDDED_OMO_FILENAME
            );

            Path tempFile = Files.createTempFile("sbe_export_", ".tmp");

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                writeManifest(zos, document);
                writeEntry(zos, SBEFormat.EMBEDDED_OMO_FILENAME, omoBytes);
            }

            Path finalPath = Path.of(outputPath);
            Files.move(tempFile, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            logger.info("Exported .SBE file: {} (checksum={})", outputPath, checksum.substring(0, 12) + "...");
            return true;

        } catch (IOException e) {
            logger.error("Error exporting .SBE file: {}", outputPath, e);
            return false;
        }
    }

    private void writeManifest(ZipOutputStream zos, SBEFormat.Document document) throws IOException {
        ZipEntry manifestEntry = new ZipEntry(SBEFormat.MANIFEST_FILENAME);
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
            MessageDigest digest = MessageDigest.getInstance(SBEFormat.CHECKSUM_ALGORITHM);
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Data Transfer Object for JSON serialization of the SBE manifest.
     */
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
        }
    }
}
