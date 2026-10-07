package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.StatsRecord;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statistics screen migration gate (#299): the shipped {@code ui/documents/statistics.sbui}
 * against the legacy renderer's committed cases at {@code FLOAT_EXACT} geometry and {@code EXACT}
 * pixels, with the same Back hit region and action. Plus: values follow the host live while the
 * screen is open, and the panel ignores clicks that are not on Back.
 */
@Tag("regression")
class StatisticsDocumentGateTest {

    @Test
    void theShippedStatisticsDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = LegacyStatisticsBaselineTest.inUs(() ->
            new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT).run("statistics",
                LegacyStatisticsBaselineTest.cases(), new LegacyStatisticsCapture(), DocumentStatisticsCapture.shipped()));
        GateAssert.passed(r);
    }

    @Test
    void valuesFollowTheHostWhileOpen() throws Exception {
        DeathDocumentGateTest.requireLua();
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        try (DocumentStage stage = DocumentStatisticsCapture.stage(GameUiDocuments.readScreen(StatisticsScreen.DOCUMENT_ID),
                hd, "fresh")) {
            stage.paint();
            var before = stage.raster.capture();
            stage.services.stats = StatsRecord.of(LegacyStatisticsCapture.sample());
            stage.settle();
            stage.paint();
            assertNotEquals(before.pixel(1180, 389), stage.raster.capture().pixel(1180, 389),
                "the Entities Killed value repaints when the host publishes new stats");
        }
    }

    @Test
    void onlyBackLeavesTheScreen() throws Exception {
        DeathDocumentGateTest.requireLua();
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        try (DocumentStage stage = DocumentStatisticsCapture.stage(GameUiDocuments.readScreen(StatisticsScreen.DOCUMENT_ID),
                hd, "played")) {
            assertEquals(List.of(), stage.clickAt(960, 400), "a stat row");
            assertEquals(List.of(), stage.clickAt(20, 20), "the scrim");
            assertEquals(List.of("stonebreak:screen.statistics.back"), stage.click("back"));
        }
    }
}
