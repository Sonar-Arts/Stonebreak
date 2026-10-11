package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import org.joml.Vector2f;

import java.util.Map;

/**
 * The legacy side of the character sheet's gate (#301): the real {@link CharacterRenderCoordinator} (no entity
 * renderer, so no GL preview) on a {@link LegacyUiRaster}, the pointer where the case puts it. A first frame lays the
 * buttons and the scroll clamps out, as in the game. Not a test class.
 */
public final class LegacyCharacterSheetCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            Vector2f mouse = new Vector2f(-100, -100);
            CharacterScreen screen = CharacterSheetFixtures.screen(raster.backend(), mouse);
            CharacterSheetFixtures.prepare(screen, c.variant());
            screen.render(w, h);
            CharacterSheetFixtures.scroll(screen, c.variant());
            Map<String, float[]> rects = CharacterSheetFixtures.rects(screen, c.variant(), w, h);
            String hover = CharacterSheetFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                float[] r = rects.get(hover);
                if (r == null) {
                    throw new IllegalArgumentException("unknown character sheet target " + hover);
                }
                mouse.set(r[0] + r[2] / 2f, r[1] + r[3] / 2f);
            }
            raster.reset();
            screen.render(w, h);
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }
}
