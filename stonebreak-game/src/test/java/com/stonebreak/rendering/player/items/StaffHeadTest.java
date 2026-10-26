package com.stonebreak.rendering.player.items;

import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.models.entities.HeldItemPose;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fire bolts leave the staff's head — the curl at the top-right of its sprite — not its foot. */
class StaffHeadTest {

    @Test
    void headIsTheCurlAtTheTopRightOfTheStaff() {
        Vector3f head = StaffHead.meshPoint();
        assertNotNull(head, "staff sprite must voxelize");

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (VoxelData voxel : SpriteVoxelizer.voxelizeSpriteWithPalette(ItemType.STAFF, null).getVoxels()) {
            Vector3f p = voxel.getPosition();
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        assertTrue(head.x > minX + 0.6f * (maxX - minX), "head is on the staff's right: " + head);
        assertTrue(head.y > minY + 0.75f * (maxY - minY), "head is at the staff's top: " + head);
    }

    @Test
    void thirdPersonHeadTurnsWithTheBody() {
        Vector3f head = StaffHead.meshPoint();
        Vector3f feet = new Vector3f(10f, 64f, -5f);
        Vector3f facing0 = HeldItemPose.toolPointWorld(feet, 0f, head, new Vector3f()).sub(feet);
        Vector3f facing90 = HeldItemPose.toolPointWorld(feet, 90f, head, new Vector3f()).sub(feet);

        assertEquals(facing0.y, facing90.y, 1e-4f, "height does not depend on facing");
        // A quarter turn about +Y maps (x, z) to (z, -x).
        assertEquals(facing0.z, facing90.x, 1e-4f);
        assertEquals(-facing0.x, facing90.z, 1e-4f);
        assertTrue(facing0.y > 1f, "the head is held above the waist: " + facing0);
    }
}
