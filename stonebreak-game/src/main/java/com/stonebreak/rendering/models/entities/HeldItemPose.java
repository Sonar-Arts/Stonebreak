package com.stonebreak.rendering.models.entities;

import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where a player-shaped figure seen from outside holds its item: a fixed offset from the
 * feet, turned with the body. {@link DropRenderer#renderHeldItems} draws remote players'
 * items here; {@link #toolPointWorld} locates a point of such a held tool in the world
 * (the local player's staff head in third person, which other players see it cast from).
 */
public final class HeldItemPose {
    /** Hand offset, in player local space (right, up, forward). */
    static final float HAND_RIGHT   = 0.35f;
    static final float HAND_UP      = 1.2f;
    static final float HAND_FORWARD = 0.15f;
    /** Voxelized tools reuse the world-drop pipeline, which renders at scale 0.25. */
    static final float TOOL_SCALE   = 0.25f;
    /** The drop pipeline's lift for voxelized sprites, against the in-hand base translation. */
    static final float VOXEL_DROP_LIFT = 1.3f;

    private HeldItemPose() {
    }

    /** World position of the hand of a figure standing at {@code feet}, facing {@code bodyYawDeg}. */
    static Vector3f hand(Vector3f feet, float bodyYawDeg, Vector3f dest) {
        float yawRad = (float) Math.toRadians(bodyYawDeg);
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        // World vectors: forward = (-sin, 0, -cos), right = (cos, 0, -sin).
        return dest.set(feet.x + HAND_RIGHT * cos + HAND_FORWARD * (-sin),
                feet.y + HAND_UP,
                feet.z + HAND_RIGHT * (-sin) + HAND_FORWARD * (-cos));
    }

    /**
     * A point of a held voxelized tool, given in the tool's voxel-mesh space, in world space —
     * the transform chain {@link DropRenderer#renderHeldItems} draws the tool with.
     */
    public static Vector3f toolPointWorld(Vector3f feet, float bodyYawDeg, Vector3f meshPoint, Vector3f dest) {
        Vector3f hand = hand(feet, bodyYawDeg, new Vector3f());
        return new Matrix4f()
                .translate(hand)
                .rotateY((float) Math.toRadians(bodyYawDeg))
                .scale(TOOL_SCALE)
                .translate(0f, VOXEL_DROP_LIFT, 0f)
                .mul(VoxelizedSpriteRenderer.getBaseSpriteTransform())
                .transformPosition(meshPoint, dest);
    }
}
