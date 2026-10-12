package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.UiBytes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implementation-independent content digest of an archive: SHA-256 over, for every entry in
 * {@link EntryPaths#ORDER}, the UTF-8 entry name, a NUL byte, the lowercase hex SHA-256 of
 * the entry bytes and an LF. Independent of ZIP compression, timestamps and entry order, so
 * a C++ writer that packs the same entries yields the same digest.
 */
public final class ArchiveDigest {

    private ArchiveDigest() {
    }

    public static String of(Map<String, UiBytes> entries) {
        TreeMap<String, UiBytes> ordered = new TreeMap<>(EntryPaths.ORDER);
        ordered.putAll(entries);
        StringBuilder sb = new StringBuilder();
        ordered.forEach((name, bytes) -> sb.append(name).append('\0').append(bytes.sha256()).append('\n'));
        return UiBytes.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
