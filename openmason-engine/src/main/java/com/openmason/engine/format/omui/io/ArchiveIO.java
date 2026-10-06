package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bounded ZIP reading and deterministic ZIP writing for OMUI/SBUI containers.
 *
 * <p>Reading streams local entries with inflated-size limits, validates every name
 * ({@link EntryPaths}), rejects exact and case-folded duplicates, and cross-checks the
 * local entries against the central directory so truncation or smuggled entries cannot
 * silently drop or add content. ZIP64 is out of scope (archives are far below 4 GiB).
 *
 * <p>Writing emits entries in {@link EntryPaths#ORDER}, uncompressed ({@code STORED}), with a
 * fixed DOS timestamp of 1980-01-01 00:00:00, no extra fields and no comments. Without a
 * compressor in the loop the bytes are a pure function of the entries, independent of the
 * platform's zlib build — which is what lets golden archives be compared byte for byte. UI
 * documents are small JSON; embedded assets (SBT/OMT/PNG) are already compressed.
 */
public final class ArchiveIO {

    /**
     * Fixed entry timestamp, two seconds past the DOS epoch. Not 00:00:00: the JDK encodes exactly
     * 1980-01-01 00:00:00 as its "before 1980" sentinel and then also writes a {@code UT} extended
     * timestamp holding epoch seconds in the system timezone, which made archive bytes differ
     * between machines (a UTC CI runner vs a UTC-5 workstation). Any later in-range local time is
     * stored as plain DOS date/time with no extra field, so the bytes are the same everywhere.
     */
    public static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

    private static final int LOCAL_SIG = 0x04034b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int END_SIG = 0x06054b50;

    private ArchiveIO() {
    }

    /**
     * @return entry name → bytes in archive order, or {@code null} after recording an error
     * that makes the archive unusable
     */
    public static Map<String, byte[]> read(byte[] archive, ArchiveLimits limits, UiDiagnostics diagnostics) {
        if (archive.length < 4 || le32(archive, 0) != LOCAL_SIG) {
            if (archive.length >= 4 && le32(archive, 0) == END_SIG) {
                return new LinkedHashMap<>(); // empty ZIP: reported as missing manifest later
            }
            diagnostics.error(Code.NOT_AN_ARCHIVE, "", "", "Not a ZIP archive");
            return null;
        }
        List<CentralEntry> central = centralDirectory(archive, limits, diagnostics);
        if (central == null) {
            return null;
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, String> folded = new HashMap<>();
        List<String> local = new ArrayList<>();
        Map<String, Integer> sizes = new HashMap<>();
        Map<String, Long> crcs = new HashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                local.add(name);
                if (local.size() > limits.maxEntries()) {
                    diagnostics.error(Code.LIMIT_EXCEEDED, "", "", "More than " + limits.maxEntries() + " entries");
                    return null;
                }
                if (entry.isDirectory()) {
                    String problem = EntryPaths.problem(name.substring(0, name.length() - 1));
                    if (problem != null) {
                        diagnostics.error(Code.UNSAFE_ENTRY_PATH, name, "", problem);
                        return null;
                    }
                    continue;
                }
                String problem = EntryPaths.problem(name);
                if (problem != null) {
                    diagnostics.error(Code.UNSAFE_ENTRY_PATH, name, "", problem);
                    return null;
                }
                String previous = folded.putIfAbsent(EntryPaths.collisionKey(name), name);
                if (previous != null) {
                    diagnostics.error(Code.DUPLICATE_ENTRY, name, "", previous.equals(name)
                            ? "Entry appears more than once"
                            : "Collides with '" + previous + "' on a case-insensitive filesystem");
                    return null;
                }
                byte[] data = readBounded(zis, Math.min(limits.maxEntryBytes(), limits.maxTotalBytes() - total));
                if (data == null) {
                    diagnostics.error(Code.LIMIT_EXCEEDED, name, "", "Entry exceeds the size limit");
                    return null;
                }
                total += data.length;
                entries.put(name, data);
                sizes.put(name, data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                crcs.put(name, crc.getValue());
            }
        } catch (ZipException | EOFException e) {
            diagnostics.error(Code.TRUNCATED_ARCHIVE, "", "", "Corrupt or truncated archive: " + e.getMessage());
            return null;
        } catch (IOException e) {
            diagnostics.error(Code.TRUNCATED_ARCHIVE, "", "", "Unreadable archive: " + e.getMessage());
            return null;
        }
        EntryPaths.checkAll(entries.keySet(), diagnostics); // file/directory overlaps
        if (diagnostics.hasErrors()) {
            return null;
        }
        if (!local.equals(central.stream().map(CentralEntry::name).toList())) {
            diagnostics.error(Code.TRUNCATED_ARCHIVE, "", "",
                    "Local entries do not match the central directory (" + local.size() + " vs " + central.size() + ")");
            return null;
        }
        // A central-directory reader (ZipFile, a C++ reader) must see exactly the bytes we read.
        for (CentralEntry c : central) {
            Integer size = sizes.get(c.name());
            if (size != null && (size != c.size() || crcs.get(c.name()) != c.crc())) {
                diagnostics.error(Code.TRUNCATED_ARCHIVE, c.name(), "",
                        "Entry content differs from its central-directory record");
                return null;
            }
        }
        return entries;
    }

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            n += r;
            if (n > max) {
                return null;
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private record CentralEntry(String name, long crc, long size) {
    }

    /** Central-directory records, in order; {@code null} on damage. */
    private static List<CentralEntry> centralDirectory(byte[] a, ArchiveLimits limits, UiDiagnostics d) {
        int eocd = -1;
        for (int i = a.length - 22; i >= Math.max(0, a.length - 22 - 0xFFFF); i--) {
            if (le32(a, i) == END_SIG) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            d.error(Code.TRUNCATED_ARCHIVE, "", "", "No end-of-central-directory record (truncated archive)");
            return null;
        }
        int count = le16(a, eocd + 10);
        long size = le32(a, eocd + 12) & 0xFFFFFFFFL;
        long offset = le32(a, eocd + 16) & 0xFFFFFFFFL;
        if (count == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
            d.error(Code.LIMIT_EXCEEDED, "", "", "ZIP64 archives are not supported");
            return null;
        }
        if (count > limits.maxEntries()) {
            d.error(Code.LIMIT_EXCEEDED, "", "", "More than " + limits.maxEntries() + " entries");
            return null;
        }
        if (offset + size > eocd) {
            d.error(Code.TRUNCATED_ARCHIVE, "", "", "Central directory lies outside the archive");
            return null;
        }
        List<CentralEntry> names = new ArrayList<>(count);
        int p = (int) offset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > eocd || le32(a, p) != CENTRAL_SIG) {
                d.error(Code.TRUNCATED_ARCHIVE, "", "", "Damaged central directory");
                return null;
            }
            int nameLen = le16(a, p + 28);
            int extraLen = le16(a, p + 30);
            int commentLen = le16(a, p + 32);
            if (p + 46 + nameLen > eocd) {
                d.error(Code.TRUNCATED_ARCHIVE, "", "", "Damaged central directory");
                return null;
            }
            names.add(new CentralEntry(new String(a, p + 46, nameLen, StandardCharsets.UTF_8),
                    le32(a, p + 16) & 0xFFFFFFFFL, le32(a, p + 24) & 0xFFFFFFFFL));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return names;
    }

    private static int le16(byte[] a, int i) {
        return (a[i] & 0xFF) | (a[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] a, int i) {
        return (a[i] & 0xFF) | (a[i + 1] & 0xFF) << 8 | (a[i + 2] & 0xFF) << 16 | (a[i + 3] & 0xFF) << 24;
    }

    /** Deterministic archive bytes for {@code entries} (any input order). */
    public static byte[] write(Map<String, UiBytes> entries) {
        TreeMap<String, UiBytes> ordered = new TreeMap<>(EntryPaths.ORDER);
        ordered.putAll(entries);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Map<String, String> folded = new HashMap<>();
        try (ZipOutputStream zos = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, UiBytes> e : ordered.entrySet()) {
                String problem = EntryPaths.problem(e.getKey());
                if (problem != null) {
                    throw new IllegalArgumentException("Invalid entry name '" + e.getKey() + "': " + problem);
                }
                String clash = folded.putIfAbsent(EntryPaths.collisionKey(e.getKey()), e.getKey());
                if (clash != null) {
                    throw new IllegalArgumentException("Entries '" + clash + "' and '" + e.getKey()
                            + "' collide on a case-insensitive filesystem");
                }
                byte[] data = e.getValue().toArray();
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(FIXED_TIME);
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("In-memory ZIP write failed", e);
        }
        return bytes.toByteArray();
    }
}
