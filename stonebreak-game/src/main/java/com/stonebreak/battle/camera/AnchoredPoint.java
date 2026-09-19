package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import org.joml.Vector3f;

/**
 * A point expressed in an anchor's own frame: {@code forward} along the anchor's facing (toward the
 * opponent for an actor, monk→Archon for the midpoint anchors), {@code up} = +Y from the anchor's
 * feet, {@code right} = forward × up. With the monk at +Z facing −Z, the monk's right is +X and the
 * Archon's right is −X.
 *
 * @param followPose measure from the actor's live (dashing / recoiling) feet instead of its home ring
 */
public record AnchoredPoint(CameraAnchor anchor, float right, float up, float forward, boolean followPose) {

    public static AnchoredPoint at(CameraAnchor anchor, float right, float up, float forward) {
        return new AnchoredPoint(anchor, right, up, forward, false);
    }

    public static AnchoredPoint following(CameraAnchor anchor, float right, float up, float forward) {
        return new AnchoredPoint(anchor, right, up, forward, true);
    }

    /** World-space position for the given stage and poses. */
    public Vector3f resolve(BattleStageLayout layout, StagePoses poses) {
        Vector3f origin;
        Vector3f fwd;
        switch (anchor) {
            case MONK, ARCHON -> {
                CombatantId id = anchor == CameraAnchor.MONK ? CombatantId.MONK : CombatantId.ARCHON;
                origin = followPose ? layout.feet(id, poses.of(id)) : layout.home(id);
                fwd = layout.facing(id);
            }
            case MIDPOINT -> {
                origin = followPose
                        ? layout.feet(CombatantId.MONK, poses.monk())
                                .add(layout.feet(CombatantId.ARCHON, poses.archon())).mul(0.5f)
                        : layout.midpoint();
                fwd = layout.facing(CombatantId.MONK);
            }
            default -> {
                origin = layout.midpoint();
                fwd = layout.facing(CombatantId.MONK);
            }
        }
        // right = forward × up with up = +Y
        float rx = -fwd.z;
        float rz = fwd.x;
        return origin.add(rx * right + fwd.x * forward, up, rz * right + fwd.z * forward);
    }
}
