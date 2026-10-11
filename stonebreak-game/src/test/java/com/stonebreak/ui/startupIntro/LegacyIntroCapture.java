package com.stonebreak.ui.startupIntro;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.startupIntro.render.SonarLogoRenderer;

/**
 * The legacy side of the intro's fidelity gate (#301): {@link SonarArtsIntroScreen#paintLegacy} of an
 * {@link IntroFixtures} scene on a {@link LegacyUiRaster}. Not a test class.
 */
public final class LegacyIntroCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale());
             SonarLogoRenderer logo = new SonarLogoRenderer()) {
            IntroScene scene = IntroFixtures.scene(c.variant(), w, h);
            var backend = raster.backend();
            backend.beginFrame(w, h, 1f);
            try {
                SonarArtsIntroScreen.paintLegacy(backend.getCanvas(), scene, logo, w, h);
            } finally {
                backend.endFrame();
            }
            return new MigrationGate.Capture(raster.capture(), IntroFixtures.logoRect(scene, w, h));
        }
    }
}
