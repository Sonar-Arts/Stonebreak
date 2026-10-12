package com.openmason.main.systems.uiPreview;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.ui.data.FixtureHost;
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
import com.openmason.engine.ui.script.UiScriptServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The #292 acceptance screen in Open Mason: the preview's path ({@code GameUiDocuments.scripts}
 * against the archive's {@link FixtureHost}) runs the Lua-authored sample in the tool's own JVM
 * and module graph, with fixture answers instead of game services.
 */
class ScriptedPreviewTest {

    static final Path SAMPLE = Path.of("../openmason-engine/src/test/resources/ui/script/scripted_pause.omui");

    @Test
    void theScriptedSampleRunsOnTheFixtureHost() throws Exception {
        // UI scripting has no fallback: fail, never skip silently, without the native library (#292).
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        assumeTrue(Files.exists(SAMPLE), "engine sample missing: " + SAMPLE.toAbsolutePath());
        OmuiArchive doc = OmuiReader.read(SAMPLE).archive();
        FixtureHost fixture = FixtureHost.forArchive(doc);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        ui.setMetrics(UiMetrics.of(800, 600, 1));
        ui.update();
        UiDocumentView view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, null));
        try {
            UiScriptRuntime scripts = GameUiDocuments.scripts(view, fixture.host(), null, UiScriptServices.NONE);
            assertTrue(scripts.diagnostics().isEmpty(), scripts.diagnostics().toString());
            frame(view, 0.5);
            assertEquals("flex", ui.find("online").computedStyle().keyword("display", "?"));
            click(view, ui.find("resync"));
            fixture.host().drain();
            frame(view, 0.016);
            assertEquals("Audited 12 chunks (1)", ui.find("status").text("text"));
            assertEquals("stonebreak:network.resync", fixture.calls().getFirst().actionId());
        } finally {
            view.close();
        }
    }

    private static void frame(UiDocumentView view, double dt) {
        view.frame(dt);
        view.instance().update();
        view.input().sync();
        view.instance().update();
    }

    private static void click(UiDocumentView view, UiElement el) {
        UiRect r = el.rect();
        view.input().pointerDown(r.x() + r.width() / 2, r.y() + r.height() / 2, PointerEvent.PRIMARY, 0);
        view.input().pointerUp(r.x() + r.width() / 2, r.y() + r.height() / 2, PointerEvent.PRIMARY, 0);
        view.instance().update();
    }
}
