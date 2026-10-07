package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code ui-masonry} house look as style (#297): {@code -sb-surface} paints exactly what the
 * legacy Masonry painters paint, a {@code Button} with an explicit surface ignores its pseudo-states,
 * {@code -sb-text-effect} draws the legacy title stack or no shadow, and {@code -sb-pixel-grid: none}
 * keeps the fractional geometry float-math screens (pause) draw.
 */
class UiMasonryStyleTest {

    private static final int W = 161;
    private static final int H = 121;

    @Test
    void thePropertiesAreKnownCheckedAndGatedByUiMasonry() {
        for (String p : new String[]{"-sb-surface", "-sb-text-effect", "-sb-pixel-grid"}) {
            assertEquals(UiFeatures.MASONRY, UiFeatures.forStyle(p, UiValue.of("none")), p);
            assertNull(UiStyleProperties.problem(p, UiValue.of("none")), p);
        }
        assertNull(UiStyleProperties.problem("-sb-surface", UiValue.of("panel")));
        assertNotNull(UiStyleProperties.problem("-sb-surface", UiValue.of("glass")));
        assertNotNull(UiStyleProperties.problem("-sb-text-effect", UiValue.of("glow")));
        assertNotNull(UiStyleProperties.problem("-sb-pixel-grid", UiValue.of(0)));
    }

    @Test
    void noPixelGridKeepsHalfPixelCentres() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        for (String grid : new String[]{"none", "device"}) {
            OmuiArchive doc = screen("t:ui/grid", box("root").style("width", "100%").style("height", "100%")
                .style("align-items", "center").style("justify-content", "center").style("-sb-pixel-grid", grid)
                .kids(box("panel").style("width", 50).style("height", 50)));
            try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), W, H).render(1f)) {
                UiRect r = host.ui().find("panel").rect();
                if (grid.equals("none")) {
                    assertEquals(55.5f, r.x(), 1e-4f, "(161 - 50) / 2, unsnapped");
                    assertEquals(35.5f, r.y(), 1e-4f);
                    assertEquals(0f, host.ui().pixelGrid());
                } else {
                    assertEquals(0f, r.x() % 1f, "device grid snaps to whole pixels");
                    assertEquals(1f, host.ui().pixelGrid());
                }
            }
        }
    }

    @Test
    void panelSurfacePaintsTheLegacyPanel() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        int[] doc = document(box("root").style("width", "100%").style("height", "100%").kids(
            box("panel").style("position", "absolute").style("left", 20.5).style("top", 10).style("width", 120)
                .style("height", 90).style("-sb-surface", "panel")).style("-sb-pixel-grid", "none"));
        int[] legacy = reference(c -> MPainter.panel(c, 20.5f, 10, 120, 90));
        assertArrayEquals(legacy, doc, "the -sb-surface panel is MPainter.panel, pixel for pixel");
    }

    @Test
    void anExplicitButtonSurfaceIgnoresHoverWhileTheDefaultLookHighlights() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        assertFalse(Arrays.equals(button(null, false), button(null, true)), "the default Button look hovers");
        assertArrayEquals(button("button", false), button("button", true), "-sb-surface: button never hovers");
        int[] legacy = reference(c -> MPainter.stoneSurface(c, 30, 20, 100, 40,
            com.openmason.engine.ui.masonry.MStyle.BUTTON_RADIUS, com.openmason.engine.ui.masonry.MStyle.BUTTON_FILL_HI,
            com.openmason.engine.ui.masonry.MStyle.BUTTON_BORDER, com.openmason.engine.ui.masonry.MStyle.BUTTON_HIGHLIGHT,
            com.openmason.engine.ui.masonry.MStyle.BUTTON_SHADOW, com.openmason.engine.ui.masonry.MStyle.BUTTON_DROP_SHADOW,
            com.openmason.engine.ui.masonry.MStyle.BUTTON_NOISE_DARK, com.openmason.engine.ui.masonry.MStyle.BUTTON_NOISE_LIGHT));
        assertArrayEquals(legacy, button("button-hover", false), "button-hover is the legacy highlighted button");
    }

    @Test
    void textEffectsDrawTheTitleStackOrNoShadow() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        int[] title = titled("title");
        int[] plain = titled("none");
        int[] shadowed = titled(null);
        assertFalse(Arrays.equals(title, plain));
        assertFalse(Arrays.equals(plain, shadowed));
        Font font = new Font(RasterDocHost.TYPEFACE, 42);
        try {
            // label box: top 10, line 42 (ascent 31.5): baseline 41.5; centred at x 80.5
            assertArrayEquals(reference(c -> MPainter.drawTitleText(c, "PAUSED", 80.5f, 41.5f, font, 0xFFFFDC64,
                MPainter.Align.CENTER)), title, "the title stack is MPainter.drawTitleText");
            assertArrayEquals(reference(c -> MPainter.drawTextPlain(c, "PAUSED", 80.5f, 41.5f, font, 0xFFFFDC64,
                MPainter.Align.CENTER)), plain, "no shadow is the bare glyphs");
        } finally {
            font.close();
        }
    }

    private static int[] titled(String effect) {
        var lbl = label("t", "PAUSED").style("position", "absolute").style("left", 0).style("right", 0)
            .style("top", 10).style("font-size", 42).style("text-align", "center").style("color", "#FFDC64");
        if (effect != null) {
            lbl.style("-sb-text-effect", effect);
        }
        return document(box("root").style("width", "100%").style("height", "100%").style("-sb-pixel-grid", "none")
            .kids(lbl));
    }

    private static int[] button(String surface, boolean hover) {
        var b = node("b", "Button").style("position", "absolute").style("left", 30).style("top", 20)
            .style("width", 100).style("height", 40);
        if (surface != null) {
            b.style("-sb-surface", surface);
        }
        OmuiArchive doc = screen("t:ui/btn", box("root").style("width", "100%").style("height", "100%").kids(b));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), W, H)) {
            host.render(1f);
            if (hover) {
                host.view.pointerMove(80, 40);
                host.render(1f);
            }
            return host.pixels();
        }
    }

    private static int[] document(com.openmason.engine.ui.runtime.UiDocs.N root) {
        try (RasterDocHost host = new RasterDocHost(screen("t:ui/m", root), UiRuntimeContext.basic(), W, H)
                .render(1f)) {
            return host.pixels();
        }
    }

    /** {@code paint} drawn straight with the Masonry painters on the same raster stage. */
    private static int[] reference(Consumer<Canvas> paint) {
        RasterMasonryBackend backend = new RasterMasonryBackend(W, H, RasterDocHost.TYPEFACE, false);
        MasonryUI masonry = new MasonryUI(backend);
        try {
            if (!masonry.beginFrame(W, H, 1f)) {
                throw new IllegalStateException("raster frame did not open");
            }
            try {
                masonry.canvas().clear(RasterDocHost.BACKDROP);
                paint.accept(masonry.canvas());
            } finally {
                masonry.endFrame();
            }
            int[] out = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    out[y * W + x] = backend.colorAt(x, y);
                }
            }
            return out;
        } finally {
            masonry.dispose();
            backend.dispose();
        }
    }
}
