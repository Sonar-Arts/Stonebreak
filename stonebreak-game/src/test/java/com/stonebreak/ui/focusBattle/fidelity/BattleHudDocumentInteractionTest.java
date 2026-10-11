package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import com.stonebreak.ui.focusBattle.elements.ResultPanel;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * The battle HUD document's pointer (#301) reaches the screen's legacy mouse rules unchanged: a hover selects the row
 * under it, a press on a target-taking row enters the target step, the result panel's buttons act once it has risen;
 * and the document takes no key, so the screen's keyboard precedence (MenuInputRouter.routeBattleKey) stays the only
 * one.
 */
@Tag("regression")
class BattleHudDocumentInteractionTest {

    private static SbuiArchive shipped;

    @BeforeAll
    static void load() throws Exception {
        ChatDocumentGateTest.requireLua();
        shipped = GameUiDocuments.readScreen(FocusBattleScreen.DOCUMENT_ID);
    }

    private static float[] row(int i) {
        float[] r = FocusBattleLayout.commandRowRect(i, 1920, 1080, Settings.getInstance().getUiScale());
        return new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
    }

    @Test
    void thePointerRunsTheScreensRules() throws Exception {
        FakeBattleView view = new FakeBattleView();
        Settings.getInstance().setUiScale(1f);
        FocusBattleScreen screen = BattleHudFixtures.screen("menu", view, 1920, 1080);
        try (DocumentStage stage = DocumentBattleHudCapture.stage(shipped, screen, 1920, 1080, 1f)) {
            float[] r2 = row(2);
            stage.view.input().pointerMove(r2[0], r2[1]);
            stage.settle();
            assertEquals(2, screen.menuState().rootIndex(), "hover selects the row");

            float[] r1 = row(1); // Flurry of Blows: aimed at the enemy
            stage.view.input().pointerMove(r1[0], r1[1]);
            stage.view.input().pointerDown(r1[0], r1[1], 0, 0);
            stage.view.input().pointerUp(r1[0], r1[1], 0, 0);
            stage.settle();
            assertTrue(screen.menuState().targeting(), "a press on a targeted command enters the target step");
            assertEquals(BattleCommand.FLURRY, screen.menuState().targetCommand());

            for (int key : new int[]{GLFW_KEY_ENTER, GLFW_KEY_SPACE, GLFW_KEY_ESCAPE}) {
                assertFalse(stage.view.input().keyDown(key, 0, false), "the document leaves key " + key + " alone");
                stage.view.input().keyUp(key, 0);
            }
        }
    }

    @Test
    void theResultPanelsButtonsActOnceRisen() throws Exception {
        List<String> calls = new ArrayList<>();
        BattleScreenHost host = new BattleScreenHost() {
            @Override public void skipIntro() { calls.add("skip"); }
            @Override public void retry() { calls.add("retry"); }
            @Override public void exploreArena() { calls.add("explore"); }
            @Override public void returnToWorld() { calls.add("return"); }
            @Override public void openPauseMenu() { calls.add("pause"); }
        };
        Settings.getInstance().setUiScale(1f);
        FakeBattleView view = new FakeBattleView();
        FocusBattleScreen screen = new FocusBattleScreen(null);
        screen.bind(view, BattleHudFixtures.NO_INPUT, FakeBattleView.crucibleLayout(), host);
        view.phase = BattlePhase.RESULT;
        view.outcome = BattleOutcome.VICTORY;
        BattleHudFixtures.frames(screen, view, ResultPanel.delayFor(BattleOutcome.VICTORY) + ResultPanel.RISE_SECONDS
            + ResultPanel.COUNT_UP_SECONDS + 0.1f, new BattleEvent.Ended(BattleOutcome.VICTORY));
        screen.prepareFrame(1920, 1080, BattleHudFixtures.camera(1920, 1080));
        assertTrue(screen.resultPanel().interactive());
        try (DocumentStage stage = DocumentBattleHudCapture.stage(shipped, screen, 1920, 1080, 1f)) {
            float px = -1, py = -1;
            for (int y = 0; y < 1080 && px < 0; y += 4) {
                for (int x = 0; x < 1920; x += 4) {
                    if (ResultPanel.buttonAt(x, y, 1920, 1080, 1f) == 0) {
                        px = x;
                        py = y;
                        break;
                    }
                }
            }
            assertTrue(px >= 0, "the Retry button is on screen");
            stage.view.input().pointerMove(px, py);
            stage.view.input().pointerDown(px, py, 0, 0);
            stage.view.input().pointerUp(px, py, 0, 0);
            stage.settle();
            assertEquals(List.of("retry"), calls);
        }
    }
}
