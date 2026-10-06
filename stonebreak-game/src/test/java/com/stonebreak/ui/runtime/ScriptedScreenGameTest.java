package com.stonebreak.ui.runtime;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.stonebreak.network.MultiplayerSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The #292 acceptance screen in Stonebreak: the engine's Lua-authored sample (the same archive the
 * Open Mason preview runs on its fixture host) on the game's {@link GameUiHost}: code-behind
 * events, a binding through a Lua converter, animation and an awaited host action.
 */
class ScriptedScreenGameTest {

    static final Path SAMPLE = Path.of("../openmason-engine/src/test/resources/ui/script/scripted_pause.omui");

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
    void theScriptedSampleRunsOnTheGameHost() throws Exception {
        // UI scripting has no fallback: fail, never skip silently, without the native library (#292).
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        assumeTrue(Files.exists(SAMPLE), "engine sample missing: " + SAMPLE.toAbsolutePath());
        OmuiArchive doc = OmuiReader.read(SAMPLE).archive();
        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.HOST);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        ui.setMetrics(UiMetrics.of(800, 600, 1));
        ui.update();
        UiDocumentView view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, null));
        boolean[] closed = {false};
        try {
            UiScriptRuntime scripts = GameUiDocuments.scripts(view, game.host(), null,
                new GameUiScriptServices(() -> closed[0] = true));
            assertTrue(scripts.diagnostics().isEmpty(), scripts.diagnostics().toString());
            assertEquals(List.of("pause.lua"), scripts.modules());
            frame(view, 0.5);
            assertEquals(1, ui.find("panel").computedStyle().number("opacity", 0), 1e-6, "opened with its fade-in");
            assertEquals("flex", ui.find("online").computedStyle().keyword("display", "?"), "hosting is online");

            click(view, ui.find("resync"));
            assertEquals("Resyncing...", ui.find("status").text("text"));
            game.drain();
            frame(view, 0.016);
            assertEquals("Audited 42 chunks (1)", ui.find("status").text("text"));

            click(view, ui.find("resume"));
            frame(view, 0.25);
            frame(view, 0.016);
            game.drain();
            assertEquals(List.of("resync", "resume"), services.calls);
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
