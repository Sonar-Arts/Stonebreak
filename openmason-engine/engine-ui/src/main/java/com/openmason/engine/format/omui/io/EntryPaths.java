package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Archive entry-name rules. A valid name is a relative, already-normalized, forward-slash
 * path that extracts identically on every supported filesystem, so pack/unpack can never
 * escape its directory or collide on a case-insensitive disk.
 */
public final class EntryPaths {

    public static final int MAX_NAME_BYTES = 255;

    /** Canonical entry order: {@code manifest.json} first, then code-point order. */
    public static final Comparator<String> ORDER = (a, b) -> {
        boolean am = a.equals("manifest.json");
        boolean bm = b.equals("manifest.json");
        if (am || bm) {
            return am == bm ? 0 : (am ? -1 : 1);
        }
        return UiValue.KEY_ORDER.compare(a, b);
    };

    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private EntryPaths() {
    }

    /** @return {@code null} when {@code name} is a valid file entry name, else the reason */
    public static String problem(String name) {
        if (name == null || name.isEmpty()) {
            return "empty name";
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            return "longer than " + MAX_NAME_BYTES + " bytes";
        }
        if (name.startsWith("/")) {
            return "absolute path";
        }
        if (name.endsWith("/")) {
            return "directory, not a file";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || "\\:*?\"<>|".indexOf(c) >= 0) {
                return "illegal character U+" + String.format("%04X", (int) c);
            }
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty()) {
                return "empty path segment";
            }
            if (segment.equals(".") || segment.equals("..")) {
                return "'" + segment + "' segment (path traversal)";
            }
            if (segment.startsWith(".")) {
                return "hidden segment '" + segment + "' (source directories ignore dot-files)";
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return "segment ends with '.' or space";
            }
            String stem = segment.toLowerCase(Locale.ROOT);
            int dot = stem.indexOf('.');
            if (WINDOWS_DEVICES.contains(dot < 0 ? stem : stem.substring(0, dot))) {
                return "reserved device name '" + segment + "'";
            }
        }
        return null;
    }

    /**
     * Checks a complete set of entry names: each must be valid, no two may collide
     * case-insensitively, and no name may also be a directory of another
     * ({@code assets/a} and {@code assets/a/b}), since such a set cannot be extracted.
     */
    public static void checkAll(Collection<String> names, UiDiagnostics d) {
        Map<String, String> folded = new HashMap<>();
        for (String name : names) {
            String problem = problem(name);
            if (problem != null) {
                d.error(Code.UNSAFE_ENTRY_PATH, name, "", problem);
                continue;
            }
            String clash = folded.putIfAbsent(collisionKey(name), name);
            if (clash != null) {
                d.error(Code.DUPLICATE_ENTRY, name, "", clash.equals(name)
                        ? "Entry appears more than once" : "Collides with '" + clash + "' on a case-insensitive filesystem");
            }
        }
        for (String key : folded.keySet()) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                String dir = folded.get(key.substring(0, slash));
                if (dir != null) {
                    d.error(Code.DUPLICATE_ENTRY, dir, "", "Is both a file and the directory of '" + folded.get(key) + "'");
                }
            }
        }
    }

    /** Key two names collide on when extracted to a case-insensitive filesystem. */
    public static String collisionKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
