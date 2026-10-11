package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pixel baselines of the legacy character sheet (#301): Overview at the standard viewports, each tab's states at
 * 1920x1080 (Classes also at 1921x1081@1.25, Feats at 1280x720@0.75), hovers at 1920x1080. No 3D preview (no entity
 * renderer on a raster). Regenerate with {@code -Dui.fidelity.write=true}; PNGs in
 * {@code src/test/resources/ui/fidelity/character-sheet/}.
 */
@Tag("regression")
class LegacyCharacterSheetBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/character-sheet", "ui.fidelity.write");

    @Test
    void legacyCharacterSheetMatchesItsBaselines() {
        LegacyCharacterSheetCapture legacy = new LegacyCharacterSheetCapture();
        for (FidelityCase c : CharacterSheetFixtures.cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
