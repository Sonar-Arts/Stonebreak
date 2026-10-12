package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Paint layers and host providers after the #282 hardening: wrapped labels (C3), click-through
 * and the pointer-anchored cursor layer (C2), overlay opacity, and the providers' GL phase (C1).
 */
class UiPaintLayersTest {

    private static final int RED = 0xFFC02020;
    private static final int GREEN = 0xFF20C020;
    private static final int WHITE = 0xFFFFFFFF;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    @Test
    void aWrappedLabelGrowsTallerWhenNarrowAndPaintsInsideItsRect() {
        String words = "The furnace needs fuel before it can smelt anything at all";
        OmuiArchive doc = screen("t:ui/wrap", box("root").style("align-items", "flex-start").kids(
            label("one", words),
            label("wrap", words).style("white-space", "normal").style("width", 140)));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 480, 260).render(1f)) {
            UiElement one = host.ui().find("one");
            UiElement wrap = host.ui().find("wrap");
            Font font = host.text.font(wrap, 1f);
            float line = (float) Math.ceil(font.getMetrics().getDescent() - font.getMetrics().getAscent());
            int lines = host.text.labelLayout(wrap, wrap.rect().width(), 1f).lineCount();
            assertTrue(lines >= 3, "140 px wraps the sentence onto several lines, got " + lines);
            assertEquals(line, one.rect().height(), 0.51, "nowrap stays one line (legacy)");
            assertEquals(line * lines, wrap.rect().height(), 0.51, "height = lines x line height");
            assertEquals(140, wrap.rect().width(), 0.51);
            float first = wrap.rect().y() + host.text.baseline(wrap, 140, wrap.rect().height(), 1f);
            assertEquals(wrap.rect().y() - font.getMetrics().getAscent(), first, 0.51, "baseline of the first line");
            UiRect r = wrap.rect();
            int inside = 0;
            for (int y = 0; y < host.height; y++) {
                for (int x = 0; x < host.width; x++) {
                    if (host.color(x, y) == RasterDocHost.BACKDROP || y + 0.5f < r.y() - 1) {
                        continue;
                    }
                    if (y + 0.5f > one.rect().bottom() + 3 && x + 0.5f > r.right() + 3) {
                        throw new AssertionError("wrapped text painted outside its box at " + x + "," + y);
                    }
                    inside++;
                }
            }
            assertTrue(inside > 0);
        }
    }

    @Test
    void pointerEventsNoneIsClickThroughAndInheritedUntilAutoAgain() {
        OmuiArchive doc = screen("t:ui/through", box("root").kids(
            box("under").style("position", "absolute").style("left", 0).style("top", 0).style("width", 100)
                .style("height", 100).style("background-color", "#C02020"),
            box("over").style("position", "absolute").style("left", 0).style("top", 0).style("width", 100)
                .style("height", 100).style("pointer-events", "none").kids(
                    box("ghostChild").style("width", 40).style("height", 40),
                    box("solidChild").style("width", 40).style("height", 40).style("pointer-events", "auto"))));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 120, 120).render(1f)) {
            assertSame(host.ui().find("under"), host.ui().hitTest(80, 80), "the overlay passes hits through");
            assertSame(host.ui().find("under"), host.ui().hitTest(10, 10), "pointer-events is inherited");
            assertSame(host.ui().find("solidChild"), host.ui().hitTest(10, 50), "auto opts back in");
        }
    }

    @Test
    void theCursorLayerFollowsThePointerPaintsOnTopAndIsNeverHit() {
        OmuiArchive doc = screen("t:ui/cursor", box("root").kids(
            box("panel").style("width", 120).style("height", 120).style("background-color", "#20C020"),
            box("popup").style("position", "absolute").style("left", 0).style("top", 0).style("width", 120)
                .style("height", 120).style("-sb-layer", 50).style("background-color", "#C02020").kids(
                    box("carried").style("position", "absolute").style("-sb-anchor", "pointer").style("left", 4)
                        .style("top", 6).style("width", 10).style("height", 10)
                        .style("background-color", "#FFFFFF"))));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 120, 120).render(1f)) {
            assertEquals(RED, host.color(58, 50), "no pointer yet: nothing in the cursor layer");
            host.view.pointerMove(50, 40);
            host.render(1f);
            UiElement carried = host.ui().find("carried");
            assertEquals(54, carried.rect().x(), 1e-3);
            assertEquals(46, carried.rect().y(), 1e-3);
            assertEquals(WHITE, host.color(58, 50), "painted above the higher authored layer");
            assertNotEquals(carried, host.ui().hitTest(58, 50), "the cursor layer is never hit");
            host.view.pointerMove(80, 90);
            host.render(1f);
            assertEquals(WHITE, host.color(88, 100));
            assertEquals(RED, host.color(58, 50), "the old spot is repainted");
            host.view.pointerLeave();
            host.render(1f);
            assertEquals(RED, host.color(88, 100), "hidden while the pointer is outside the frame");
        }
    }

    @Test
    void aLiftedOverlayFadesWithItsAncestors() {
        OmuiArchive doc = screen("t:ui/fade", box("root").kids(
            box("panel").style("width", 100).style("height", 100).style("opacity", 0.5).kids(
                box("pop").style("position", "absolute").style("left", 10).style("top", 10).style("width", 30)
                    .style("height", 30).style("-sb-layer", 5).style("background-color", "#FFFFFF"))));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 100, 100).render(1f)) {
            int c = host.color(20, 20);
            int g = (c >> 8) & 0xFF;
            assertEquals(128, g, 3, "white overlay at half its ancestor's opacity over black: " + Integer.toHexString(c));
            host.ui().find("panel").setStyle("opacity", com.openmason.engine.format.omui.UiValue.of(0));
            host.render(1f);
            assertEquals(RasterDocHost.BACKDROP, host.color(20, 20), "a fully faded ancestor hides its overlay");
        }
    }

    @Test
    void aMissingProviderPaintsAPlaceholderAndReportsOnce() {
        OmuiArchive doc = screen("t:ui/missing", box("root").kids(
            node("slot", "DrawProvider").prop("provider", "stonebreak:item-icon").style("width", 40)
                .style("height", 40),
            node("empty", "ItemSlot").style("width", 40).style("height", 40)));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 100, 100).render(1f).render(1f)) {
            assertTrue(has(host.ui(), UiRuntimeDiagnostic.Code.MISSING_DRAW_PROVIDER));
            long reports = host.ui().diagnostics().stream()
                .filter(d -> d.code() == UiRuntimeDiagnostic.Code.MISSING_DRAW_PROVIDER).count();
            assertEquals(1, reports, "an empty ItemSlot (no provider id) is not an error");
            int c = host.color(20, 8);
            assertTrue(((c >> 16) & 0xFF) > 100 && (c & 0xFF) > 100 && ((c >> 8) & 0xFF) < 60,
                "magenta placeholder, got " + Integer.toHexString(c));
        }
    }

    @Test
    void providersPrepareOutsideTheFrameOnlyForElementsThatWillPaint() {
        List<String> calls = new ArrayList<>();
        UiPaintHost.UiDrawProvider recorder = new UiPaintHost.UiDrawProvider() {
            @Override
            public void prepare(UiElement element, UiRect rect, float scale) {
                calls.add("prepare:" + element.key());
            }

            @Override
            public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
                calls.add("draw:" + element.key());
            }
        };
        UiPaintHost providers = UiPaintHost.of(id -> null, Map.of("rec", recorder));
        OmuiArchive doc = screen("t:ui/prepare", box("root").kids(
            node("shown", "ItemSlot").prop("provider", "rec").style("width", 20).style("height", 20),
            node("collapsed", "ItemSlot").prop("provider", "rec").style("width", 20).style("height", 20)
                .style("display", "none"),
            box("faded").style("opacity", 0).kids(
                node("inFaded", "ItemSlot").prop("provider", "rec").style("width", 20).style("height", 20)),
            box("clip").style("width", 20).style("height", 20).style("overflow", "hidden").kids(
                box("spacer").style("width", 20).style("height", 40).style("flex-shrink", 0),
                node("clipped", "ItemSlot").prop("provider", "rec").style("width", 20).style("height", 20)
                    .style("flex-shrink", 0))));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), providers, 100, 200)) {
            host.view.layout(100, 200, 1f, 1f);
            host.view.prepareProviders();
            assertEquals(List.of("prepare:shown"), calls, "collapsed, faded and clipped-away slots skip prepare");
            assertTrue(host.masonry.beginFrame(100, 200, 1f));
            try {
                host.view.paint(host.masonry);
            } finally {
                host.masonry.endFrame();
            }
            assertEquals(List.of("prepare:shown", "draw:shown"), calls, "a clipped-away provider is not drawn either");
        }
    }

    @Test
    void aSteadyFrameRunsOneUpdate() {
        OmuiArchive doc = screen("t:ui/steady", box("root").kids(label("l", "Paused")));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 100, 60).render(1f)) {
            int[] updates = {0};
            host.ui().addUpdateObserver((ui, stats) -> updates[0]++);
            host.render(1f);
            assertEquals(1, updates[0], "nothing changed after the first update, so no second one");
            host.view.pointerMove(5, 5); // hover changes a pseudo-state: the frame re-resolves
            host.render(1f);
            assertTrue(updates[0] <= 3);
        }
    }

    @Test
    void textAndCursorPropertiesAreFeatureGated() {
        OmuiArchive doc = screen("t:ui/features", box("root").kids(
            label("w", "x").style("white-space", "normal"),
            label("r", "x").prop("rich", true),
            box("c").style("-sb-anchor", "pointer"),
            box("p").style("pointer-events", "none")));
        var used = UiFeatures.used(doc);
        assertTrue(used.contains(UiFeatures.TEXT), used.toString());
        assertTrue(used.contains(UiFeatures.CURSOR), used.toString());
    }
}
