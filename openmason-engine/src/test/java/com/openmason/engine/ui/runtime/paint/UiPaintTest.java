package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.style.StyleValues;
import io.github.humbleui.skija.Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The painter over real layouts: render regions = hit regions, text metrics, state visuals. */
class UiPaintTest {

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    /**
     * Every element opaque and pickable: overlapping flow, an overlay layer, a scrolled and
     * clipped scroll view, a translated box and an overflow-hidden clip.
     */
    private static OmuiArchive regions() {
        return screen("t:ui/regions", box("root").style("background-color", "#101010").style("padding-left", 7)
                .style("padding-top", 5).style("flex-direction", "row").style("flex-wrap", "wrap").style("column-gap", 6)
                .style("row-gap", 4).kids(
                    box("a").style("width", 40).style("height", 30).style("background-color", "#C02020"),
                    box("b").style("width", 55).style("height", 22).style("background-color", "#20C020")
                        .style("translate-x", 9).style("translate-y", 3),
                    node("scroller", "ScrollView").style("width", 60).style("height", 50)
                        .style("background-color", "#2020C0").kids(
                            box("row1").style("height", 30).style("background-color", "#C0C020"),
                            box("row2").style("height", 30).style("background-color", "#20C0C0"),
                            box("row3").style("height", 30).style("background-color", "#C020C0")),
                    box("clip").style("width", 30).style("height", 30).style("overflow", "hidden")
                        .style("background-color", "#806040").kids(
                            box("spill").style("width", 80).style("height", 12).style("flex-shrink", 0)
                                .style("background-color", "#408060")),
                    box("pop").style("position", "absolute").style("left", 30).style("top", 20).style("width", 50)
                        .style("height", 25).style("-sb-layer", 10).style("background-color", "#F0F0F0"),
                    box("late").style("width", 70).style("height", 26).style("background-color", "#604080")));
    }

    @Test
    void everyPaintedPixelBelongsToTheElementAHitReturnsAtEveryScale() {
        for (float scale : new float[]{1f, 1.5f, 2f}) {
            try (RasterDocHost host = new RasterDocHost(regions(), UiRuntimeContext.basic(), 260, 160)) {
                host.ui().find("scroller").scrollTo(0, 0); // laid out on first render
                host.render(scale);
                UiElement scroller = host.ui().find("scroller");
                scroller.scrollTo(0, 25 * scale);
                host.render(scale);
                assertEquals(25 * scale, scroller.scrollY(), 1e-3, "scroll offset clamped inside the content");
                UiRect bar = scroller.rect();
                float barLeft = bar.right() - 4 * scale;
                int compared = 0;
                for (int y = 0; y < host.height; y++) {
                    for (int x = 0; x < host.width; x++) {
                        float px = x + 0.5f;
                        float py = y + 0.5f;
                        if (px >= barLeft && bar.contains(px, py)) {
                            continue; // the scrollbar paints over its own view
                        }
                        UiElement hit = host.ui().hitTest(px, py);
                        int want = hit == null ? RasterDocHost.BACKDROP
                            : StyleValues.color(hit.computedStyle().get("background-color"), 0);
                        int got = host.color(x, y);
                        if (got != want) {
                            throw new AssertionError("scale " + scale + " pixel " + x + "," + y + " painted "
                                + Integer.toHexString(got) + " but a hit there returns " + hit + " ("
                                + Integer.toHexString(want) + ")");
                        }
                        compared++;
                    }
                }
                assertTrue(compared > host.width * host.height * 9 / 10);
            }
        }
    }

