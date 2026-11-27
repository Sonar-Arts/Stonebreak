package com.stonebreak.rendering.player.items;

import com.stonebreak.items.ItemType;
import com.stonebreak.items.held.HeldGrip;
import com.stonebreak.items.held.HeldGrips;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.entities.HeldItemFrames;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Where a held staff's head is in the world — the point its fire bolts leave from: the staff's {@code head} held
 * point (#459, authored in its SBO {@code held} data: the curl at the top of the sprite), carried through the same
 * transforms the staff is drawn with, so a bolt leaves the staff the player sees in first person, and the staff other
 * players see in their hand in third person.
 */
public final class StaffHead {

    private static Vector3f meshPoint;
    private static boolean located;

    private StaffHead() {
    }

    /**
     * The staff head in world space for how {@code player} is viewed, or null when the
     * staff's geometry (or the renderer) isn't available.
     */
    public static org.joml.Vector3d world(Player player) {
        if (player.isThirdPerson()) {
            // The staff on the body's grip socket (#458); the eye-height fallback is RaycastEngine's.
            return HeldItemFrames.point(player, HeldItemFrames.HEAD, new org.joml.Vector3d());
        }
        Vector3f head = meshPoint();
        if (head == null) {
            return null;
        }
        com.stonebreak.rendering.Renderer renderer = com.stonebreak.core.Game.getRenderer();
        return renderer != null ? renderer.getPlayerArmRenderer().heldPointWorld(player, head) : null;
    }

    /**
     * The head in the staff's voxel-mesh space (what the first-person arm draws), from its {@code head} held point in
     * the canonical held frame. Computed once; null when the staff authors no head.
     */
    static Vector3f meshPoint() {
        if (located) {
            return meshPoint;
        }
        located = true;
        HeldGrip grip = HeldGrips.of(ItemType.STAFF);
        Matrix4fc head = null;
        if (grip != null) {
            for (var e : grip.points().entrySet()) {
                if (e.getKey().equalsIgnoreCase(HeldItemFrames.HEAD)) head = e.getValue();
            }
        }
        if (head == null) {
            return null;
        }
        meshPoint = new Matrix4f(HeldItemFrames.SPRITE).invertAffine()
                .transformPosition(head.getTranslation(new Vector3f()));
        return meshPoint;
    }
}
