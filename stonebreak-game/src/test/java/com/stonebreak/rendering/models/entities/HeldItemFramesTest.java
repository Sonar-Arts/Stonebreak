package com.stonebreak.rendering.models.entities;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.held.HeldGrip;
import com.stonebreak.items.held.HeldGrips;
import com.stonebreak.items.held.HeldKind;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.player.animation.PlayerAnimationSet;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #458: a held object hangs on the body's grip socket - its frame is the socket's posed frame times the object's grip,
 * the same for the local body and a remote one posed from the same inputs - and it follows the body's animation.
 */
class HeldItemFramesTest {

    private static final float DT = 1f / 60f;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    @Test
    void theFrameIsThePosedSocketTimesTheGrip() {
        RemotePlayer rp = posedRemote(ItemType.SWORD, (short) PlayerStateFlags.ON_GROUND, 30);
        try {
            Matrix4f frame = HeldItemFrames.of(rp, new Matrix4f());
            assertNotNull(frame);
            HeldGrip grip = HeldGrips.of(ItemType.SWORD);
            Matrix4f base = SbePoseSolver.baseMatrix(rp.getPosition(), rp.getBodyYaw(), rp.getScale());
            Matrix4f expected = PlayerAnimationSet.player().socket(grip.socket())
                    .frame(rp.bodyAnimator().palette(), base, new Matrix4f()).mul(grip.local());
            assertTrue(frame.equals(expected, 1e-6f), "frame = base K(socket joint) socket grip");
            // An empty hand holds nothing; nothing is held before the body is posed.
            rp.setHeldItem(null);
            assertNull(HeldItemFrames.of(rp, new Matrix4f()));
            RemotePlayer fresh = new RemotePlayer(null, new Vector3d(), 5, "fresh");
            fresh.setHeldItem(ItemType.SWORD);
            assertNull(HeldItemFrames.of(fresh, new Matrix4f()), "an unposed body holds nothing yet");
        } finally {
            rp.onRemovedFromWorld();
        }
    }

    @Test
    void localAndRemoteBodiesHoldItTheSame() {
        short flags = (short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.ATTACKING);
        RemotePlayer rp = posedRemote(ItemType.STAFF, flags, 12);
        try (PlayerBodyAnimator local = PlayerBodyAnimator.player()) {
            PlayerPoseInputs in = new PlayerPoseInputs().set(flags, rp.getHorizontalSpeed(), false, rp.getHeadYaw(),
                    rp.getHeadPitch()).held(HeldKind.of(ItemType.STAFF).graphValue(), 0f);
            for (int i = 0; i < 12; i++) local.update(DT, in, PlayerBodyAnimator.Events.NONE);
            PosedPlayerBody body = PosedPlayerBody.of(local, rp.getPosition(), rp.getBodyYaw(), rp.getScale());
            Matrix4f localFrame = HeldItemFrames.of(body, ItemType.STAFF, new Matrix4f());
            Matrix4f remoteFrame = HeldItemFrames.of(rp, new Matrix4f());
            assertNotNull(localFrame);
            assertTrue(localFrame.equals(remoteFrame, 1e-6f), "same inputs, same held frame");
        } finally {
            rp.onRemovedFromWorld();
        }
    }

    @Test
    void theItemFollowsTheSwing() {
        RemotePlayer idle = posedRemote(ItemType.SWORD, (short) PlayerStateFlags.ON_GROUND, 12);
        RemotePlayer swinging = posedRemote(ItemType.SWORD,
                (short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.ATTACKING), 12);
        try {
            Vector3f a = HeldItemFrames.of(idle, new Matrix4f()).getTranslation(new Vector3f());
            Vector3f b = HeldItemFrames.of(swinging, new Matrix4f()).getTranslation(new Vector3f());
            assertFalse(a.equals(b, 0.05f), "the punch moves the held sword: " + a + " vs " + b);
            assertTrue(b.y > a.y, "the swing raises the hand: " + a + " -> " + b);
        } finally {
            idle.onRemovedFromWorld();
            swinging.onRemovedFromWorld();
        }
    }

    private static RemotePlayer posedRemote(ItemType item, short flags, int frames) {
        RemotePlayer rp = new RemotePlayer(null, new Vector3d(3, 64, -2), 4, "remote");
        rp.applyNetworkState(3, 64, -2, 30f, -10f);
        rp.setStateFlags(flags);
        rp.setHeldItem(item);
        for (int i = 0; i < frames; i++) rp.updateClientVisuals(DT);
        return rp;
    }
}
