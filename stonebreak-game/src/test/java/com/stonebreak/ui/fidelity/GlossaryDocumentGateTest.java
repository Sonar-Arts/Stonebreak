package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity glossary migration gate (#299): the shipped {@code ui/documents/glossary.sbui} against
 * the legacy renderer's frozen captures at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels,
 * with the same hit region and effect for every row, arrow and Back. Plus the selection staying in
 * the game: clicking rows and arrows changes the screen's selection and shown variant.
 */
@Tag("regression")
class GlossaryDocumentGateTest {

    /** Every frozen glossary capture. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("glossary", List.of("cow"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        out.add(new FidelityCase("glossary", "cow", new FidelityCase.Viewport(800, 600, 2f)));
        for (String v : List.of("sheep", "chicken", "goose", "cow-variant2", "cow-hover-back", "cow-hover-row1",
                "cow-hover-right")) {
            out.add(new FidelityCase("glossary", v, hd));
        }
        out.add(new FidelityCase("glossary", "cow-hover-left", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void theShippedGlossaryDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = StatisticsDocumentGateTest.inUs(() ->
            new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT).run("glossary",
                cases(), FrozenCaptures.forTests("glossary").replay(), DocumentGlossaryCapture.shipped()));
        GateAssert.passed(r);
    }

    @Test
    void clicksChangeTheGamesSelection() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentGlossaryCapture.stage(GameUiDocuments.readScreen(GlossaryScreen.DOCUMENT_ID),
                FidelityCase.STANDARD.get(0), "sheep")) {
            GlossaryScreen g = stage.services.glossary;
            assertEquals(1, g.getSelectedEntityIndex());
            stage.click("row0");
            assertEquals(0, g.getSelectedEntityIndex(), "row 0 selects the cow");
            stage.click("right");
            assertEquals(1, g.getSelectedVariantIndex(g.getSelectedEntityType(), 2), "the cycler steps the cow");
            stage.click("right");
            assertEquals(0, g.getSelectedVariantIndex(g.getSelectedEntityType(), 2), "and wraps");
            stage.click("row3");
            assertEquals(3, g.getSelectedEntityIndex());
            assertEquals(List.of(), stage.clickAt(960, 400), "the goose has no cycler; the preview ignores clicks");
            assertEquals(List.of("stonebreak:screen.glossary.back"), stage.click("back"));
        }
    }
}
