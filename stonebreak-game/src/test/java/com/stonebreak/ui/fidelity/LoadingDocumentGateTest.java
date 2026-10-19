package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.LoadingRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The world loading screen migration gate (#299): the shipped {@code ui/documents/loading.sbui}
 * against the legacy renderer's frozen captures at {@code FLOAT_EXACT} geometry and {@code EXACT}
 * pixels (logo and its drop shadow included). Plus: the bar follows the generator's reports while
 * the screen is open, the screen ignores clicks (the legacy one took none), and it draws in device
 * pixels whatever the UI scale.
 */
@Tag("regression")
class LoadingDocumentGateTest {

    /** Every frozen loading capture. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("loading", List.of("pregen"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("first", "services", "stream")) {
            out.add(new FidelityCase("loading", v, hd));
        }
        out.add(new FidelityCase("loading", "pregen", new FidelityCase.Viewport(800, 600, 1f)));
        return out;
    }

    @Test
    void theShippedLoadingDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("loading", cases(), FrozenCaptures.forTests("loading").replay(), DocumentLoadingCapture.shipped());
        GateAssert.passed(r);
    }

    @Test
    void theBarFollowsTheReportsAndClicksDoNothing() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentLoadingCapture.stage(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID),
                FidelityCase.STANDARD.get(0), "first")) {
            stage.paint();
            int before = stage.raster.capture().pixel(1000, 605);
            DocumentLoadingCapture.Timeline t = new DocumentLoadingCapture.Timeline();
            LoadingScreen screen = new LoadingScreen(t.tracker);
            screen.showProgress();
            DocumentLoadingCapture.stream(t);
            screen.settleProgress();
            stage.services.loading = LoadingRecord.of(screen);
            stage.settle();
            stage.paint();
            assertNotEquals(before, stage.raster.capture().pixel(1000, 605), "the fill reaches the bar's right half");
            assertEquals(List.of(), stage.clickAt(960, 605));
        }
    }

    @Test
    void theUiScaleMovesNothing() throws Exception {
        DeathDocumentGateTest.requireLua();
        DocumentLoadingCapture capture = DocumentLoadingCapture.shipped();
        var one = capture.render(new FidelityCase("loading", "pregen", new FidelityCase.Viewport(1920, 1080, 1f))).image();
        var two = capture.render(new FidelityCase("loading", "pregen", new FidelityCase.Viewport(1920, 1080, 2f))).image();
        int differ = 0;
        for (int y = 0; y < 1080; y++) {
            for (int x = 0; x < 1920; x++) {
                if (one.pixel(x, y) != two.pixel(x, y)) {
                    differ++;
                }
            }
        }
        assertEquals(0, differ, "the loading screen draws in device pixels at every UI scale");
    }
}
