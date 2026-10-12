package com.stonebreak.ui.runtime;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.graph.GraphDerived;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The #291 acceptance screen in Stonebreak: the engine's graph-only pause sample (the archive the
 * Open Mason preview runs on its fixture host), exported to an SBUI whose {@code derived/} Lua
 * the game runs as is, on {@link GameUiHost}: open animation, conditional visibility, pause
 * navigation and an awaited resync, with no code-behind at all.
 */
class GraphScreenGameTest {

    static final Path SAMPLE = Path.of("../openmason-engine/src/test/resources/ui/script/graph_pause.omui");

    private static final class FakeServices implements GameUiHost.Services {
        final List<String> calls = new ArrayList<>();

        @Override public void resume() { calls.add("resume"); }
        @Override public void openStatistics() { calls.add("statistics"); }
        @Override public void openGlossary() { calls.add("glossary"); }
        @Override public void openSettings() { calls.add("settings"); }
        @Override public void quitToMenu() { calls.add("quit"); }
        @Override public int resync() { calls.add("resync"); return 42; }
        @Override public UiValue.Obj settings() {
            return new UiValue.Obj(Map.of("uiScale", UiValue.of(1), "uiTextScale", UiValue.of(1),
                "reducedMotion", UiValue.FALSE, "renderDistance", UiValue.of(8), "maxFps", UiValue.of(120)));
        }
        @Override public void applySettings(UiValue.Obj value) { }
    }

    @Test
    void theGraphSampleRunsFromItsDerivedLuaOnTheGameHost() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        assumeTrue(Files.exists(SAMPLE), "engine sample missing: " + SAMPLE.toAbsolutePath());
        OmuiArchive doc = OmuiReader.read(SAMPLE).archive();
        SbuiArchive exported = SbuiExporter.export(doc, new SbuiExporter.Options(null, Map.of(), false, Map.of(),
            GraphDerived.compileAll(doc, UiDocumentSource.EMPTY))).archive();
        SbuiArchive sbui = SbuiReader.read(SbuiWriter.write(exported), SbuiReader.Options.RUNTIME).archive();
        String derived = new String(sbui.derived().get("derived/" + GraphDerived.name("pause")).toArray(),
            StandardCharsets.UTF_8);

        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.HOST);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forExport(sbui, List.of()), List.of(),
            MTextureRegistry.cache()).withDerived(sbui);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(sbui.source(), UiRuntimeContext.basic().withSource(assets));
        ui.setMetrics(UiMetrics.of(800, 600, 1));
        ui.update();
        UiDocumentView view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, null));
        boolean[] closed = {false};
        try {
            UiScriptRuntime scripts = GameUiDocuments.scripts(view, game.host(), null,
                new GameUiScriptServices(() -> closed[0] = true));
            assertTrue(scripts.diagnostics().isEmpty(), "the derived cache was current: " + scripts.diagnostics());
            assertEquals(Map.of("pause", derived), scripts.graphChunks(), "the game ran the shipped Lua");

            frame(view, 0.5);
            assertEquals(1, ui.find("panel").computedStyle().number("opacity", 0), 1e-6, "opened with its fade-in");
            assertEquals("flex", ui.find("online").computedStyle().keyword("display", "?"), "hosting is online");
            assertEquals("flex", ui.find("resync").computedStyle().keyword("display", "?"));
            assertEquals("resume", view.input().focus().focused().key());

            click(view, ui.find("resync"));
            assertEquals("Resyncing...", ui.find("status").text("text"));
            game.drain();
            frame(view, 0.016);
            assertEquals("Audited 42 chunks (1)", ui.find("status").text("text"));

            click(view, ui.find("resume"));
            frame(view, 0.25);
            frame(view, 0.016);
            game.drain();
            click(view, ui.find("quit"));
            game.drain();
            assertEquals(List.of("resync", "resume", "quit"), services.calls);
        } finally {
            view.close();
        }
    }

    private static void frame(UiDocumentView view, double dt) {
        view.input().tick(dt);
        view.frame(dt);
        view.instance().update();
        view.input().sync();
        view.instance().update();
    }

    private static void click(UiDocumentView view, UiElement el) {
        UiRect r = el.rect();
        float x = r.x() + r.width() / 2;
        float y = r.y() + r.height() / 2;
        view.input().pointerDown(x, y, PointerEvent.PRIMARY, 0);
        view.input().pointerUp(x, y, PointerEvent.PRIMARY, 0);
        view.instance().update();
    }
}
