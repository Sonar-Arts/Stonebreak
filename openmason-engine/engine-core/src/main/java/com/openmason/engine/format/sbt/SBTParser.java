package com.openmason.engine.format.sbt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reader for Stonebreak Texture (.SBT) archives.
 *
 * <p>Counterpart to {@link SBTSerializer}. Pulls the manifest metadata and
 * the embedded {@code texture.omt} bytes out of an SBT ZIP container. The
 * embedded OMT is returned as raw bytes so callers can hand it to
 * {@code OMTReader} without writing it back to disk.
 */
public final class SBTParser {

    private static final Logger logger = LoggerFactory.getLogger(SBTParser.class);

    /**
     * Result of a successful SBT read: the parsed manifest plus the embedded
     * OMT file bytes.
     */
    public record Result(SBTFormat.Document manifest, byte[] omtBytes) {}

    private final ObjectMapper objectMapper;

    public SBTParser() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Read an SBT archive from a filesystem path. */
    public Result read(Path sbtPath) throws IOException {
        if (sbtPath == null || !Files.exists(sbtPath)) {
            throw new IOException("SBT file does not exist: " + sbtPath);
        }
        try (InputStream in = Files.newInputStream(sbtPath)) {
            return read(in);
        }
    }

    /** Read an SBT archive from raw ZIP bytes. */
    public Result read(byte[] sbtBytes) throws IOException {
        if (sbtBytes == null || sbtBytes.length == 0) {
            throw new IOException("SBT data is empty");
        }
        try (InputStream in = new ByteArrayInputStream(sbtBytes)) {
            return read(in);
        }
    }

    /**
     * Read an SBT archive from an arbitrary input stream.
     * Caller owns the stream — this method does not close it.
     */
    public Result read(InputStream in) throws IOException {
        byte[] manifestBytes = null;
        byte[] omtBytes = null;

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = readEntry(zis);
                if (SBTFormat.MANIFEST_FILENAME.equals(name)) {
                    manifestBytes = data;
                } else if (SBTFormat.EMBEDDED_OMT_FILENAME.equals(name)) {
                    omtBytes = data;
                }
                zis.closeEntry();
            }
        }

        if (manifestBytes == null) {
            throw new IOException("SBT archive is missing " + SBTFormat.MANIFEST_FILENAME);
        }
        if (omtBytes == null) {
            throw new IOException("SBT archive is missing " + SBTFormat.EMBEDDED_OMT_FILENAME);
        }

        ManifestDTO dto = objectMapper.readValue(manifestBytes, ManifestDTO.class);
        SBTFormat.Document manifest = dto.toDocument();

        logger.debug("Read SBT '{}' ({} bytes embedded OMT)",
                manifest.textureName(), omtBytes.length);
        return new Result(manifest, omtBytes);
    }

    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = zis.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    /** Mirror of the manifest written by {@link SBTSerializer}. */
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

        SBTFormat.Document toDocument() {
            return new SBTFormat.Document(
                    version != null ? version : SBTFormat.FORMAT_VERSION,
                    textureId != null ? textureId : "",
                    textureName != null ? textureName : "",
                    textureType != null ? textureType : SBTFormat.TextureType.OTHER.getId(),
                    texturePack != null ? texturePack : "default",
                    checksum != null ? checksum : "",
                    author != null ? author : "",
                    description,
                    createdAt != null ? createdAt : "",
                    omtFile != null ? omtFile : SBTFormat.EMBEDDED_OMT_FILENAME);
        }
    }
}
