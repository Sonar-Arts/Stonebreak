package com.stonebreak.player;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rig.JointPalette;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #457: the player body on the animation graph. The local player ({@link PlayerBodyAnimation}) and a remote copy
 * ({@link RemotePlayer}) given the same inputs pose the same, joint for joint; footsteps land where the old gait
 * clock put them; a short attack pulse plays the whole punch.
 */
class PlayerBodyGraphTest {

    private static final float DT = 1f / 60f;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    /** One scripted frame: replicated flags and horizontal velocity (blocks/second along +X). */
    private record Frame(short flags, float velocity) {}

    /** Idle, walk, sprint, a punch while walking, a jump and its landing, a swim, sneaking. */
    private static List<Frame> script() {
        List<Frame> frames = new ArrayList<>();
        short ground = PlayerStateFlags.ON_GROUND;
        add(frames, 30, ground, 0f);
        add(frames, 60, ground, 4f);
        add(frames, 60, (short) (ground | PlayerStateFlags.SPRINTING), 6f);
        add(frames, 2, (short) (ground | PlayerStateFlags.ATTACKING), 4f);
        add(frames, 50, ground, 4f);
        add(frames, 80, (short) PlayerStateFlags.AIRBORNE, 3f);
        add(frames, 30, ground, 0f);
        add(frames, 60, (short) (PlayerStateFlags.AIRBORNE | PlayerStateFlags.PARTIALLY_IN_WATER), 2f);
        add(frames, 40, (short) (ground | PlayerStateFlags.STEALTHED), 1.5f);
        return frames;
    }

    private static void add(List<Frame> frames, int count, short flags, float velocity) {
        for (int i = 0; i < count; i++) frames.add(new Frame(flags, velocity));
    }

    /** #458: the action kind and the bow draw ride free flag bits and reach the graph's inputs. */
    @Test
    void theActionAndBowBitsReachThePoseInputs() {
        int others = PlayerStateFlags.SPRINTING | PlayerStateFlags.STEALTHED | PlayerStateFlags.SWIMMING
                | PlayerStateFlags.AIRBORNE | PlayerStateFlags.ON_GROUND | PlayerStateFlags.ATTACKING
                | PlayerStateFlags.PARTIALLY_IN_WATER | PlayerStateFlags.MINING | PlayerStateFlags.FLYING
                | PlayerStateFlags.DODGING | PlayerStateFlags.LIFESTEAL | PlayerStateFlags.DODGE_NOISE;
        assertEquals(0, others & (PlayerStateFlags.ACTION_MASK | PlayerStateFlags.DRAWING_BOW), "free bits only");
        assertEquals(0, PlayerStateFlags.ACTION_MASK & PlayerStateFlags.DRAWING_BOW);
        assertTrue((PlayerStateFlags.ACTION_MASK | PlayerStateFlags.DRAWING_BOW) <= 0xFFFF, "16-bit flags");
        for (int action = 0; action < 4; action++) {
            short flags = (short) (PlayerStateFlags.ATTACKING | PlayerStateFlags.actionBits(action));
            assertEquals(action, PlayerStateFlags.action(flags));
            assertEquals(action, new PlayerPoseInputs().set(flags, 0f, false, 0f, 0f).action);
        }
        // A remote bow bends as its draw is timed from the replicated flag (base draw speed).
        RemotePlayer rp = new RemotePlayer(null, new Vector3d(0, 64, 0), 7, "archer");
        try {
            rp.applyNetworkState(0, 64, 0, 0f, 0f);
            rp.setHeldItem(com.stonebreak.items.ItemType.BOW);
            rp.setStateFlags((short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.DRAWING_BOW));
            for (int i = 0; i < 90; i++) rp.updateClientVisuals(DT);  // 1.5 s of a 3 s draw
            assertEquals(com.stonebreak.player.combat.BowController.sboStateFor(0.5f), rp.heldItemState());
            rp.setStateFlags((short) PlayerStateFlags.ON_GROUND);
            rp.updateClientVisuals(DT);
            assertEquals(null, rp.heldItemState(), "released: the bow is slack");
        } finally {
            rp.onRemovedFromWorld();
        }
    }

    @Test
    void localAndRemoteBodiesGivenTheSameInputsPoseTheSame() {
        RemotePlayer remote = new RemotePlayer(null, new Vector3d(), 7, "remote");
        PlayerBodyAnimation local = new PlayerBodyAnimation();
        double x = 0;
        int frame = 0;
        Matrix4f a = new Matrix4f();
        Matrix4f b = new Matrix4f();
        for (Frame f : script()) {
            x += f.velocity() * DT;
            remote.applyNetworkState(x, 64, 0, 90f, -15f);
            remote.setStateFlags(f.flags());
            remote.updateClientVisuals(DT);
            // The local player's own source: its replicated flags and speed, the head look of its orientation.
            local.animate(DT, f.flags(), remote.getHorizontalSpeed(), false, remote.getHeadYaw(), remote.getHeadPitch(), 0, 0f,
                    PlayerBodyAnimator.Events.NONE);
            JointPalette r = remote.bodyAnimator().palette();
            JointPalette l = local.animator().palette();
            assertEquals(r.count(), l.count());
            for (int j = 0; j < r.count(); j++) {
                assertEquals(r.skinMatrix(j, a), l.skinMatrix(j, b), "frame " + frame + " joint " + j);
            }
            frame++;
        }
        remote.onRemovedFromWorld();
    }

