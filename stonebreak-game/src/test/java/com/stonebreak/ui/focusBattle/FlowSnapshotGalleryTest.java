package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.ui.focusBattle.elements.EncounterTransition;
import com.stonebreak.ui.focusBattle.elements.ResultPanel;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Every representative state of the flow elements (banner, target cursor, floaters, encounter
 * transition, result card) inside the full HUD, over arena snow and over near-black, checking each
 * one actually changes the picture on both. With {@code -Dstonebreak.flow.snapshots=<dir>} the frames
 * are also written as PNGs for a human (or an agent) to look at.
 */
class FlowSnapshotGalleryTest {

    private static final int W = 1280, H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;
    private static final int SNOW = BattleRasterFixture.BACKGROUND;
    private static final int DARK = 0xFF0A1018;

    private static final BattleInput NO_INPUT = new BattleInput() {
        @Override public void introFinished() { }
        @Override public boolean submit(BattleCommand command) { return true; }
        @Override public void pressConfirm() { }
        @Override public void pressDirection(ComboDirection direction) { }
    };

    private static final BattleScreenHost NO_HOST = new BattleScreenHost() {
        @Override public void skipIntro() { }
        @Override public void retry() { }
        @Override public void exploreArena() { }
        @Override public void returnToWorld() { }
        @Override public void openPauseMenu() { }
    };

