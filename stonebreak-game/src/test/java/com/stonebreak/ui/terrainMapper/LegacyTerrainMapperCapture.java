package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

import java.util.Map;

/**
 * The legacy side of the terrain mapper's gate (#301): the real {@link TerrainMapperScreen} (its own MasonryUI,
 * map, sidebar, footer) on a {@link LegacyUiRaster}, the pointer fed to its mouse handler. Not a test class.
 */
public final class LegacyTerrainMapperCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            LegacyUiClock.pin(TerrainMapperFixtures.clock(c.variant()), LegacyUiClock.DEFAULT_SEED);
            TerrainMapperScreen screen = new TerrainMapperScreen(raster.backend());
            TerrainMapperFixtures.prepare(screen.getStateManager(), c.variant());
            Map<String, float[]> rects = TerrainMapperFixtures.rects(w, h, screen.getStateManager());
            String hover = TerrainMapperFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                // a frame first: the legacy renderer positions its buttons as it draws them
                screen.render(w, h);
                float[] p = TerrainMapperFixtures.pointer(rects, hover);
                screen.handleMouseMove(p[0], p[1], w, h);
            }
            screen.render(w, h);
            screen.dispose();
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }
}
