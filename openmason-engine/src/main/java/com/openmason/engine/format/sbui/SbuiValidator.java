package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.omui.io.ArchiveDigest;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedEntry;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consistency of an SBUI with its embedded OMUI: source digest, dependency resolution rows,
 * requirement unions, component recursion and derived-cache freshness. Shared by the
 * exporter (before writing) and the reader (after decoding).
 */
final class SbuiValidator {

    private static final String E = SbuiFormat.MANIFEST;

    private SbuiValidator() {
    }

    /** @return derived entries that are stale (source or compiler changed) */
    static List<DerivedEntry> validate(SbuiArchive a, Map<String, UiBytes> sourceEntries,
                                       SbuiReader.Options options, UiDiagnostics d) {
        SbuiManifest m = a.manifest();
        OmuiArchive src = a.source();
        if (!OmuiFormat.LOGICAL_ID.matcher(m.assetId()).matches()) {
            d.error(Code.INVALID_ID, E, "/assetId", "Invalid asset id '" + m.assetId() + "'");
        }
        if (!m.entry().equals(src.manifest().documentId())) {
            d.error(Code.INCONSISTENT_MANIFEST, E, "/entry", "Entry '" + m.entry() + "' is not the embedded document '"
                    + src.manifest().documentId() + "'");
        }
        if (!m.source().entry().startsWith(SbuiFormat.SOURCE_DIR) || !m.source().entry().endsWith(OmuiFormat.FILE_EXTENSION)) {
            d.error(Code.INVALID_VALUE, E, "/source/entry", "Source must be source/<name>.omui");
        }
        if (!m.source().schemaVersion().equals(src.manifest().schemaVersion())) {
            d.error(Code.INCONSISTENT_MANIFEST, E, "/source/schemaVersion", "Does not match the embedded OMUI");
        }
        if (!ArchiveDigest.of(sourceEntries).equals(m.source().digest())) {
            d.error(Code.HASH_MISMATCH, m.source().entry(), "", "Embedded OMUI does not match the recorded digest");
        }
        entryNames(a, d);
        Set<String> sbuiEntries = dependencies(a, d);
        for (String entry : a.assets().keySet()) {
            if (!sbuiEntries.contains(entry)) {
                d.warning(Code.ORPHAN_ENTRY, entry, "", "No dependency row references this asset; preserved");
            }
        }
        Map<String, OmuiArchive> components = components(a, options.limits(), d);
        List<UiRequirements> docs = new ArrayList<>();
        docs.add(src.manifest());
        components.values().forEach(c -> docs.add(c.manifest()));
        Requirements.union(docs, d).checkCoveredBy(m, d);
        OmuiValidator.requiredFeatures(m.requires(), d);
        OmuiValidator.componentCycles(src, components::get, d);
        return derived(a, sourceEntries, options, d);
    }

