package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.LegacyUiClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pixel baselines of the legacy terrain mapper (#301): the default screen at the standard viewports (it never
 * scaled), the other states at 1920x1080, hovers at 1920x1080@1 and 1921x1081@1.25. The live map is the
 * seed {@value TerrainMapperFixtures#SEED} preview. Regenerate with {@code -Dui.fidelity.write=true}; PNGs in
 * {@code src/test/resources/ui/fidelity/terrain-mapper/}.
 */
@Tag("regression")
class LegacyTerrainMapperBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/terrain-mapper", "ui.fidelity.write");

    @Test
    void legacyTerrainMapperMatchesItsBaselines() {
        LegacyTerrainMapperCapture legacy = new LegacyTerrainMapperCapture();
        try {
            for (FidelityCase c : TerrainMapperFixtures.cases()) {
                STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
            }
        } finally {
            LegacyUiClock.release();
        }
    }
}
