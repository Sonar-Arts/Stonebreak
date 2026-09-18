package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Input routing of the battle screen, driven headlessly (null backend, no GL): the strict order
 * intro → combo → ring/parry → menu, the "nothing leaks while bound" rule, and mouse hit-tests that
 * agree with the rects the painters draw.
 */
class FocusBattleScreenInputTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** Records every model call as a readable line. */
    private static final class RecordingInput implements BattleInput {
        final List<String> calls = new ArrayList<>();
        boolean accept = true;

        @Override public void introFinished() { calls.add("introFinished"); }
        @Override public boolean submit(BattleCommand command) { calls.add("submit " + command); return accept; }
        @Override public void pressConfirm() { calls.add("confirm"); }
        @Override public void pressDirection(ComboDirection direction) { calls.add("direction " + direction); }
    }

    private static final class RecordingHost implements BattleScreenHost {
        final List<String> calls = new ArrayList<>();

        @Override public void skipIntro() { calls.add("skipIntro"); }
        @Override public void retry() { calls.add("retry"); }
        @Override public void exploreArena() { calls.add("exploreArena"); }
        @Override public void returnToWorld() { calls.add("returnToWorld"); }
        @Override public void openPauseMenu() { calls.add("openPauseMenu"); }
    }

    private FakeBattleView view;
    private RecordingInput input;
    private RecordingHost host;
    private FocusBattleScreen screen;

    @BeforeEach
    void bindAScreen() {
        view = new FakeBattleView();
        input = new RecordingInput();
        host = new RecordingHost();
        screen = new FocusBattleScreen(null);
        screen.bind(view, input, FakeBattleView.crucibleLayout(), host);
    }

    private boolean press(int key) {
        return screen.handleKeyInput(key, GLFW_PRESS, 0);
    }

    private static float[] centre(float[] rect) {
        return new float[]{rect[0] + rect[2] / 2f, rect[1] + rect[3] / 2f};
    }

    private float uiScale() {
        return Settings.getInstance().getUiScale();
    }

    // ── binding ──────────────────────────────────────────────────────────────

    @Test
    void anUnboundScreenConsumesNothingAndDrawsNothing() {
        FocusBattleScreen idle = new FocusBattleScreen(null);
        assertFalse(idle.handleKeyInput(GLFW_KEY_ENTER, GLFW_PRESS, 0));
        assertFalse(idle.handleMouseClick(10, 10, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS));
        idle.handleMouseMove(10, 10, W, H);
        idle.update(0.016f);
        idle.render(W, H, null);

        screen.unbind();
        assertFalse(press(GLFW_KEY_ENTER), "unbind releases the keyboard again");
        assertTrue(input.calls.isEmpty());
    }

    @Test
    void aBoundScreenConsumesEveryKeySoNothingLeaksToGameplay() {
        assertTrue(press(GLFW_KEY_F), "an unmapped key is still swallowed");
        assertTrue(screen.handleKeyInput(GLFW_KEY_W, GLFW_RELEASE, 0));
        assertTrue(screen.handleMouseClick(5, 5, W, H, GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS));
        assertTrue(input.calls.isEmpty());
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void renderingHeadlessIsANoOp() {
        view.commandWindowOpen = true;
        screen.update(0.05f);
        screen.render(W, H, null);   // null backend: must not throw
        screen.cleanup();
        assertFalse(screen.isBound());
    }

    // ── routing order ────────────────────────────────────────────────────────

    @Test
    void anyConfirmDuringTheIntroSkipsIt() {
        view.phase = BattlePhase.INTRO;
        view.commandWindowOpen = true;   // even a stray open window must not win over the intro
        assertTrue(press(GLFW_KEY_ENTER));
        assertTrue(press(GLFW_KEY_SPACE));
        assertTrue(press(GLFW_KEY_E));
        assertEquals(List.of("skipIntro", "skipIntro", "skipIntro"), host.calls);
        assertTrue(input.calls.isEmpty(), "the intro never reaches the model through the HUD");

        press(GLFW_KEY_S);
        assertEquals(0, screen.menuState().rootIndex(), "navigation is dead during the intro");
        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_REPEAT, 0);
        assertEquals(3, host.calls.size(), "a held confirm does not re-fire");
    }

    @Test
    void comboPromptTakesTheDirectionKeys() {
        view.phase = BattlePhase.ACTION;
        view.prompt = new PromptView.Combo(List.of(ComboDirection.UP, ComboDirection.LEFT), 0, 0f, 1f, List.of());
        press(GLFW_KEY_W);
        press(GLFW_KEY_A);
        press(GLFW_KEY_S);
        press(GLFW_KEY_D);
        press(GLFW_KEY_UP);
        press(GLFW_KEY_LEFT);
        press(GLFW_KEY_DOWN);
        press(GLFW_KEY_RIGHT);
        assertEquals(List.of("direction UP", "direction LEFT", "direction DOWN", "direction RIGHT",
                "direction UP", "direction LEFT", "direction DOWN", "direction RIGHT"), input.calls);

        screen.handleKeyInput(GLFW_KEY_W, GLFW_REPEAT, 0);
        press(GLFW_KEY_ENTER);
        press(GLFW_KEY_Q);
        assertEquals(8, input.calls.size(), "held keys, confirm and Q mean nothing to a combo");
        assertTrue(host.calls.isEmpty(), "Q beside W/A must not open the pause menu mid-combo");
    }

    @Test
    void ringAndParryPromptsTakeConfirm() {
        view.phase = BattlePhase.ACTION;
        view.prompt = new PromptView.Ring(0, 3, 0.1f, 0.8f, 0.5f, 0.6f, 0.4f, 0.7f);
        press(GLFW_KEY_SPACE);
        view.prompt = new PromptView.Parry(0.2f, 0.8f, 1.0f, 1.03f);
        view.telegraph = new TelegraphView(EnemyAction.OVERHEAD, 0.2f, 1.03f, 0.8f, 1.0f, false);
        press(GLFW_KEY_E);
        assertEquals(List.of("confirm", "confirm"), input.calls);

        press(GLFW_KEY_W);
        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_REPEAT, 0);
        assertEquals(2, input.calls.size(), "directions and key repeat do not resolve a timed prompt");
    }

    @Test
    void aPromptOutranksAnOpenCommandWindow() {
        view.commandWindowOpen = true;
        view.prompt = new PromptView.Ring(0, 3, 0.1f, 0.8f, 0.5f, 0.6f, 0.4f, 0.7f);
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("confirm"), input.calls, "confirm resolves the ring, it does not submit Strike");
    }

    @Test
    void confirmSubmitsTheHighlightedCommandThroughTheTargetStep() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_ENTER);
        assertTrue(input.calls.isEmpty(), "Strike is aimed first");
        assertEquals(BattleMenuState.Level.TARGET, screen.menuState().level());
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("submit STRIKE"), input.calls);
        assertFalse(screen.menuState().targeting());

        press(GLFW_KEY_S);
        press(GLFW_KEY_SPACE);
        press(GLFW_KEY_E);
        assertEquals(List.of("submit STRIKE", "submit FLURRY"), input.calls);
    }

    // ── target step ──────────────────────────────────────────────────────────

    @Test
    void aReactionGuardIsOnePressFromTheRootMenu() {
        view.commandWindowOpen = true;
        screen.update(0.016f);
        screen.menuState().selectRoot(4);
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("submit GUARD"), input.calls, "no target step between the player and a Guard");
        assertFalse(screen.menuState().targeting());

        screen.menuState().selectRoot(3);
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("submit GUARD", "submit MEDITATE"), input.calls, "self-targeted commands submit at once");
    }

    @Test
    void everyEnemyAimedCommandTakesTheTargetStepAndTheRestDoNot() {
        view.commandWindowOpen = true;
        screen.update(0.016f);
        view.focus = 100f;
        List<BattleCommand> aimed = List.of(BattleCommand.STRIKE, BattleCommand.FLURRY,
                BattleCommand.STUNNING_STRIKE, BattleCommand.FOCUS_COMBO);
        for (BattleCommand command : BattleCommand.values()) {
            input.calls.clear();
            screen.menuState().reset();
            pointCursorAt(command);
            press(GLFW_KEY_ENTER);
            if (aimed.contains(command)) {
                assertTrue(input.calls.isEmpty(), command + " waits for a target");
                assertEquals(command, screen.menuState().targetCommand());
                assertEquals(command.displayName() + ": " + BattleHelpText.TARGET_HINT,
                        BattleHelpText.lineFor(view, screen.menuState()).text());
                press(GLFW_KEY_SPACE);
            }
            assertEquals(List.of("submit " + command), input.calls, command.name());
        }
    }

    private void pointCursorAt(BattleCommand command) {
        BattleMenuState menu = screen.menuState();
        for (int i = 0; i < com.stonebreak.battle.api.BattleMenu.ROOT.size(); i++) {
            if (com.stonebreak.battle.api.BattleMenu.ROOT.get(i).command() == command) {
                menu.selectRoot(i);
                return;
            }
        }
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        menu.openSubmenu();
        for (int i = 0; i < com.stonebreak.battle.api.BattleMenu.QI_ARTS.size(); i++) {
            if (com.stonebreak.battle.api.BattleMenu.QI_ARTS.get(i).command() == command) menu.selectSubmenu(i);
        }
    }

    @Test
    void backLeavesTheTargetStepWithoutPausingOrSubmitting() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_S);
        press(GLFW_KEY_ENTER);
        assertTrue(screen.menuState().targeting());

        press(GLFW_KEY_W);
        press(GLFW_KEY_D);
        screen.handleKeyInput(GLFW_KEY_DOWN, GLFW_REPEAT, 0);
        assertTrue(screen.menuState().targeting(), "directions do nothing with a single target");
        assertEquals(1, screen.menuState().rootIndex());

        press(GLFW_KEY_ESCAPE);
        assertFalse(screen.menuState().targeting());
        assertEquals(1, screen.menuState().rootIndex(), "back on Flurry, where the player was");
        assertTrue(host.calls.isEmpty(), "escape stepped back instead of pausing");

        press(GLFW_KEY_ENTER);
        press(GLFW_KEY_Q);
        assertFalse(screen.menuState().targeting(), "Q is back as well");
        assertTrue(input.calls.isEmpty());
        assertTrue(host.calls.isEmpty());

        press(GLFW_KEY_ENTER);
        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_REPEAT, 0);
        assertTrue(input.calls.isEmpty(), "holding confirm through the menu does not fire at the target");
    }

    @Test
    void aTargetedQiArtBacksOutThroughTheSubmenu() {
        view.commandWindowOpen = true;
        screen.update(0.016f);
        pointCursorAt(BattleCommand.STUNNING_STRIKE);
        press(GLFW_KEY_ENTER);
        assertTrue(screen.menuState().targeting());
        assertTrue(screen.menuState().submenuOpen());
        press(GLFW_KEY_ESCAPE);
        assertTrue(screen.menuState().submenuOpen(), "first back: target step only");
        press(GLFW_KEY_ESCAPE);
        assertFalse(screen.menuState().submenuOpen());
        press(GLFW_KEY_ESCAPE);
        assertEquals(List.of("openPauseMenu"), host.calls);
    }

    @Test
    void aCommandThatBecomesUnavailableWhileAimingIsNotSubmitted() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_ENTER);
        view.unavailable.put(BattleCommand.STRIKE, "Arms too heavy.");
        press(GLFW_KEY_ENTER);
        assertTrue(input.calls.isEmpty());
        BattleHelpText.Line line = BattleHelpText.lineFor(view, screen.menuState());
        assertEquals("Arms too heavy.", line.text());
        assertTrue(line.warning());
    }

    @Test
    void theTurnEndingDropsTheTargetStep() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_ENTER);
        assertTrue(screen.menuState().targeting());
        view.commandWindowOpen = false;
        screen.update(0.016f);
        assertFalse(screen.menuState().targeting());
        press(GLFW_KEY_ENTER);
        assertTrue(input.calls.isEmpty());
    }

    @Test
    void clickingTheTargetConfirmsAtSeveralResolutions() {
        int[][] windows = {{1024, 600}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            input.calls.clear();
            view.commandWindowOpen = true;
            screen.menuState().reset();
            press(GLFW_KEY_ENTER);

            // No camera matrix: the cursor is pinned to the enemy plate, and so is the hit area.
            screen.render(win[0], win[1], null);
            com.stonebreak.ui.focusBattle.elements.TargetCursor.Placement pinned =
                    com.stonebreak.ui.focusBattle.elements.TargetCursor.place(FakeBattleView.crucibleLayout(), view,
                            null, win[0], win[1], uiScale());
            assertTrue(pinned.pinned());
            screen.handleMouseClick(win[0] * 0.5, win[1] * 0.55, win[0], win[1], GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            assertTrue(input.calls.isEmpty(), "a click on empty arena is not a target");
            assertTrue(screen.menuState().targeting());
            float[] hand = centre(pinned.handRect());
            screen.handleMouseClick(hand[0], hand[1], win[0], win[1], GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            assertEquals(List.of("submit STRIKE"), input.calls, win[0] + "x" + win[1] + " hand cursor");

            // With a camera looking at the Archon the hit area is its projected body.
            input.calls.clear();
            press(GLFW_KEY_ENTER);
            org.joml.Matrix4f vp = lookAtArchon(win[0], win[1]);
            screen.render(win[0], win[1], vp);
            com.stonebreak.ui.focusBattle.elements.TargetCursor.Placement placed =
                    com.stonebreak.ui.focusBattle.elements.TargetCursor.place(FakeBattleView.crucibleLayout(), view,
                            vp, win[0], win[1], uiScale());
            assertFalse(placed.pinned(), win[0] + "x" + win[1]);
            float[] body = centre(placed.bodyRect());
            screen.handleMouseClick(body[0], body[1], win[0], win[1], GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            assertEquals(List.of("submit STRIKE"), input.calls, win[0] + "x" + win[1] + " projected body");
        }
    }

    /** Camera behind the monk looking down the arena at the Archon's chest. */
    private static org.joml.Matrix4f lookAtArchon(int w, int h) {
        return new org.joml.Matrix4f().perspective((float) Math.toRadians(60.0), (float) w / h, 0.1f, 200f)
                .lookAt(0f, 2.2f, 13f, 0f, 1.9f, -9f, 0f, 1f, 0f);
    }

    @Test
    void hoverIsInertWhileAimingButAClickOnARowTakesOver() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_ENTER);
        float[] guard = centre(FocusBattleLayout.commandRowRect(4, W, H, uiScale()));
        screen.handleMouseMove(guard[0], guard[1], W, H);
        assertTrue(screen.menuState().targeting(), "the pointer crosses the list on its way to the target");
        assertEquals(0, screen.menuState().rootIndex());

        screen.handleMouseClick(guard[0], guard[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(List.of("submit GUARD"), input.calls, "a clicked Guard still lands in one action");
        assertFalse(screen.menuState().targeting());
    }

    @Test
    void navigationRepeatsButConfirmDoesNot() {
        view.commandWindowOpen = true;
        screen.handleKeyInput(GLFW_KEY_DOWN, GLFW_REPEAT, 0);
        screen.handleKeyInput(GLFW_KEY_DOWN, GLFW_REPEAT, 0);
        assertEquals(2, screen.menuState().rootIndex());
        press(GLFW_KEY_W);
        press(GLFW_KEY_UP);
        press(GLFW_KEY_UP);
        assertEquals(5, screen.menuState().rootIndex(), "up wraps from the first row to the last");

        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_REPEAT, 0);
        assertTrue(input.calls.isEmpty(), "a held confirm must not submit");
    }

    @Test
    void nothingIsSubmittedWhileTheCommandWindowIsClosed() {
        view.commandWindowOpen = false;
        press(GLFW_KEY_ENTER);
        press(GLFW_KEY_S);
        assertTrue(input.calls.isEmpty());
        assertEquals(0, screen.menuState().rootIndex());
    }

    @Test
    void anUnavailableRowDoesNothingButExplainsItself() {
        view.commandWindowOpen = true;
        view.unavailable.put(BattleCommand.STRIKE, "Arms too heavy.");
        press(GLFW_KEY_ENTER);
        assertTrue(input.calls.isEmpty(), "unavailable commands never reach the model");
        BattleHelpText.Line line = BattleHelpText.lineFor(view, screen.menuState());
        assertEquals("Arms too heavy.", line.text());
        assertTrue(line.warning());
    }

    @Test
    void theSubmenuOpensWithConfirmOrRightAndClosesWithLeftOrBack() {
        view.commandWindowOpen = true;
        int qiRow = FocusBattleLayout.qiArtsRowIndex();

        press(GLFW_KEY_D);
        assertFalse(screen.menuState().submenuOpen(), "right does nothing off the Qi Arts row");

        for (int i = 0; i < qiRow; i++) press(GLFW_KEY_S);
        press(GLFW_KEY_D);
        assertTrue(screen.menuState().submenuOpen());
        press(GLFW_KEY_A);
        assertFalse(screen.menuState().submenuOpen());

        press(GLFW_KEY_ENTER);
        assertTrue(screen.menuState().submenuOpen(), "confirm on the opener row opens it too");
        assertTrue(input.calls.isEmpty(), "opening the submenu submits nothing");
        press(GLFW_KEY_ESCAPE);
        assertFalse(screen.menuState().submenuOpen());
        assertTrue(host.calls.isEmpty(), "escape closed the submenu instead of pausing");

        press(GLFW_KEY_RIGHT);
        press(GLFW_KEY_Q);
        assertFalse(screen.menuState().submenuOpen());
        press(GLFW_KEY_LEFT);
        assertEquals(qiRow, screen.menuState().rootIndex(), "left at the root is harmless");
    }

    @Test
    void aQiArtIsSubmittedFromTheSubmenu() {
        view.commandWindowOpen = true;
        for (int i = 0; i < FocusBattleLayout.qiArtsRowIndex(); i++) press(GLFW_KEY_S);
        press(GLFW_KEY_D);
        press(GLFW_KEY_S);
        press(GLFW_KEY_S);
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("submit MARTIAL_SURGE"), input.calls);
        assertTrue(screen.menuState().submenuOpen(), "a free action leaves the cursor where it was");

        view.commandWindowOpen = false;   // a turn-ending command closes the window…
        screen.update(0.016f);
        view.commandWindowOpen = true;    // …and the next turn starts clean
        screen.update(0.016f);
        assertFalse(screen.menuState().submenuOpen());
        assertEquals(0, screen.menuState().rootIndex());
    }

    @Test
    void escapeAtTheRootOpensThePauseMenu() {
        view.commandWindowOpen = true;
        press(GLFW_KEY_ESCAPE);
        press(GLFW_KEY_Q);
        assertEquals(List.of("openPauseMenu", "openPauseMenu"), host.calls);

        view.commandWindowOpen = false;
        view.phase = BattlePhase.ACTION;
        press(GLFW_KEY_ESCAPE);
        assertEquals(3, host.calls.size(), "escape pauses in every phase");
        press(GLFW_KEY_Q);
        assertEquals(3, host.calls.size(), "Q is only a back key inside the menu");
    }

    // ── mouse ────────────────────────────────────────────────────────────────

    @Test
    void hoverMovesTheCursorToTheRowUnderThePointer() {
        view.commandWindowOpen = true;
        for (int i = FocusBattleLayout.commandRowCount() - 1; i >= 0; i--) {
            float[] c = centre(FocusBattleLayout.commandRowRect(i, W, H, uiScale()));
            screen.handleMouseMove(c[0], c[1], W, H);
            assertEquals(i, screen.menuState().rootIndex(), "hover over root row " + i);
        }
        screen.handleMouseMove(W / 2.0, H / 2.0, W, H);
        assertEquals(0, screen.menuState().rootIndex(), "hovering empty space keeps the cursor");
        assertTrue(input.calls.isEmpty(), "hover never submits");
    }

    @Test
    void hoverIsIgnoredWhileTheWindowIsClosed() {
        float[] c = centre(FocusBattleLayout.commandRowRect(3, W, H, uiScale()));
        screen.handleMouseMove(c[0], c[1], W, H);
        assertEquals(0, screen.menuState().rootIndex());
        assertTrue(screen.handleMouseClick(c[0], c[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS));
        assertTrue(input.calls.isEmpty(), "and a click submits nothing");
    }

    @Test
    void clickConfirmsTheRowUnderThePointer() {
        view.commandWindowOpen = true;
        float[] guard = centre(FocusBattleLayout.commandRowRect(4, W, H, uiScale()));
        screen.handleMouseClick(guard[0], guard[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(List.of("submit GUARD"), input.calls);

        screen.handleMouseClick(guard[0], guard[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        screen.handleMouseClick(guard[0], guard[1], W, H, GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
        screen.handleMouseClick(W / 2.0, H / 2.0, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(1, input.calls.size(), "only a left press on a row confirms");
    }

    @Test
    void theSubmenuIsFullyUsableWithTheMouse() {
        view.commandWindowOpen = true;
        int qiRow = FocusBattleLayout.qiArtsRowIndex();
        float[] opener = centre(FocusBattleLayout.commandRowRect(qiRow, W, H, uiScale()));
        screen.handleMouseClick(opener[0], opener[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertTrue(screen.menuState().submenuOpen(), "clicking Qi Arts opens the submenu");

        // The pointer crosses the opener row on its way right: that must not close the submenu.
        screen.handleMouseMove(opener[0] + 20, opener[1], W, H);
        assertTrue(screen.menuState().submenuOpen());

        float[] swift = centre(FocusBattleLayout.submenuRowRect(1, W, H, uiScale()));
        screen.handleMouseMove(swift[0], swift[1], W, H);
        assertEquals(1, screen.menuState().submenuIndex());
        screen.handleMouseClick(swift[0], swift[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(List.of("submit SWIFT_STEP"), input.calls);

        screen.handleMouseClick(opener[0], opener[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertFalse(screen.menuState().submenuOpen(), "clicking the opener again closes it");

        screen.handleMouseClick(opener[0], opener[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        float[] strike = centre(FocusBattleLayout.commandRowRect(0, W, H, uiScale()));
        screen.handleMouseClick(strike[0], strike[1], W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertFalse(screen.menuState().submenuOpen(), "a click on another root row takes over");
        assertEquals(BattleCommand.STRIKE, screen.menuState().targetCommand(), "Strike goes on to its target step");
        assertEquals(List.of("submit SWIFT_STEP"), input.calls);
    }

    @Test
    void mouseHitTestsFollowTheLayoutAtEveryResolution() {
        view.commandWindowOpen = true;
        int[][] windows = {{1024, 600}, {1280, 720}, {2560, 1440}, {3840, 2160}};
        for (int[] win : windows) {
            for (int i = 0; i < FocusBattleLayout.commandRowCount(); i++) {
                float[] row = FocusBattleLayout.commandRowRect(i, win[0], win[1], uiScale());
                screen.handleMouseMove(row[0] + 1, row[1] + 1, win[0], win[1]);
                assertEquals(i, screen.menuState().rootIndex(), win[0] + "x" + win[1] + " top-left of row " + i);
                screen.handleMouseMove(row[0] + row[2] - 1, row[1] + row[3] - 1, win[0], win[1]);
                assertEquals(i, screen.menuState().rootIndex(), win[0] + "x" + win[1] + " bottom-right of row " + i);
            }
        }
    }

    @Test
    void clicksRouteLikeConfirmOutsideTheMenu() {
        view.phase = BattlePhase.INTRO;
        screen.handleMouseClick(100, 100, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(List.of("skipIntro"), host.calls);

        view.phase = BattlePhase.ACTION;
        view.prompt = new PromptView.Ring(0, 3, 0.1f, 0.8f, 0.5f, 0.6f, 0.4f, 0.7f);
        screen.handleMouseClick(100, 100, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(List.of("confirm"), input.calls);

        view.prompt = new PromptView.Combo(List.of(ComboDirection.UP), 0, 0f, 1f, List.of());
        screen.handleMouseClick(100, 100, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertEquals(1, input.calls.size(), "a combo is answered with directions only");
    }
}
