package com.stonebreak.ui.hotbar;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.items.Inventory;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.UiLayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the HUD's fidelity gate (#300): the shipped export {@code ui/documents/hud.sbui},
 * opened as the game opens it (a {@link GameUiHost} serving a {@link HudFixtures} player and hotbar, Lua
 * running, the game's draw providers with that player for the class gauge) and painted on the same
 * {@link LegacyUiRaster} stage as {@link LegacyHudCapture}. Rects are named as in
 * {@link HudFixtures#rects}. Not a test class.
 */
public final class DocumentHudCapture implements MigrationGate.Renderer {

    public static final String ID = "hud";

    private final SbuiArchive shipped;

    public DocumentHudCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentHudCapture shipped() throws java.io.IOException {
        return new DocumentHudCapture(GameUiDocuments.readScreen(ID));
    }

    /** The HUD open on a raster stage. */
    public static final class Stage implements AutoCloseable {
        public final LegacyUiRaster raster;
        public final HudFixtures.Hud hud;
        public final GameUiHost host;
        final GameDrawProviders providers;
        public final UiDocumentView view;
        final MasonryUI masonry;
        final float scale;

        public Stage(SbuiArchive sbui, int w, int h, float scale, HudFixtures.Hud hud) throws Exception {
            raster = new LegacyUiRaster(w, h, scale);
            this.scale = scale;
            this.hud = hud;
            host = new GameUiHost(services(hud), MultiplayerSession.Mode.SINGLEPLAYER);
            providers = new GameDrawProviders(null, raster.backend()::getMinecraftTypeface, hud::player);
            view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.HUD);
            masonry = new MasonryUI(raster.backend());
            settle();
        }

        public void settle() {
            for (int i = 0; i < 4; i++) {
                host.drain();
                GameUiDocuments.frame(view, 0.016, 0);
                view.layout(raster.width, raster.height, scale, 1f);
            }
        }

        public void paint() {
            GameUiDocuments.render(view, masonry, raster.width, raster.height, scale);
        }

        public UiElement q(String selector) {
            return view.instance().q(selector);
        }

        @Override
        public void close() {
            try {
                masonry.dispose();
                GameUiDocuments.close(view);
                providers.close();
            } finally {
                raster.close();
            }
        }
    }

    public static GameUiHost.Services services(HudFixtures.Hud hud) {
        return new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return -1; }
            @Override public UiValue.Obj settings() {
                return SettingsContract.read(com.stonebreak.config.Settings.getInstance());
            }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public Inventory inventory() { return hud.inventory(); }
            @Override public Player hudPlayer() { return hud.player(); }
            @Override public HotbarScreen hotbarScreen() { return hud.hotbar(); }
        };
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (Stage stage = new Stage(shipped, w, h, c.viewport().uiScale(), HudFixtures.hud(c.variant()))) {
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the HUD document for " + c.id(), e);
        }
    }

    static Map<String, float[]> rects(Stage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "hotbar", stage.q("#hotbar"));
        List<UiElement> slots = stage.view.instance().qAll(".hud-slot");
        for (int i = 0; i < slots.size(); i++) {
            put(out, "slot" + i, slots.get(i));
        }
        List<UiElement> hearts = stage.view.instance().qAll(".heart");
        for (int i = 0; i < hearts.size(); i++) {
            put(out, "heart" + i, hearts.get(i));
        }
        for (String bar : List.of("stamina", "mana")) {
            UiElement el = stage.q("#" + bar);
            if (el != null && !el.computedStyle().collapsed()) {
                put(out, bar, el);
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
}