    /** Over the monk's shoulder: the Archon mid-screen, the monk low in the foreground. */
    private static Matrix4f shoulderCamera() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) W / H, 0.1f, 200f)
                .lookAt(1.5f, 2.4f, 12.5f, 0f, 1.9f, -9f, 0f, 1f, 0f);
    }

    private static void frames(FocusBattleScreen screen, FakeBattleView view, float seconds, BattleEvent... first) {
        view.events.clear();
        view.events.addAll(List.of(first));
        screen.update(DT);
        view.events.clear();
        for (int i = 1; i < Math.round(seconds / DT); i++) screen.update(DT);
    }

    /** Scene name → what happens on a freshly bound screen before the frame is taken. */
    private static Map<String, BiConsumer<FocusBattleScreen, FakeBattleView>> scenes() {
        Map<String, BiConsumer<FocusBattleScreen, FakeBattleView>> all = new LinkedHashMap<>();
        all.put("1_aiming_archon_banner_floaters", (screen, view) -> {
            view.commandWindowOpen = true;
            frames(screen, view, 0.4f, new BattleEvent.ActionStarted(CombatantId.ARCHON, "Glacial Overhead", null,
                    EnemyAction.OVERHEAD, 0.1f));
            screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_PRESS, 0);   // Strike → choose a target
            frames(screen, view, 0.02f,
                    new BattleEvent.DamageDealt(CombatantId.ARCHON, 204f, DamageFlavor.CRITICAL),
                    new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 4f),
                    new BattleEvent.DamageDealt(CombatantId.MONK, 12f, DamageFlavor.BLOCKED),
                    new BattleEvent.QiChanged(1, 4),
                    new BattleEvent.CommandRejected(BattleCommand.SWIFT_STEP, "Not enough Qi."));
            screen.floaters().resolvePending(W, H, UI, shoulderCamera());
            frames(screen, view, 0.25f);
        });
        all.put("2_focus_combo_banner_parry_heal", (screen, view) -> {
            view.phase = BattlePhase.ACTION;
            view.currentAction = new ActionView(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 0f, 6f);
            frames(screen, view, 0.5f, new BattleEvent.ActionStarted(CombatantId.MONK, "Focus Combo",
                    BattleCommand.FOCUS_COMBO, null, 6f));
            frames(screen, view, 0.02f,
                    new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED),
                    new BattleEvent.Healed(CombatantId.MONK, 54f),
                    new BattleEvent.DamageDealt(CombatantId.ARCHON, 128f, DamageFlavor.NORMAL),
                    new BattleEvent.FocusFull());
            screen.floaters().resolvePending(W, H, UI, shoulderCamera());
            frames(screen, view, 0.2f);
        });
        all.put("3_monk_banner", (screen, view) -> {
            view.phase = BattlePhase.ACTION;
            view.currentAction = new ActionView(CombatantId.MONK, "Flurry of Blows", BattleCommand.FLURRY, null, 0f, 2f);
            frames(screen, view, 0.5f, new BattleEvent.ActionStarted(CombatantId.MONK, "Flurry of Blows",
                    BattleCommand.FLURRY, null, 2f));
        });
        all.put("4_intro_iris", (screen, view) -> frames(screen, view, 0.45f));
        all.put("5_intro_name_card", (screen, view) -> frames(screen, view,
                EncounterTransition.CARD_IN_SECONDS + EncounterTransition.CARD_FADE_IN_SECONDS + 0.5f));
        all.put("6_victory", (screen, view) -> end(screen, view, BattleOutcome.VICTORY, 0));
        all.put("7_defeat_second_button", (screen, view) -> end(screen, view, BattleOutcome.DEFEAT, 1));
        all.put("8_victory_rising", (screen, view) -> {
            view.phase = BattlePhase.RESULT;
            view.outcome = BattleOutcome.VICTORY;
            frames(screen, view, BattleHudRules.VICTORY_CINEMATIC_SECONDS + ResultPanel.RISE_SECONDS * 0.4f,
                    new BattleEvent.Ended(BattleOutcome.VICTORY));
        });
        return all;
    }

    private static void end(FocusBattleScreen screen, FakeBattleView view, BattleOutcome outcome, int focus) {
        view.phase = BattlePhase.RESULT;
        view.outcome = outcome;
        view.stats = new BattleStats(2400f, 96f, 4, 2, 7, 5, 6, 11);
        view.elapsedSeconds = 83f;
        if (outcome == BattleOutcome.DEFEAT) view.monk.hp = 0f;
        frames(screen, view, ResultPanel.delayFor(outcome) + ResultPanel.RISE_SECONDS + ResultPanel.COUNT_UP_SECONDS + 0.1f,
                new BattleEvent.Ended(outcome));
        screen.resultPanel().moveFocus(focus);
    }

    private static BattleRasterFixture render(String name, BiConsumer<FocusBattleScreen, FakeBattleView> scene,
                                              int clear, boolean run) {
        FakeBattleView view = new FakeBattleView();
        if (name.contains("intro")) view.phase = BattlePhase.INTRO;
        FocusBattleScreen screen = new FocusBattleScreen(null);
        screen.bind(view, NO_INPUT, FakeBattleView.crucibleLayout(), NO_HOST);
        if (run) scene.accept(screen, view);
        else frames(screen, view, 0.4f);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        fx.canvas.clear(clear);
        screen.renderer().paintHud(fx.ui, fx.canvas, W, H, UI, view, screen.menuState(), screen.animState(),
                shoulderCamera());
        return fx;
    }

    @Test
    void everyFlowStateChangesThePictureOnSnowAndOnDark() throws Exception {
        String out = System.getProperty("stonebreak.flow.snapshots");
        Path outDir = out == null || out.isBlank() ? null : Files.createDirectories(Path.of(out));
        for (Map.Entry<String, BiConsumer<FocusBattleScreen, FakeBattleView>> e : scenes().entrySet()) {
            for (int clear : new int[]{SNOW, DARK}) {
                BattleRasterFixture with = render(e.getKey(), e.getValue(), clear, true);
                BattleRasterFixture quiet = render(e.getKey(), e.getValue(), clear, false);
                assertTrue(with.diff(quiet) > 400,
                        e.getKey() + " must be visible over " + (clear == SNOW ? "snow" : "dark"));
                if (outDir != null) write(with, outDir.resolve(e.getKey() + (clear == SNOW ? "_snow" : "_dark") + ".png"));
            }
        }
    }

    private static void write(BattleRasterFixture fx, Path file) throws Exception {
        try (Image image = Image.makeRasterFromBitmap(fx.bitmap); Data png = EncoderPNG.encode(image)) {
            Files.write(file, png.getBytes());
        }
    }
}
