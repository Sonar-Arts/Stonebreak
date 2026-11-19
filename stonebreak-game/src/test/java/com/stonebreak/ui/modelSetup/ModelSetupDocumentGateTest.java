package com.stonebreak.ui.modelSetup;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The model setup migration gate (Daedalus): {@code ui/documents/model_setup.sbui} against the frozen captures of
 * the branch's legacy {@code ModelSetupScreen} renderer (steps panel and buttons {@code FLOAT_EXACT}, pixels
 * {@code EXACT}; the running step's spinner on the pinned UI clock).
 */
@Tag("regression")
class ModelSetupDocumentGateTest {

    private static final String MODEL_PROPERTY = "stonebreak.tgmpipe.model";

    @Test
    void theShippedModelSetupDocumentPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        // Pinned fixture input: the subtitle names the model folder (TGMPipe.generatorLabel). The frozen captures were
        // taken with v4; switching the default model (2026-11-19, exp2-r1) is not a rendering change.
        String prev = System.getProperty(MODEL_PROPERTY);
        System.setProperty(MODEL_PROPERTY, "checkpoints/v4");
        try {
            MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
                .run("model-setup", DocumentModelSetupCapture.cases(), FrozenCaptures.forTests("model-setup").replay(),
                    new DocumentModelSetupCapture());
            GateAssert.passed(r);
        } finally {
            if (prev == null) System.clearProperty(MODEL_PROPERTY);
            else System.setProperty(MODEL_PROPERTY, prev);
        }
    }
}
