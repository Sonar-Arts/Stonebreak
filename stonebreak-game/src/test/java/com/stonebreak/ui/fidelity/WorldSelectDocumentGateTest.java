package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.openmason.engine.ui.masonry.MKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world select migration gate (#299): the shipped document against the legacy screen's committed
 * cases at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels, with the same hit regions and host
 * actions. Plus what the pixels cannot show: keys, the wheel, the info card following the pointer and
 * the delete confirmation round trip.
 */
@Tag("regression")
class WorldSelectDocumentGateTest {

    /** GLFW letter keys (MKeys names only the keys widgets use). */
    private static final int KEY_N = 78, KEY_S = 83, KEY_W = 87;

    private static FidelityCase at(String variant) {
        return new FidelityCase("world-select", variant, FidelityCase.STANDARD.get(0));
    }

    @Test
    void theWorldSelectScreenPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("world-select", LegacyWorldSelectBaselineTest.cases(), new LegacyWorldSelectCapture(),
                new DocumentWorldSelectCapture());
        GateAssert.passed(r);
    }

    @Test
    void keysDriveTheSelectionLikeTheLegacyPoll() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentWorldSelectCapture.stage(at("worlds"))) {
            var state = stage.services.worldSelect.getStateManager();
            assertEquals(List.of("stonebreak:screen.world-select.move 1"), stage.key(MKeys.KEY_DOWN));
            assertEquals(List.of("stonebreak:screen.world-select.move 1"), stage.key(KEY_S));
            assertEquals(2, state.getSelectedIndex());
            assertEquals(List.of("stonebreak:screen.world-select.move -1"), stage.key(KEY_W));
            assertEquals(1, state.getSelectedIndex());
            assertEquals(List.of("stonebreak:screen.world-select.activate"), stage.key(MKeys.KEY_ENTER));
            assertEquals(List.of("stonebreak:screen.world-select.activate"), stage.key(MKeys.KEY_SPACE));
            assertEquals(List.of("stonebreak:screen.world-select.create"), stage.key(KEY_N));
            assertEquals(List.of("stonebreak:screen.world-select.back"), stage.key(MKeys.KEY_ESCAPE));
        }
    }

    /** One row per tick: wheel-up (GLFW's positive offset) scrolls toward the top of the list. */
    @Test
    void theWheelScrollsOneRowPerTick() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentWorldSelectCapture.stage(at("many"))) {
            var state = stage.services.worldSelect.getStateManager();
            assertEquals(3, state.getScrollOffset());
            stage.view.input().wheel(stage.raster.width / 2f, stage.raster.height / 2f, 0, 1, 0);
            stage.settle();
            assertEquals(2, state.getScrollOffset(), "wheel up shows earlier worlds");
            stage.view.input().wheel(stage.raster.width / 2f, stage.raster.height / 2f, 0, -1, 0);
            stage.view.input().wheel(stage.raster.width / 2f, stage.raster.height / 2f, 0, -1, 0);
            stage.settle();
            assertEquals(4, state.getScrollOffset(), "wheel down shows later worlds");
            assertTrue(stage.q("#row0").rect().height() > 0);
        }
    }

    @Test
    void theInfoCardOpensOnRestAndStaysWhileThePointerIsOnIt() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentWorldSelectCapture.stage(at("worlds"))) {
            var state = stage.services.worldSelect.getStateManager();
            stage.hover("row1");
            assertEquals(1, state.getHoveredIndex());
            assertFalse(state.isCardOpen(), "opens only after the rest delay");
            Thread.sleep(com.stonebreak.ui.worldSelect.managers.WorldStateManager.CARD_OPEN_DELAY_MS + 50);
            stage.settle();
            assertEquals(1, state.getCardIndex());
            assertFalse(stage.q("#card").isCollapsed());
            stage.hover("folder");
            assertEquals(1, state.getCardIndex());
            assertEquals(1, state.getHoveredIndex(), "the card lights its row");
        }
    }

    @Test
    void deleteAsksFirstAndTheScrimCancels() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentWorldSelectCapture.stage(at("worlds"))) {
            stage.services.worldSelectLive.addAll(List.of("delete", "cancel-delete"));
            var state = stage.services.worldSelect.getStateManager();
            assertEquals(List.of("stonebreak:screen.world-select.delete"), stage.click("delete"));
            assertTrue(state.isShowDeleteDialog());
            assertFalse(stage.q("#dialog").isCollapsed());
            assertEquals(List.of("stonebreak:screen.world-select.cancel-delete"), stage.clickAt(4, 4));
            assertFalse(state.isShowDeleteDialog());
            assertTrue(stage.q("#dialog").isCollapsed());
            stage.click("delete");
            assertEquals(List.of("stonebreak:screen.world-select.cancel-delete"), stage.key(MKeys.KEY_ESCAPE));
        }
    }

    @Test
    void disabledButtonsDoNothing() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentWorldSelectCapture.stage(at("empty"))) {
            assertEquals(List.of(), stage.click("play"));
            assertEquals(List.of(), stage.click("delete"));
            assertEquals(List.of("stonebreak:screen.world-select.create"), stage.click("create"));
        }
    }
}
