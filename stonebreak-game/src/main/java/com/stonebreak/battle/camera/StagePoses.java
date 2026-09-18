package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;

/** The two actor poses a shot is evaluated against (so shots can be sampled without a live battle). */
public record StagePoses(ActorPose monk, ActorPose archon) {

    /** Both actors standing on their home rings. */
    public static final StagePoses HOME = new StagePoses(ActorPose.IDLE, ActorPose.IDLE);

    public StagePoses {
        monk = monk == null ? ActorPose.IDLE : monk;
        archon = archon == null ? ActorPose.IDLE : archon;
    }

    public static StagePoses of(BattleView view) {
        if (view == null) return HOME;
        return new StagePoses(view.monk() == null ? null : view.monk().pose(),
                view.archon() == null ? null : view.archon().pose());
    }

    /** Home poses with one actor part-way through its dash. */
    public static StagePoses dashing(CombatantId who, float dashProgress) {
        ActorPose dash = new ActorPose("sprinting", 0f, dashProgress, 0f, 1f);
        return who == CombatantId.MONK ? new StagePoses(dash, ActorPose.IDLE) : new StagePoses(ActorPose.IDLE, dash);
    }

    public ActorPose of(CombatantId id) {
        return id == CombatantId.MONK ? monk : archon;
    }
}
