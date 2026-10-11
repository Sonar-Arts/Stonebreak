package com.stonebreak.ui.debug;

import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The F3 overlay migration gate (#301): {@code ui/documents/debug_overlay.sbui} against the legacy
 * {@code MStatPanel} cards (card geometry {@code FLOAT_EXACT}, pixels {@code EXACT}).
 */
@Tag("regression")
class DebugOverlayDocumentGateTest {

    @Test
    void theShippedDebugOverlayPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("debug-overlay", LegacyDebugOverlayBaselineTest.cases(), new LegacyDebugOverlayCapture(),
                DocumentDebugOverlayCapture.shipped());
        GateAssert.passed(r);
    }
}
