package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Resolves dependency ids through one document's dependency table. Precedence is fixed and
 * documented in {@code openmason-engine/docs/ui-program/ui-asset-resolution.md}:
 * <ul>
 *   <li>An <b>embedded</b> row is owned by its archive and is only ever read from the archive
 *       entry it names. No project or runtime resource with the same id can replace it.</li>
 *   <li>A <b>shared</b> row resolves through the host's sources in the order given; the first
 *       source holding the id owns it. A row that names a {@code pack} resolves only in that
 *       pack; other shared rows resolve only in default roots (sources without a pack id).</li>
 *   <li>A missing <b>optional</b> row resolves to its {@code fallback} (a chain is followed,
 *       bounded and loop-checked); without one it is reported and left absent. A missing
 *       <b>required</b> row is an error.</li>
 * </ul>
 * Machine paths never participate: rows carry ids, sources take portable relative paths.
 */
public final class AssetResolver {

    private static final int MAX_FALLBACK_CHAIN = 16;

    private final Map<String, AssetRow> rows;
    private final Function<String, UiBytes> documentEntries;
    private final Function<String, UiBytes> exportEntries;
    private final List<AssetSource> sources;
    private final String tableEntry;

    private AssetResolver(Map<String, AssetRow> rows, Function<String, UiBytes> documentEntries,
                          Function<String, UiBytes> exportEntries, List<? extends AssetSource> sources,
                          String tableEntry) {
        this.rows = rows;
        this.documentEntries = documentEntries;
        this.exportEntries = exportEntries;
        this.sources = List.copyOf(sources);
        this.tableEntry = tableEntry;
    }

    /** Editor/preview resolution of an OMUI document against project and packaged sources. */
    public static AssetResolver forDocument(OmuiArchive doc, List<? extends AssetSource> sources) {
        Map<String, AssetRow> rows = new LinkedHashMap<>();
        for (UiDependency d : doc.dependencies().entries()) {
            rows.put(d.id(), AssetRow.of(d));
        }
        return new AssetResolver(rows, doc.assets()::get, e -> null, sources, OmuiFormat.DEPENDENCIES);
    }

    /** Runtime resolution of an SBUI: follows its resolution table, never the source OMUI's. */
    public static AssetResolver forExport(SbuiArchive sbui, List<? extends AssetSource> sources) {
        Map<String, AssetRow> rows = new LinkedHashMap<>();
        for (SbuiDependency d : sbui.manifest().dependencies()) {
            rows.put(d.id(), AssetRow.of(d));
        }
        return new AssetResolver(rows, sbui.source().assets()::get, sbui.assets()::get, sources, SbuiFormat.MANIFEST);
    }

    public Set<String> ids() {
        return rows.keySet();
    }

    public AssetRow row(String id) {
        return rows.get(id);
    }

    public Resolution resolveAll() {
        return resolve(rows.keySet());
    }

    /** Resolves {@code ids} in code-point order, so diagnostics come out deterministically. */
    public Resolution resolve(Collection<String> ids) {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, ResolvedAsset> assets = new HashMap<>();
        Map<String, String> fallbacks = new HashMap<>();
        Set<String> missing = new HashSet<>();
        TreeSet<String> ordered = new TreeSet<>(UiValue.KEY_ORDER);
        ordered.addAll(ids);
        for (String id : ordered) {
            ResolvedAsset a = resolveWithFallback(id, d);
            if (a == null) {
                missing.add(id);
                continue;
            }
            assets.put(id, a);
            if (!a.id().equals(id)) {
                fallbacks.put(id, a.id());
            }
        }
        return new Resolution(assets, fallbacks, missing, d.list());
    }

    /** {@code id} itself, without fallbacks. Errors and drift go to {@code d}. */
    public ResolvedAsset resolveOne(String id, UiDiagnostics d) {
        AssetRow row = rows.get(id);
        if (row == null) {
            d.error(Code.UNRESOLVED_REFERENCE, tableEntry, "", "'" + id + "' is not in the dependency table");
            return null;
        }
        return locate(row, d);
    }

    /**
     * Every shared source that holds {@code id}, in precedence order. More than one with
     * different bytes means a lower source is shadowed; export reports flag it.
     */
    public List<ResolvedAsset> candidates(String id) {
        AssetRow row = rows.get(id);
        List<ResolvedAsset> out = new ArrayList<>();
        if (row == null || !row.shared()) {
            return out;
        }
        for (AssetSource s : eligible(row)) {
            try {
                ResolvedAsset a = s.find(row.id(), row.kind(), row.sourceHint());
                if (a != null) {
                    out.add(a);
                }
            } catch (IOException ignored) {
                // locate() reports read failures of the owning source; shadow probes stay quiet
            }
        }
        return out;
    }

