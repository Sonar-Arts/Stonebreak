package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.StatsRecord;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statistics screen migration gate (#299): the shipped {@code ui/documents/statistics.sbui}
 * against the legacy renderer's frozen captures at {@code FLOAT_EXACT} geometry and {@code EXACT}
 * pixels, with the same Back hit region and action. Plus: values follow the host live while the
 * screen is open, and the panel ignores clicks that are not on Back.
 */
@Tag("regression")
class StatisticsDocumentGateTest {

    /** Every frozen statistics capture. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("statistics", List.of("played"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("statistics", "fresh", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("statistics", "played-hover-back", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("statistics", "played-hover-back", FidelityCase.STANDARD.get(3)));
        return out;
    }

    /** Runs {@code r} with the default locale pinned to US English (the captures' separators). */
    static <T> T inUs(java.util.concurrent.Callable<T> r) throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            return r.call();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void theShippedStatisticsDocumentPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = inUs(() ->
            new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT).run("statistics",
                cases(), FrozenCaptures.forTests("statistics").replay(), DocumentStatisticsCapture.shipped()));
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
            stage.services.stats = StatsRecord.of(DocumentStatisticsCapture.sample());
            stage.settle();
            stage.paint();
            assertNotEquals(before.pixel(1180, 389), stage.raster.capture().pixel(1180, 389),
                "the Entities Killed value repaints when the host publishes new stats");
        }
    }

    @Test
    void theRowsScrollAboveBackSoNewTrackersNeverPushItOffThePanel() throws Exception {
        DeathDocumentGateTest.requireLua();
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        try (DocumentStage stage = DocumentStatisticsCapture.stage(GameUiDocuments.readScreen(StatisticsScreen.DOCUMENT_ID),
                hd, "played")) {
            stage.paint();
            var view = stage.q("#rows-scroll");
            assertTrue(view.isScrollContainer(), "the stat rows live in a scroll container");
            assertEquals(0f, view.maxScrollY(), 0.01f, "today's rows fit without scrolling");
            assertTrue(view.rect().bottom() <= stage.rect("back").y(), "the scrolling rows end above Back");
            assertTrue(stage.rect("items-crafted").bottom() <= view.rect().bottom(), "the last row is in view");
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
