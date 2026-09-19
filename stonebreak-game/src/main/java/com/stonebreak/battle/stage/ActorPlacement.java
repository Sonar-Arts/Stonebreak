package com.stonebreak.battle.stage;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import org.joml.Vector3f;

/**
 * Where and which way a battle actor's SBE model is drawn. Pure math, GL-free.
 *
 * <p><b>Yaw convention</b> (the same one the mob framework uses, see
 * {@code EntityType.getModelYawOffsetDegrees()} and {@code PlayerBodyOrientation.modelYawFromDirection}):
 * the SBE base transform is {@code T(position) · Ry(yaw) · S(scale)}, so a model authored facing +Z
 * faces world direction {@code d} at {@code yaw = atan2(d.x, d.z)}. A model authored facing −Z needs
 * a half turn on top of that.
 *
 * <p><b>Vertical convention</b>: the preview render path places the model-space origin at the given
 * position with no ground anchoring, so the lowest rest-pose vertex ({@code restMinY}) is lifted onto
 * the floor here, exactly as {@code SbeRenderSupport.groundAnchoredPosition} does for live mobs.
 */
public final class ActorPlacement {
    private ActorPlacement() {}

    /** {@code SB_Player.sbe} is authored facing −Z (same half turn the third-person body uses). */
    public static final float MONK_MODEL_YAW_OFFSET_DEGREES = 180f;
    /** {@code SB_Ice_Archon.sbe} is authored facing −Z (asset manifest), the cow/sheep convention. */
    public static final float ARCHON_MODEL_YAW_OFFSET_DEGREES = 180f;

    /**
     * Model-origin position and yaw to hand to the SBE preview renderer.
     *
     * @param position   world position of the model-space origin
     * @param yawDegrees rotation about +Y, in the SBE renderer's sense
     */
    public record Placement(Vector3f position, float yawDegrees) {
        public Placement {
            position = new Vector3f(position);
        }
    }

    public static float modelYawOffsetDegrees(CombatantId id) {
        return id == CombatantId.MONK ? MONK_MODEL_YAW_OFFSET_DEGREES : ARCHON_MODEL_YAW_OFFSET_DEGREES;
    }

    /**
     * Yaw that points a model along a world direction.
     *
     * @param facing                world direction to face (only X/Z are used; need not be normalized)
     * @param modelYawOffsetDegrees 0 for a model authored facing +Z, 180 for one authored facing −Z
     * @return yaw in degrees, normalized to [0, 360)
     */
    public static float yawDegrees(Vector3f facing, float modelYawOffsetDegrees) {
        float yaw = (float) Math.toDegrees(Math.atan2(facing.x, facing.z)) + modelYawOffsetDegrees;
        yaw %= 360f;
        return yaw < 0f ? yaw + 360f : yaw;
    }

    /**
     * World direction a model drawn at {@code yawDegrees} actually faces: the inverse of
     * {@link #yawDegrees}, by applying {@code Ry(yaw)} to the authored forward axis.
     */
    public static Vector3f facingOf(float yawDegrees, float modelYawOffsetDegrees) {
        double a = Math.toRadians(yawDegrees - modelYawOffsetDegrees);
        return new Vector3f((float) Math.sin(a), 0f, (float) Math.cos(a));
    }

    /** How far below the floor a fading actor has sunk: 0 when fully present, its whole height when gone. */
    public static float sinkDepth(float presence, float height) {
        float p = Math.max(0f, Math.min(1f, presence));
        return (1f - p) * Math.max(0f, height);
    }

    /**
     * Places one combatant for this frame.
     *
     * @param restMinY     lowest rest-pose model-space Y of the asset ({@code SbeModelGeometry.restMinY})
     * @param scaleY       vertical render scale
     * @param sinkOnFade   true to lower the actor into the floor as {@code pose.presence()} falls
     */
    public static Placement place(BattleStageLayout layout, CombatantId id, ActorPose pose,
                                  float restMinY, float scaleY, boolean sinkOnFade) {
        Vector3f feet = layout.feet(id, pose);
        float y = feet.y - restMinY * scaleY;
        if (sinkOnFade) {
            y -= sinkDepth(pose.presence(), layout.height(id));
        }
        return new Placement(new Vector3f(feet.x, y, feet.z),
                yawDegrees(layout.facing(id), modelYawOffsetDegrees(id)));
    }
}
