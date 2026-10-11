package com.stonebreak.ui.chat;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The chat migration gate (#301): the shipped {@code ui/documents/chat.sbui} against the legacy
 * chat renderer's frozen captures on every committed case (geometry {@code FLOAT_EXACT}, pixels {@code EXACT}).
 * Its pointer input is {@code ChatDocumentInteractionTest}.
 */
@Tag("regression")
public class ChatDocumentGateTest {

    /**
     * The closed chat's fading lines, the open chat (lines with inline and animated emoji, input with
     * caret), scrolled history, the commands tab, completion ghost text, the emoji picker with a favorite
     * and recents, and a long input scrolled left, at 1920x1080; the open chat and the picker at the other
     * standard viewports (it follows the UI scale).
     */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("chat", ChatFixtures.VARIANTS,
            List.of(FidelityCase.STANDARD.get(0))));
        out.addAll(FidelityCase.matrix("chat", List.of("open", "picker"), FidelityCase.STANDARD.subList(1, 4)));
        return out;
    }

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
            .run("chat", cases(), FrozenCaptures.forTests("chat").replay(), DocumentChatCapture.shipped());
        GateAssert.passed(r);
    }
}
