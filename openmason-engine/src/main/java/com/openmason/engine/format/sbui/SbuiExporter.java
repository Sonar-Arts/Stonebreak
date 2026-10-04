package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.omui.io.ArchiveDigest;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedEntry;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedKind;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;
import com.openmason.engine.format.sbui.SbuiManifest.SourceRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link SbuiArchive} from an authoritative {@link OmuiArchive}. The exporter does
 * not resolve project assets or compile graphs: the caller (#285 export planner, #291
 * compiler) hands it collected bytes and derived outputs, and it records hashes, resolution
 * rows and requirement unions, refusing anything inconsistent.
 */
public final class SbuiExporter {

    /**
     * Inputs of one export.
     *
     * @param assetId    game-facing id; {@code null} uses the document id
     * @param collected  shared dependency id → bytes to embed in the SBUI (collect-all /
     *                   selective embedding). Entries are named {@code assets/<id path><ext>}
     * @param collectAll when true every required shared dependency must be collected
     * @param packs      shared dependency id → resource pack id it ships in (optional)
     * @param derived    derived caches to ship
     */
    public record Options(String assetId, Map<String, UiBytes> collected, boolean collectAll,
                          Map<String, String> packs, List<DerivedInput> derived) {
        public Options {
            collected = collected == null ? Map.of() : Map.copyOf(collected);
            packs = packs == null ? Map.of() : Map.copyOf(packs);
            derived = derived == null ? List.of() : List.copyOf(derived);
        }

        public static Options shared() {
            return new Options(null, Map.of(), false, Map.of(), List.of());
        }
    }

    /**
     * A derived cache produced by a compiler/baker.
     *
     * @param name   file name under {@code derived/} ({@code graphs/behaviors.lua})
     * @param source {@code graph:<id>} or {@code dependency:<id>}
     */
    public record DerivedInput(String name, DerivedKind kind, String source, UiBytes bytes, String compiler,
                               String compilerVersion) {
    }

    public record Export(SbuiArchive archive, List<UiDiagnostic> diagnostics) {
    }

    private SbuiExporter() {
    }

    public static Export export(OmuiArchive source, Options options) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, UiBytes> sourceEntries = OmuiWriter.entries(source);
        UiBytes sourceBytes = UiBytes.copyOf(ArchiveIO.write(sourceEntries));
        String sourceEntry = SbuiFormat.sourceEntry(source.manifest().documentId());

        Map<String, UiBytes> assets = new LinkedHashMap<>();
        List<SbuiDependency> rows = new ArrayList<>();
        for (UiDependency dep : source.dependencies().entries()) {
            rows.add(row(dep, options, assets, d));
        }
        for (String id : options.collected().keySet()) {
            if (source.dependencies().find(id) == null) {
                d.error(Code.UNRESOLVED_REFERENCE, "", "", "Collected '" + id + "' is not a dependency of the document");
            }
        }
        d.throwIfErrors("Export blocked");

        SbuiManifest draft = manifest(source, options, sourceEntry, sourceEntries, rows, List.of());
        SbuiArchive partial = new SbuiArchive(draft, source, sourceBytes, assets, Map.of(), Map.of());
        Map<String, UiBytes> derivedBytes = new LinkedHashMap<>();
        List<DerivedEntry> derived = new ArrayList<>();
        for (DerivedInput in : options.derived()) {
            String entry = SbuiFormat.DERIVED_DIR + in.name();
            String problem = EntryPaths.problem(entry);
            String sourceHash = SbuiValidator.currentSourceHash(partial, sourceEntries, in.source());
            if (problem != null || sourceHash == null) {
                d.error(Code.INVALID_VALUE, entry, "", problem != null ? problem : "No source '" + in.source() + "'");
                continue;
            }
            derivedBytes.put(entry, in.bytes());
            derived.add(new DerivedEntry(entry, in.kind(), in.source(), sourceHash, in.bytes().sha256(), in.compiler(),
                    in.compilerVersion(), Map.of()));
        }
        d.throwIfErrors("Export blocked");

