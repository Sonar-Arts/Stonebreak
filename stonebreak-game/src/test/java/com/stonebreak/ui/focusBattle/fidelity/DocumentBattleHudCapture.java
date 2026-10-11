package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.BattleHudProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The candidate side of the battle HUD's gate (#301): {@code ui/documents/battle_hud.sbui} opened as the game opens
 * it, bound to a screen driven through the same scene, its pieces drawn by {@link BattleHudProvider}. The pointer goes
 * through the document (Lua forwards it to the screen's rules). Not a test class.
 */
public final class DocumentBattleHudCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentBattleHudCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentBattleHudCapture shipped() throws java.io.IOException {
        return new DocumentBattleHudCapture(GameUiDocuments.readScreen(FocusBattleScreen.DOCUMENT_ID));
    }

    public static DocumentStage stage(SbuiArchive sbui, FocusBattleScreen screen, int w, int h, float uiScale)
            throws Exception {
        AtomicReference<DocumentStage> holder = new AtomicReference<>();
        RecordingServices services = new RecordingServices() {
            @Override
            public FocusBattleScreen focusBattle() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }

            @Override
            public io.github.humbleui.skija.Typeface uiTypeface() {
                return holder.get() == null ? null : holder.get().raster.backend().getMinecraftTypeface();
            }
        };
        DocumentStage stage = new DocumentStage(sbui, w, h, uiScale, services, MultiplayerSession.Mode.SINGLEPLAYER,
            Map.of(BattleHudProvider.ID, new BattleHudProvider(() -> screen,
                () -> holder.get() == null ? null : holder.get().raster.backend().getMinecraftTypeface())));
        holder.set(stage);
        stage.host.battleHud().poll();
        stage.settle();
        return stage;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        int w = vp.width();
        int h = vp.height();
        FakeBattleView view = new FakeBattleView();
        // the scene's layout reads the UI scale: set it first, as the legacy raster does before its scene
        float previous = com.stonebreak.config.Settings.getInstance().getUiScale();
        com.stonebreak.config.Settings.getInstance().setUiScale(vp.uiScale());
        FocusBattleScreen screen = BattleHudFixtures.screen(c.variant(), view, w, h);
        try (DocumentStage stage = stage(shipped, screen, w, h, vp.uiScale())) {
            float[] p = BattleHudFixtures.pointer(c.variant(), w, h);
            if (p != null) {
                stage.view.pointerMove(p[0], p[1]);
                stage.settle();
                stage.host.battleHud().poll();
                stage.settle();
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the battle HUD document for " + c.id(), e);
        } finally {
            com.stonebreak.config.Settings.getInstance().setUiScale(previous);
        }
    }

    static Map<String, float[]> rects(DocumentStage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : new String[]{"mode", "enemy", "party", "command"}) {
            UiElement el = stage.view.instance().find("piece_" + n);
            if (el != null && !el.isCollapsed()) {
                UiRect r = el.rect();
                out.put(n, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
        }
        return out;
    }
}
