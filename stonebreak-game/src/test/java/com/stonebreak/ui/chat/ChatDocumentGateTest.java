package com.stonebreak.ui.chat;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The chat migration gate (#301): the shipped {@code ui/documents/chat.sbui} against
 * {@link SkijaChatRenderer} on every committed case (geometry {@code FLOAT_EXACT}, pixels {@code EXACT}).
 * Its pointer input is {@code ChatDocumentInteractionTest}.
 */
@Tag("regression")
public class ChatDocumentGateTest {

    public static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedChatDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("chat", LegacyChatBaselineTest.cases(), new LegacyChatCapture(), DocumentChatCapture.shipped());
        GateAssert.passed(r);
    }
}