        // The requirement union covers every component document that travels with the export.
        List<UiRequirements> docs = new ArrayList<>();
        docs.add(source.manifest());
        SbuiValidator.components(partial, ArchiveLimits.DEFAULT, d).values().forEach(c -> docs.add(c.manifest()));
        Requirements union = Requirements.union(docs, d);
        d.throwIfErrors("Export blocked");

        SbuiManifest manifest = new SbuiManifest(SbuiFormat.SCHEMA_VERSION,
                options.assetId() == null ? source.manifest().documentId() : options.assetId(),
                source.manifest().documentId(),
                new SourceRef(sourceEntry, ArchiveDigest.of(sourceEntries), source.manifest().schemaVersion(), Map.of()),
                union.uiApi(), union.layoutSemantics(), union.requires(), union.hostApis(), union.providers(), rows,
                derived, Map.of());
        SbuiArchive archive = new SbuiArchive(manifest, source, sourceBytes, assets, derivedBytes, Map.of());
        SbuiValidator.validate(archive, sourceEntries, SbuiReader.Options.RUNTIME, d);
        d.throwIfErrors("Export blocked");
        return new Export(archive, d.list());
    }

    private static SbuiManifest manifest(OmuiArchive source, Options options, String sourceEntry,
                                         Map<String, UiBytes> sourceEntries, List<SbuiDependency> rows,
                                         List<DerivedEntry> derived) {
        var m = source.manifest();
        return new SbuiManifest(SbuiFormat.SCHEMA_VERSION, options.assetId() == null ? m.documentId() : options.assetId(),
                m.documentId(), new SourceRef(sourceEntry, ArchiveDigest.of(sourceEntries), m.schemaVersion(), Map.of()),
                m.uiApi(), m.layoutSemantics(), m.requires(), m.hostApis(), m.providers(), rows, derived, Map.of());
    }

    private static SbuiDependency row(UiDependency dep, Options options, Map<String, UiBytes> assets, UiDiagnostics d) {
        if (dep.mode() == UiDependency.Mode.EMBEDDED) {
            return new SbuiDependency(dep.id(), dep.kind(), dep.version(), dep.sha256(), dep.size(),
                    UiDependency.Mode.EMBEDDED, Location.SOURCE, dep.entry(), null, dep.requires(), dep.optional(),
                    dep.fallback(), dep.license(), Map.of());
        }
        UiBytes bytes = options.collected().get(dep.id());
        if (bytes == null) {
            if (options.collectAll() && !dep.optional()) {
                d.error(Code.MISSING_ENTRY, "", "", "Collect-all export is missing required dependency '" + dep.id() + "'");
            }
            return new SbuiDependency(dep.id(), dep.kind(), dep.version(), dep.sha256(), dep.size(),
                    UiDependency.Mode.SHARED, null, null, options.packs().get(dep.id()), dep.requires(),
                    dep.optional(), dep.fallback(), dep.license(), Map.of());
        }
        if ((dep.kind() == UiDependency.Kind.FONT || dep.kind() == UiDependency.Kind.SOUND) && dep.license() == null) {
            d.error(Code.MISSING_FIELD, OmuiFormat.DEPENDENCIES, "",
                    "Cannot redistribute '" + dep.id() + "' without licence provenance");
        }
        if (!bytes.sha256().equals(dep.sha256())) {
            d.warning(Code.HASH_MISMATCH, "", "", "Shared '" + dep.id() + "' changed since the document last recorded it;"
                    + " the export snapshots the current bytes");
        }
        String entry = SbuiFormat.ASSETS_DIR + dep.id().replace(':', '/') + extension(dep.sourceHint());
        String problem = EntryPaths.problem(entry);
        if (problem != null) {
            d.error(Code.UNSAFE_ENTRY_PATH, entry, "", "Cannot collect '" + dep.id() + "': " + problem);
        }
        assets.put(entry, bytes);
        return new SbuiDependency(dep.id(), dep.kind(), dep.version(), bytes.sha256(), bytes.size(),
                UiDependency.Mode.EMBEDDED, Location.SBUI, entry, null, dep.requires(), dep.optional(), dep.fallback(),
                dep.license(), Map.of());
    }

    private static String extension(String hint) {
        if (hint == null) {
            return "";
        }
        String file = hint.substring(hint.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot <= 0 ? "" : file.substring(dot);
    }
}
