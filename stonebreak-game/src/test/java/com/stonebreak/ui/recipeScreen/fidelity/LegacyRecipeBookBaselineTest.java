package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pixel baselines of the legacy recipe book (#301): every state at the standard viewports (the book never
 * scaled), and the hover/tooltip cases at 1920x1080@1 and 1921x1081@1.25. Regenerate with
 * {@code -Dui.fidelity.write=true}; PNGs in {@code src/test/resources/ui/fidelity/recipe-book/}.
 */
@Tag("regression")
class LegacyRecipeBookBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/recipe-book", "ui.fidelity.write");

    @Test
    void legacyRecipeBookMatchesItsBaselines() {
        LegacyRecipeBookCapture legacy = new LegacyRecipeBookCapture();
        for (FidelityCase c : RecipeBookFixtures.cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
