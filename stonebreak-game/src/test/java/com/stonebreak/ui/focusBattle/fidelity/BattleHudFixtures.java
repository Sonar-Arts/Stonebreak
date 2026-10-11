package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
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
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import com.stonebreak.ui.focusBattle.elements.EncounterTransition;
import com.stonebreak.ui.focusBattle.elements.ResultPanel;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * The focus battle HUD's #301 fidelity cases: a headless {@link FocusBattleScreen} bound to a
 * {@link FakeBattleView} and driven through the snapshot gallery's scenes (the same frames on both sides: every
 * window and layer animates deterministically on C1), the camera over the monk's shoulder, and the window rects as
 * the geometry oracle. Not a test class.
 */
public final class BattleHudFixtures {

    private BattleHudFixtures() {
    }

    static final float DT = 1f / 60f;

    public static final List<String> SCENES = List.of("aiming", "combo-parry", "banner", "intro-iris", "intro-card",
        "victory", "defeat", "victory-rising", "telegraph", "statuses", "prompts", "hover-row2");

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("battle-hud", List.of("menu"), FidelityCase.STANDARD));
        for (String s : SCENES) {
            out.add(new FidelityCase("battle-hud", s, FidelityCase.STANDARD.get(0)));
        }
        out.add(new FidelityCase("battle-hud", "aiming", FidelityCase.STANDARD.get(3)));
        out.add(new FidelityCase("battle-hud", "victory", FidelityCase.STANDARD.get(1)));
        return out;
    }

    static final BattleInput NO_INPUT = new BattleInput() {
        @Override public void introFinished() { }
        @Override public boolean submit(BattleCommand command) { return true; }
        @Override public void pressConfirm() { }
        @Override public void pressDirection(ComboDirection direction) { }
    };

    static final BattleScreenHost NO_HOST = new BattleScreenHost() {
        @Override public void skipIntro() { }
        @Override public void retry() { }
        @Override public void exploreArena() { }
        @Override public void returnToWorld() { }
        @Override public void openPauseMenu() { }
    };

    /** Over the monk's shoulder: the Archon mid-screen. */
    public static Matrix4f camera(int w, int h) {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) w / h, 0.1f, 200f)
            .lookAt(1.5f, 2.4f, 12.5f, 0f, 1.9f, -9f, 0f, 1f, 0f);
    }

    static void frames(FocusBattleScreen screen, FakeBattleView view, float seconds, BattleEvent... first) {
        view.events.clear();
        view.events.addAll(List.of(first));
        screen.update(DT);
        view.events.clear();
        for (int i = 1; i < Math.round(seconds / DT); i++) screen.update(DT);
    }

    /** A bound screen driven through {@code scene}, its frame prepared at {@code w}x{@code h} (the camera seen once). */
    public static FocusBattleScreen screen(String scene, FakeBattleView view, int w, int h) {
        if (scene.startsWith("intro")) view.phase = BattlePhase.INTRO;
        FocusBattleScreen screen = new FocusBattleScreen(null);
        screen.bind(view, NO_INPUT, FakeBattleView.crucibleLayout(), NO_HOST);
        Matrix4f cam = camera(w, h);
        switch (scene) {
            case "menu" -> {
                view.commandWindowOpen = true;
                frames(screen, view, 0.4f);
            }
            case "aiming" -> {
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
                screen.floaters().resolvePending(w, h, Settings.getInstance().getUiScale(), cam);
                frames(screen, view, 0.25f);
            }
            case "combo-parry" -> {
                view.phase = BattlePhase.ACTION;
                view.currentAction = new ActionView(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 0f, 6f);
                frames(screen, view, 0.5f, new BattleEvent.ActionStarted(CombatantId.MONK, "Focus Combo",
                    BattleCommand.FOCUS_COMBO, null, 6f));
                frames(screen, view, 0.02f,
                    new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED),
                    new BattleEvent.Healed(CombatantId.MONK, 54f),
                    new BattleEvent.DamageDealt(CombatantId.ARCHON, 128f, DamageFlavor.NORMAL),
                    new BattleEvent.FocusFull());
                screen.floaters().resolvePending(w, h, Settings.getInstance().getUiScale(), cam);
                frames(screen, view, 0.2f);
            }
            case "banner" -> {
                view.phase = BattlePhase.ACTION;
                view.currentAction = new ActionView(CombatantId.MONK, "Flurry of Blows", BattleCommand.FLURRY, null, 0f, 2f);
                frames(screen, view, 0.5f, new BattleEvent.ActionStarted(CombatantId.MONK, "Flurry of Blows",
                    BattleCommand.FLURRY, null, 2f));
            }
            case "intro-iris" -> frames(screen, view, 0.45f);
            case "intro-card" -> frames(screen, view,
                EncounterTransition.CARD_IN_SECONDS + EncounterTransition.CARD_FADE_IN_SECONDS + 0.5f);
            case "victory" -> end(screen, view, BattleOutcome.VICTORY, 0);
            case "defeat" -> end(screen, view, BattleOutcome.DEFEAT, 1);
            case "victory-rising" -> {
                view.phase = BattlePhase.RESULT;
                view.outcome = BattleOutcome.VICTORY;
                frames(screen, view, BattleHudRules.VICTORY_CINEMATIC_SECONDS + ResultPanel.RISE_SECONDS * 0.4f,
                    new BattleEvent.Ended(BattleOutcome.VICTORY));
            }
            case "telegraph" -> {
                view.commandWindowOpen = true;
                view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1));
                view.archon.statuses.add(new StatusView(BattleStatus.CHILLED, 6f, 1));
                float impact = EnemyAction.OVERHEAD.impactTime();
                view.telegraph = new TelegraphView(EnemyAction.OVERHEAD, impact * 0.62f, impact, impact * 0.75f,
                    impact * 0.95f, false);
                frames(screen, view, 0.4f);
            }
            case "statuses" -> {
                view.commandWindowOpen = true;
                view.monk.statuses.add(new StatusView(BattleStatus.HASTE, 12f, 1));
                view.monk.hp = 30f;
                view.qi = 1;
                view.focus = 64f;
                frames(screen, view, 0.4f, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.NORMAL));
                frames(screen, view, 0.1f);
            }
            case "prompts" -> {
                view.phase = BattlePhase.ACTION;
                view.prompt = new PromptView.Ring(0, 3, 0.1f, 0.8f, 0.5f, 0.6f, 0.4f, 0.7f);
                frames(screen, view, 0.3f);
            }
            case "hover-row2" -> {
                view.commandWindowOpen = true;
                frames(screen, view, 0.4f);
            }
            default -> throw new IllegalArgumentException(scene);
        }
        screen.prepareFrame(w, h, cam);
        return screen;
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

    /** Where a hover case points: the centre of a command-window row. */
    public static float[] pointer(String scene, int w, int h) {
        if (!scene.startsWith("hover-row")) return null;
        int row = Integer.parseInt(scene.substring("hover-row".length()));
        float[] r = FocusBattleLayout.commandRowRect(row, w, h, Settings.getInstance().getUiScale());
        return new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
    }

    /** The window rects as SkijaFocusBattleRenderer.paintHud places them. */
    public static Map<String, float[]> rects(FocusBattleScreen screen, int w, int h) {
        float ui = Settings.getInstance().getUiScale();
        float s = FocusBattleLayout.effectiveScale(w, h, ui);
        var a = screen.animState();
        float[] help = FocusBattleLayout.helpStripRect(w, h, ui);
        float out = com.openmason.engine.ui.masonry.MColor.clamp01(a.bottomHudSlideOut) * (h - help[1] + 8f * s);
        Map<String, float[]> m = new LinkedHashMap<>();
        m.put("mode", FocusBattleLayout.modeTagRect(w, h, ui));
        m.put("enemy", FocusBattleLayout.offset(FocusBattleLayout.enemyPlateRect(w, h, ui), a.enemyShakeX, a.enemyShakeY));
        m.put("party", FocusBattleLayout.offset(FocusBattleLayout.partyWindowRect(w, h, ui), a.partyShakeX,
            a.partyShakeY + out));
        if (screen.view().outcome() == BattleOutcome.NONE) {
            m.put("command", FocusBattleLayout.offset(FocusBattleLayout.commandWindowRect(w, h, ui), 0f, out));
        }
        return m;
    }
}
