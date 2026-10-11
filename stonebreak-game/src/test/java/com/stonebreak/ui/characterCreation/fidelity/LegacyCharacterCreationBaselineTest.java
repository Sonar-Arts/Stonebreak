package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pixel baselines of the legacy character creation screen (#301): the Background tab at the standard viewports
 * (the screen never scaled), every other tab state at 1920x1080 (Class Abilities also at 1280x720), hovers at
 * 1920x1080 (Next also at 1921x1081@1.25). No 3D preview (no renderer on a raster). Regenerate with
 * {@code -Dui.fidelity.write=true}; PNGs in {@code src/test/resources/ui/fidelity/character-creation/}.
 */
@Tag("regression")
class LegacyCharacterCreationBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/character-creation", "ui.fidelity.write");

    @Test
    void legacyCharacterCreationMatchesItsBaselines() {
        LegacyCharacterCreationCapture legacy = new LegacyCharacterCreationCapture();
        for (FidelityCase c : CharacterCreationFixtures.cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
