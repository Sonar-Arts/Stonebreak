package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The death menu migration gate (#299): the shipped {@code ui/documents/death.sbui}, authored in
 * Open Mason, against the legacy renderer's committed cases at the strictest rules (geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}, the same hit region and the same action). Plus what
 * the static cases cannot show: a click beside the button does nothing, and a UI-scale change
 * moves the title's device-pixel shadow with it.
 */
@Tag("regression")
class DeathDocumentGateTest {

    static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedDeathDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("death", LegacyDeathBaselineTest.cases(), new LegacyDeathCapture(), DocumentDeathCapture.shipped());
        GateAssert.passed(r);
    }

    @Test
    void onlyTheButtonRespawns() throws Exception {
        requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(DeathMenu.DOCUMENT_ID), 1920, 1080, 1f)) {
            assertEquals(List.of(), stage.clickAt(960, 300), "the title");
            assertEquals(List.of(), stage.clickAt(20, 1060), "the scrim");
            assertEquals(List.of("stonebreak:screen.death.respawn"), stage.click("respawn"));
            assertEquals(List.of("stonebreak:screen.death.respawn"), stage.click("respawn"), "every click respawns");
        }
    }

    @Test
    void theTitleShadowStaysFourDevicePixelsOffAtEveryScale() throws Exception {
        requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(DeathMenu.DOCUMENT_ID), 1920, 1080, 1.5f)) {
            var front = stage.rect("title");
            var shadow = stage.rect("title-shadow");
            assertEquals(4f, shadow.x() - front.x(), 1e-3);
            assertEquals(4f, shadow.y() - front.y(), 1e-3);
        }
    }
}
