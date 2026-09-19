package com.stonebreak.battle;

import com.stonebreak.battle.api.EnemyAction;

import java.util.List;

/**
 * Every {@code SB_Ice_Archon.sbe} animation state the battle plays. Reaction clips are copied from
 * {@code /sbe/Mobs/ice-archon-combat/clips.json}; the three attacks come from {@link EnemyAction}, which
 * the contract already pins to the authored clips. Checked by {@code BattleClipsContractTest}.
 */
final class ArchonClips {
    private ArchonClips() {}

    /** Legacy rest pose: played while the intro camera runs. */
    static final BattleClip IDLE = new BattleClip("idle", 6.00f, true);

    static final BattleClip COMBAT_IDLE = new BattleClip("combat_idle", 3.20f, true);
    /** Cue = the strongest point of the recoil. */
    static final BattleClip HURT = new BattleClip("hurt", 0.76f, false, 0.13f);
    static final BattleClip STUNNED_ENTER = new BattleClip("stunned_enter", 0.66f, false);
    static final BattleClip STUNNED = new BattleClip("stunned", 2.80f, true);
    static final BattleClip STUNNED_EXIT = new BattleClip("stunned_exit", 0.90f, false);
    /** Cue = ground contact of the collapse. */
    static final BattleClip DEATH = new BattleClip("death", 3.10f, false, 1.82f);
    static final BattleClip DEFEATED = new BattleClip("defeated", 2.40f, true);

    static final List<BattleClip> LEGACY = List.of(IDLE);
    static final List<BattleClip> REACTIONS = List.of(COMBAT_IDLE, HURT, STUNNED_ENTER, STUNNED, STUNNED_EXIT,
            DEATH, DEFEATED);

    /** The authored attack clip; its single cue is the impact. */
    static BattleClip attack(EnemyAction action) {
        return new BattleClip(action.sbeState(), action.clipDuration(), false, action.impactTime());
    }
}
