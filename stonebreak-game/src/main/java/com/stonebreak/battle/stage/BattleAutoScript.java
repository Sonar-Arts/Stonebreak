package com.stonebreak.battle.stage;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.camera.BattleCameraSystem;
import com.stonebreak.core.dev.DevScreenshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dev tooling for {@code -Dstonebreak.autobattle=<s>:script:<dir>} and {@code ...:shots:<dir>}: a bot
 * that plays the encounter (every command, perfect rings, parries, the Focus Combo) and dumps a
 * labelled back-buffer PNG the first time each interesting moment happens, or pins every camera
 * sequence in turn. Lets the HUD and the cinematic camera be reviewed from one command line.
 * Never active in normal play.
 */
public final class BattleAutoScript {

    private enum Mode { SCRIPT, SHOTS }

    /** Turn plan, repeated; the Focus Combo pre-empts it whenever the gauge is full. */
    private static final BattleCommand[] PLAN = {
            BattleCommand.STRIKE, BattleCommand.FLURRY, BattleCommand.GUARD, BattleCommand.SWIFT_STEP,
            BattleCommand.MARTIAL_SURGE, BattleCommand.STUNNING_STRIKE, BattleCommand.MEDITATE,
            BattleCommand.GUARD, BattleCommand.FLURRY, BattleCommand.FLURRY,
    };
    private static final float GIVE_UP_SECONDS = 300f;

    private record Pending(String label, float dueAt) {}

    private final Mode mode;
    private final String outDir;
    private final Set<String> taken = new HashSet<>();
    private final List<Pending> pending = new ArrayList<>();
    private float clock;
    private int planIndex;
    private int shotCounter;
    private float commandOpenFor;
    private boolean surgeSubmittedThisTurn;
    private float resultFor;
    private float introStartedAt;
    // SHOTS mode
    private List<String> sequences;
    private int sequenceIndex = -1;
    private float sequenceFor;

    private BattleAutoScript(Mode mode, String outDir) {
        this.mode = mode;
        this.outDir = outDir;
    }

    /** @return a script for the {@code <mode>:<dir>} tail of the property, or null when it names no script */
    public static BattleAutoScript parse(String[] parts) {
        if (parts.length < 2) {
            return null;
        }
        Mode mode = switch (parts[1].trim().toLowerCase(java.util.Locale.ROOT)) {
            case "script" -> Mode.SCRIPT;
            case "shots" -> Mode.SHOTS;
            default -> null;
        };
        if (mode == null) {
            return null;
        }
        return new BattleAutoScript(mode, parts.length > 2 ? parts[2].trim() : "autobattle");
    }

    /**
     * Call once per frame after the frame was rendered and before the buffer swap.
     *
     * @return true when the run is over and the game should quit
     */
    public boolean frame(float dt, int width, int height) {
        BattleView view = FocusBattle.view();
        BattleInput input = FocusBattle.input();
        BattleCameraSystem camera = FocusBattle.camera();
        if (view == null || input == null || camera == null) {
            return clock > 1f; // the battle ended underneath us
        }
        clock += dt;
        if (FocusBattle.encounterTransitionActive()) {
            once("encounter_1_freeze", 0.05f);
            once("encounter_2_twist", 0.75f);
            once("encounter_3_whiteout", 1.35f);
            introStartedAt = clock;
            flush(width, height);
            return false;
        }
        boolean done = mode == Mode.SCRIPT ? script(view, input, dt) : shots(view, camera, dt);
        flush(width, height);
        return done || clock > GIVE_UP_SECONDS;
    }

    // ─── SCRIPT: play the fight ───────────────────────────────────────────────

    private boolean script(BattleView view, BattleInput input, float dt) {
        if (view.phase() == BattlePhase.INTRO) {
            at("intro_0_reveal", introStartedAt + 0.15f);
            at("intro_1_crane", introStartedAt + 0.9f);
            at("intro_2_archon", introStartedAt + 2.2f);
            at("intro_3_monk", introStartedAt + 3.0f);
            return false;
        }
        for (BattleEvent event : view.frameEvents()) {
            onEvent(view, event);
        }
        once("idle_wide", 0.4f);

        PromptView prompt = view.prompt();
        if (prompt instanceof PromptView.Ring ring) {
            if (ring.progress() > 0.35f) {
                once("ring_" + ring.hitIndex(), 0f);
            }
            if (ring.elapsed() >= (ring.perfectStart() + ring.perfectEnd()) * 0.5f) {
                input.pressConfirm();
            }
        } else if (prompt instanceof PromptView.Combo combo) {
            if (combo.index() == 0 || combo.index() == 3) {
                once("combo_prompt_" + combo.index(), 0.1f);
            }
            if (combo.stepElapsed() >= 0.2f && combo.index() < combo.sequence().size()) {
                input.pressDirection(combo.sequence().get(combo.index()));
            }
        } else if (prompt instanceof PromptView.Parry) {
            TelegraphView telegraph = view.telegraph();
            if (telegraph != null) {
                if (telegraph.progress() > 0.45f) {
                    once("guard_telegraph", 0f);
                }
                if (telegraph.inParryWindow()) {
                    input.pressConfirm();
                }
            }
        } else if (view.telegraph() != null && view.telegraph().progress() > 0.5f) {
            once("telegraph_unguarded_" + view.telegraph().action().name().toLowerCase(java.util.Locale.ROOT), 0f);
        }

        if (view.commandWindowOpen()) {
            commandOpenFor += dt;
            once("menu_open", 0.7f);
            boolean waitingOnGuard = view.monk().has(BattleStatus.GUARDING);
            if (commandOpenFor > 1.1f && !waitingOnGuard) {
                submitNext(view, input);
            }
        } else {
            commandOpenFor = 0f;
            surgeSubmittedThisTurn = false;
        }

        if (view.outcome() != BattleOutcome.NONE) {
            resultFor += dt;
            return resultFor > 7.5f;
        }
        return false;
    }

