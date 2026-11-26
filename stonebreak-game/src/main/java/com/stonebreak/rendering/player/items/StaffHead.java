package com.stonebreak.rendering.player.items;

import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.entities.HeldItemFrames;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import org.joml.Vector3f;

/**
 * Where a held staff's head is in the world — the point its fire bolts leave from. The head
 * is the curl at the top of the staff sprite; it is carried through the same transforms the
 * staff is drawn with, so a bolt leaves the staff the player sees in first person, and the
 * staff other players see in their hand in third person.
 */
public final class StaffHead {
    /** Sprite rows, from the top, that hold the curl. */
    private static final int HEAD_ROWS = 5;

    private static Vector3f meshPoint;
    private static boolean located;

    private StaffHead() {
    }

    /**
     * The staff head in world space for how {@code player} is viewed, or null when the
     * staff's geometry (or the renderer) isn't available.
     */
    public static org.joml.Vector3d world(Player player) {
        Vector3f head = meshPoint();
        if (head == null) {
            return null;
        }
        if (player.isThirdPerson()) {
            // The staff on the body's grip socket (#458); the eye-height fallback is RaycastEngine's.
            org.joml.Matrix4f frame = HeldItemFrames.of(player, new org.joml.Matrix4f());
            return frame == null ? null : HeldItemFrames.worldPoint(frame,
                    HeldItemFrames.spritePoint(head, new Vector3f()), new org.joml.Vector3d());
        }
        com.stonebreak.rendering.Renderer renderer = com.stonebreak.core.Game.getRenderer();
        return renderer != null ? renderer.getPlayerArmRenderer().heldPointWorld(player, head) : null;
    }

    /**
     * The head in the staff's voxel-mesh space: the centroid of the curl's voxels. Computed
     * once; null when the staff sprite can't be voxelized.
     */
    static Vector3f meshPoint() {
        if (located) {
            return meshPoint;
        }
        located = true;
        SpriteVoxelizer.VoxelizationResult result = SpriteVoxelizer.voxelizeSpriteWithPalette(ItemType.STAFF, null);
        if (result == null || !result.isValid()) {
            return null;
        }
        Vector3f sum = new Vector3f();
        int count = 0;
        for (VoxelData voxel : result.getVoxels()) {
            if (voxel.getTextureY() < HEAD_ROWS) {
                sum.add(voxel.getPosition());
                count++;
            }
        }
        meshPoint = count > 0 ? sum.div(count) : null;
        return meshPoint;
    }
}
