package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.io.InputStream;

/**
 * A document hosted on the CPU raster reference backend with the pinned game font: the same
 * {@link UiDocumentView} + {@link UiPainter} + {@link MasonryContentMeasurer} wiring the game
 * and the editor use, read back pixel by pixel. Not a test class (no {@code Test} affix).
 */
final class RasterDocHost implements AutoCloseable {

    static final int BACKDROP = 0xFF000000;
    static final Typeface TYPEFACE = loadTypeface();

    final RasterMasonryBackend backend;
    final MasonryUI masonry;
    final MasonryContentMeasurer text;
    final UiDocumentView view;
    final int width;
    final int height;

    RasterDocHost(OmuiArchive doc, UiRuntimeContext base, UiPaintHost host, int width, int height) {
        this.width = width;
        this.height = height;
        backend = new RasterMasonryBackend(width, height, TYPEFACE, false);
        masonry = new MasonryUI(backend);
        text = new MasonryContentMeasurer(() -> TYPEFACE, host);
        UiRuntimeContext ctx = base.withMeasurer(text);
        view = new UiDocumentView(UiDocumentInstance.instantiate(doc, ctx), new UiPainter(host, text));
    }

    RasterDocHost(OmuiArchive doc, UiRuntimeContext base, int width, int height) {
        this(doc, base, UiPaintHost.NONE, width, height);
    }

    UiDocumentInstance ui() {
        return view.instance();
    }

    /** Paints one frame at {@code scale} and returns this host for chaining. */
    RasterDocHost render(float scale) {
        if (!masonry.beginFrame(width, height, 1f)) {
            throw new IllegalStateException("raster frame did not open");
        }
        try {
            masonry.canvas().clear(BACKDROP);
            view.render(masonry, width, height, scale, 1f);
        } finally {
            masonry.endFrame();
        }
        return this;
    }

    /** Unpremultiplied ARGB. */
    int color(int x, int y) {
        return backend.colorAt(x, y);
    }

    int[] pixels() {
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = backend.colorAt(x, y);
            }
        }
        return out;
    }

    @Override
    public void close() {
        view.close();
        text.close();
        masonry.dispose();
        backend.dispose();
    }

    private static Typeface loadTypeface() {
        try (InputStream in = RasterDocHost.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("pinned test font missing", e);
        }
    }
}