    private static Set<String> dependencies(SbuiArchive a, UiDiagnostics d) {
        OmuiArchive src = a.source();
        Set<String> sbuiEntries = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<SbuiDependency> rows = a.manifest().dependencies();
        for (int i = 0; i < rows.size(); i++) {
            SbuiDependency row = rows.get(i);
            String ptr = "/dependencies/" + i;
            if (!seen.add(row.id())) {
                d.error(Code.DUPLICATE_ID, E, ptr + "/id", "'" + row.id() + "' has more than one resolution row");
                continue;
            }
            UiDependency origin = src.dependencies().find(row.id());
            if (origin == null) {
                d.error(Code.INCONSISTENT_MANIFEST, E, ptr, "'" + row.id() + "' is not a dependency of the source");
                continue;
            }
            if (row.kind() != origin.kind() || !Objects.equals(row.version(), origin.version())
                    || !row.requires().equals(origin.requires()) || row.optional() != origin.optional()
                    || !Objects.equals(row.fallback(), origin.fallback())
                    || !Objects.equals(row.license(), origin.license())) {
                d.error(Code.INCONSISTENT_MANIFEST, E, ptr, "'" + row.id() + "' disagrees with its source row");
            }
            if (origin.mode() == UiDependency.Mode.EMBEDDED) {
                if (row.mode() != UiDependency.Mode.EMBEDDED || row.location() != Location.SOURCE
                        || !origin.entry().equals(row.entry()) || !origin.sha256().equals(row.sha256())) {
                    d.error(Code.INCONSISTENT_MANIFEST, E, ptr, "Source-embedded '" + row.id()
                            + "' must resolve to its source entry with the same hash");
                }
            } else if (row.mode() == UiDependency.Mode.SHARED) {
                if (row.location() != null || row.entry() != null) {
                    d.error(Code.INVALID_VALUE, E, ptr, "Shared rows have no location or entry");
                }
                if (!row.sha256().equals(origin.sha256()) || row.size() != origin.size()) {
                    d.error(Code.INCONSISTENT_MANIFEST, E, ptr, "Shared '" + row.id() + "' hash/size differs from the source");
                }
            } else {
                if (row.location() != Location.SBUI || row.entry() == null
                        || !row.entry().startsWith(SbuiFormat.ASSETS_DIR)) {
                    d.error(Code.INVALID_VALUE, E, ptr, "Collected rows live under the SBUI's assets/");
                    continue;
                }
                sbuiEntries.add(row.entry());
                UiBytes bytes = a.assets().get(row.entry());
                if (bytes == null) {
                    d.error(Code.MISSING_ENTRY, E, ptr + "/entry", "Collected snapshot '" + row.entry() + "' is missing");
                } else if (!bytes.sha256().equals(row.sha256())) {
                    d.error(Code.HASH_MISMATCH, row.entry(), "", "Bytes do not match the hash recorded for '" + row.id() + "'");
                }
            }
        }
        for (UiDependency origin : src.dependencies().entries()) {
            if (!seen.contains(origin.id())) {
                d.error(Code.INCONSISTENT_MANIFEST, E, "/dependencies",
                        "Source dependency '" + origin.id() + "' has no resolution row");
            }
        }
        return sbuiEntries;
    }

    /** Every entry the writer would emit must be a safe, collision-free archive name. */
    private static void entryNames(SbuiArchive a, UiDiagnostics d) {
        List<String> names = new ArrayList<>(List.of(SbuiFormat.MANIFEST, a.manifest().source().entry()));
        names.addAll(a.assets().keySet());
        names.addAll(a.derived().keySet());
        names.addAll(a.extraEntries().keySet());
        EntryPaths.checkAll(names, d);
    }

    /**
     * Component documents whose bytes travel with the export, decoded once per distinct entry,
     * by dependency id. Rows that are malformed or disagree with the source are skipped here;
     * {@link #dependencies} reports them. All components together inflate within
     * {@code limits.maxTotalBytes()}: each one reads under what the previous ones left.
     */
    static Map<String, OmuiArchive> components(SbuiArchive a, ArchiveLimits limits, UiDiagnostics d) {
        long remaining = limits.maxTotalBytes();
        Map<String, OmuiArchive> out = new LinkedHashMap<>();
        Map<String, OmuiArchive> parsed = new HashMap<>();
        for (SbuiDependency row : a.manifest().dependencies()) {
            if (row.kind() != UiDependency.Kind.COMPONENT || row.mode() != UiDependency.Mode.EMBEDDED
                    || row.location() == null || row.entry() == null || a.source().dependencies().find(row.id()) == null) {
                continue;
            }
            String key = row.location().wire() + ":" + row.entry();
            if (parsed.containsKey(key)) {
                if (parsed.get(key) != null) {
                    out.put(row.id(), parsed.get(key));
                }
                continue;
            }
            UiBytes bytes = row.location() == Location.SOURCE ? a.source().assets().get(row.entry())
                    : a.assets().get(row.entry());
            OmuiArchive doc = null;
            if (bytes != null) {
                try {
                    ArchiveLimits budget = limits.withRemainingTotal(remaining);
                    UiDiagnostics nested = new UiDiagnostics();
                    Map<String, byte[]> raw = ArchiveIO.read(bytes.toArray(), budget, nested);
                    nested.throwIfErrors("Cannot open embedded component");
                    for (byte[] entry : raw.values()) {
                        remaining -= entry.length;
                    }
                    doc = OmuiReader.fromEntries(raw, budget).archive();
                } catch (UiFormatException e) {
                    d.error(Code.INVALID_VALUE, row.entry(), "", "Embedded component '" + row.id() + "' is invalid");
                    d.addAll(e.diagnostics());
                }
            }
            parsed.put(key, doc);
            if (doc != null) {
                out.put(row.id(), doc);
            }
        }
        return out;
    }

