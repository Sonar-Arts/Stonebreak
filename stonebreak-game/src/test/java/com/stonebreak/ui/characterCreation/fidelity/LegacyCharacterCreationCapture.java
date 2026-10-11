package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MButton;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

import java.util.Map;

/**
 * The legacy side of character creation's gate (#301): the real {@link CharacterCreationScreen} (its own
 * MasonryUI; no renderer, so no GL preview) on a {@link LegacyUiRaster}, a frame drawn before the pointer moves
 * (the footer buttons are placed as they draw). Rects: the fixture oracle plus the footer buttons as drawn.
 * Not a test class.
 */
public final class LegacyCharacterCreationCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            CharacterCreationScreen screen = new CharacterCreationScreen(raster.backend());
            CharacterCreationFixtures.prepare(screen, c.variant());
            screen.updateLabelsForMode();
            screen.render(w, h);
            Map<String, float[]> rects = CharacterCreationFixtures.rects(screen, c.variant(), w, h);
            rects.put("back", bounds(screen.state().getBackToWorldSelectButton()));
            rects.put("next", bounds(screen.state().getTerrainMapperButton()));
            String hover = CharacterCreationFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                float[] r = rects.get(hover);
                if (r == null) {
                    throw new IllegalArgumentException("unknown character creation target " + hover);
                }
                screen.handleMouseMove(r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h);
            }
            screen.render(w, h);
            screen.dispose();
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }

    static float[] bounds(MButton b) {
        return new float[]{b.x(), b.y(), b.width(), b.height()};
    }
}
