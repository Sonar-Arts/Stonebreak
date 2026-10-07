package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.LoadingRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The world loading screen migration gate (#299): the shipped {@code ui/documents/loading.sbui}
 * against the legacy renderer's committed cases at {@code FLOAT_EXACT} geometry and {@code EXACT}
 * pixels (logo and its drop shadow included). Plus: the bar follows the generator's reports while
 * the screen is open, and the screen ignores clicks (the legacy one took none).
 */
@Tag("regression")
class LoadingDocumentGateTest {

    @Test
    void theShippedLoadingDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("loading", LegacyLoadingBaselineTest.cases(), new LegacyLoadingCapture(), DocumentLoadingCapture.shipped());
        GateAssert.passed(r);
    }

    @Test
    void theBarFollowsTheReportsAndClicksDoNothing() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentLoadingCapture.stage(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID),
                FidelityCase.STANDARD.get(0), "first")) {
            stage.paint();
            int before = stage.raster.capture().pixel(1000, 605);
            LoadingScreen screen = LegacyLoadingCapture.screen(null, "first");
            screen.updateProgress("Meshing Chunk");
            stage.services.loading = LoadingRecord.of(screen);
            stage.settle();
            stage.paint();
            assertNotEquals(before, stage.raster.capture().pixel(1000, 605), "the fill reaches the bar's right half");
            assertEquals(List.of(), stage.clickAt(960, 605));
        }
    }
}
