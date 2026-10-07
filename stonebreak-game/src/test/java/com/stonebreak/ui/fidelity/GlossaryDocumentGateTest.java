package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity glossary migration gate (#299): the shipped {@code ui/documents/glossary.sbui} against
 * the legacy renderer's committed cases at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels,
 * with the same hit region and effect for every row, arrow and Back. Plus the selection staying in
 * the game: clicking a row then an arrow changes exactly what the legacy clicks change.
 */
@Tag("regression")
class GlossaryDocumentGateTest {

    @Test
    void theShippedGlossaryDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = LegacyStatisticsBaselineTest.inUs(() ->
            new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT).run("glossary",
                LegacyGlossaryBaselineTest.cases(), new LegacyGlossaryCapture(), DocumentGlossaryCapture.shipped()));
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
