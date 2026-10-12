package com.stonebreak.ui.runtime;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.assets.export.UiExportService;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.binding.UiActivationException;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScripts;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.screens.UiLayer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The production path of a migrated screen, end to end (#282 review): the editor's export
 * (OMUI -> SBUI with compiled graph Lua) -> shipped bytes -> {@code GameUiDocuments.openBound}
 * (activation, asset and input gates) -> scripts -> frames -> input through the game window's
 * {@link GameUiInput} -> a host action on {@link GameUiHost}. And the refusals that keep the
 * legacy screen instead of a broken one.
 */
class OpenBoundGameTest {

    static final Path GRAPH_PAUSE = Path.of("../openmason-engine/src/test/resources/ui/script/graph_pause.omui");
    static final Path SCRIPTED_PAUSE = Path.of("../openmason-engine/src/test/resources/ui/script/scripted_pause.omui");

    private static final class FakeServices implements GameUiHost.Services {
        final List<String> calls = new ArrayList<>();

        @Override public void resume() { calls.add("resume"); }
        @Override public void openStatistics() { calls.add("statistics"); }
        @Override public void openGlossary() { calls.add("glossary"); }
        @Override public void openSettings() { calls.add("settings"); }
        @Override public void quitToMenu() { calls.add("quit"); }
        @Override public int resync() { calls.add("resync"); return 42; }
        @Override public UiValue.Obj settings() {
            return com.stonebreak.ui.runtime.contracts.SettingsContract.read(com.stonebreak.config.Settings.getInstance());
        }
        @Override public void applySettings(UiValue.Obj value) { }
    }

    private static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    /** What the UI editor ships: a collect-all export, written and read back as bytes. */
    private static SbuiArchive shipped(Path omui) throws Exception {
        assumeTrue(Files.exists(omui), "engine sample missing: " + omui.toAbsolutePath());
        OmuiArchive doc = OmuiReader.read(omui).archive();
        SbuiArchive sbui = UiExportService.export(doc, GameUiAssets.sources(Map.of()),
            ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null).sbui();
        return SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
    }

    private static void frame(UiDocumentView view, double dt) {
        GameUiInput.get().frame();
        GameUiDocuments.frame(view, dt, 0);
        view.instance().update();
        view.input().sync();
        view.instance().update();
    }

    private static void click(UiElement el) {
        UiRect r = el.rect();
        float x = r.x() + r.width() / 2;
        float y = r.y() + r.height() / 2;
        GameUiInput in = GameUiInput.get();
        assertTrue(in.onMouseButton(x, y, 0, MKeys.PRESS, 0, false), "the document takes the press");
        in.onMouseButton(x, y, 0, MKeys.RELEASE, 0, false);
    }

    private static void runsThroughTheGameHost(Path sample) throws Exception {
        requireLua();
        SbuiArchive sbui = shipped(sample);
        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.HOST);
        boolean[] closeRequested = {false};
        UiDocumentView view = GameUiDocuments.openBound(sbui, Map.of(), () -> null, Map.of(), game.host(), null,
            new GameUiScriptServices(() -> closeRequested[0] = true), UiLayer.SCREEN);
        try {
            assertTrue(GameUiInput.get().isOpen(view), "openBound joins the game window's input stack");
            assertEquals(UiLayer.SCREEN, GameUiInput.get().layerOf(view));
            assertNotNull(UiScripts.of(view), "code-behind / graph Lua runs");
            view.instance().setMetrics(UiMetrics.of(800, 600, 1));
            frame(view, 0.5);
            frame(view, 0.016);
            click(view.instance().find("resume"));
            frame(view, 0.25);
            frame(view, 0.016);
            game.drain();
            assertTrue(services.calls.contains("resume"), "the button reached the host action: " + services.calls);
        } finally {
            GameUiDocuments.close(view);
        }
        assertFalse(GameUiInput.get().isOpen(view));
    }

    @Test
    void aGraphScreenRunsFromItsShippedExport() throws Exception {
        runsThroughTheGameHost(GRAPH_PAUSE);
    }

    @Test
    void aScriptedScreenRunsFromItsShippedExport() throws Exception {
        runsThroughTheGameHost(SCRIPTED_PAUSE);
    }

    @Test
    void aScreenNeedingContractsTheHostLacksIsRefused() throws Exception {
        SbuiArchive sbui = shipped(SCRIPTED_PAUSE);
        int before = GameUiInput.get().views().size();
        assertThrows(UiActivationException.class, () -> GameUiDocuments.openBound(sbui, Map.of(), () -> null,
            Map.of(), new UiHost(), null, new GameUiScriptServices(null)));
        assertEquals(before, GameUiInput.get().views().size(), "nothing was opened");
    }

    private static OmuiArchive plain(String id, List<UiNode> kids) {
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(), Map.of(), null, List.of(), null, kids,
            Map.of());
        return OmuiArchive.of(UiManifest.create(id, UiManifest.DocumentKind.SCREEN, id),
            new UiDocument(root, List.of(), null, null, Map.of()));
    }

    @Test
    void aMissingRequiredTextureRefusesTheScreenInsteadOfDrawingNothing() throws Exception {
        UiDependency missing = UiDependency.shared("t:ui/textures/nowhere", UiDependency.Kind.IMAGE,
            "0".repeat(64), 10, null);
        OmuiArchive doc = plain("t:ui/missing", List.of())
            .withDependencies(new UiDependencies(List.of(missing), Map.of()));
        SbuiArchive sbui = SbuiExporter.export(doc, SbuiExporter.Options.shared()).archive();
        UiActivationException e = assertThrows(UiActivationException.class, () -> GameUiDocuments.openBound(sbui,
            Map.of(), () -> null, Map.of(), new UiHost(), null, new GameUiScriptServices(null)));
        assertTrue(e.getMessage().contains("nowhere") || e.diagnostics().toString().contains("nowhere"),
            e.diagnostics().toString());
    }

    @Test
    void aScreenWhoseInputTheWindowCannotServeIsRefused() throws Exception {
        UiNode field = new UiNode("field", null, "TextField", 1, List.of(), Map.of(), Map.of(), null, List.of(), null,
            List.of(), Map.of());
        SbuiArchive sbui = SbuiExporter.export(plain("t:ui/name", List.of(field)), SbuiExporter.Options.shared())
            .archive();
        Locale saved = Locale.getDefault();
        int before = GameUiInput.get().views().size();
        try {
            Locale.setDefault(Locale.JAPANESE); // no IME source in GLFW
            assertThrows(IllegalStateException.class, () -> GameUiDocuments.openBound(sbui, Map.of(), () -> null,
                Map.of(), new UiHost(), null, new GameUiScriptServices(null)));
        } finally {
            Locale.setDefault(saved);
        }
        assertEquals(before, GameUiInput.get().views().size(), "the legacy screen stays");
    }
}
