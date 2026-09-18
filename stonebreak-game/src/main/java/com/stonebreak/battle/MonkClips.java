package com.stonebreak.battle;

import java.util.List;

/**
 * Every {@code SB_Player.sbe} animation state the battle knows about, copied from
 * {@code /sbe/Mobs/player-combat/clips.json}. This is the ONLY place monk clip names, lengths and cue
 * times are written down; {@code BattleClipsContractTest} fails when the artists retime a clip and
 * this table is not updated with it.
 */
final class MonkClips {
    private MonkClips() {}

    // ---- legacy states (pre-combat asset; not in clips.json, checked against the SBE manifest) -----
    /** Rest pose: what the monk plays while the intro camera runs. */
    static final BattleClip IDLE = new BattleClip("idle", 3.20f, true);

    // ---- stance ---------------------------------------------------------------------------------
    static final BattleClip COMBAT_ENTER = new BattleClip("combat_enter", 0.72f, false);
    static final BattleClip COMBAT_IDLE = new BattleClip("combat_idle", 3.20f, true);
    /** In-place gait: only ever played while the stage is moving the monk. */
    static final BattleClip COMBAT_DASH = new BattleClip("combat_dash", 0.64f, true);

    // ---- attacks (cues = contacts) ----------------------------------------------------------------
    static final BattleClip STRIKE = new BattleClip("strike", 0.96f, false, 0.38f);
    /** Lead jab, rear cross, lead hook. */
    static final BattleClip FLURRY = new BattleClip("flurry", 2.08f, false, 0.43f, 0.95f, 1.47f);
    static final BattleClip STUNNING_STRIKE = new BattleClip("stunning_strike", 1.38f, false, 0.62f);
    static final BattleClip KICK = new BattleClip("kick", 1.42f, false, 0.60f);
    /** Jab, cross, two hooks, front kick, open-palm finisher. */
    static final BattleClip FOCUS_COMBO = new BattleClip("focus_combo", 4.90f, false,
            0.60f, 1.17f, 1.74f, 2.31f, 2.88f, 3.80f);
    /** Which Focus Combo contact is the front kick (it reaches farther than the punches around it). */
    static final int FOCUS_COMBO_KICK_CONTACT = 4;

    // ---- support (cues = effects) -----------------------------------------------------------------
    static final BattleClip SWIFT_STEP = new BattleClip("swift_step", 1.32f, false, 0.62f);
    static final BattleClip MARTIAL_SURGE = new BattleClip("martial_surge", 1.58f, false, 0.86f);
    static final BattleClip MEDITATE = new BattleClip("meditate", 3.60f, false, 1.80f);

    // ---- guard ------------------------------------------------------------------------------------
    static final BattleClip GUARD_ENTER = new BattleClip("guard_enter", 0.32f, false);
    static final BattleClip GUARD = new BattleClip("guard", 2.40f, true);
    static final BattleClip GUARD_EXIT = new BattleClip("guard_exit", 0.38f, false);
    /** Cue = the deflect; the model lines it up with the Archon's impact. */
    static final BattleClip PARRY = new BattleClip("parry", 0.78f, false, 0.20f);
    static final BattleClip BLOCK = new BattleClip("block", 0.58f, false);

    // ---- reactions and endings --------------------------------------------------------------------
    static final BattleClip HURT = new BattleClip("hurt", 0.72f, false);
    static final BattleClip VICTORY = new BattleClip("victory", 3.20f, false);
    static final BattleClip VICTORY_LOOP = new BattleClip("victory_loop", 2.80f, true);
    static final BattleClip DEFEAT = new BattleClip("defeat", 2.60f, false);
    static final BattleClip DEFEATED = new BattleClip("defeated", 3.20f, true);

    // ---- authored but not needed by the current rules (listed so the contract test covers them) ----
    static final BattleClip COMBAT_EXIT = new BattleClip("combat_exit", 0.76f, false);
    static final BattleClip MEDITATE_ENTER = new BattleClip("meditate_enter", 0.84f, false);
    static final BattleClip MEDITATE_LOOP = new BattleClip("meditate_loop", 3.20f, true);
    static final BattleClip MEDITATE_EXIT = new BattleClip("meditate_exit", 0.80f, false);
    static final BattleClip STUNNED_ENTER = new BattleClip("stunned_enter", 0.40f, false);
    static final BattleClip STUNNED = new BattleClip("stunned", 2.40f, true);
    static final BattleClip STUNNED_EXIT = new BattleClip("stunned_exit", 0.68f, false);
    static final BattleClip VICTORY_EXIT = new BattleClip("victory_exit", 0.90f, false);

    /** States that predate the combat set and are absent from clips.json. */
    static final List<BattleClip> LEGACY = List.of(IDLE);

    /** Combat clips the model plays. */
    static final List<BattleClip> USED = List.of(COMBAT_ENTER, COMBAT_IDLE, COMBAT_DASH, STRIKE, FLURRY,
            STUNNING_STRIKE, KICK, FOCUS_COMBO, SWIFT_STEP, MARTIAL_SURGE, MEDITATE, GUARD_ENTER, GUARD, GUARD_EXIT,
            PARRY, BLOCK, HURT, VICTORY, VICTORY_LOOP, DEFEAT, DEFEATED);

    /** Combat clips no current rule plays. */
    static final List<BattleClip> UNUSED = List.of(COMBAT_EXIT, MEDITATE_ENTER, MEDITATE_LOOP, MEDITATE_EXIT,
            STUNNED_ENTER, STUNNED, STUNNED_EXIT, VICTORY_EXIT);
}
