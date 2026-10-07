package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.ui.runtime.GameUiDocuments;
import io.github.humbleui.skija.Typeface;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Paints a UI document once on the CPU, through the same runtime, painter and fonts as the game
 * and the designer: project browser thumbnails, and the editor's canvas smoke tests (no GL).
 */
public final class UiSnapshot {

    private static final int BACKDROP = 0xFF1B1D21;

    private UiSnapshot() {
    }

    /**
     * @param width   frame width in device pixels
     * @param height  frame height in device pixels
     * @param uiScale device pixels per logical pixel
     */
    public static BufferedImage render(OmuiArchive doc, List<AssetSource> sources, Typeface typeface, int width,
                                       int height, float uiScale) throws java.io.IOException {
        try (UiDocumentView view = GameUiDocuments.open(doc, sources, () -> typeface, com.stonebreak.ui.runtime.GameUiProviders.skiaOnly())) {
            return render(view, typeface, width, height, uiScale);
        }
    }

    /** Paints an already open view (tests inspect the same view's geometry afterwards). */
    public static BufferedImage render(UiDocumentView view, Typeface typeface, int width, int height, float uiScale) {
        return render(view, typeface, width, height, uiScale, 1f);
    }

    /**
     * Paints an already open view at a UI scale and device pixel ratio, exactly like the designer
     * canvas does for a frame of that size.
     */
    public static BufferedImage render(UiDocumentView view, Typeface typeface, int width, int height, float uiScale,
                                       float pixelRatio) {
        RasterMasonryBackend raster = new RasterMasonryBackend(typeface, false);
        MasonryUI ui = new MasonryUI(raster);
        try {
            raster.beginFrame(width, height, 1f);
            raster.endFrame();
            if (ui.beginFrame(width, height, 1f)) {
                try {
                    ui.canvas().clear(BACKDROP);
                    view.render(ui, width, height, uiScale, pixelRatio);
                } finally {
                    ui.endFrame();
                }
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    img.setRGB(x, y, raster.colorAt(x, y));
                }
            }
            return img;
        } finally {
            ui.dispose();
            raster.dispose();
        }
    }
}
