package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.cenda.CendaLua;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pause menu migration gate (#297): the shipped {@code ui/documents/pause.sbui}, authored in
 * Open Mason, against the legacy renderer's committed cases, with the strictest rules the ledger
 * sets for pause (geometry {@code FLOAT_EXACT}, pixels {@code EXACT}, the same hit regions and the
 * same action per button). Plus what the gate's static cases cannot show: the Resync slot reflowing
 * when the session changes, and a failing action leaving the menu usable.
 */
@Tag("regression")
class PauseDocumentGateTest {

    private static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedPauseDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("pause", LegacyPauseBaselineTest.cases(), new LegacyPauseCapture(), DocumentPauseCapture.shipped());
        assertTrue(r.passed(), r.table());
    }

    @Test
    void goingOnlineReflowsTheResyncSlotAndItsHitRegion() throws Exception {
        requireLua();
        FidelityCase.Viewport vp = FidelityCase.STANDARD.get(3); // 1921x1081 @1.25: half-pixel centres
        try (DocumentPauseCapture.Stage stage = new DocumentPauseCapture.Stage(GameUiDocuments.readScreen("pause"),
                vp.width(), vp.height(), vp.uiScale(), false)) {
            assertTrue(stage.button("resync").isCollapsed(), "offline: five buttons");
            UiRect quitOffline = stage.button("quit").rect();

            stage.host.sessionChanged(MultiplayerSession.Mode.JOIN);
            stage.settle();

            assertFalse(stage.button("resync").isCollapsed(), "online: Resync World appears");
            Map<String, float[]> legacy = LegacyPauseCapture.rects(vp.width(), vp.height(), vp.uiScale(), true);
            for (String b : LegacyPauseCapture.BUTTONS) {
                UiRect r = stage.button(b).rect();
                float[] want = legacy.get(b);
                assertEquals(want[0], r.x(), 1e-3, b + " x");
                assertEquals(want[1], r.y(), 1e-3, b + " y");
                assertEquals(want[2], r.width(), 1e-3, b + " w");
                assertEquals(want[3], r.height(), 1e-3, b + " h");
                float cx = r.x() + r.width() / 2f;
                assertTrue(hits(stage, "#" + b, cx, r.y() + 0.5f) && hits(stage, "#" + b, cx, r.bottom() - 0.5f),
                    b + " hit region follows its new rect");
            }
            assertEquals(35f * vp.uiScale(), stage.button("quit").rect().y() - quitOffline.y(), 1e-3,
                "every button below the reflow moves 35 px x scale, as the legacy offsets do");
        }
    }

    @Test
    void aFailingResyncLeavesEveryOtherButtonWorking() throws Exception {
        requireLua();
        try (DocumentPauseCapture.Stage stage = new DocumentPauseCapture.Stage(GameUiDocuments.readScreen("pause"),
                1920, 1080, 1f, true)) {
            stage.services.failResync = true;
            click(stage, "resync");
            click(stage, "resync");
            click(stage, "resume");
            assertEquals(java.util.List.of("stonebreak:network.resync", "stonebreak:network.resync",
                "stonebreak:screen.pause.resume"), stage.services.calls,
                "a failed action is reported to the script, not fatal; the next click still works");
        }
    }

    private static boolean hits(DocumentPauseCapture.Stage stage, String selector, float x, float y) {
        var target = stage.view.instance().q(selector);
        for (var e = stage.view.instance().hitTest(x, y); e != null; e = e.parent()) {
            if (e == target) {
                return true;
            }
        }
        return false;
    }

    private static void click(DocumentPauseCapture.Stage stage, String name) {
        UiRect r = stage.button(name).rect();
        float cx = r.x() + r.width() / 2f;
        float cy = r.y() + r.height() / 2f;
        stage.view.pointerMove(cx, cy);
        stage.view.pointerDown(cx, cy);
        stage.view.pointerUp(cx, cy);
        stage.settle();
    }

    @Test
    void theKeyboardActivatesTheButtons() throws Exception {
        requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen("pause"), 1920, 1080, 1f)) {
            stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_TAB);
            assertEquals(java.util.List.of("stonebreak:screen.pause.resume"),
                stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_ENTER), "Tab focuses Resume, Enter presses it (#299)");
        }
    }
}
