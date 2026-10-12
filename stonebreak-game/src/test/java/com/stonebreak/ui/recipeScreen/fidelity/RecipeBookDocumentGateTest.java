package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The recipe book migration gate (#301): {@code ui/documents/recipe_book.sbui} against the legacy
 * renderer's frozen captures (panel, search, categories, grid cells, detail slots and variant buttons
 * {@code FLOAT_EXACT}, pixels {@code EXACT}).
 */
@Tag("regression")
class RecipeBookDocumentGateTest {

    @Test
    void theShippedRecipeBookPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("recipe-book", RecipeBookFixtures.cases(), FrozenCaptures.forTests("recipe-book").replay(),
                DocumentRecipeBookCapture.shipped());
        GateAssert.passed(r);
    }
}
