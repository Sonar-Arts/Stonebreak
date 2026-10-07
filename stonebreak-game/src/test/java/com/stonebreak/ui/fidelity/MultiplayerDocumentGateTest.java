package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The multiplayer menu, host-a-world and join migration gates (#299): each shipped document against
 * the legacy screen's committed cases at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels, with
 * the same hit regions and host actions. Plus the forms: what is typed reaches the host's actions.
 */
@Tag("regression")
class MultiplayerDocumentGateTest {

    private static void gate(String screen, List<FidelityCase> cases) throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run(screen, cases, new LegacyMultiplayerCapture(), new DocumentMultiplayerCapture());
        GateAssert.passed(r);
    }

    @Test
    void theMultiplayerMenuPassesTheMigrationGate() throws Exception {
        gate("multiplayer", LegacyMultiplayerBaselineTest.menuCases());
    }

    @Test
    void theHostScreenPassesTheMigrationGate() throws Exception {
        gate("host-world", LegacyMultiplayerBaselineTest.hostCases());
    }

    @Test
    void theJoinScreenPassesTheMigrationGate() throws Exception {
        gate("join-world", LegacyMultiplayerBaselineTest.joinCases());
    }

    @Test
    void typedFieldsReachTheHostActions() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentMultiplayerCapture.stage(new FidelityCase("host-world", "worlds",
                FidelityCase.STANDARD.get(0)))) {
            assertEquals(List.of("stonebreak:screen.host-world.select 2"), stage.click("row2"));
            assertEquals(2, stage.services.hostWorld.selectedWorld());
            assertEquals(List.of("stonebreak:screen.host-world.start"), stage.click("start"));
        }
    }

    @Test
    void escapeGoesOneScreenBack() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentMultiplayerCapture.stage(new FidelityCase("join-world", "filled",
                FidelityCase.STANDARD.get(0)))) {
            assertEquals(List.of("stonebreak:screen.join-world.back"),
                stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_ESCAPE));
        }
        try (DocumentStage stage = DocumentMultiplayerCapture.stage(new FidelityCase("multiplayer", "plain",
                FidelityCase.STANDARD.get(0)))) {
            assertEquals(List.of("stonebreak:screen.multiplayer.back"),
                stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_ESCAPE));
        }
    }
}