    /**
     * {@code id}, or for a missing optional row the first fallback in its chain that resolves
     * (the returned asset's {@link ResolvedAsset#id()} then names the fallback). Errors, drift
     * and every fallback taken go to {@code d}. This is what runtimes use; {@link #resolveOne}
     * is the strict single-row lookup.
     */
    public ResolvedAsset resolveWithFallback(String id, UiDiagnostics d) {
        String current = id;
        Set<String> visited = new HashSet<>();
        visited.add(id);
        for (int hop = 0; hop <= MAX_FALLBACK_CHAIN; hop++) {
            AssetRow row = rows.get(current);
            if (row == null) {
                d.error(Code.UNRESOLVED_REFERENCE, tableEntry, "", "'" + current + "' is not in the dependency table");
                return null;
            }
            ResolvedAsset a = locate(row, d);
            if (a != null) {
                return a;
            }
            if (!row.optional()) {
                d.error(Code.MISSING_ENTRY, tableEntry, "", "Required dependency '" + current + "' was not found ("
                        + lookedIn(row) + ")" + (current.equals(id) ? "" : " while falling back for '" + id + "'"));
                return null;
            }
            if (row.fallback() == null) {
                d.warning(Code.MISSING_ENTRY, tableEntry, "", "Optional dependency '" + current
                        + "' was not found and has no fallback; it is left out");
                return null;
            }
            d.warning(Code.MISSING_ENTRY, tableEntry, "", "Optional dependency '" + current
                    + "' was not found; using fallback '" + row.fallback() + "'");
            if (!visited.add(row.fallback())) {
                d.error(Code.DEPENDENCY_CYCLE, tableEntry, "", "Fallback chain of '" + id + "' loops at '"
                        + row.fallback() + "'");
                return null;
            }
            current = row.fallback();
        }
        d.error(Code.LIMIT_EXCEEDED, tableEntry, "", "Fallback chain of '" + id + "' is longer than "
                + MAX_FALLBACK_CHAIN);
        return null;
    }

    private ResolvedAsset locate(AssetRow row, UiDiagnostics d) {
        return switch (row.placement()) {
            case DOCUMENT -> embedded(row, documentEntries, AssetOrigin.DOCUMENT, "document", d);
            case EXPORT -> embedded(row, exportEntries, AssetOrigin.EXPORT, "export", d);
            case SHARED -> shared(row, d);
        };
    }

    private ResolvedAsset embedded(AssetRow row, Function<String, UiBytes> entries, AssetOrigin origin, String source,
                                   UiDiagnostics d) {
        UiBytes bytes = row.entry() == null ? null : entries.apply(row.entry());
        if (bytes == null) {
            return null;
        }
        if (!bytes.sha256().equals(row.sha256())) {
            d.error(Code.HASH_MISMATCH, row.entry(), "", "Embedded snapshot of '" + row.id()
                    + "' does not match its recorded hash");
            return null;
        }
        return new ResolvedAsset(row.id(), row.kind(), bytes, origin, source, row.entry());
    }

    private ResolvedAsset shared(AssetRow row, UiDiagnostics d) {
        for (AssetSource s : eligible(row)) {
            ResolvedAsset a;
            try {
                a = s.find(row.id(), row.kind(), row.sourceHint());
            } catch (IOException e) {
                d.error(Code.MISSING_ENTRY, tableEntry, "", "Cannot read '" + row.id() + "' from " + s.name() + ": "
                        + e.getMessage());
                return null;
            }
            if (a != null) {
                if (!a.sha256().equals(row.sha256())) {
                    d.warning(Code.HASH_MISMATCH, tableEntry, "", "Shared '" + row.id() + "' at " + a.describe()
                            + " differs from the recorded hash (the document is behind the shared asset)");
                }
                return a;
            }
        }
        return null;
    }

    private List<AssetSource> eligible(AssetRow row) {
        List<AssetSource> out = new ArrayList<>();
        for (AssetSource s : sources) {
            if (row.pack() == null ? s.packId() == null : row.pack().equals(s.packId())) {
                out.add(s);
            }
        }
        return out;
    }

    private String lookedIn(AssetRow row) {
        return switch (row.placement()) {
            case DOCUMENT -> "document entry " + row.entry();
            case EXPORT -> "export entry " + row.entry();
            case SHARED -> {
                List<String> names = eligible(row).stream().map(AssetSource::name).toList();
                if (row.pack() != null && names.isEmpty()) {
                    yield "pack '" + row.pack() + "' is not mounted";
                }
                yield names.isEmpty() ? "no shared sources are configured" : "looked in " + String.join(", ", names);
            }
        };
    }
}
