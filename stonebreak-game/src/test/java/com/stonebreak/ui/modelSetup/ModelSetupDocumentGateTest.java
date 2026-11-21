package com.stonebreak.ui.modelSetup;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // taken with v4; switching the default model (2026-11-19, exp2-r1) is not a rendering change. Only the label
        // string is pinned: nothing reads the folder (v4 itself was removed 2026-11-19).
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

    /**
     * Opt-in ({@code -Dstonebreak.ui.modelsetup.refreeze=true}): re-records every model-setup capture (PNG + its
     * geometry.json entry) from the shipped document. For a reviewed, intended change of the screen's content only -
     * 2026-11-20 the setup steps changed with the Python removal (uv / Python environment rows became the CUDA
     * compiler / model files rows; the frozen legacy oracle cannot know steps added after #302) - never to make a
     * failure go away.
     */
    @Test
    void refreezeTheCaptures() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("stonebreak.ui.modelsetup.refreeze"),
            "opt-in re-freeze");
        ChatDocumentGateTest.requireLua();
        String prev = System.getProperty(MODEL_PROPERTY);
        System.setProperty(MODEL_PROPERTY, "checkpoints/v4");   // same pinned subtitle as the gate
        try {
            FrozenCaptures frozen = FrozenCaptures.forTests("model-setup");
            java.nio.file.Path geometry = frozen.geometryFile();
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(geometry.toFile());
            var all = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("cases");
            DocumentModelSetupCapture live = new DocumentModelSetupCapture();
            for (var c : DocumentModelSetupCapture.cases()) {
                assertTrue(all.has(c.id()), "no frozen case " + c.id());
                MigrationGate.Capture cap = live.render(c);
                cap.image().write(geometry.resolveSibling(c.id() + ".png"));
                var node = json.createObjectNode();
                writeRects(node.putObject("rects"), cap.rects());
                writeRects(node.putObject("hits"), cap.hits());
                var actions = node.putObject("actions");
                new TreeMap<>(cap.actions()).forEach(actions::put);
                all.set(c.id(), node);
            }
            json.writeValue(geometry.toFile(), root);
        } finally {
            if (prev == null) System.clearProperty(MODEL_PROPERTY);
            else System.setProperty(MODEL_PROPERTY, prev);
        }
    }

    private static void writeRects(com.fasterxml.jackson.databind.node.ObjectNode into,
                                   java.util.Map<String, float[]> rects) {
        new TreeMap<>(rects).forEach((name, r) -> {
            var a = into.putArray(name);
            for (float v : r) {
                a.add(v);
            }
        });
    }
}
