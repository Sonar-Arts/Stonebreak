package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.input.PreviewInput;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.diag.UiBudgets;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.script.UiScriptServices;
import com.openmason.engine.ui.script.UiScripts;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiPreview.MasonryPreview;
import com.stonebreak.ui.runtime.GameUiDocuments;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The engine side of the designer canvas for one document: a live runtime instance of the
 * source, painted through {@link GameUiDocuments} (the game's own context, painter and fonts) on
 * a {@link MasonryPreview}. What the canvas shows is the engine's result, never a recreation.
 *
 * <ul>
 *   <li><b>Design</b>: no scripts, no bindings, no input. Every source edit is applied with
 *       {@link UiDocumentInstance#reload} (state kept by key); a changed dependency table rebuilds
 *       the view. Forced pseudo-states are runtime state only.</li>
 *   <li><b>Preview</b>: a fresh view bound to the document's fixtures with its code-behind and
 *       graphs running, fed real input. Edits hot-reload into it. Leaving preview throws the
 *       whole runtime away, so preview can never write to the source.</li>
 * </ul>
 */
public final class DesignerRuntime implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DesignerRuntime.class);
    private static final int BACKDROP_DARK = 0xFF1B1D21;

    public enum Mode { DESIGN, PREVIEW }

    private final UiEditorDocument doc;
    private final UiProjectContext project;
    private final Typeface typeface;
    private final MasonryPreview preview;
    private UiDocumentView view;
    private Mode mode = Mode.DESIGN;
    private long builtRevision = -1;
    private OmuiArchive builtFrom;
    private String error;
    private final Map<String, Set<String>> forcedStates = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private FixtureHost fixtures;
    private UiScriptRuntime scripts;
    private PreviewInput input;
    private List<UiDiagnostic> activation = List.of();
    // repaint gating
    private Object paintedKey;
    private double sincePaint;
    private double sinceWatch;
    private boolean assetsStale;
    private final Map<Path, String> watched = new HashMap<>();
    private java.util.function.Consumer<List<Path>> onFilesChanged;
    private MasonryPreview.Frame lastFrame;

    public DesignerRuntime(UiEditorDocument doc, UiProjectContext project, Typeface typeface) {
        this.doc = doc;
        this.project = project;
        this.typeface = typeface;
        this.preview = new MasonryPreview(typeface, MasonryPreview.Path.GPU);
    }

    public Mode mode() {
        return mode;
    }

    public UiDocumentView view() {
        return view;
    }

    public UiDocumentInstance instance() {
        return view == null ? null : view.instance();
    }

    /** Why the document cannot be shown (the source is untouched and still editable), or null. */
    public String error() {
        return error;
    }

    public MasonryPreview preview() {
        return preview;
    }

    public PreviewInput input() {
        return input;
    }

    public UiScriptRuntime scripts() {
        return scripts;
    }

    public FixtureHost fixtures() {
        return fixtures;
    }

    /** Host requests a previewed script made (sound, navigate, close): listed, never performed. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public List<UiDiagnostic> activationFindings() {
        return activation;
    }

    // ── mode ────────────────────────────────────────────────────────────────

    public void setMode(Mode next) {
        if (next == mode) {
            return;
        }
        mode = next;
        disposeView();
        error = null;
        builtRevision = -1;
        requests.clear();
        sync();
    }

    // ── source sync ─────────────────────────────────────────────────────────

    /** Brings the runtime up to the document's current revision. */
    public void sync() {
        OmuiArchive a = doc.archive();
        if (builtRevision == doc.revision() && (view != null || error != null)) {
            return; // up to date, or failed for this revision (retry after the next edit or mode switch)
        }
        if (view != null && builtFrom != null && !assetsStale && sameResolution(builtFrom, a)) {
            try {
                view.instance().reload(a);
                if (scripts != null) {
                    scripts.reload(); // hot reload of edited code-behind/graphs into the preview
                }
                builtRevision = doc.revision();
                builtFrom = a;
                error = null;
                paintedKey = null;
                return;
            } catch (RuntimeException e) {
                logger.debug("Reload failed, rebuilding: {}", e.getMessage());
            }
        }
        rebuild(a);
    }

    /** Dependency rows and embedded assets decide resolution; any change there needs a new view. */
    private static boolean sameResolution(OmuiArchive a, OmuiArchive b) {
        return a.dependencies().equals(b.dependencies()) && a.assets().equals(b.assets());
    }

    private void rebuild(OmuiArchive a) {
        assetsStale = false;
        disposeView();
        try {
            view = GameUiDocuments.open(a, project.sources(), () -> typeface, Map.of());
            if (mode == Mode.PREVIEW) {
                fixtures = fixtures(a);
                activation = GameUiDocuments.activationGate(view, fixtures.host());
                // The game's hard limits (#296): a script that would fail in the game fails here first
                scripts = GameUiDocuments.scripts(view, fixtures.host(), null, services(),
                    UiBudgets.forDocument(a).scriptOptions().withGraphDebug(true));
                input = new PreviewInput(view.input());
            }
            error = null;
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.warn("UI editor cannot run {}: {}", doc.title(), error);
            disposeView();
        }
        builtRevision = doc.revision();
        builtFrom = a;
        paintedKey = null;
    }

    /**
     * Project asset files changed (#294): re-resolve what this view uses and, when any bytes
     * changed, rebuild it so layout re-measures and every cached image is resolved afresh.
     *
     * @return texture cache keys the old bytes left behind (the context evicts those no open
     *         document still draws)
     */
    public java.util.Set<String> assetsChanged() {
        var assets = resolvedAssets();
        watched.clear();
        paintedKey = null;
        if (assets == null) {
            return java.util.Set.of();
        }
        var refresh = assets.refresh();
        if (refresh.any()) {
            assetsStale = true;
            builtRevision = -1;
        }
        return refresh.staleTextureKeys();
    }

    /** Texture cache keys this view currently draws from. */
    public java.util.Set<String> textureKeysInUse() {
        var assets = resolvedAssets();
        return assets == null ? java.util.Set.of() : assets.textureKeys();
    }

    /** Called with the project files that changed under a view (edits outside the editor). */
    public void setOnFilesChanged(java.util.function.Consumer<List<Path>> listener) {
        onFilesChanged = listener;
    }

    /** The runtime's asset resolution (textures, sprite sheets), or null without a view. */
    public com.openmason.engine.ui.runtime.paint.ResolvedUiAssets resolvedAssets() {
        return view != null && view.instance().context().source()
            instanceof com.openmason.engine.ui.runtime.paint.ResolvedUiAssets r ? r : null;
    }

    /**
     * Once a second: compares modification time and size of the project files this view
     * resolved, without reading them; a change is reported like a Texture Editor save.
     */
    private void watchFiles(double dt) {
        sinceWatch += dt;
        var assets = resolvedAssets();
        Path root = project.root();
        if (sinceWatch < 1.0 || assets == null || root == null) {
            return;
        }
        sinceWatch = 0;
        List<Path> changed = new ArrayList<>();
        for (var a : assets.resolvedAssets()) {
            if (a.origin() != com.openmason.engine.ui.assets.AssetOrigin.PROJECT) {
                continue;
            }
            Path file = root.resolve(a.location());
            String stamp;
            try {
                stamp = Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
            } catch (java.io.IOException e) {
                stamp = "missing";
            }
            String before = watched.put(file, stamp);
            if (before != null && !before.equals(stamp)) {
                changed.add(file);
            }
        }
        if (!changed.isEmpty() && onFilesChanged != null) {
            onFilesChanged.accept(changed);
        }
    }

    private FixtureHost fixtures(OmuiArchive a) throws java.io.IOException {
        Path file = doc.file();
        if (file != null) {
            Path sidecar = file.resolveSibling(file.getFileName() + ".fixture.json");
            if (Files.isRegularFile(sidecar)) {
                return FixtureHost.parse(Files.readAllBytes(sidecar), sidecar.getFileName().toString(), a.manifest());
            }
        }
        return FixtureHost.forArchive(a);
    }

    private UiScriptServices services() {
        return new UiScriptServices() {
            @Override
            public void playSound(String id, UiValue.Obj options) {
                request("ui.sound(" + id + ")");
            }

            @Override
            public boolean navigate(String target, UiValue.Obj args) {
                request("ui.navigate(" + target + ")");
                return true;
            }

            @Override
            public void requestClose() {
                request("ui.close()");
            }
        };
    }

    private void request(String what) {
        requests.add(what);
        if (requests.size() > 30) {
            requests.removeFirst();
        }
    }

    // ── pseudo-state forcing (design) ───────────────────────────────────────

    public Set<String> forcedStates(String key) {
        return forcedStates.getOrDefault(key, Set.of());
    }

    public void forceState(String key, String state, boolean on) {
        Set<String> s = new HashSet<>(forcedStates(key));
        if (on) {
            s.add(state);
        } else {
            s.remove(state);
            UiElement el = view == null ? null : view.instance().find(key);
            if (el != null) {
                el.setState(state, false);
            }
        }
        if (s.isEmpty()) {
            forcedStates.remove(key);
        } else {
            forcedStates.put(key, s);
        }
        paintedKey = null;
    }

    public boolean hasForcedStates() {
        return !forcedStates.isEmpty();
    }

    public void clearForcedStates() {
        for (String key : new ArrayList<>(forcedStates.keySet())) {
            for (String st : new ArrayList<>(forcedStates.get(key))) {
                forceState(key, st, false);
            }
        }
    }

    /** Elements hidden in the designer only: a runtime style on the design view, never source. */
    public void applyDesignerVisibility(Set<String> hidden) {
        if (designerHidden.equals(hidden)) {
            return;
        }
        Set<String> next = new HashSet<>(hidden);
        if (view != null) {
            for (String key : designerHidden) {
                if (!next.contains(key)) {
                    UiElement el = view.instance().find(key);
                    if (el != null) {
                        el.clearStyle("visibility");
                    }
                }
            }
        }
        designerHidden = next;
        paintedKey = null;
    }

    private Set<String> designerHidden = new HashSet<>();

    private void applyForcedStates() {
        if (view == null || mode != Mode.DESIGN) {
            return;
        }
        for (String key : designerHidden) {
            UiElement el = view.instance().find(key);
            if (el != null && el.localStyle("visibility") == null) {
                el.setStyle("visibility", UiValue.of("hidden"));
            }
        }
        forcedStates.forEach((key, states) -> {
            UiElement el = view.instance().find(key);
            if (el != null) {
                states.forEach(st -> el.setState(st, true));
            }
        });
    }

    /** Every forced pseudo-state, by element key (a copy). */
    public Map<String, Set<String>> forcedStateMap() {
        Map<String, Set<String>> out = new HashMap<>();
        forcedStates.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return out;
    }

    // ── frame ───────────────────────────────────────────────────────────────

    /**
     * Advances the runtime by {@code dt} seconds without painting: in Preview the fixtures'
     * queued results, the input router's clock and the scripts and their animations; in Design
     * only the UI clock (animated sprites). {@link #paint} calls it every frame; automation calls
     * it to let a preview settle deterministically (#324).
     */
    public void step(double dt) {
        sync();
        if (view == null) {
            return;
        }
        if (mode == Mode.PREVIEW) {
            if (fixtures != null) {
                fixtures.host().drain();
            }
            if (input != null) {
                input.router().tick(dt);
            }
            view.frame(dt);
        } else {
            view.instance().advanceClock(dt); // animated sprites (#294) play in design too
        }
    }


    /**
     * Advances and paints one frame at the given device size and scales.
     *
     * @return the frame, or null when there is nothing to show
     */
    public MasonryPreview.Frame paint(int width, int height, float uiScale, float pixelRatio, double dt,
                                      boolean forceRepaint) {
        sync();
        if (view == null || width < 1 || height < 1) {
            return null;
        }
        sincePaint += dt;
        step(dt);
        watchFiles(dt); // files changed outside the editor
        applyForcedStates();
        Object key = List.of(doc.revision(), width, height, uiScale, pixelRatio, mode, forcedStates.toString(),
            designerHidden.toString());
        boolean dirty = mode == Mode.PREVIEW || forceRepaint || !Objects.equals(key, paintedKey) || lastFrame == null
            || sincePaint > 0.5;
        if (!dirty) {
            // nothing visible changed; a texture load or reload may still mark regions dirty
            UiRect r = view.instance().consumeDirtyRegion();
            dirty = r != null && !r.isEmpty();
        }
        if (!dirty) {
            return lastFrame;
        }
        paintedKey = key;
        sincePaint = 0;
        float ratio = pixelRatio;
        lastFrame = preview.paint(width, height, (MasonryUI ui, int[] size) -> {
            ui.canvas().clear(BACKDROP_DARK);
            view.render(ui, size[0], size[1], uiScale, ratio);
        });
        return lastFrame;
    }

    // ── lifetime ────────────────────────────────────────────────────────────

    private void disposeView() {
        if (view != null) {
            try {
                view.close(); // closes the script runtime and binder extensions too
            } catch (RuntimeException e) {
                logger.debug("Closing view: {}", e.getMessage());
            }
        }
        view = null;
        scripts = null;
        input = null;
        fixtures = null;
        activation = List.of();
        lastFrame = null;
    }

    @Override
    public void close() {
        disposeView();
        preview.close();
    }

    /** The view's script runtime, when the preview has one (graphs window, console). */
    public UiScriptRuntime scriptsOf() {
        return view == null ? null : UiScripts.of(view);
    }
}
