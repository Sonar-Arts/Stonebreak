package com.stonebreak.ui.hotbar;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The legacy side of the HUD's fidelity gate (#300): the real {@link MHotbarRenderer} (hotbar frame,
 * slots and selection ring, hearts, stamina and mana bars, the class gauge, the dodge indicator, counts,
 * then the selected item's tooltip) on a {@link LegacyUiRaster}, showing a {@link HudFixtures} player.
 * Its GL collaborators are mocks that never draw anything visible. Not a test class.
 */
public final class LegacyHudCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            HudFixtures.Hud hud = HudFixtures.hud(c.variant());
            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            MHotbarRenderer legacy = new MHotbarRenderer(mock(UIRenderer.class), renderer, hud::player);
            legacy.renderHotbar(hud.hotbar(), w, h);
            legacy.renderHotbarTooltip(hud.hotbar(), w, h);
            return new MigrationGate.Capture(raster.capture(), HudFixtures.rects(w, h, hud));
        }
    }
}