    private void submitNext(BattleView view, BattleInput input) {
        if (view.focusReady() && view.availability(BattleCommand.FOCUS_COMBO).available()) {
            input.submit(BattleCommand.FOCUS_COMBO);
            return;
        }
        for (int tries = 0; tries < PLAN.length; tries++) {
            BattleCommand command = PLAN[planIndex % PLAN.length];
            if (command == BattleCommand.MARTIAL_SURGE && surgeSubmittedThisTurn) {
                planIndex++;
                continue;
            }
            if (view.availability(command).available() && input.submit(command)) {
                planIndex++;
                if (command == BattleCommand.MARTIAL_SURGE) {
                    surgeSubmittedThisTurn = true;
                    commandOpenFor = 0.6f; // free action: show the surge chip, then the next plan entry follows up
                }
                return;
            }
            planIndex++;
        }
        input.submit(BattleCommand.STRIKE);
    }

    private void onEvent(BattleView view, BattleEvent event) {
        switch (event) {
            case BattleEvent.ActionStarted started -> {
                String name = label(started.displayName());
                once("action_" + name + "_start", 0.3f);
                once("action_" + name + "_mid", started.duration() * 0.5f);
            }
            case BattleEvent.Impact impact -> {
                String who = impact.actor() == CombatantId.MONK ? "monk" : "archon";
                once("impact_" + who + "_" + label(actionName(view)), 0.08f);
            }
            case BattleEvent.PromptResolved resolved -> once("resolved_" + resolved.kind() + "_" + resolved.grade(), 0.05f);
            case BattleEvent.ComboFinished finished -> {
                once("combo_finished", 0.5f);
                once("combo_finisher_orbit", 1.4f);
            }
            case BattleEvent.StatusApplied applied -> once("status_" + applied.status(), 0.4f);
            case BattleEvent.FocusFull ignored -> once("focus_full", 0.3f);
            case BattleEvent.Ended ended -> {
                once("ended_" + ended.outcome() + "_1", 1.0f);
                once("ended_" + ended.outcome() + "_2_collapse", 2.0f);
                once("ended_" + ended.outcome() + "_3", 4.0f);
                once("ended_" + ended.outcome() + "_4_result_panel", 6.5f);
            }
            default -> { }
        }
    }

    private static String actionName(BattleView view) {
        return view.currentAction() == null ? "none" : view.currentAction().displayName();
    }

    // ─── SHOTS: pin every camera sequence ─────────────────────────────────────

    private boolean shots(BattleView view, BattleCameraSystem camera, float dt) {
        if (view.phase() == BattlePhase.INTRO) {
            FocusBattle.skipIntroNow();
            return false;
        }
        if (sequences == null) {
            sequences = camera.sequenceNames();
        }
        sequenceFor += dt;
        if (sequenceIndex < 0 || sequenceFor > 1.6f) {
            sequenceIndex++;
            sequenceFor = 0f;
            if (sequenceIndex >= sequences.size()) {
                camera.debugPinSequence(null);
                return true;
            }
            camera.debugPinSequence(sequences.get(sequenceIndex));
            pending.add(new Pending("seq_" + sequences.get(sequenceIndex) + "_a", clock + 0.25f));
            pending.add(new Pending("seq_" + sequences.get(sequenceIndex) + "_b", clock + 1.3f));
        }
        return false;
    }

    // ─── Screenshot queue ─────────────────────────────────────────────────────

    /** Intro-relative one-shot at an absolute script time. */
    private void at(String label, float time) {
        if (clock >= time) {
            once(label, 0f);
        }
    }

    /** Queue a labelled screenshot once per run, {@code delay} seconds from now. */
    private void once(String label, float delay) {
        if (taken.add(label)) {
            pending.add(new Pending(label, clock + delay));
        }
    }

    private void flush(int width, int height) {
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            if (clock >= p.dueAt()) {
                pending.remove(i);
                String file = String.format("%s/%03d_%s.png", outDir, shotCounter++, p.label());
                DevScreenshot.capture(file, width, height);
                System.out.println("[autobattle] shot " + file + " camera=" + FocusBattle.shotName());
                return; // one capture per frame: glReadPixels stalls
            }
        }
    }

    private static String label(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
