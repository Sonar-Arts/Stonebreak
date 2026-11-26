package com.stonebreak.rendering.player.items;

import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.items.ItemType;
import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.rendering.models.entities.HeldItemFrames;
import org.joml.Matrix4f;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** Third person (#458): the head is the staff's curl on the body's grip socket, high above the hand, turning with the body. */
    @Test
    void thirdPersonHeadRidesTheGripAndTurnsWithTheBody() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
        Vector3d a = headRelativeToFeet(0f);
        Vector3d b = headRelativeToFeet(90f);
        assertEquals(a.y, b.y, 1e-3, "height does not depend on facing");
        assertEquals(Math.hypot(a.x, a.z), Math.hypot(b.x, b.z), 1e-3, "a turn moves the head around the feet");
        assertTrue(a.y > 1.2, "an upright staff holds its head above the shoulders' height band: " + a);
    }

    private static Vector3d headRelativeToFeet(float yaw) {
        RemotePlayer rp = new RemotePlayer(null, new Vector3d(10, 64, -5), 4, "remote");
        try {
            rp.applyNetworkState(10, 64, -5, yaw, 0f);
            rp.setStateFlags((short) PlayerStateFlags.ON_GROUND);
            rp.setHeldItem(ItemType.STAFF);
            for (int i = 0; i < 120; i++) rp.updateClientVisuals(1 / 60f);
            Matrix4f frame = HeldItemFrames.of(rp, new Matrix4f());
            assertNotNull(frame, "the posed body holds the staff");
            Vector3d world = HeldItemFrames.worldPoint(frame,
                    HeldItemFrames.spritePoint(StaffHead.meshPoint(), new Vector3f()), new Vector3d());
            return world.sub(rp.getPosition());
        } finally {
            rp.onRemovedFromWorld();
        }
    }
}