    @Test
    void labelsAreMeasuredAndPlacedByTheSharedFontMetrics() {
        OmuiArchive doc = screen("t:ui/labels", box("root").style("align-items", "flex-start").style("padding-left", 4)
            .kids(label("small", "Resume Game"),
                label("big", "Paused").style("font-size", 36).style("color", "#FFCC55"),
                box("row").style("flex-direction", "row").style("align-items", "baseline").kids(
                    label("r1", "Ab").style("font-size", 14), label("r2", "Ab").style("font-size", 40))));
        for (float scale : new float[]{1f, 1.5f}) {
            try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 360, 220).render(scale)) {
                for (String key : List.of("small", "big", "r1", "r2")) {
                    UiElement el = host.ui().find(key);
                    Font font = host.text.font(el, scale);
                    UiRect r = el.rect();
                    assertEquals(MPainter.measureWidth(font, el.text("text")), r.width(), 0.51,
                        key + " width is its advance");
                    int inside = 0;
                    int outside = 0;
                    float shadow = 2f; // house-style text shadow offset
                    for (int y = 0; y < host.height; y++) {
                        for (int x = 0; x < host.width; x++) {
                            if (host.color(x, y) == RasterDocHost.BACKDROP) {
                                continue;
                            }
                            boolean in = x + 0.5f >= r.x() - 1 && x + 0.5f <= r.right() + shadow + 1
                                && y + 0.5f >= r.y() - 1 && y + 0.5f <= r.bottom() + shadow + 1;
                            if (in) {
                                inside++;
                            } else if (owner(host, x, y) == el) {
                                outside++;
                            }
                        }
                    }
                    assertTrue(inside > 0, key + " painted no glyphs");
                    assertEquals(0, outside, key + " painted outside its rect");
                }
                UiElement r1 = host.ui().find("r1");
                UiElement r2 = host.ui().find("r2");
                float b1 = r1.rect().y() + host.text.baseline(r1, r1.rect().width(), r1.rect().height(), scale);
                float b2 = r2.rect().y() + host.text.baseline(r2, r2.rect().width(), r2.rect().height(), scale);
                assertEquals(b2, b1, 1.01, "align-items: baseline lines the text up");
            }
        }
    }

    /** The label whose rect (grown by the shadow) is nearest a pixel, for attribution. */
    private static UiElement owner(RasterDocHost host, int x, int y) {
        UiElement best = null;
        double bestD = Double.MAX_VALUE;
        for (UiElement e : host.ui().elements()) {
            if (!"Label".equals(e.type())) {
                continue;
            }
            UiRect r = e.rect();
            double dx = Math.max(0, Math.max(r.x() - x, x - r.right()));
            double dy = Math.max(0, Math.max(r.y() - y, y - r.bottom()));
            double d = dx + dy;
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static OmuiArchive stateDocument() {
        return screen("t:ui/states", box("root").style("padding-left", 10).style("padding-top", 10).style("row-gap", 10)
                .kids(node("go", "Button").name("go").style("width", 120).style("height", 40).kids(
                        label("go_text", "Go").style("text-align", "center").style("flex-grow", 1)),
                    node("stop", "Button").name("stop").style("width", 120).style("height", 40)),
            sheet("s", rule("Button:focus", "border-color", "#FFCC55", "border-left-width", 3,
                    "border-top-width", 3, "border-right-width", 3, "border-bottom-width", 3),
                rule("Label:disabled", "color", "#787878")));
    }

    @Test
    void pseudoStateRulesChangeWhatIsPaintedAndBothHostsAgree() {
        try (RasterDocHost game = new RasterDocHost(stateDocument(), UiRuntimeContext.basic(), 200, 120).render(1f);
             RasterDocHost preview = new RasterDocHost(stateDocument(), UiRuntimeContext.basic(), 200, 120).render(1f)) {
            UiRect go = game.ui().find("go").rect();
            int cx = (int) (go.x() + 8);
            int cy = (int) (go.y() + go.height() / 2);
            int idle = game.color(cx, cy);

            for (RasterDocHost h : List.of(game, preview)) {
                h.view.pointerMove(go.x() + 10, go.y() + 10);
                h.render(1f);
            }
            int hovered = game.color(cx, cy);
            assertNotEquals(idle, hovered, ":hover repaints the stone surface");
            assertArrayEquals(game.pixels(), preview.pixels(), "same document, same events, same pixels");

            for (RasterDocHost h : List.of(game, preview)) {
                h.view.pointerLeave();
                h.view.focus(h.ui().find("stop"));
                h.ui().find("go").setEnabled(false);
                h.render(1f);
            }
            UiRect stop = game.ui().find("stop").rect();
            assertEquals(0xFFFFCC55, game.color((int) stop.x() + 1, (int) (stop.y() + stop.height() / 2)),
                ":focus border");
            int disabled = game.color(cx, cy);
            assertNotEquals(idle, disabled, ":disabled repaints the button");
            assertNotEquals(hovered, disabled);
            assertArrayEquals(game.pixels(), preview.pixels(), "focus and disabled agree too");
        }
    }

    @Test
    void clickRequiresPressAndReleaseOnTheSameEnabledElement() {
        try (RasterDocHost host = new RasterDocHost(stateDocument(), UiRuntimeContext.basic(), 200, 120).render(1f)) {
            UiRect go = host.ui().find("go").rect();
            UiRect stop = host.ui().find("stop").rect();
            host.view.pointerDown(go.x() + 5, go.y() + 5);
            assertTrue(host.ui().find("go").hasState(UiElement.ACTIVE));
            assertTrue(host.ui().root().hasState(UiElement.ACTIVE), ":active applies to ancestors");
            UiElement clicked = host.view.pointerUp(go.x() + 6, go.y() + 6);
            assertEquals("go_text", clicked.key(), "the deepest element under the pointer is the target");
            assertEquals("go", clicked.parent().key(), "its button is on the chain events bubble along (#288)");
            host.view.pointerDown(go.x() + 5, go.y() + 5);
            assertEquals(null, host.view.pointerUp(stop.x() + 5, stop.y() + 5), "release elsewhere is not a click");
            host.ui().find("go").setEnabled(false);
            host.view.pointerDown(go.x() + 5, go.y() + 5);
            assertEquals(null, host.view.pointerUp(go.x() + 5, go.y() + 5), "disabled elements are not clicked");
        }
    }

    @Test
    void textureBackgroundsTintAndImagesHonourScaleModes() {
        com.openmason.engine.ui.masonry.textures.MTexture tex = UiPaintFixtures.checker();
        UiPaintHost host = UiPaintHost.of(ref -> "t:tex/checker".equals(ref) ? tex : null, java.util.Map.of());
        OmuiArchive doc = screen("t:ui/tex", box("root").style("flex-direction", "row").kids(
            node("img", "Image").prop("source", "t:tex/checker"),
            box("stretch").style("width", 40).style("height", 40).style("background-image", "t:tex/checker"),
            box("tinted").style("width", 40).style("height", 40).style("background-image", "t:tex/checker")
                .style("-sb-tint", "#FF000080")));
        try (RasterDocHost r = new RasterDocHost(doc, UiRuntimeContext.basic(), host, 160, 60).render(2f)) {
            UiRect img = r.ui().find("img").rect();
            assertEquals(4 * 2, img.width(), 1e-3, "an Image measures as its texture size x scale");
            assertEquals(0xFFFFFFFF, r.color((int) img.x(), (int) img.y()), "nearest sampling keeps texels crisp");
            assertEquals(0xFF000000, r.color((int) img.x() + 2, (int) img.y()));
            UiRect tinted = r.ui().find("tinted").rect();
            int c = r.color((int) tinted.x() + 1, (int) tinted.y() + 1);
            assertEquals(0, c & 0xFF, "the tint removes blue from white texels");
        }
        tex.close();
    }

    @Test
    void labelColourDefaultsToTheHouseTextColour() {
        OmuiArchive doc = screen("t:ui/colour", box("root").kids(label("l", "MMMM").style("font-size", 40)));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), 200, 80).render(1f)) {
            UiRect r = host.ui().find("l").rect();
            boolean found = false;
            for (int y = (int) r.y(); y < r.bottom() && !found; y++) {
                for (int x = (int) r.x(); x < r.right(); x++) {
                    if (host.color(x, y) == MStyle.TEXT_PRIMARY) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, "solid glyph pixels use MStyle.TEXT_PRIMARY");
        }
    }
}
