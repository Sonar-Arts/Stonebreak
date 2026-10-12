package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.assets.AssetKinds;
import com.openmason.engine.ui.assets.AssetOrigin;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.HostCompatibility;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.assets.export.PlanItem;
import com.openmason.engine.ui.assets.export.UiExportService;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.binding.UiActivation;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.stonebreak.ui.runtime.GameUiAssets;
import com.stonebreak.ui.runtime.GameUiHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks an export against the real game host and ships it into the game (C14/C15).
 *
 * <p><b>Host check</b>: the editor previews documents against fixture data, which can promise
 * roots, actions and contracts the game does not have. The check runs the same gates the game's
 * {@code GameUiDocuments.openBound} runs, against {@link GameUiHost#declaration()} (every
 * contract the game registers, no game behind it): activation (contracts, data roots, actions,
 * providers, features) and host compatibility (profile + asset resolution). An export the game
 * would refuse is reported before anyone ships it.
 *
 * <p><b>Deploy</b>: writes the SBUI to {@code ui/documents/<screen>.sbui} and every shared
 * dependency the export needs from the project to {@code ui/shared/<ns>/<path><ext>} in the
 * game's resource tree, the layout the game's packaged asset source reads. Files that already
 * exist with different bytes are conflicts: nothing is written unless the caller confirms.
 */
public final class UiGameDeploy {

    /** Gate findings, split like the game reports them; {@link #runnable()} = the game would open it. */
    public record HostCheck(List<UiDiagnostic> host, List<UiDiagnostic> assets) {
        public HostCheck {
            host = List.copyOf(host);
            assets = List.copyOf(assets);
        }

        public boolean runnable() {
            return host.stream().noneMatch(UiDiagnostic::isError) && assets.stream().noneMatch(UiDiagnostic::isError);
        }