    private static List<DerivedEntry> derived(SbuiArchive a, Map<String, UiBytes> sourceEntries,
                                              SbuiReader.Options options, UiDiagnostics d) {
        List<DerivedEntry> stale = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        List<DerivedEntry> rows = a.manifest().derived();
        for (int i = 0; i < rows.size(); i++) {
            DerivedEntry x = rows.get(i);
            String ptr = "/derived/" + i;
            listed.add(x.entry());
            if (!x.entry().startsWith(SbuiFormat.DERIVED_DIR)) {
                d.error(Code.INVALID_VALUE, E, ptr + "/entry", "Derived caches live under derived/");
                continue;
            }
            UiBytes bytes = a.derived().get(x.entry());
            if (bytes == null) {
                d.error(Code.MISSING_ENTRY, E, ptr + "/entry", "Derived cache '" + x.entry() + "' is missing");
                continue;
            }
            if (!bytes.sha256().equals(x.sha256())) {
                d.error(Code.HASH_MISMATCH, x.entry(), "", "Derived bytes do not match their recorded hash");
            }
            String current = currentSourceHash(a, sourceEntries, x.source());
            if (current == null) {
                d.error(Code.UNRESOLVED_REFERENCE, E, ptr + "/source", "No source '" + x.source() + "'");
                continue;
            }
            String expectedCompiler = options.compilerVersions().get(x.kind());
            boolean sourceChanged = !current.equals(x.sourceSha256());
            boolean compilerChanged = expectedCompiler != null && !expectedCompiler.equals(x.compilerVersion());
            if (sourceChanged || compilerChanged) {
                stale.add(x);
                String why = sourceChanged ? "its source changed" : "compiler " + x.compiler() + " is now "
                        + expectedCompiler + ", cache built by " + x.compilerVersion();
                if (options.stalePolicy() == SbuiReader.StalePolicy.REJECT) {
                    d.error(Code.STALE_DERIVED, x.entry(), "", "Stale derived cache: " + why);
                } else {
                    d.warning(Code.STALE_DERIVED, x.entry(), "", "Stale derived cache (rebuild): " + why);
                }
            }
        }
        for (String entry : a.derived().keySet()) {
            if (!listed.contains(entry)) {
                d.warning(Code.ORPHAN_ENTRY, entry, "", "Derived entry not listed in the manifest; preserved");
            }
        }
        return stale;
    }

    /** SHA-256 of a derived cache's source as it is now, or {@code null} if it does not exist. */
    static String currentSourceHash(SbuiArchive a, Map<String, UiBytes> sourceEntries, String source) {
        if (source.startsWith(SbuiFormat.GRAPH_SOURCE)) {
            UiBytes graph = sourceEntries.get(OmuiFormat.graphEntry(source.substring(SbuiFormat.GRAPH_SOURCE.length())));
            return graph == null ? null : graph.sha256();
        }
        if (source.startsWith(SbuiFormat.DEPENDENCY_SOURCE)) {
            SbuiDependency dep = a.manifest().dependency(source.substring(SbuiFormat.DEPENDENCY_SOURCE.length()));
            return dep == null ? null : dep.sha256();
        }
        return null;
    }

    /** Raw entries of an embedded OMUI archive, for digests and source hashes. */
    static Map<String, UiBytes> entries(UiBytes omui, UiDiagnostics d) {
        Map<String, byte[]> raw = ArchiveIO.read(omui.toArray(), ArchiveLimits.DEFAULT, d);
        Map<String, UiBytes> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> out.put(k, UiBytes.copyOf(v)));
        }
        return out;
    }
}
