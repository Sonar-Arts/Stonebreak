package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import org.joml.Vector3f;

import java.util.List;

/**
 * A still battle for the editor preview (#301): the battle HUD document's {@code stonebreak:battle-hud} provider draws
 * this when no game battle is bound, so the document composes in Open Mason as in the game. A monk and an Archon at
 * rest in the Crucible, the command window open, the HUD settled; nothing ever happens to it.
 */
public final class BattleHudPreview {

    private static FocusBattleScreen screen;

    private BattleHudPreview() {
    }

    /** The preview screen, bound and settled once. Render thread. */
    public static synchronized FocusBattleScreen screen() {
        if (screen == null) {
            FocusBattleScreen s = new FocusBattleScreen(null);
            s.bind(new StillView(), NO_INPUT, new BattleStageLayout(new Vector3f(0f, 0.06f, 9f),
                new Vector3f(0f, 0.06f, -9f), 1.8f, 2.72f, 2.0f, 0.35f, 50f, 0.06f, List.of()), NO_HOST);
            for (int i = 0; i < 24; i++) {
                s.update(1f / 60f);
            }
            screen = s;
        }
        return screen;
    }

    private record Combatant(CombatantId id, String displayName, float hp, float maxHp, float atb)
            implements CombatantView {
        @Override public ActorPose pose() { return ActorPose.IDLE; }
        @Override public List<StatusView> statuses() { return List.of(); }
    }

    private static final class StillView implements BattleView {
        private final CombatantView monk = new Combatant(CombatantId.MONK, "Monk", 142f, 180f, 1f);
        private final CombatantView archon = new Combatant(CombatantId.ARCHON, "Ice Archon", 610f, 900f, 0.4f);

        @Override public BattlePhase phase() { return BattlePhase.RUNNING; }
        @Override public BattleOutcome outcome() { return BattleOutcome.NONE; }
        @Override public float elapsedSeconds() { return 42f; }
        @Override public CombatantView monk() { return monk; }
        @Override public CombatantView archon() { return archon; }
        @Override public int qi() { return 3; }
        @Override public int maxQi() { return 5; }
        @Override public float focus() { return 60f; }
        @Override public float maxFocus() { return 100f; }
        @Override public int meditateCharges() { return 2; }
        @Override public int queuedSurgeHits() { return 0; }
        @Override public boolean commandWindowOpen() { return true; }
        @Override public CommandAvailability availability(BattleCommand command) { return CommandAvailability.OK; }
        @Override public ActionView currentAction() { return null; }
        @Override public TelegraphView telegraph() { return null; }
        @Override public PromptView prompt() { return null; }
        @Override public BattleStats stats() { return BattleStats.ZERO; }
        @Override public List<BattleEvent> frameEvents() { return List.of(); }
    }

    private static final BattleInput NO_INPUT = new BattleInput() {
        @Override public void introFinished() { }
        @Override public boolean submit(BattleCommand command) { return false; }
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
}
