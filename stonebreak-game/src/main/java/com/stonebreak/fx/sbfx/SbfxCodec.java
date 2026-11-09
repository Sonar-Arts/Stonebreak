package com.stonebreak.fx.sbfx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.format.ext.ManifestJson;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes {@code .sbfx} (Stonebreak#435), Stonebreak's shipped FX container.
 *
 * <h2>Container</h2>
 * A zip archive:
 * <pre>
 * manifest.json   { "format": "stonebreak.fx", "version": 1,
 *                   "objectId": "stonebreak:block_debris", "fxId": "block_debris", "author": "...",
 *                   "engineFormat": "openmason.fx", "engineVersion": 2,
 *                   "checksum": "&lt;sha-256 of the payload&gt;", "checksumAlgorithm": "SHA-256",
 *                   "extensions": { "stonebreak:...": { game data, kept as written } } }
 * system.json     ┐
 * graphs/*.json   │ the payload: exactly the entries of the engine's .omfx v2 archive
 * textures/*      │ (OmfxCodec) other than its manifest — the neutral compiled form the
 * sounds/*        ┘ engine runtime reads, sprites and samples embedded
 * </pre>
 * The payload is the engine's own format on purpose: the game never re-encodes an FX system, so whatever the
 * engine can author it can ship. The checksum covers every payload entry (name and bytes, in name order), so a
 * damaged or hand-edited archive is refused instead of half-loaded.
 *
 * <p>Writing is deterministic (fixed entry times, payload in the engine's order): the same document gives the same
 * bytes.
 */
public final class SbfxCodec {

    public static final String FORMAT = "stonebreak.fx";
    public static final int VERSION = 1;
    public static final String FILE_EXTENSION = ".sbfx";
    public static final String MANIFEST = "manifest.json";
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /** Manifest members this codec owns; everything else is an extension (raw-preserved). */
    static final Set<String> CORE_FIELDS = Set.of("format", "version", "objectId", "fxId", "author",
            "engineFormat", "engineVersion", "checksum", "checksumAlgorithm", ManifestJson.EXTENSIONS_FIELD);

    private static final long MAX_ENTRY_BYTES = 64L << 20;
    private static final long MAX_TOTAL_BYTES = 256L << 20;
    private static final int MAX_ENTRIES = 4096;
    private static final long ENTRY_TIME = 315_532_800_000L; // 1980-01-01T00:00:00Z, the zip epoch
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SbfxCodec() {
    }

    // ------------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------------

    public static byte[] write(SbfxDocument doc) throws IOException {
        Map<String, byte[]> engine = readEntries(new ByteArrayInputStream(OmfxCodec.write(doc.fx())));
        engine.remove(OmfxCodec.MANIFEST);

        ObjectNode core = MAPPER.createObjectNode();
        core.put("format", FORMAT);
        core.put("version", VERSION);
        core.put("objectId", doc.objectId());
        core.put("fxId", doc.fxId());
        core.put("author", doc.fx().author());
        core.put("engineFormat", OmfxCodec.FORMAT);
        core.put("engineVersion", OmfxCodec.VERSION);
        core.put("checksum", checksum(engine));
        core.put("checksumAlgorithm", CHECKSUM_ALGORITHM);
        String manifest = ManifestJson.write(core, doc.fx().extensions());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : engine.entrySet()) {
                put(zip, e.getKey(), e.getValue());
            }
        }
        return out.toByteArray();
    }

    /** Writes through a sibling temp file, so a failed write leaves the old file. */
    public static void write(SbfxDocument doc, Path file) throws IOException {
        byte[] bytes = write(doc);
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, abs.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------

    public static SbfxDocument read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    public static SbfxDocument read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    /** Reads a document; the stream is read to its end but not closed. Fails with a reason. */
    public static SbfxDocument read(InputStream in) throws IOException {
        Map<String, byte[]> entries = readEntries(in);
        byte[] manifestBytes = entries.remove(MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("not an .sbfx archive: " + MANIFEST + " is missing");
        }
        String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
        JsonNode manifest;
        try {
            manifest = MAPPER.readTree(manifestText);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException(MANIFEST + ": not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (manifest == null || !manifest.isObject()) {
            throw new IOException(MANIFEST + " must be a JSON object");
        }
        String format = manifest.path("format").asText("");
        if (!FORMAT.equals(format)) {
            throw new IOException(MANIFEST + ": format must be '" + FORMAT + "', got '" + format + "'");
        }
        JsonNode version = manifest.get("version");
        if (version == null || !version.isInt()) {
            throw new IOException(MANIFEST + ": 'version' must be an integer");
        }
        if (version.intValue() > VERSION || version.intValue() < 1) {
            throw new IOException(".sbfx version " + version.intValue() + " is not supported (this game reads "
                    + VERSION + ")");
        }
        String objectId = text(manifest, "objectId");
        String fxId = text(manifest, "fxId");
        int engineVersion = manifest.path("engineVersion").asInt(OmfxCodec.VERSION);
        if (engineVersion > OmfxCodec.VERSION) {
            throw new IOException(objectId + ": FX payload version " + engineVersion
                    + " is newer than this engine reads (" + OmfxCodec.VERSION + ")");
        }
        String algorithm = manifest.path("checksumAlgorithm").asText(CHECKSUM_ALGORITHM);
        if (!CHECKSUM_ALGORITHM.equalsIgnoreCase(algorithm)) {
            throw new IOException(objectId + ": unsupported checksum algorithm " + algorithm);
        }
        String expected = text(manifest, "checksum");
        String actual = checksum(entries);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException(objectId + ": checksum mismatch (archive damaged or edited by hand): expected "
                    + expected + ", payload is " + actual);
        }
        FormatExtensions extensions;
        try {
            extensions = ManifestJson.readExtensions(manifestText, CORE_FIELDS);
        } catch (RuntimeException e) {
            throw new IOException(MANIFEST + ": unreadable extensions: " + e.getMessage(), e);
        }

        ObjectNode engineCore = MAPPER.createObjectNode();
        engineCore.put("format", OmfxCodec.FORMAT);
        engineCore.put("version", OmfxCodec.VERSION);
        engineCore.put("id", fxId);
        engineCore.put("author", manifest.path("author").asText(""));
        Map<String, byte[]> engine = new LinkedHashMap<>();
        engine.put(OmfxCodec.MANIFEST, ManifestJson.write(engineCore, extensions).getBytes(StandardCharsets.UTF_8));
        engine.putAll(entries);
        OmfxDocument fx;
        try {
            fx = OmfxCodec.read(zip(engine));
        } catch (IOException e) {
            throw new IOException(objectId + ": " + e.getMessage(), e);
        }
        try {
            return new SbfxDocument(objectId, fx);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------

    /** SHA-256 over every payload entry in name order: name, a zero byte, the length (8 bytes), the bytes. */
    static String checksum(Map<String, byte[]> payload) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(CHECKSUM_ALGORITHM + " is not available", e);
        }
        List<String> names = new ArrayList<>(payload.keySet());
        names.sort(null);
        for (String name : names) {
            byte[] data = payload.get(name);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            long n = data.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (n >>> shift));
            }
            digest.update(data);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String text(JsonNode manifest, String field) throws IOException {
        JsonNode node = manifest.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IOException(MANIFEST + ": '" + field + "' must be a non-blank string");
        }
        return node.asText();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                put(zip, e.getKey(), e.getValue());
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static Map<String, byte[]> readEntries(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("..")) {
                    throw new IOException("unsafe .sbfx entry name '" + name + "'");
                }
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IOException(".sbfx has more than " + MAX_ENTRIES + " entries");
                }
                byte[] data = readLimited(zip, name, Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total));
                total += data.length;
                if (entries.put(name, data) != null) {
                    throw new IOException(".sbfx entry '" + name + "' appears twice");
                }
            }
        } catch (ZipException | java.io.EOFException e) {
            throw new IOException("damaged .sbfx archive: " + e.getMessage(), e);
        }
        if (entries.isEmpty()) {
            throw new IOException("not an .sbfx archive (empty or not a zip)");
        }
        return entries;
    }

    private static byte[] readLimited(ZipInputStream zip, String name, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long size = 0;
        int n;
        while ((n = zip.read(buf)) != -1) {
            size += n;
            if (size > limit) {
                throw new IOException(".sbfx entry '" + name + "' is too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