        /** Non-info findings as {@code severity: message} lines (UI and MCP results). */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (List<UiDiagnostic> list : List.of(host, assets)) {
                for (UiDiagnostic d : list) {
                    if (d.severity() != UiDiagnostic.Severity.INFO) {
                        out.add(d.severity().name().toLowerCase(java.util.Locale.ROOT) + ": " + d.message());
                    }
                }
            }
            return out;
        }
    }

    /** One file a deploy writes: where, the bytes, and whether a different file is already there. */
    public record DeployFile(Path target, UiBytes bytes, boolean conflict) {
    }

    /** What {@link #write} will do; nothing has been written yet. */
    public record Plan(String screenId, List<DeployFile> files, List<String> notes, HostCheck check) {
        public Plan {
            files = List.copyOf(files);
            notes = List.copyOf(notes);
        }

        public List<DeployFile> conflicts() {
            return files.stream().filter(DeployFile::conflict).toList();
        }
    }

    private UiGameDeploy() {
    }

    /**
     * Checks {@code sbui} against the game's declared host. Shared rows resolve through the game's
     * packaged assets and then {@code pending} (the project files a deploy would ship).
     */
    public static HostCheck check(SbuiArchive sbui, List<? extends AssetSource> pending) {
        List<AssetSource> sources = new ArrayList<>();
        try {
            sources.addAll(GameUiAssets.sources(Map.of()));
        } catch (IOException e) {
            UiDiagnostics d = new UiDiagnostics();
            d.error(UiDiagnostic.Code.MISSING_ENTRY, "", "", "The game's packaged UI assets are unreadable: " + e.getMessage());
            return new HostCheck(List.of(), d.list());
        }
        sources.addAll(pending);
        GameUiHost declared = GameUiHost.declaration();
        List<UiDiagnostic> host = new ArrayList<>();
        UiDocumentSource components;
        try {
            components = new ResolvedUiAssets(AssetResolver.forExport(sbui, sources), sources, null);
        } catch (RuntimeException e) {
            components = UiDocumentSource.EMPTY;
        }
        host.addAll(UiActivation.check(sbui, components, declared.host()));
        HostCompatibility compat = HostCompatibility.check(sbui, declared.host().profile(), sources);
        host.addAll(compat.host());
        return new HostCheck(dedupe(host), compat.assets());
    }

    /**
     * Where the game reads shipped screen {@code id} from, relative to its resources: the C14
     * layout {@code ui/documents/<id>.sbui} ({@code GameUiDocuments.readScreen}).
     */
    public static String screenPath(String id) {
        return "ui/documents/" + id + ".sbui";
    }

    /** The screen id the game opens an export by: the last segment of its document id. */
    public static String screenId(OmuiArchive doc) {
        String id = doc.manifest().documentId();
        return id.substring(Math.max(id.lastIndexOf('/'), id.indexOf(':')) + 1);
    }

    /**
     * Exports {@code doc} and plans its deployment under {@code gameResources}
     * ({@code stonebreak-game/src/main/resources}).
     *
     * @throws UiFormatException when the export itself is blocked
     */
    public static Plan plan(OmuiArchive doc, List<? extends AssetSource> projectSources, ExportMode mode,
                            Path gameResources) throws UiFormatException, IOException {
        UiExportService.Result r = UiExportService.export(doc, projectSources, ExportPlanner.Request.of(mode), null);
        String screen = screenId(doc);
        List<DeployFile> files = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        UiBytes sbuiBytes = UiBytes.copyOf(SbuiWriter.write(r.sbui()));
        files.add(file(gameResources.resolve(screenPath(screen)), sbuiBytes));
        Map<String, ResolvedAsset> resolved = new LinkedHashMap<>();
        UiDiagnostics scratch = new UiDiagnostics();
        AssetResolver resolver = AssetResolver.forDocument(doc, projectSources);
        for (PlanItem item : r.plan().mustShip()) {
            if (item.pack() != null) {
                notes.add(item.id() + " ships in resource pack " + item.pack() + " (not deployed here)");
                continue;
            }
            if (item.origin() != AssetOrigin.PROJECT) {
                continue; // already a game resource: shipped with the game
            }
            ResolvedAsset a = resolver.resolveOne(item.id(), scratch);
            if (a == null) {
                notes.add(item.id() + " did not resolve and was not deployed");
                continue;
            }
            resolved.put(item.id(), a);
            String rel = GameUiAssets.RESOURCE_ROOT + AssetKinds.fileName(item.id(), item.kind(), item.location());
            files.add(file(gameResources.resolve(rel), a.bytes()));
        }
        HostCheck check = check(r.sbui(), projectSources);
        return new Plan(screen, files, notes, check);
    }

    /**
     * Writes a plan's files, each atomically.
     *
     * @param overwrite replace files that exist with different bytes (the user confirmed)
     * @return the files written (identical existing files are left alone)
     * @throws IllegalStateException when the game would refuse the export, or when conflicts exist
     *                               and {@code overwrite} is false; nothing is written then
     */
    public static List<Path> write(Plan plan, boolean overwrite) throws IOException {
        if (!plan.check().runnable()) {
            throw new IllegalStateException("Not deployed: the game would refuse this screen: "
                + String.join("; ", plan.check().lines()));
        }
        if (!overwrite && !plan.conflicts().isEmpty()) {
            throw new IllegalStateException("Not deployed: these game files exist with different content: "
                + plan.conflicts().stream().map(f -> f.target().getFileName().toString()).toList()
                + " (confirm to replace them)");
        }
        List<Path> written = new ArrayList<>();
        for (DeployFile f : plan.files()) {
            if (Files.isRegularFile(f.target()) && same(f.target(), f.bytes())) {
                continue;
            }
            Files.createDirectories(f.target().getParent());
            AtomicFiles.write(f.target(), f.bytes().toArray());
            written.add(f.target());
        }
        return written;
    }

    private static DeployFile file(Path target, UiBytes bytes) throws IOException {
        boolean conflict = Files.isRegularFile(target) && !same(target, bytes);
        return new DeployFile(target, bytes, conflict);
    }

    private static boolean same(Path file, UiBytes bytes) throws IOException {
        return Files.size(file) == bytes.size() && Arrays.equals(Files.readAllBytes(file), bytes.toArray());
    }

    private static List<UiDiagnostic> dedupe(List<UiDiagnostic> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }
}
