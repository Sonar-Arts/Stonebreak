package com.stonebreak.battle.api;

/**
 * How the stage should pose one combatant this frame.
 *
 * @param sbeState     SBE animation-state name to play (e.g. "idle", "attacking", "attack_slash")
 * @param clipTime     seconds into that clip
 * @param dashProgress 0 = at home ring, 1 = at the strike anchor in front of the opponent
 * @param recoil       0..1 hit-reaction knock-back amount (stage offsets the actor away from its opponent)
 * @param presence     1 = fully present, 0 = not drawn. Both characters ship authored collapse
 *                     clips, so the model keeps this at 1; it remains for actors without one.
 */
public record ActorPose(String sbeState, float clipTime, float dashProgress, float recoil, float presence) {
    public static final ActorPose IDLE = new ActorPose("idle", 0f, 0f, 0f, 1f);
}
