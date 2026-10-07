package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.HostCompatibility;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.binding.UiActivation;
import com.openmason.engine.ui.runtime.binding.UiActivationException;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.runtime.input.UiInputGate;
import com.openmason.engine.ui.runtime.paint.MasonryContentMeasurer;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import com.openmason.engine.ui.diag.UiBudgets;
import com.openmason.engine.ui.diag.UiFrameMonitor;
import com.openmason.engine.ui.script.UiScriptOptions;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.script.UiScriptServices;
import com.openmason.engine.ui.script.UiScripts;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.ui.runtime.screens.DocumentScreenPolicy;
import com.stonebreak.ui.runtime.screens.UiLayer;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hosts OMUI/SBUI documents in the game window (#287): builds the runtime context the editor
 * preview also uses (built-in widgets, #285 asset resolution, the game's texture cache, the
 * Masonry font measurer) and paints through the game's Skija backend.
 *
 * <p>Screens migrate onto this in #297 onward through
 * {@link com.stonebreak.ui.runtime.screens.DocumentScreenHost}, which opens shipped exports with
 * {@link #openScreen} ({@link #openBound}: activation, asset and input gates). The
 * {@link DevDocumentOverlay} shows any document over every game state for comparison with the
 * editor preview.
 */
public final class GameUiDocuments {

    private GameUiDocuments() {
    }

    /** A view of an exported screen, resolving shared rows through the packaged root and {@code packs}. */
    public static UiDocumentView open(SbuiArchive sbui, Map<String, Path> packs, Supplier<Typeface> typeface,
                                      Map<String, UiPaintHost.UiDrawProvider> providers) throws IOException {
        List<AssetSource> sources = GameUiAssets.sources(packs);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forExport(sbui, sources), sources,
            MTextureRegistry.cache()).withDerived(sbui);
        return view(sbui.source(), assets, typeface, providers);
    }

    /** A view of an authoring document (dev and tests): embedded rows plus the packaged root. */
    public static UiDocumentView open(OmuiArchive omui, Supplier<Typeface> typeface,
                                      Map<String, UiPaintHost.UiDrawProvider> providers) throws IOException {
        List<AssetSource> sources = GameUiAssets.sources(Map.of());
        return view(omui, AssetResolver.forDocument(omui, sources), sources, typeface, providers);
    }

    /**
     * A view of an authoring document whose shared rows resolve through {@code projectSources}
     * first (the UI editor's project, #293), then the packaged root.
     */
    public static UiDocumentView open(OmuiArchive omui, List<AssetSource> projectSources, Supplier<Typeface> typeface,
                                      Map<String, UiPaintHost.UiDrawProvider> providers) throws IOException {
        List<AssetSource> sources = new java.util.ArrayList<>(projectSources);
        for (AssetSource s : GameUiAssets.sources(Map.of())) {
            if (sources.stream().noneMatch(x -> x.name().equals(s.name()))) {
                sources.add(s);
            }
        }
        return view(omui, AssetResolver.forDocument(omui, sources), sources, typeface, providers);
    }

    /** Reads {@code .sbui} or {@code .omui} from disk. */
    public static UiDocumentView open(Path file, Supplier<Typeface> typeface) throws IOException {
        return open(read(file), typeface, GameUiProviders.all());
    }

    /** The editable tree of a {@code .sbui} (its embedded source) or {@code .omui} file. */
    public static OmuiArchive read(Path file) throws IOException {
        if (file.getFileName().toString().endsWith(".sbui")) {
            return SbuiReader.read(file, SbuiReader.Options.RUNTIME).archive().source();
        }
        return OmuiReader.read(file).archive();
    }

    /**
     * The input part of a migration gate (#288): needs of the running document (components
     * included) in {@code locale} that the game window lacks ({@link GameUiInput#CAPABILITIES}).
     * Each is also recorded as an {@code INPUT_GATE_BLOCKED} diagnostic on the instance.
     */
    public static List<UiInputGate.Block> inputGate(UiDocumentView view, Locale locale) {
        List<UiInputGate.Block> blocks = UiInputGate.check(view.instance(), locale, GameUiInput.CAPABILITIES);
        for (UiInputGate.Block b : blocks) {
            view.instance().reportDiagnostic(UiRuntimeDiagnostic.error(UiRuntimeDiagnostic.Code.INPUT_GATE_BLOCKED,
                b.nodeId(), b.reason()));
        }
        return blocks;
    }

    /**
     * Refuses a migrated screen whose input needs the game cannot meet: the legacy screen stays
     * in place instead of the document running with input quietly missing.
     *
     * @throws IllegalStateException listing every unmet need
     */
    public static void requireInputGate(UiDocumentView view, Locale locale) {
        List<UiInputGate.Block> blocks = inputGate(view, locale);
        if (!blocks.isEmpty()) {
            throw new IllegalStateException("UI document " + view.instance().document().manifest().documentId()
                + " is blocked by its input gate: " + blocks.stream().map(UiInputGate.Block::reason).toList());
        }
    }

    // ── host bindings (#289) ────────────────────────────────────────────────

    /**
     * Opens an exported screen bound to {@code host} in the {@link UiLayer#SCREEN} layer. See
     * {@link #openBound(SbuiArchive, Map, Supplier, Map, UiHost, UiConverters, UiScriptServices, UiLayer)}.
     */
    public static UiDocumentView openBound(SbuiArchive sbui, Map<String, Path> packs, Supplier<Typeface> typeface,
                                           Map<String, UiPaintHost.UiDrawProvider> providers, UiHost host,
                                           UiConverters converters, UiScriptServices services) throws IOException {
        return openBound(sbui, packs, typeface, providers, host, converters, services, UiLayer.SCREEN);
    }

    /**
     * Opens an exported screen bound to {@code host}: the production path every migrated screen
     * takes (#297 onward, C9). The screen is refused, before anything runs, when
     * <ul>
     *   <li>its activation gate fails: a required host contract, provider, feature or data root
     *       the host lacks ({@link UiActivation});</li>
     *   <li>a required dependency does not resolve, or the export does not fit the game's host
     *       profile ({@link HostCompatibility}): no blank textures at runtime;</li>
     *   <li>its input gate fails: an input need the game window cannot meet ({@link #requireInputGate}).</li>
     * </ul>
     * The caller keeps the legacy screen instead of showing a broken one. Its Lua code-behind (#292)
     * and behavior graphs (#291, from the SBUI's {@code derived/} Lua while it is current) are loaded
     * and opened, and the view joins the game window's input stack ({@link GameUiInput}) in
     * {@code layer}; close it with {@link #close}. {@code view.frame(dt)} drives it per frame.
     *
     * @param converters Java converters for names no script declares, or null
     * @throws UiActivationException listing every unmet activation or asset need
     * @throws IllegalStateException when the input gate blocks the screen
     * @throws com.openmason.engine.cenda.CendaLuaUnavailableException when the screen has
     *         code-behind and the Lua host cannot load
     */
    public static UiDocumentView openBound(SbuiArchive sbui, Map<String, Path> packs, Supplier<Typeface> typeface,
                                           Map<String, UiPaintHost.UiDrawProvider> providers, UiHost host,
                                           UiConverters converters, UiScriptServices services, UiLayer layer)
            throws IOException {
        return openBound(sbui, packs, typeface, providers, host, converters, services, layer, false);
    }

    /**
     * As above; {@code firstParty} marks an export the game itself ships (a classpath
     * {@code ui/documents/} screen): its {@code derived/} graph Lua is trusted as built by the
     * editor. Documents from anywhere else have their graphs compiled at load (#292 review).
     */
    static UiDocumentView openBound(SbuiArchive sbui, Map<String, Path> packs, Supplier<Typeface> typeface,
                                    Map<String, UiPaintHost.UiDrawProvider> providers, UiHost host,
                                    UiConverters converters, UiScriptServices services, UiLayer layer,
                                    boolean firstParty) throws IOException {
        List<AssetSource> sources = GameUiAssets.sources(packs);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forExport(sbui, sources), sources,
            MTextureRegistry.cache()).withDerived(sbui); // graph Lua compiled at export (#291)
        UiActivation.require(sbui, assets, host);
        requireAssets(sbui, host, sources);
        List<UiDiagnostic> unresolved = assets.check().stream().filter(UiDiagnostic::isError).toList();
        if (!unresolved.isEmpty()) { // resolution through the runtime's own tables (fallbacks, hashes)
            throw new UiActivationException(sbui.source().manifest().documentId(), unresolved);
        }
        UiDocumentView view = view(sbui.source(), assets, typeface, providers);
        try {
            requireInputGate(view, Locale.getDefault());
            UiScriptOptions options = budgets(view, layer).scriptOptions();
            scripts(view, host, converters, services, firstParty ? options.withTrustedDerivedGraphs(true) : options);
            GameUiInput.get().open(view, layer);
            return view;
        } catch (RuntimeException e) {
            view.close();
            throw e;
        }
    }

    /**
     * Refuses an export whose required dependencies do not resolve here or that does not fit the
     * host's profile: a missing texture would otherwise draw nothing, silently (#285 review).
     */
    static void requireAssets(SbuiArchive sbui, UiHost host, List<AssetSource> sources) {
        HostCompatibility compat = HostCompatibility.check(sbui, host.profile(), sources);
        if (!compat.runnable()) {
            List<UiDiagnostic> all = new java.util.ArrayList<>(compat.host());
            all.addAll(compat.assets());
            throw new UiActivationException(sbui.source().manifest().documentId(), all);
        }
    }

    /** Takes {@code view} out of the input stack and closes it (scripts, bindings and monitor with it). */
    public static void close(UiDocumentView view) {
        GameUiInput.get().close(view);
        view.close();
    }

    /**
     * Opens the shipped screen {@code id} ({@code ui/documents/<id>.sbui} on the game classpath,
     * C14) bound to the game's host through {@link #openBound}.
     *
     * @throws java.io.FileNotFoundException when the game ships no such screen
     */
    public static UiDocumentView openScreen(String id, Supplier<Typeface> typeface, UiScriptServices services,
                                            UiLayer layer) throws IOException {
        UiDocumentView view = openBound(readScreen(id), Map.of(), typeface, GameUiProviders.all(),
            GameUiHost.get().host(), null, services, layer, true);
        monitor(view, id, layer);
        return view;
    }

    /**
     * The runtime budgets of {@code view} in {@code layer}: HUD-layer documents run every gameplay
     * frame and get the tighter {@link UiBudgets#HUD} (#296 review).
     */
    static UiBudgets budgets(UiDocumentView view, UiLayer layer) {
        return UiBudgets.forDocument(view.instance().document(), layer == UiLayer.HUD);
    }

    /** The shipped export of screen {@code id}, read with the runtime's stale-cache policy. */
    public static SbuiArchive readScreen(String id) throws IOException {
        String path = DocumentScreenPolicy.resourcePath(id);
        byte[] bytes;
        try (java.io.InputStream in = GameUiDocuments.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new java.io.FileNotFoundException("no shipped UI document " + path);
            }
            bytes = in.readAllBytes();
        }
        return SbuiReader.read(bytes, SbuiReader.Options.RUNTIME).archive();
    }

    /**
     * Connects an open view to {@code host}: bindings, Lua/graph host calls and their lifetime go
     * through one scope that closes with the view. The game passes {@link GameUiHost}; the editor
     * preview a {@code FixtureHost}. Prefer {@link #scripts}, which also runs code-behind.
     */
    public static UiDocumentView bind(UiDocumentView view, UiHost host, UiConverters converters) {
        return view.bind(UiBinder.open(view.instance(), host, converters));
    }

    /**
     * Binds {@code view} to {@code host} (when given) and runs its Lua code-behind (#292): the
     * scripts' converters come first, then {@code fallback}. The runtime closes with the view.
     */
    public static UiScriptRuntime scripts(UiDocumentView view, UiHost host, UiConverters fallback,
                                          UiScriptServices services) {
        // Hard limits from the document's budgets (#296): 4 MiB / 50 ms, 32 MiB for Canvas minigames
        UiScriptOptions options = UiBudgets.forDocument(view.instance().document()).scriptOptions();
        return UiScripts.open(view, host, fallback, options, services);
    }

    /**
     * Attaches the runtime budget monitor (#296) to {@code view} and lists it under {@code name}
     * in the F3 overlay's UI card; overruns are logged. Call after {@link #scripts} so the first
     * frame is not charged with loading the code-behind. It closes with the view.
     */
    public static UiFrameMonitor monitor(UiDocumentView view, String name) {
        return monitor(view, name, UiLayer.OVERLAY);
    }

    /** As {@link #monitor(UiDocumentView, String)} with the budgets of {@code layer} (HUD: every gameplay frame). */
    public static UiFrameMonitor monitor(UiDocumentView view, String name, UiLayer layer) {
        UiFrameMonitor m = UiFrameMonitor.attach(view, budgets(view, layer));
        GameUiDiagnostics.register(name, m);
        return m;
    }

    /**
     * As {@link #scripts(UiDocumentView, UiHost, UiConverters, UiScriptServices)} with explicit
     * options: the editor preview passes {@code withGraphDebug(true)} so graphs (#291) compile
     * with trace calls for its highlighting, watches and breakpoints. The game never does.
     */
    public static UiScriptRuntime scripts(UiDocumentView view, UiHost host, UiConverters fallback,
                                          UiScriptServices services, UiScriptOptions options) {
        return UiScripts.open(view, host, fallback, options, services);
    }

    /** Activation findings of an already open view's document against {@code host} (dev overlay, preview). */
    public static List<UiDiagnostic> activationGate(UiDocumentView view, UiHost host) {
        return UiActivation.check(view.instance().document(), view.instance().context().source(), host);
    }

    /**
     * Re-reads {@code file} into {@code view}, keeping instance state of surviving elements, then
     * hot-swaps its code-behind (a module that no longer compiles keeps the previous version).
     */
    public static UiDocumentInstance.ReloadReport reload(UiDocumentView view, Path file) throws IOException {
        UiDocumentInstance.ReloadReport report = view.instance().reload(read(file));
        UiScriptRuntime scripts = UiScripts.of(view);
        if (scripts != null) {
            scripts.reload();
        }
        return report;
    }

    private static UiDocumentView view(OmuiArchive doc, AssetResolver resolver, List<AssetSource> sources,
                                       Supplier<Typeface> typeface, Map<String, UiPaintHost.UiDrawProvider> providers) {
        return view(doc, new ResolvedUiAssets(resolver, sources, MTextureRegistry.cache()), typeface, providers);
    }

    private static UiDocumentView view(OmuiArchive doc, ResolvedUiAssets assets, Supplier<Typeface> typeface,
                                       Map<String, UiPaintHost.UiDrawProvider> providers) {
        UiPaintHost host = assets.paintHost(providers);
        MasonryContentMeasurer text = new MasonryContentMeasurer(typeface, host);
        UiRuntimeContext context = UiRuntimeContext.basic().withSource(assets).withMeasurer(text);
        return new UiDocumentView(UiDocumentInstance.instantiate(doc, context), new UiPainter(host, text));
    }

    /**
     * One host frame of a document (#295): the {@code game} clock advances only while gameplay
     * runs, so clips on it freeze under the pause menu, while the UI clock (and with it every
     * transition, tween and UI clip) always advances by the unscaled frame time {@code dt}.
     *
     * @param gameRunning false while gameplay is paused or not in a world
     */
    public static void frame(UiDocumentView view, double dt, boolean gameRunning) {
        frame(view, dt, gameRunning ? dt : 0);
    }

    /**
     * One host frame with separate clocks: {@code uiDt} for the {@code ui} clock and scripts,
     * {@code gameDt} (simulation time stepped this frame, {@link UiFrameClock#gameDt()}) for the
     * {@code game} clock.
     */
    public static void frame(UiDocumentView view, double uiDt, double gameDt) {
        if (gameDt > 0) {
            view.instance().clocks().advance(UiClocks.GAME, gameDt);
        }
        view.frame(uiDt);
    }

    /** True while gameplay advances (in a world and not paused): what {@link #frame}'s game clock follows. */
    public static boolean gameRunning() {
        Game game = Game.getInstance();
        return game != null && game.getState() == GameState.PLAYING && !game.isPaused();
    }

    /**
     * Paints {@code view} as one Masonry frame over whatever is on screen: layout, then the draw
     * providers' GL phase (item icons, model previews render into their textures) outside the
     * Skia frame, then the paint inside it.
     *
     * <p>UI space is framebuffer pixels: {@code width}/{@code height} are the framebuffer size
     * ({@code GameWindow.width()}, from {@code glfwGetFramebufferSize}) and pointer input arrives in
     * the same space ({@code GameWindow.toUiX}), so layout and hit-testing agree on HiDPI
     * displays. The pixel ratio stays 1, as for every legacy Skija screen: the player's UI scale
     * setting is the only magnification, so documents match the legacy screens they replace.
     *
     * @param masonry a per-view Masonry handle on the game backend
     */
    public static void render(UiDocumentView view, MasonryUI masonry, int width, int height, float uiScale) {
        view.layout(width, height, uiScale, 1f);
        view.prepareProviders();
        if (!masonry.beginFrame(width, height, 1f)) {
            return;
        }
        try {
            view.paint(masonry);
        } finally {
            masonry.endFrame();
        }
    }
}
