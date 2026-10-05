package com.openmason.engine.ui.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs.N;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.contract;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Retained Yoga layout through the runtime: legacy fidelity, flex fixtures, scaling, hits, invalidation. */
class UiLayoutTest {

    private static final float EPS = 1e-3f;
    private static final String BUTTON = "t:ui/components/button";

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static UiDocumentInstance laid(OmuiArchive doc, UiRuntimeContext ctx, UiMetrics m) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        ui.setMetrics(m);
        ui.update();
        return ui;
    }

    private static UiDocumentInstance laid(OmuiArchive doc, float w, float h) {
        return laid(doc, UiRuntimeContext.basic().withPixelGrid(UiRuntimeContext.NO_PIXEL_GRID), UiMetrics.of(w, h, 1));
    }

    private static void assertRect(UiElement e, float x, float y, float w, float h) {
        UiRect r = e.rect();
        String msg = e.key() + " was " + r;
        assertEquals(x, r.x(), EPS, msg);
        assertEquals(y, r.y(), EPS, msg);
        assertEquals(w, r.width(), EPS, msg);
        assertEquals(h, r.height(), EPS, msg);
    }

    // ── legacy fidelity ─────────────────────────────────────────────────────

    /** Today's pause menu as a document: a fixed panel centring stone buttons (a component). */
    private static OmuiArchive pauseDocument(boolean online) {
        List<N> buttons = new ArrayList<>();
        for (String id : List.of("resume", "statistics", "glossary", "settings", "resync", "quit")) {
            N b = inst(id, BUTTON, Map.of()).cls("menu-button");
            if (id.equals("resync") && !online) {
                b.style("display", "none");
            }
            buttons.add(b);
        }
        return screen("t:ui/pause", box("root").style("width", "100%").style("height", "100%")
                .style("justify-content", "center").style("align-items", "center")
                .kids(box("panel").style("width", 520).style("height", 560).style("justify-content", "center")
                    .style("align-items", "center").style("row-gap", 20).style("padding-top", 50)
                    .kids(buttons.toArray(N[]::new))),
            sheet("pause", rule(".menu-button", "-sb-tint", "#FFFFFF")));
    }

    private static UiRuntimeContext pauseContext(float grid) {
        OmuiArchive button = component(BUTTON, node("surface", "Button").cls("stone-button"),
            contract(List.of(), List.of()), sheet("button", rule(".stone-button", "width", 360, "height", 50)));
        return UiRuntimeContext.basic().withPixelGrid(grid)
            .withSource(UiDocumentSource.of(Map.of(BUTTON, button), Map.of()));
    }

    @Test
    void pauseMenuReproducesLegacyGeometryThroughDocumentsAndComponents() throws Exception {
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/ui/fixtures/legacy-geometry.json")) {
            fixture = new ObjectMapper().readTree(in);
        }
        int compared = 0;
        for (JsonNode c : fixture.get("cases")) {
            if (!c.get("screen").asText().equals("pause")) {
                continue;
            }
            int fbW = c.get("framebuffer").get(0).asInt();
            int fbH = c.get("framebuffer").get(1).asInt();
            float scale = (float) c.get("uiScale").asDouble();
            boolean online = c.get("online").asBoolean();
            for (float grid : new float[]{UiRuntimeContext.NO_PIXEL_GRID, UiRuntimeContext.DEVICE_PIXEL_GRID}) {
                try (UiDocumentInstance ui = laid(pauseDocument(online), pauseContext(grid), UiMetrics.of(fbW, fbH, scale))) {
                    var fields = c.get("rects").fields();
                    while (fields.hasNext()) {
                        var e = fields.next();
                        UiRect r = ui.find(e.getKey()).rect();
                        float[] want = new float[4];
                        boolean integral = true;
                        for (int i = 0; i < 4; i++) {
                            want[i] = (float) e.getValue().get(i).asDouble();
                            integral &= want[i] == Math.rint(want[i]);
                        }
                        // Grid off reproduces today's float math exactly; the device grid may
                        // round fractional legacy edges by at most one pixel.
                        float tol = grid == 0 || integral ? EPS : 1f + EPS;
                        String msg = fbW + "x" + fbH + " x" + scale + (online ? " online " : " ") + e.getKey()
                            + " grid " + grid + " was " + r;
                        assertEquals(want[0], r.x(), tol, msg);
                        assertEquals(want[1], r.y(), tol, msg);
                        assertEquals(want[2], r.width(), tol, msg);
                        assertEquals(want[3], r.height(), tol, msg);
                        compared++;
                    }
                    if (!online) {
                        assertTrue(ui.find("resync").isCollapsed());
                    }
                }
            }
        }
        assertTrue(compared >= 70, "compared " + compared);
    }

    // ── flexbox fixtures ────────────────────────────────────────────────────

    @Test
    void wrapPlacesOverflowOnTheNextLine() {
        OmuiArchive doc = screen("t:ui/wrap", box("root").kids(box("row").style("width", 100)
            .style("flex-direction", "row").style("flex-wrap", "wrap").kids(
                box("a").style("width", 40).style("height", 10),
                box("b").style("width", 40).style("height", 10),
                box("c").style("width", 40).style("height", 10))));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertRect(ui.find("a"), 0, 0, 40, 10);
            assertRect(ui.find("b"), 40, 0, 40, 10);
            assertRect(ui.find("c"), 0, 10, 40, 10);
        }
    }

    @Test
    void growAndShrinkShareFreeSpace() {
        OmuiArchive doc = screen("t:ui/grow", box("root").kids(
            box("grow").style("width", 300).style("height", 10).style("flex-direction", "row").kids(
                box("g1").style("flex-basis", 100).style("flex-grow", 1),
                box("g2").style("flex-basis", 100).style("flex-grow", 2)),
            box("shrink").style("width", 150).style("height", 10).style("flex-direction", "row").kids(
                box("s1").style("width", 100).style("flex-shrink", 1),
                box("s2").style("width", 100).style("flex-shrink", 1))));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertEquals(100 + 100 / 3f, ui.find("g1").rect().width(), EPS);
            assertEquals(100 + 200 / 3f, ui.find("g2").rect().width(), EPS);
            assertEquals(75, ui.find("s1").rect().width(), EPS);
            assertEquals(75, ui.find("s2").rect().width(), EPS);
        }
    }

    @Test
    void justifyAndAlignPlaceChildren() {
        OmuiArchive doc = screen("t:ui/justify", box("root").kids(box("row").style("width", 100).style("height", 50)
            .style("flex-direction", "row").style("justify-content", "space-between").style("align-items", "center")
            .kids(box("a").style("width", 10).style("height", 10),
                box("b").style("width", 10).style("height", 10),
                box("c").style("width", 10).style("height", 20).style("align-self", "flex-end"))));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertRect(ui.find("a"), 0, 20, 10, 10);
            assertRect(ui.find("b"), 45, 20, 10, 10);
            assertRect(ui.find("c"), 90, 30, 10, 20);
        }
    }

    @Test
    void absoluteAnchorsPinToCornersAndCentres() {
        OmuiArchive doc = screen("t:ui/abs", box("root").style("width", 200).style("height", 100)
            .style("padding-left", 10).style("padding-top", 10).kids(
                box("flow").style("height", 5),
                box("br").style("position", "absolute").style("right", 5).style("bottom", 5)
                    .style("width", 20).style("height", 20),
                box("mid").style("position", "absolute").style("left", "50%").style("top", "50%")
                    .style("width", 20).style("height", 20).style("margin-left", -10).style("margin-top", -10),
                box("tl").style("position", "absolute").style("left", 0).style("top", 0)
                    .style("width", 8).style("height", 8)));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertRect(ui.find("flow"), 10, 10, 190, 5);
            assertRect(ui.find("br"), 175, 75, 20, 20);
            assertRect(ui.find("mid"), 90, 40, 20, 20);
            assertRect(ui.find("tl"), 0, 0, 8, 8);
        }
    }

    @Test
    void percentAndAutoMarginsResolve() {
        OmuiArchive doc = screen("t:ui/pct", box("root").style("width", 300).style("height", 100).kids(
            box("half").style("width", "50%").style("height", 10),
            box("centred").style("width", 60).style("height", 10).style("margin-left", "auto").style("margin-right", "auto")));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertRect(ui.find("half"), 0, 0, 150, 10);
            assertRect(ui.find("centred"), 120, 10, 60, 10);
        }
    }

    @Test
    void measuredLabelsSizeFromTheHostAndScale() {
        OmuiArchive doc = screen("t:ui/text", box("root").style("align-items", "flex-start")
            .kids(label("hello", "Hello"), label("long", "Hello world")));
        UiRuntimeContext ctx = UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT);
        try (UiDocumentInstance ui = laid(doc, ctx, UiMetrics.of(800, 600, 1))) {
            assertRect(ui.find("hello"), 0, 0, 40, 16);
            assertRect(ui.find("long"), 0, 16, 88, 16);
        }
        try (UiDocumentInstance ui = laid(doc, ctx, UiMetrics.of(800, 600, 1.5f))) {
            assertRect(ui.find("hello"), 0, 0, 60, 24);
        }
    }

    // ── scale, DPI and viewport ─────────────────────────────────────────────

    @Test
    void uiScaleAndPixelRatioScaleGeometryIndependently() {
        UiRuntimeContext ctx = pauseContext(UiRuntimeContext.NO_PIXEL_GRID);
        try (UiDocumentInstance base = laid(pauseDocument(true), ctx, new UiMetrics(800, 600, 1, 1));
             UiDocumentInstance scaled = laid(pauseDocument(true), ctx, new UiMetrics(1600, 1200, 2, 1));
             UiDocumentInstance hiDpi = laid(pauseDocument(true), ctx, new UiMetrics(1600, 1200, 1, 2))) {
            for (UiElement e : base.elements()) {
                UiRect r = e.rect();
                for (UiDocumentInstance other : List.of(scaled, hiDpi)) {
                    UiRect o = other.find(e.key()).rect();
                    assertEquals(r.x() * 2, o.x(), EPS, e.key());
                    assertEquals(r.y() * 2, o.y(), EPS, e.key());
                    assertEquals(r.width() * 2, o.width(), EPS, e.key());
                }
            }
        }
    }

    @Test
    void narrowAndWideWindowsKeepThePanelCentred() {
        UiRuntimeContext ctx = pauseContext(UiRuntimeContext.DEVICE_PIXEL_GRID);
        try (UiDocumentInstance ui = laid(pauseDocument(false), ctx, UiMetrics.of(640, 1080, 1))) {
            assertRect(ui.find("panel"), 60, 260, 520, 560);
            ui.setMetrics(UiMetrics.of(3440, 1440, 1));
            UiDocumentInstance.UpdateStats stats = ui.update();
            assertEquals(0, stats.recordsPushed(), "a viewport change re-lays out without re-pushing styles");
            assertTrue(stats.laidOut());
            assertRect(ui.find("panel"), 1460, 440, 520, 560);
        }
    }

    // ── hits and visibility ─────────────────────────────────────────────────

    @Test
    void renderAndHitRegionsAgreeAtEveryScale() {
        for (float scale : new float[]{0.75f, 1f, 1.25f, 2f}) {
            UiRuntimeContext ctx = pauseContext(UiRuntimeContext.DEVICE_PIXEL_GRID);
            try (UiDocumentInstance ui = laid(pauseDocument(true), ctx, UiMetrics.of(1921, 1081, scale))) {
                for (UiElement e : ui.elements()) {
                    UiRect r = e.rect();
                    if (!e.isPickable() || r.isEmpty()) {
                        continue;
                    }
                    for (float[] p : new float[][]{{r.x() + r.width() / 2, r.y() + r.height() / 2},
                        {r.x(), r.y()}, {r.right(), r.bottom()}}) {
                        UiElement hit = ui.hitTest(p[0], p[1]);
                        assertTrue(hit != null && isSelfOrDescendant(hit, e) || covers(ui, hit, p),
                            "scale " + scale + ": " + e.key() + " at " + p[0] + "," + p[1] + " hit " + hit);
                    }
                }
            }
        }
    }

    private static boolean isSelfOrDescendant(UiElement hit, UiElement e) {
        for (UiElement x = hit; x != null; x = x.parent()) {
            if (x == e) {
                return true;
            }
        }
        return false;
    }

    /** At a shared edge a later sibling may win; that is fine as long as it also covers the point. */
    private static boolean covers(UiDocumentInstance ui, UiElement hit, float[] p) {
        return hit != null && hit.rect().contains(p[0], p[1]);
    }

    @Test
    void hiddenCollapsedIgnoredAndDisabledBehaveDifferently() {
        OmuiArchive doc = screen("t:ui/vis", box("root").style("width", 300).style("height", 300).kids(
            box("hidden").style("height", 50).style("visibility", "hidden"),
            box("after").style("height", 50),
            box("gone").style("height", 50).style("display", "none"),
            box("under").style("height", 50),
            box("overlay").style("position", "absolute").style("left", 0).style("top", 100)
                .style("width", 300).style("height", 50).style("picking-mode", "ignore"),
            node("btn", "Button").style("height", 50)));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertRect(ui.find("after"), 0, 50, 300, 50);
            assertSame(ui.root(), ui.hitTest(10, 25), "hidden keeps its space but is not hit");
            assertRect(ui.find("under"), 0, 100, 300, 50);
            assertSame(ui.find("under"), ui.hitTest(10, 125), "collapsed takes no space; ignore lets hits through");
            assertTrue(ui.find("gone").isCollapsed());
            ui.find("btn").setEnabled(false);
            ui.update();
            assertSame(ui.find("btn"), ui.hitTest(10, 175), "disabled is still hit; input routing decides");
            assertFalse(ui.find("btn").isEnabledInHierarchy());
        }
    }

    @Test
    void overflowHiddenClipsHits() {
        OmuiArchive doc = screen("t:ui/clip", box("root").kids(
            box("clip").style("width", 50).style("height", 50).style("overflow", "hidden").kids(
                box("big").style("width", 200).style("height", 200).style("flex-shrink", 0))));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertSame(ui.find("big"), ui.hitTest(25, 25));
            assertSame(ui.root(), ui.hitTest(100, 100), "outside the clip the child is not hit");
        }
    }

    // ── invalidation ────────────────────────────────────────────────────────

    @Test
    void conditionalCollapseReflowsSiblings() {
        UiRuntimeContext ctx = pauseContext(UiRuntimeContext.NO_PIXEL_GRID);
        try (UiDocumentInstance ui = laid(pauseDocument(true), ctx, UiMetrics.of(1920, 1080, 1))) {
            assertRect(ui.find("resume"), 780, 365, 360, 50);
            ui.find("resync").setStyle("display", UiValue.of("none"));
            UiDocumentInstance.UpdateStats stats = ui.update();
            assertEquals(1, stats.recordsPushed(), "only the collapsed element re-pushes its record");
            assertRect(ui.find("resume"), 780, 400, 360, 50);
            assertRect(ui.find("quit"), 780, 680, 360, 50);
            ui.find("resync").clearStyle("display");
            ui.update();
            assertRect(ui.find("resume"), 780, 365, 360, 50);
        }
    }

    @Test
    void unchangedFramesDoNoWorkAndPaintOnlyChangesSkipLayout() {
        UiRuntimeContext ctx = pauseContext(UiRuntimeContext.NO_PIXEL_GRID);
        try (UiDocumentInstance ui = laid(pauseDocument(true), ctx, UiMetrics.of(1920, 1080, 1))) {
            ui.consumeDirtyRegion();
            UiDocumentInstance.UpdateStats idle = ui.update();
            assertEquals(0, idle.stylesResolved());
            assertFalse(idle.laidOut());
            assertTrue(ui.consumeDirtyRegion().isEmpty());

            UiElement surface = ui.find("statistics/surface");
            surface.setStyle("-sb-tint", UiValue.of("#FF0000"));
            UiDocumentInstance.UpdateStats paint = ui.update();
            assertEquals(1, paint.stylesResolved());
            assertEquals(0, paint.recordsPushed());
            assertFalse(paint.laidOut(), "a paint-only change never reaches Yoga");
            assertEquals(surface.rect(), ui.consumeDirtyRegion(), "only the restyled element needs repainting");
        }
    }

    @Test
    void contentChangesRemeasureOnlyTheAffectedRegion() {
        OmuiArchive doc = screen("t:ui/content", box("root").style("align-items", "flex-start").kids(
            box("top").style("width", 100).style("height", 100),
            box("row").style("flex-direction", "row").kids(label("name", "Bob"), label("tail", "!"))));
        UiRuntimeContext ctx = UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT);
        try (UiDocumentInstance ui = laid(doc, ctx, UiMetrics.of(800, 600, 1))) {
            ui.consumeDirtyRegion();
            ui.find("name").setProp("text", UiValue.of("Robert"));
            UiDocumentInstance.UpdateStats stats = ui.update();
            assertEquals(1, stats.remeasured());
            assertEquals(0, stats.recordsPushed());
            assertRect(ui.find("name"), 0, 100, 48, 16);
            assertRect(ui.find("tail"), 48, 100, 8, 16);
            UiRect dirty = ui.consumeDirtyRegion();
            assertEquals(100, dirty.y(), EPS, "the untouched block above is not invalidated: " + dirty);
            assertTrue(dirty.bottom() <= 116 + EPS, dirty.toString());
            assertRect(ui.find("top"), 0, 0, 100, 100);
        }
    }

    // ── diagnostics and lifecycle ───────────────────────────────────────────

    @Test
    void cyclicPercentagesAndConflictingConstraintsAreReported() {
        OmuiArchive doc = screen("t:ui/cycle", box("root").kids(
            // A shrink-wrapped row: its width depends on the child that asks for half of it.
            box("row").style("flex-direction", "row").style("align-self", "flex-start")
                .kids(box("pct").style("width", "50%").style("height", 10)),
            box("fine").style("width", "50%"),
            box("conflict").style("min-width", 100).style("max-width", 50),
            box("over").style("position", "absolute").style("left", 0).style("right", 0).style("width", 10)));
        try (UiDocumentInstance ui = laid(doc, 800, 600)) {
            assertTrue(ui.diagnostics().stream().anyMatch(d -> d.code() == Code.CYCLIC_PERCENTAGE
                && d.element().equals("pct")), ui.diagnostics().toString());
            assertTrue(ui.diagnostics().stream().noneMatch(d -> d.code() == Code.CYCLIC_PERCENTAGE
                && d.element().equals("fine")), "a stretched child of the viewport is definite");
            assertTrue(ui.diagnostics().stream().filter(d -> d.code() == Code.CONFLICTING_CONSTRAINTS).count() >= 2);
            assertTrue(has(ui, Code.CONFLICTING_CONSTRAINTS));
        }
    }

    @Test
    void closeReleasesTheNativeTree() {
        UiDocumentInstance ui = laid(screen("t:ui/close", box("root")), 10, 10);
        assertFalse(ui.isClosed());
        ui.close();
        assertTrue(ui.isClosed());
        ui.close();
        assertNull(ui.hitTest(1000, 1000));
    }
}
