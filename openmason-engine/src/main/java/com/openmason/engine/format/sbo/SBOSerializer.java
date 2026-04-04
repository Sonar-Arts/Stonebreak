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
import java.util.HexFormat;
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
            byte[] omoBytes = Files.readAllBytes(omoPath);
            String checksum = computeChecksum(omoBytes);

            SBOFormat.Document document = new SBOFormat.Document(
                    SBOFormat.FORMAT_VERSION,
                    params.getObjectId(),
                    params.getObjectName(),
                    params.getObjectType().getId(),
                    params.getObjectPack(),
                    checksum,
                    params.getAuthor(),
                    params.getDescription().isBlank() ? null : params.getDescription(),
                    Instant.now().toString(),
                    SBOFormat.EMBEDDED_OMO_FILENAME
            );

            Path tempFile = Files.createTempFile("sbo_export_", ".tmp");

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                writeManifest(zos, document);
                writeEntry(zos, SBOFormat.EMBEDDED_OMO_FILENAME, omoBytes);
            }

            Path finalPath = Path.of(outputPath);
            Files.move(tempFile, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            logger.info("Exported .SBO file: {} (checksum={})", outputPath, checksum.substring(0, 12) + "...");
            return true;

        } catch (IOException e) {
            logger.error("Error exporting .SBO file: {}", outputPath, e);
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
        }
    }
}
