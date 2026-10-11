package com.stonebreak.ui.crosshair;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.fidelity.LegacyUiRaster;


/**
 * The legacy side of the crosshair's fidelity gate (#301): the real {@code MCrosshairRenderer},
 * configured as {@link CrosshairFixtures}, on a {@link LegacyUiRaster}. Not a test class.
 */
public final class LegacyCrosshairCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            var crosshair = CrosshairFixtures.crosshair(c.variant(), raster.backend());
            crosshair.renderCrosshair(w, h);
            return new MigrationGate.Capture(raster.capture(), CrosshairFixtures.legacyRects(crosshair, w, h));
        }
    }
}
