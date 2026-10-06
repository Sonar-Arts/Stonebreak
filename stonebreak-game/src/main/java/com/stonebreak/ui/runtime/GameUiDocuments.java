package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
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
import com.openmason.engine.ui.script.UiScriptOptions;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.script.UiScriptServices;
import com.openmason.engine.ui.script.UiScripts;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
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
 * <p>Screens migrate onto this in #297 onward. Until then the {@link DevDocumentOverlay} shows a
 * document over any game state for comparison with the editor preview.
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
        return open(read(file), typeface, Map.of());
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
     * Opens an exported screen bound to {@code host}, after its activation gate: a required host
     * contract, provider, feature or data root the host lacks refuses the screen before anything
     * is instantiated, so the caller keeps the legacy screen instead of showing a broken one. Its
     * Lua code-behind (#292) and behavior graphs (#291, from the SBUI's {@code derived/} Lua while it
     * is current) are loaded and opened; {@code view.frame(dt)} drives them per frame.
     *
     * @param converters Java converters for names no script declares, or null
     * @throws UiActivationException listing every unmet need
     * @throws com.openmason.engine.cenda.CendaLuaUnavailableException when the screen has
     *         code-behind and the Lua host cannot load
     */
    public static UiDocumentView openBound(SbuiArchive sbui, Map<String, Path> packs, Supplier<Typeface> typeface,
                                           Map<String, UiPaintHost.UiDrawProvider> providers, UiHost host,
                                           UiConverters converters, UiScriptServices services) throws IOException {
        List<AssetSource> sources = GameUiAssets.sources(packs);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forExport(sbui, sources), sources,
            MTextureRegistry.cache()).withDerived(sbui); // graph Lua compiled at export (#291)
        UiActivation.require(sbui, assets, host);
        UiDocumentView view = view(sbui.source(), assets, typeface, providers);
        try {
            scripts(view, host, converters, services);
            return view;
        } catch (RuntimeException e) {
            view.close();
            throw e;
        }
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
        return UiScripts.open(view, host, fallback, UiScriptOptions.DEFAULTS, services);
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
     * Paints {@code view} as one Masonry frame over whatever is on screen.
     *
     * @param masonry a per-view Masonry handle on the game backend
     */
    public static void render(UiDocumentView view, MasonryUI masonry, int width, int height, float uiScale) {
        if (!masonry.beginFrame(width, height, 1f)) {
            return;
        }
        try {
            view.render(masonry, width, height, uiScale, 1f);
        } finally {
            masonry.endFrame();
        }
    }
}
