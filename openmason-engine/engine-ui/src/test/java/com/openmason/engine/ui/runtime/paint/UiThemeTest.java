package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.UiVocabulary;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;
import com.openmason.engine.ui.theme.UiTheme;
import com.openmason.engine.ui.theme.UiThemeProvider;
import com.openmason.engine.ui.theme.UiThemeWidget;
import com.openmason.engine.ui.theme.UiThemes;
import io.github.humbleui.skija.Canvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI theme registry (#384): the engine paints named surfaces, symbols and game widgets only
 * through a host's theme, ships neutral entries alone, and a game's entries are namespaced with
 * optional bare aliases for documents written before.
 */
class UiThemeTest {

    private static final int RED = 0xFFFF0000;
    private static final int W = 80;
    private static final int H = 60;

    private static final WidgetDescriptor SLOT = WidgetDescriptor.of("game:Slot", 1, false, false, "test slot",
        List.of(new PropertyDescriptor("provider", ValueType.STRING, UiValue.NULL, "provider id")));

    /** A stand-in game: one surface (+ alias), one symbol, one provider-drawing widget. */
    private static final UiThemeProvider GAME = t -> t
        .surface("game:glass", (c, r, s) -> MPainter.fillRect(c, r.x(), r.y(), r.width(), r.height(), RED))
        .surfaceAlias("glass", "game:glass")
        .symbol("game:dot", (c, x, y, w, h, color) -> MPainter.fillRect(c, x, y, w, h, color))
        .symbolAlias("dot", "game:dot")
        .widget(new UiThemeWidget(SLOT, p -> {
            MPainter.fillRect(p.canvas(), p.rect().x(), p.rect().y(), p.rect().width(), p.rect().height(), RED);
            p.provider().run();
        }, "cell", true));

    private static UiTheme gameTheme() {
        return UiThemes.compose(UiThemes.neutral(), List.of(GAME));
    }

    @Test
    void theNeutralThemeNamesNothingAGameOwns() {
        UiTheme neutral = UiThemes.neutral();
        assertEquals(Set.of("panel", "button", "button-hover", "button-disabled", "inset"), neutral.surfaceNames());
        for (String game : List.of("hotbar", "hud", "slot", "crafting-arrow", "smiley", "heart", "nav-left")) {
            assertNull(neutral.surface(game), game);
            assertNull(neutral.symbol(game), game);
        }
        assertNotNull(neutral.symbol("chevron-up"));
        assertTrue(neutral.widgets().isEmpty());
        assertTrue(neutral.surfaceAliases().isEmpty() && neutral.symbolAliases().isEmpty());
    }

    @Test
    void aGameContributesNamespacedNamesAndBareAliasesThatNeverShadowTheEngine() {
        UiTheme t = gameTheme();
        assertSame(t.surface("game:glass"), t.surface("glass"), "the alias paints the namespaced surface");
        assertSame(t.symbol("game:dot"), t.symbol("dot"));
        assertTrue(t.names("-sb-surface").containsAll(Set.of("auto", "none", "panel", "game:glass")));
        assertFalse(t.names("-sb-surface").contains("glass"), "pickers offer canonical names");

        assertThrows(IllegalArgumentException.class, () -> UiThemes.compose(UiThemes.neutral(),
            List.of(b -> b.surface("glass", (c, r, s) -> { }))), "a game's names are namespaced");
        assertThrows(IllegalArgumentException.class, () -> UiThemes.compose(UiThemes.neutral(),
            List.of(b -> b.surface("game:x", (c, r, s) -> { }).surfaceAlias("panel", "game:x"))),
            "an alias never shadows a neutral name");
        assertThrows(IllegalArgumentException.class, () -> UiThemes.compose(UiThemes.neutral(),
            List.of(b -> b.surface("game:x", (c, r, s) -> { }).surfaceAlias("none", "game:x"))),
            "auto/none are keywords, never aliases");
    }

    @Test
    void aSurfaceTheThemeLacksPaintsNothingAndReportsOnce() {
        OmuiArchive doc = screen("t:ui/theme", box("root").style("width", "100%").style("height", "100%")
            .kids(box("pane").style("width", 40).style("height", 40).style("-sb-surface", "glass")
                .style("-sb-symbol", "dot").style("color", "#FF0000")));
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), W, H).render(1f).render(1f)) {
            assertEquals(RasterDocHost.BACKDROP, host.color(20, 20));
            assertEquals(1, count(host, UiRuntimeDiagnostic.Code.UNKNOWN_SURFACE), "reported once, not per frame");
            assertEquals(1, count(host, UiRuntimeDiagnostic.Code.UNKNOWN_SYMBOL));
        }
        UiPaintHost themed = UiPaintHost.withTheme(UiPaintHost.NONE, gameTheme());
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), themed, W, H).render(1f)) {
            assertEquals(RED, host.color(4, 4), "the game's theme paints the aliased surface");
            assertFalse(has(host.ui(), UiRuntimeDiagnostic.Code.UNKNOWN_SURFACE));
            assertFalse(has(host.ui(), UiRuntimeDiagnostic.Code.UNKNOWN_SYMBOL));
        }
    }

    @Test
    void aGameWidgetPaintsItselfAndItsProviderUnderItsLegacyAlias() {
        UiVocabulary.register(() -> Map.of("ThemeTestSlot", "game:Slot"));
        assertEquals("game:Slot", UiVocabulary.canonicalType("ThemeTestSlot"));

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
        UiPaintHost host = UiPaintHost.withTheme(UiPaintHost.of(id -> null, Map.of("rec", recorder)), gameTheme());
        WidgetRegistry widgets = WidgetRegistry.withBuiltIns().register(SLOT);
        UiRuntimeContext base = UiRuntimeContext.basic();
        UiRuntimeContext ctx = new UiRuntimeContext(widgets, base.source(), base.theme(), base.measurer(),
            base.pixelGrid(), base.localizer());
        OmuiArchive doc = screen("t:ui/slot", box("root").style("width", "100%").style("height", "100%").kids(
            node("legacy", "ThemeTestSlot").prop("provider", "rec").style("width", 20).style("height", 20),
            node("named", "game:Slot").prop("provider", "rec").style("width", 20).style("height", 20)));
        try (RasterDocHost r = new RasterDocHost(doc, ctx, host, W, H)) {
            assertFalse(has(r.ui(), UiRuntimeDiagnostic.Code.UNKNOWN_WIDGET), "the alias resolves");
            r.view.layout(W, H, 1f, 1f);
            r.view.prepareProviders();
            assertTrue(r.masonry.beginFrame(W, H, 1f));
            try {
                r.view.paint(r.masonry);
            } finally {
                r.masonry.endFrame();
            }
            assertEquals(List.of("prepare:legacy", "prepare:named", "draw:legacy", "draw:named"), calls);
            assertEquals(RED, r.color(10, 10), "the widget's own look");
            assertEquals("ThemeTestSlot", r.ui().root().children().get(0).type(), "documents are never rewritten");
        }
    }

    private static long count(RasterDocHost host, UiRuntimeDiagnostic.Code code) {
        return host.ui().diagnostics().stream().filter(d -> d.code() == code).count();
    }
}
