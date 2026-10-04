package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.SchemaVersion;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.ManifestCodec;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedEntry;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code .sbui} exports headlessly and strictly: the embedded OMUI is decoded and
 * validated with the same rules as a stand-alone document, its digest and every dependency
 * resolution row are checked, and derived caches are compared with their current sources.
 */
public final class SbuiReader {

    /** What to do with a derived cache whose source or compiler changed. */
    public enum StalePolicy {
        /** Fail the read (game runtime default: never run an outdated cache). */
        REJECT,
        /** Warn and list it in {@link Result#staleDerived()} so the caller rebuilds it (editor import). */
        REPORT
    }

    /**
     * @param stalePolicy      handling of stale derived caches
     * @param compilerVersions derived kind → the host's current compiler version; a cache built
     *                         by a different version is stale. Absent kinds are not checked
     */
    public record Options(StalePolicy stalePolicy, Map<DerivedKind, String> compilerVersions, ArchiveLimits limits) {
        public static final Options RUNTIME = new Options(StalePolicy.REJECT, Map.of(), ArchiveLimits.DEFAULT);
        public static final Options EDITOR = new Options(StalePolicy.REPORT, Map.of(), ArchiveLimits.DEFAULT);

        public Options {
            compilerVersions = Map.copyOf(compilerVersions);
        }
    }

    public record Result(SbuiArchive archive, List<DerivedEntry> staleDerived, List<UiDiagnostic> diagnostics) {
    }

    private SbuiReader() {
    }

    public static Result read(Path path, Options options) throws IOException {
        if (Files.size(path) > options.limits().maxTotalBytes()) {
            UiDiagnostics d = new UiDiagnostics();
            d.error(Code.LIMIT_EXCEEDED, "", "", "Archive is too large");
            throw new UiFormatException("Cannot read " + path, d.list());
        }
        return read(Files.readAllBytes(path), options);
    }

    public static Result read(byte[] archive, Options options) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = ArchiveIO.read(archive, options.limits(), d);
        d.throwIfErrors("Cannot open SBUI archive");
        return fromEntries(entries, options, d);
    }

    /** Decode already-extracted entries (pack/unpack). */
    public static Result fromEntries(Map<String, byte[]> entries, Options options) throws UiFormatException {
        return fromEntries(entries, options, new UiDiagnostics());
    }

    private static Result fromEntries(Map<String, byte[]> entries, Options options, UiDiagnostics d)
            throws UiFormatException {
        byte[] manifestBytes = entries.get(SbuiFormat.MANIFEST);
        if (manifestBytes == null) {
            d.error(Code.MISSING_ENTRY, SbuiFormat.MANIFEST, "", "Required entry is missing");
        } else if (manifestBytes.length > options.limits().maxJsonBytes()) {
            d.error(Code.LIMIT_EXCEEDED, SbuiFormat.MANIFEST, "", "Manifest is too large");
        }
        d.throwIfErrors("Cannot read SBUI manifest");
        UiValue json = CanonicalJson.parse(manifestBytes, SbuiFormat.MANIFEST, d);
        d.throwIfErrors("Cannot read SBUI manifest");
        SchemaVersion version = ManifestCodec.peekVersion(json, SbuiFormat.FORMAT_ID, d);
        d.throwIfErrors("Cannot read SBUI manifest");
        negotiate(version, d);
        d.throwIfErrors("Unsupported SBUI schema");
        SbuiManifest manifest = SbuiManifestCodec.read(json, d);
        d.throwIfErrors("Malformed SBUI manifest");

        byte[] sourceBytes = entries.get(manifest.source().entry());
        if (sourceBytes == null) {
            d.error(Code.MISSING_ENTRY, manifest.source().entry(), "", "Embedded source OMUI is missing");
            d.throwIfErrors("Incomplete SBUI archive");
        }
        OmuiArchive source;
        Map<String, UiBytes> sourceEntries = new LinkedHashMap<>();
        try {
            UiDiagnostics nested = new UiDiagnostics();
            Map<String, byte[]> raw = ArchiveIO.read(sourceBytes, options.limits(), nested);
            nested.throwIfErrors("Cannot open embedded OMUI");
            raw.forEach((k, v) -> sourceEntries.put(k, UiBytes.copyOf(v)));
            OmuiReader.Result r = OmuiReader.fromEntries(raw, options.limits());
            source = r.archive();
            d.addAll(r.diagnostics());
        } catch (UiFormatException e) {
            d.error(Code.INVALID_VALUE, manifest.source().entry(), "", "Embedded source OMUI is invalid");
            d.addAll(e.diagnostics());
            throw new UiFormatException("Invalid SBUI source", d.list());
        }

        Map<String, UiBytes> assets = new LinkedHashMap<>();
        Map<String, UiBytes> derived = new LinkedHashMap<>();
        Map<String, UiBytes> extra = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.equals(SbuiFormat.MANIFEST) || name.equals(manifest.source().entry())) {
                continue;
            }
            UiBytes bytes = UiBytes.copyOf(e.getValue());
            if (name.startsWith(SbuiFormat.ASSETS_DIR)) {
                assets.put(name, bytes);
            } else if (name.startsWith(SbuiFormat.DERIVED_DIR)) {
                derived.put(name, bytes);
            } else {
                d.info(Code.UNKNOWN_ENTRY, name, "", "Unrecognized entry preserved verbatim");
                extra.put(name, bytes);
            }
        }
        UiBytes sourceBlob = UiBytes.copyOf(sourceBytes);
        SbuiArchive archive = new SbuiArchive(manifest, source, sourceBlob, assets, derived, extra);
        List<DerivedEntry> stale = SbuiValidator.validate(archive, sourceEntries, options, d);
        d.throwIfErrors("Invalid SBUI archive");
        return new Result(archive, new ArrayList<>(stale), d.list());
    }

    private static void negotiate(SchemaVersion version, UiDiagnostics d) {
        SchemaVersion current = SbuiFormat.SCHEMA_VERSION;
        if (version.major() != current.major()) {
            d.error(Code.UNSUPPORTED_SCHEMA_VERSION, SbuiFormat.MANIFEST, "/schemaVersion",
                    "SBUI schema " + version + " is not readable by this " + current + " reader; re-export from"
                            + " the embedded source");
        } else if (version.minor() > current.minor()) {
            d.warning(Code.NEWER_MINOR_VERSION, SbuiFormat.MANIFEST, "/schemaVersion",
                    "SBUI schema " + version + " is newer than " + current + "; unknown optional data is preserved");
        }
    }
}
