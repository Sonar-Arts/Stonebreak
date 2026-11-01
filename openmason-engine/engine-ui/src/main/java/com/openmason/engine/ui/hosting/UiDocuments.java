package com.openmason.engine.ui.hosting;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.diag.UiBudgets;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import com.openmason.engine.ui.runtime.binding.UiActivation;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import com.openmason.engine.ui.runtime.paint.MasonryContentMeasurer;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import com.openmason.engine.ui.script.UiScriptOptions;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.script.UiScriptServices;
import com.openmason.engine.ui.script.UiScripts;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hosts OMUI/SBUI documents for any host (#381): the runtime context, asset resolution, texture
 * cache, font measurer and painter a document view needs, and its bindings, code-behind and
 * frame. Moved from the game's {@code GameUiDocuments}, which keeps only what is the game's own
 * (its host, input stack, shipped screens and budgets monitor).
 *
 * <p>A host supplies what is specific to it: the asset sources shared rows resolve through
 * (project files, a game's packaged root, packs), the {@link MTextureCache} that owns the
 * textures, the typeface, the draw providers, and the {@link UiHost} documents bind to (a game's
 * host, or the editor's fixtures).
 */
public final class UiDocuments {

    private UiDocuments() {
    }

    /** {@code first}, then each of {@code fallback} whose name {@code first} does not already list. */
    public static List<AssetSource> withFallback(List<? extends AssetSource> first, List<? extends AssetSource> fallback) {
        List<AssetSource> sources = new ArrayList<>(first);
        for (AssetSource s : fallback) {
            if (sources.stream().noneMatch(x -> x.name().equals(s.name()))) {
                sources.add(s);
            }
        }
        return sources;
    }

    /** The editable tree of a {@code .sbui} (its embedded source) or {@code .omui} file. */
    public static OmuiArchive read(Path file) throws IOException {
        if (file.getFileName().toString().endsWith(".sbui")) {
            return SbuiReader.read(file, SbuiReader.Options.RUNTIME).archive().source();
        }
        return OmuiReader.read(file).archive();
    }

    /**
     * The assets of an exported screen: its own resolution table over {@code sources}, plus the
     * graph Lua compiled at export ({@code derived/}, #291).
     */
    public static ResolvedUiAssets exportAssets(SbuiArchive sbui, List<? extends AssetSource> sources,
                                                MTextureCache textures) {
        return new ResolvedUiAssets(AssetResolver.forExport(sbui, sources), sources, textures).withDerived(sbui);
    }

    /** A view of an exported screen whose shared rows resolve through {@code sources}. */
    public static UiDocumentView open(SbuiArchive sbui, List<? extends AssetSource> sources, MTextureCache textures,
                                      Supplier<Typeface> typeface, Map<String, UiPaintHost.UiDrawProvider> providers) {
        return view(sbui.source(), exportAssets(sbui, sources, textures), typeface, providers);
    }

    /** A view of an authoring document: embedded rows, then {@code sources} in order. */
    public static UiDocumentView open(OmuiArchive omui, List<? extends AssetSource> sources, MTextureCache textures,
                                      Supplier<Typeface> typeface, Map<String, UiPaintHost.UiDrawProvider> providers) {
        return view(omui, new ResolvedUiAssets(AssetResolver.forDocument(omui, sources), sources, textures),
                typeface, providers);
    }

    /** A view of {@code doc} over already resolved assets: built-in widgets, font measurer, painter. */
    public static UiDocumentView view(OmuiArchive doc, ResolvedUiAssets assets, Supplier<Typeface> typeface,
                                      Map<String, UiPaintHost.UiDrawProvider> providers) {
        UiPaintHost host = assets.paintHost(providers);
        MasonryContentMeasurer text = new MasonryContentMeasurer(typeface, host);
        UiRuntimeContext context = UiRuntimeContext.basic().withSource(assets).withMeasurer(text);
        return new UiDocumentView(UiDocumentInstance.instantiate(doc, context), new UiPainter(host, text));
    }

    /**
     * Connects an open view to {@code host}: bindings, Lua/graph host calls and their lifetime go
     * through one scope that closes with the view. Prefer {@link #scripts}, which also runs code-behind.
     */
    public static UiDocumentView bind(UiDocumentView view, UiHost host, UiConverters converters) {
        return view.bind(UiBinder.open(view.instance(), host, converters));
    }

    /**
     * Binds {@code view} to {@code host} (when given) and runs its Lua code-behind (#292) under the
     * document's budgets (#296): the scripts' converters come first, then {@code fallback}. The
     * runtime closes with the view.
     */
    public static UiScriptRuntime scripts(UiDocumentView view, UiHost host, UiConverters fallback,
                                          UiScriptServices services) {
        return scripts(view, host, fallback, services, UiBudgets.forDocument(view.instance().document()).scriptOptions());
    }

    /**
     * As {@link #scripts(UiDocumentView, UiHost, UiConverters, UiScriptServices)} with explicit
     * options (an editor preview passes {@code withGraphDebug(true)} for graph tracing).
     */
    public static UiScriptRuntime scripts(UiDocumentView view, UiHost host, UiConverters fallback,
                                          UiScriptServices services, UiScriptOptions options) {
        return UiScripts.open(view, host, fallback, options, services);
    }

    /** Activation findings of an already open view's document against {@code host}. */
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

    /**
     * One host frame (#295): {@code uiDt} drives the {@code ui} clock and scripts, {@code gameDt}
     * (simulation time stepped this frame; 0 while the host's game is paused) the {@code game} clock.
     */
    public static void frame(UiDocumentView view, double uiDt, double gameDt) {
        if (gameDt > 0) {
            view.instance().clocks().advance(UiClocks.GAME, gameDt);
        }
        view.frame(uiDt);
    }

    /**
     * Paints {@code view} as one Masonry frame over whatever is on screen: layout, then the draw
     * providers' GL phase outside the Skia frame, then the paint inside it. {@code width}/{@code
     * height} are the framebuffer size, in the same pixel space pointer input arrives in; the pixel
     * ratio is 1 (the UI scale is the only magnification).
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
