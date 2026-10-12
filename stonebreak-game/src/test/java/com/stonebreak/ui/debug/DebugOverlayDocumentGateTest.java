package com.stonebreak.ui.debug;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The F3 overlay migration gate (#301): {@code ui/documents/debug_overlay.sbui} against the legacy
 * {@code MStatPanel} cards' frozen captures (card geometry {@code FLOAT_EXACT}, pixels {@code EXACT}).
 */
@Tag("regression")
class DebugOverlayDocumentGateTest {

    /**
     * RAM, VRAM (a red bar), the debug card and, with a monitored document, the UI budget card, at
     * every standard viewport (never scaled); the cards alone at 1920x1080.
     */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("debug-overlay", List.of("with-budgets"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("debug-overlay", "cards", FidelityCase.STANDARD.get(0)));
        return out;
    }

    @Test
    void theShippedDebugOverlayPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("debug-overlay", cases(), FrozenCaptures.forTests("debug-overlay").replay(),
                DocumentDebugOverlayCapture.shipped());
        GateAssert.passed(r);
    }
}