    @Test
    void footstepsLandWhereTheOldGaitClockPutThem() {
        // Walk 1.5 s, sprint 1.5 s (phase carried across the change of pace), stop, walk again.
        List<float[]> legs = List.of(new float[]{4f, 90}, new float[]{6f, 90}, new float[]{0f, 20}, new float[]{4f, 60});
        List<Integer> graph = new ArrayList<>();
        List<Integer> old = new ArrayList<>();
        OldGaitClock clock = new OldGaitClock();
        int frame = 0;
        try (PlayerBodyAnimator body = PlayerBodyAnimator.player()) {
            PlayerPoseInputs in = new PlayerPoseInputs();
            int[] at = {0};
            PlayerBodyAnimator.Events events = () -> graph.add(at[0]);
            for (float[] leg : legs) {
                for (int i = 0; i < (int) leg[1]; i++, frame++) {
                    at[0] = frame;
                    body.update(DT, in.set((short) PlayerStateFlags.ON_GROUND, leg[0], false, 0f, 0f), events);
                    // Old: the walking clip (0.96 s) or the sprinting clip (0.68 s) while moving, else no gait.
                    float duration = leg[0] == 0f ? 0f : leg[0] >= 6f ? 0.68f : 0.96f;
                    if (clock.update(DT, duration)) old.add(frame);
                }
            }
        }
        assertFalse(old.isEmpty());
        assertEquals(old, graph, "foot contacts (frame indices)");
    }

    @Test
    void noFootstepsInTheAirOrInWater() {
        try (PlayerBodyAnimator body = PlayerBodyAnimator.player()) {
            int[] steps = {0};
            PlayerPoseInputs in = new PlayerPoseInputs();
            for (int i = 0; i < 120; i++) {
                body.update(DT, in.set((short) PlayerStateFlags.AIRBORNE, 4f, false, 0f, 0f), () -> steps[0]++);
            }
            for (int i = 0; i < 120; i++) {
                body.update(DT, in.set((short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.PARTIALLY_IN_WATER),
                        4f, false, 0f, 0f), () -> steps[0]++);
            }
            assertEquals(0, steps[0]);
        }
    }

    @Test
    void aShortAttackPulsePlaysTheWholePunch() {
        try (PlayerBodyAnimator body = PlayerBodyAnimator.player()) {
            PlayerAnimationSet set = body.set();
            int upper = set.graph().node("upper");
            int punch = set.graph().states(upper).indexOf("punch");
            PlayerPoseInputs in = new PlayerPoseInputs().standing();
            in.attacking = true;
            body.update(DT, in, PlayerBodyAnimator.Events.NONE);
            in.attacking = false;
            for (int i = 0; i < 30; i++) body.update(DT, in, PlayerBodyAnimator.Events.NONE);
            assertEquals(punch, body.instance().state(upper), "still punching half a second after the pulse");
            for (int i = 0; i < 30; i++) body.update(DT, in, PlayerBodyAnimator.Events.NONE);
            assertTrue(body.instance().state(upper) != punch, "back to ready after the punch");
        }
    }

    @Test
    void aSkippedFrameKeepsTheAttackPulse() {
        try (PlayerBodyAnimator body = PlayerBodyAnimator.player()) {
            int upper = body.set().graph().node("upper");
            int punch = body.set().graph().states(upper).indexOf("punch");
            PlayerPoseInputs in = new PlayerPoseInputs().standing();
            body.update(DT, in, 3, PlayerBodyAnimator.Events.NONE);  // first update always evaluates
            in.attacking = true;
            assertFalse(body.update(DT, in, 3, PlayerBodyAnimator.Events.NONE), "skipped (interval 3)");
            in.attacking = false;
            assertFalse(body.update(DT, in, 3, PlayerBodyAnimator.Events.NONE));
            assertTrue(body.update(DT, in, 3, PlayerBodyAnimator.Events.NONE));
            assertEquals(punch, body.instance().state(upper), "the pulse on a skipped frame still started the punch");
        }
    }

    @Test
    void updateIntervalGrowsWithDistance() {
        assertEquals(1, PlayerBodyAnimator.intervalFor(10 * 10));
        assertEquals(2, PlayerBodyAnimator.intervalFor(50 * 50));
        assertEquals(3, PlayerBodyAnimator.intervalFor(100 * 100));
        assertEquals(4, PlayerBodyAnimator.intervalFor(500 * 500));
    }

    @Test
    void bodyOrientationStillTurnsWithMovementAndSeats() {
        PlayerBodyAnimation body = new PlayerBodyAnimation();
        body.orient(0.5f, new Vector3f(3, 0, 0), new Vector3f(0, 0, -1), false, 0f);
        float moving = body.getBodyOrientation().getBodyYaw();
        body.orient(0.5f, new Vector3f(3, 0, 0), new Vector3f(0, 0, -1), true, 42f);
        assertEquals(42f, body.getBodyOrientation().getBodyYaw(), 1e-4f);
        assertTrue(moving != 42f);
    }

    /** Frozen copy of the deleted {@code mobs/sbe/PlayerGaitClock} (git b30c71fe): the contact rule ported from. */
    private static final class OldGaitClock {
        private double phase;
        private float duration;
        private boolean active;

        boolean update(float dt, float cycleDuration) {
            if (!Float.isFinite(cycleDuration) || cycleDuration <= 0f) {
                phase = 0;
                duration = 0;
                active = false;
                return false;
            }
            duration = cycleDuration;
            if (!Float.isFinite(dt) || dt <= 0f) return false;
            double next = phase + (double) dt / duration;
            boolean contact = !active || Math.floor(next * 2 + 1e-7) > Math.floor(phase * 2 + 1e-7);
            phase = next - Math.floor(next);
            active = true;
            return contact;
        }
    }
}
