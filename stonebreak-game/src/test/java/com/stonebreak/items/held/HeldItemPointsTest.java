package com.stonebreak.items.held;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.rendering.models.entities.HeldItemFrames;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #459: the points the game used to compute by hand are DATA now (SBO {@code held.points}, canonical held frame), and
 * equal what the code computed (frozen below): the torch's {@code flame} = its sprite tip, the rod's {@code tip} = its
 * sprite tip, the staff's {@code head} = the centroid of the curl (top 5 sprite rows) - and on a posed body a point's
 * world position is the held frame times the point, as before.
 */
class HeldItemPointsTest {

    private static final float EPS = 1e-5f;

    /** The pre-#459 sprite tip: the voxel farthest along +Y in the canonical frame. */
    static Vector3f legacyTip(ItemType type) {
        Vector3f best = null;
        Vector3f p = new Vector3f();
        for (VoxelData voxel : SpriteVoxelizer.voxelizeSpriteWithPalette(type, null).getVoxels()) {
            HeldItemFrames.SPRITE.transformPosition(voxel.getPosition(), p);
            if (best == null || p.y > best.y) best = new Vector3f(p);
        }
        return best;
    }

    /** The pre-#459 staff head: the centroid of the top 5 sprite rows, in the canonical frame. */
    static Vector3f legacyStaffHead() {
        Vector3f sum = new Vector3f();
        int count = 0;
        for (VoxelData voxel : SpriteVoxelizer.voxelizeSpriteWithPalette(ItemType.STAFF, null).getVoxels()) {
            if (voxel.getTextureY() < 5) {
                sum.add(voxel.getPosition());
                count++;
            }
        }
        return HeldItemFrames.SPRITE.transformPosition(sum.div(count), new Vector3f());
    }

    static Vector3f authored(Item item, String name) {
        HeldGrip grip = HeldGrips.of(item);
        assertNotNull(grip, item + " is held");
        for (Map.Entry<String, Matrix4fc> e : grip.points().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue().getTranslation(new Vector3f());
        }
        throw new AssertionError(item + " authors no '" + name + "' point (want flame " + exact(legacyTip(TorchBlock.item()))
                + ", rod tip " + exact(legacyTip(ItemType.FISHING_ROD)) + ", staff head " + exact(legacyStaffHead()) + ")");
    }

    static String exact(Vector3f v) {
        return v.x + "," + v.y + "," + v.z;
    }

    @Test
    void theShippedPointsAreTheOldComputedOnes() {
        assertTrue(authored(TorchBlock.item(), HeldItemFrames.FLAME).equals(legacyTip(TorchBlock.item()), EPS));
        assertTrue(authored(ItemType.FISHING_ROD, HeldItemFrames.TIP).equals(legacyTip(ItemType.FISHING_ROD), EPS));
        assertTrue(authored(ItemType.STAFF, HeldItemFrames.HEAD).equals(legacyStaffHead(), EPS));
    }

    @Test
    void aSpriteWithoutAnAuthoredTipGetsItsVoxelTip() {
        HeldGrip grip = HeldGrips.of(ItemType.SWORD);
        Map<String, Matrix4fc> points = HeldItemFrames.points(ItemType.SWORD, grip);
        assertTrue(points.get(HeldItemFrames.TIP).getTranslation(new Vector3f()).equals(legacyTip(ItemType.SWORD), EPS));
    }

    /** On a posed body a point = the held frame (posed socket x grip) x the point, as the old code computed it. */
    @Test
    void aPointRidesTheHeldFrame() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
        RemotePlayer rp = new RemotePlayer(null, new Vector3d(10, 64, -5), 4, "remote");
        try {
            rp.applyNetworkState(10, 64, -5, 35f, 0f);
            rp.setStateFlags((short) PlayerStateFlags.ON_GROUND);
            rp.setHeldItem(ItemType.FISHING_ROD);
            for (int i = 0; i < 30; i++) rp.updateClientVisuals(1 / 60f);
            Matrix4f frame = HeldItemFrames.of(rp, new Matrix4f());
            assertNotNull(frame);
            Vector3d want = HeldItemFrames.worldPoint(frame, legacyTip(ItemType.FISHING_ROD), new Vector3d());
            Vector3d got = HeldItemFrames.point(rp, HeldItemFrames.TIP, new Vector3d());
            assertNotNull(got);
            assertEquals(0.0, got.distance(want), 1e-4, got + " vs " + want);
        } finally {
            rp.onRemovedFromWorld();
        }
    }
}
