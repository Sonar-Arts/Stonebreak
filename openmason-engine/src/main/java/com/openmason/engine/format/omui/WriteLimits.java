package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.DocumentCodec;
import com.openmason.engine.format.omui.io.GraphCodec;

import java.util.Map;

/**
 * The reader's bounds applied on the writing side (wire contract §3.5: a document that saves
 * always reopens). Rather than restating the rules, it runs the reader's own checks on the
 * canonical entries: entry count and sizes as {@code ArchiveIO} counts them, every JSON entry
 * through {@link CanonicalJson#parse} (nesting, string and number bounds), and the document
 * and graph codecs with their node, depth and graph-size limits.
 */
public final class WriteLimits {

    private WriteLimits() {
    }

    /** Records an error for every bound {@code entries} would break when read back under {@code limits}. */
    public static void check(Map<String, UiBytes> entries, ArchiveLimits limits, UiDiagnostics d) {
        if (entries.size() > limits.maxEntries()) {
            d.error(Code.LIMIT_EXCEEDED, "", "", entries.size() + " entries (limit " + limits.maxEntries() + ")");
        }
        long total = 0;
        for (Map.Entry<String, UiBytes> e : entries.entrySet()) {
            String name = e.getKey();
            long size = e.getValue().size();
            total += size;
            if (size > limits.maxEntryBytes()) {
                d.error(Code.LIMIT_EXCEEDED, name, "", "Entry is " + size + " bytes (limit " + limits.maxEntryBytes() + ")");
            }
            if (!isJson(name)) {
                continue;
            }
            if (size > limits.maxJsonBytes()) {
                d.error(Code.LIMIT_EXCEEDED, name, "", "JSON entry is " + size + " bytes (limit "
                        + limits.maxJsonBytes() + ")");
                continue;
            }
            UiDiagnostics read = new UiDiagnostics();
            UiValue json = CanonicalJson.parse(e.getValue().toArray(), name, read);
            if (json != null) {
                String graph;
                if (name.equals(OmuiFormat.DOCUMENT)) {
                    DocumentCodec.read(json, limits, read);
                } else if ((graph = OmuiReader.part(name, OmuiFormat.GRAPHS_DIR, OmuiFormat.GRAPH_SUFFIX)) != null) {
                    GraphCodec.read(graph, name, json, limits, read);
                }
            }
            // Only the bounds matter here: content problems were the validator's to report.
            for (UiDiagnostic x : read.list()) {
                if (x.isError() && x.code() == Code.LIMIT_EXCEEDED) {
                    d.error(Code.LIMIT_EXCEEDED, x.entry(), x.pointer(), x.message());
                }
            }
        }
        if (total > limits.maxTotalBytes()) {
            d.error(Code.LIMIT_EXCEEDED, "", "", "Entries total " + total + " bytes (limit " + limits.maxTotalBytes() + ")");
        }
    }

    /**
     * The file-size gate of {@code OmuiReader.read(Path)} / {@code SbuiReader.read(Path)}: the
     * archive itself (headers included) must not exceed {@code maxTotalBytes}.
     */
    public static void checkArchiveSize(byte[] archive, ArchiveLimits limits, String what) throws UiFormatException {
        if (archive.length > limits.maxTotalBytes()) {
            UiDiagnostics d = new UiDiagnostics();
            d.error(Code.LIMIT_EXCEEDED, "", "", "Archive would be " + archive.length + " bytes (limit "
                    + limits.maxTotalBytes() + ")");
            d.throwIfErrors("Refusing to write an " + what + " archive the reader could not reopen");
        }
    }

    /** Entries the readers decode as JSON (everything else is read as opaque bytes). */
    static boolean isJson(String name) {
        return name.equals(OmuiFormat.MANIFEST) || name.equals(OmuiFormat.DOCUMENT)
                || name.equals(OmuiFormat.DEPENDENCIES)
                || OmuiReader.part(name, OmuiFormat.STYLES_DIR, OmuiFormat.STYLE_SUFFIX) != null
                || OmuiReader.part(name, OmuiFormat.GRAPHS_DIR, OmuiFormat.GRAPH_SUFFIX) != null
                || OmuiReader.part(name, OmuiFormat.ANIMATIONS_DIR, OmuiFormat.ANIMATION_SUFFIX) != null
                || OmuiReader.part(name, OmuiFormat.ANIMATIONS_DIR, OmuiFormat.STATE_MACHINE_SUFFIX) != null;
    }
}
