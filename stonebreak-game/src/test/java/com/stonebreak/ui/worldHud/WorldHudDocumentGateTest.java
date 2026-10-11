package com.stonebreak.ui.worldHud;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.rendering.UI.components.StealthHudRenderer;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.GateAssert;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The world HUD migration gate (#301): the shipped {@code ui/documents/world_hud.sbui} against the
 * legacy world-space renderers on every committed case, each frame published right before a single
 * paint (as the game does), geometry {@code FLOAT_EXACT} on every marker's anchor, pixels {@code EXACT}.
 */
@Tag("regression")
class WorldHudDocumentGateTest {

    static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedWorldHudDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("world-hud", LegacyWorldHudBaselineTest.cases(), new LegacyWorldHudCapture(),
                DocumentWorldHudCapture.shipped());
        GateAssert.passed(r);
    }

    /** Markers come and go from one frame to the next: rows follow the published list, none linger. */
    @Test
    void rowsFollowEveryPublishedFrame() throws Exception {
        requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(WorldHud.DOCUMENT_ID), 1920, 1080, 1f)) {
            WorldHudFixtures.Frame mixed = WorldHudFixtures.frame("mixed", 1920, 1080);
            stage.host.worldHud().publish(mixed.markers(), mixed.stealth());
            stage.paint();
            assertEquals(mixed.markers().size() + 2, DocumentWorldHudCapture.anchors(stage).size(),
                "every marker, the stealth label and the re-entry bar");
            stage.host.worldHud().publish(mixed.markers().subList(0, 2), StealthHudRenderer.State.NONE);
            stage.paint();
            assertEquals(2, DocumentWorldHudCapture.anchors(stage).size());
            assertEquals(true, stage.q("#stealth").isCollapsed(), "the stealth HUD hides with its state");
            stage.host.worldHud().clear();
            stage.paint();
            assertEquals(List.of(), List.copyOf(DocumentWorldHudCapture.anchors(stage).keySet()));
        }
    }
}
