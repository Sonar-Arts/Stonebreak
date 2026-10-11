package com.stonebreak.ui.debug;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

/** The legacy side of the F3 gate (#301): {@link DebugOverlay#paintLegacy} on a raster. Not a test class. */
public final class LegacyDebugOverlayCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            DebugOverlay.Cards cards = DebugOverlayFixtures.cards(c.variant());
            MasonryUI ui = new MasonryUI(raster.backend());
            try {
                DebugOverlay.paintLegacy(ui, cards, w, h);
            } finally {
                ui.dispose();
            }
            return new MigrationGate.Capture(raster.capture(), DebugOverlayFixtures.rects(cards, w));
        }
    }
}
