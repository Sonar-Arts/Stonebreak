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
import com.openmason.engine.ui.runtime.paint.MasonryContentMeasurer;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
        return view(sbui.source(), AssetResolver.forExport(sbui, sources), sources, typeface, providers);
    }

    /** A view of an authoring document (dev and tests): embedded rows plus the packaged root. */
    public static UiDocumentView open(OmuiArchive omui, Supplier<Typeface> typeface,
                                      Map<String, UiPaintHost.UiDrawProvider> providers) throws IOException {
        List<AssetSource> sources = GameUiAssets.sources(Map.of());
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

    /** Re-reads {@code file} into {@code view}, keeping instance state of surviving elements. */
    public static UiDocumentInstance.ReloadReport reload(UiDocumentView view, Path file) throws IOException {
        return view.instance().reload(read(file));
    }

    private static UiDocumentView view(OmuiArchive doc, AssetResolver resolver, List<AssetSource> sources,
                                       Supplier<Typeface> typeface, Map<String, UiPaintHost.UiDrawProvider> providers) {
        ResolvedUiAssets assets = new ResolvedUiAssets(resolver, sources, MTextureRegistry.cache());
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
