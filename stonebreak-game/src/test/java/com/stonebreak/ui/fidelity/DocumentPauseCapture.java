package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.screens.UiLayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the pause menu's fidelity gate (#297): the shipped export
 * {@code ui/documents/pause.sbui}, opened exactly as the game opens it ({@code GameUiDocuments.openBound}
 * against a {@link GameUiHost}, Lua code-behind running) and painted on a {@link UiRaster}
 * stage, as the frozen legacy captures were. Variants are {@code <where>-<session>[-hover-<button>]}:
 * {@code field} paints the document twice per frame, as {@code PauseMenu.render} is called twice over
 * the world (two stacked scrims, ledger hard visual 13); {@code battle} once; {@code offline} has five
 * buttons, {@code online} six (Resync World).
 *
 * <p>Reports each button's rect (the component instance named after the legacy part), where the
 * document's own hit test answers for it, and which host action a click on it reaches. Not a test class.
 */
public final class DocumentPauseCapture implements MigrationGate.Renderer {

    public static final List<String> BUTTONS = List.of("resume", "statistics", "glossary", "settings", "resync", "quit");

    private final SbuiArchive shipped;

    public DocumentPauseCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    /** What the game ships ({@code GameUiDocuments.readScreen("pause")}). */
    public static DocumentPauseCapture shipped() throws java.io.IOException {
        return new DocumentPauseCapture(GameUiDocuments.readScreen("pause"));
    }

    /** Host services that only remember which pause action ran, in order. */
    static final class RecordingServices implements GameUiHost.Services {
        final List<String> calls = new ArrayList<>();
        /** Makes the resync action throw, as a dead connection would. */
        boolean failResync;

        @Override public void resume() { calls.add("stonebreak:screen.pause.resume"); }
        @Override public void openStatistics() { calls.add("stonebreak:screen.pause.statistics"); }
        @Override public void openGlossary() { calls.add("stonebreak:screen.pause.glossary"); }
        @Override public void openSettings() { calls.add("stonebreak:screen.pause.settings"); }
        @Override public void quitToMenu() { calls.add("stonebreak:screen.pause.quit"); }
        @Override public int resync() {
            calls.add("stonebreak:network.resync");
            if (failResync) {
                throw new IllegalStateException("connection lost");
            }
            return 7;
        }
        @Override public UiValue.Obj settings() {
            return com.stonebreak.ui.runtime.contracts.SettingsContract.read(com.stonebreak.config.Settings.getInstance());
        }
        @Override public void applySettings(UiValue.Obj value) { }
    }

    /** The document open on a raster stage, bound to a recording game host. */
    static final class Stage implements AutoCloseable {
        final UiRaster raster;
        final RecordingServices services = new RecordingServices();
        final GameUiHost host;
        final UiDocumentView view;
        final MasonryUI masonry;
        final float scale;

        Stage(SbuiArchive sbui, int w, int h, float scale, boolean online) throws Exception {
            raster = new UiRaster(w, h, scale);
            this.scale = scale;
            host = new GameUiHost(services, online ? MultiplayerSession.Mode.HOST : MultiplayerSession.Mode.SINGLEPLAYER);
            view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface, Map.of(),
                host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
            masonry = new MasonryUI(raster.backend());
            settle();
        }

        /** Scripts, bindings and layout caught up (the game's frame, minus painting). */
        void settle() {
            for (int i = 0; i < 3; i++) {
                host.drain();
                GameUiDocuments.frame(view, 0.016, 0);
                view.layout(raster.width, raster.height, scale, 1f);
            }
        }

        void paint() {
            GameUiDocuments.render(view, masonry, raster.width, raster.height, scale);
        }

        UiElement button(String name) {
            return view.instance().q("#" + name);
        }

        @Override
        public void close() {
            try {
                masonry.dispose();
                GameUiDocuments.close(view);
            } finally {
                raster.close();
            }
        }
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        String[] v = c.variant().split("-");
        int passes = v[0].equals("field") ? 2 : 1;
        boolean online = v[1].equals("online");
        String hover = v.length >= 4 && v[2].equals("hover") ? v[3] : "";
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (Stage stage = new Stage(shipped, w, h, c.viewport().uiScale(), online)) {
            if (!hover.isEmpty()) {
                UiRect r = stage.button(hover).rect();
                stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                stage.settle();
            }
            for (int i = 0; i < passes; i++) {
                stage.paint();
            }
            Map<String, float[]> rects = rects(stage);
            Map<String, float[]> hits = hits(stage, rects, w, h);
            Map<String, String> actions = actions(stage, rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, hits, actions);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the pause document for " + c.id(), e);
        }
    }

    /** Rects named as in the legacy oracle: the panel and every displayed button. */
    private static Map<String, float[]> rects(Stage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "panel", stage.view.instance().q("#panel"));
        for (String b : BUTTONS) {
            UiElement el = stage.button(b);
            if (el != null && !el.isCollapsed()) {
                put(out, b, el);
            }
        }
        return out;
    }

    private static void put(Map<String, float[]> out, String name, UiElement el) {
        if (el != null) {
            UiRect r = el.rect();
            out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
        }
    }

    /** Where the document's hit test answers for each button ({@link DocumentStage#probeRegion}). */
    private static Map<String, float[]> hits(Stage stage, Map<String, float[]> rects, int w, int h) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String b : BUTTONS) {
            float[] r = rects.get(b);
            if (r == null) {
                continue;
            }
            UiElement button = stage.button(b);
            out.put(b, DocumentStage.probeRegion((x, y) -> within(stage.view.instance().hitTest(x, y), button), r, w, h));
        }
        return out;
    }

    private static boolean within(UiElement hit, UiElement ancestor) {
        for (UiElement e = hit; e != null; e = e.parent()) {
            if (e == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The host action each displayed button's click reaches (exactly one per click). */
    private static Map<String, String> actions(Stage stage, Map<String, float[]> rects) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String b : BUTTONS) {
            float[] r = rects.get(b);
            if (r == null) {
                continue;
            }
            int before = stage.services.calls.size();
            float cx = r[0] + r[2] / 2f;
            float cy = r[1] + r[3] / 2f;
            stage.view.pointerMove(cx, cy);
            stage.view.pointerDown(cx, cy);
            stage.view.pointerUp(cx, cy);
            stage.settle();
            List<String> fired = stage.services.calls.subList(before, stage.services.calls.size());
            out.put(b, fired.size() == 1 ? fired.getFirst() : "fired " + fired);
        }
        return out;
    }
}
