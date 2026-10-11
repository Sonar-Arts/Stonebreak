package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;

/**
 * The legacy side of the battle HUD's gate (#301): the screen's own {@code SkijaFocusBattleRenderer.paintHud} over a
 * {@link LegacyUiRaster}, after the scene's frames and the frame preparation the game does. Not a test class.
 */
public final class LegacyBattleHudCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            FakeBattleView view = new FakeBattleView();
            FocusBattleScreen screen = BattleHudFixtures.screen(c.variant(), view, w, h);
            float[] p = BattleHudFixtures.pointer(c.variant(), w, h);
            if (p != null) {
                screen.handleMouseMove(p[0], p[1], w, h);
            }
            MasonryUI ui = new MasonryUI(raster.backend());
            screen.renderer().paintHud(ui, ui.canvas(), w, h, Settings.getInstance().getUiScale(), view,
                screen.menuState(), screen.animState(), screen.lastViewProjection());
            return new MigrationGate.Capture(raster.capture(), BattleHudFixtures.rects(screen, w, h));
        }
    }
}
