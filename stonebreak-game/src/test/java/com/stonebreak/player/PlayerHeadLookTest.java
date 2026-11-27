package com.stonebreak.player;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.player.animation.PlayerAnimationSet;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #460 parity: the head look moved from a clamped pre-rotation ({@code PlayerBodyOrientation} clamped yaw to +-70 and
 * pitch to +-45; the graph instance pre-rotated the head joint by {@code Ry(yaw) Rx(pitch)} in its parent's frame) to
 * the player graph's {@code aim} on {@code role:head} toward the {@code lookAt} target, limited by the graph (yaw
 * +-70, pitch +-45, roll 0, {@code order: yxz}: the same yaw x pitch rectangle).
 *
 * <ul>
 *   <li><b>Old vs new</b>: over the rest pose (no clip moves the head chain) the aimed head's whole rotation equals the
 *   old pipeline's (old clamps, then the old pre-rotation) for looks inside and past the limits.</li>
 *   <li><b>Local and remote bodies</b> (the real input paths, walking one way and looking another, clips bound): the
 *   head looks exactly along the look inside the limits; past a limit, the look seen from the torso (the head's
 *   parent) stops at 70 / 45 on that axis and keeps the other. What differs from the old path here on purpose: the old pre-rotation rode the
 *   clip's own head-chain motion (the walk sways the torso ~1 degree); the aim holds the look direction.</li>
 * </ul>
 */
class PlayerHeadLookTest {

    private static final float DT = 1f / 60f;
    private static final float WALK = 4f;
    /** Head rotation, largest matrix-entry difference (aim: 4 passes; target 64 units out). */
    private static final float ROT_EPS = 2e-4f;
    /** Head forward vs the expected look, degrees (the target's origin is the head one frame old). */
    private static final double LOOK_EPS_DEG = 0.02;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    /** A look offset from the body facing (degrees) and where the old clamps stopped it. */
    private record Look(float yaw, float pitch, float limitedYaw, float limitedPitch) {
        static Look of(float yaw, float pitch) {
            return new Look(yaw, pitch, Math.clamp(yaw, -70f, 70f), Math.clamp(pitch, -45f, 45f));
        }

        boolean inside() {
            return yaw == limitedYaw && pitch == limitedPitch;
        }

        @Override
        public String toString() {
            return String.format("(%.0f,%.0f)->(%.0f,%.0f)", yaw, pitch, limitedYaw, limitedPitch);
        }
    }

    private static final List<Look> LOOKS = List.of(
            // inside the limits: the look as asked
            Look.of(25f, -12f), Look.of(60f, 40f), Look.of(-69f, -44f), Look.of(0f, 30f), Look.of(45f, 0f),
            // past a limit: stops there, on that axis only
            Look.of(100f, 0f), Look.of(-120f, 0f), Look.of(0f, 80f), Look.of(0f, -60f), Look.of(100f, 30f),
            Look.of(-150f, -70f));

    @Test
    void overTheRestPoseTheLookIsTheOldPreRotation() {
        PlayerAnimationSet set = PlayerAnimationSet.player();
        int head = set.rig().headJoint();
        List<String> report = new ArrayList<>();
        for (Look look : LOOKS) {
            try (PlayerBodyAnimator body = new PlayerBodyAnimator(set, false)) {
                PlayerPoseInputs in = new PlayerPoseInputs().set((short) PlayerStateFlags.ON_GROUND, 0f, false,
                        look.yaw(), look.pitch());
                for (int f = 0; f < 3; f++) body.update(DT, in, PlayerBodyAnimator.Events.NONE);
                JointPalette old = SbePoseSolver.pose(set.geometry(), set.asset(), AnimState.single("(rest)", 0f),
                        new Matrix4f(), look.limitedYaw(), look.limitedPitch(), null, new JointPalette());
                JointPalette rest = SbePoseSolver.pose(set.geometry(), set.asset(), AnimState.single("(rest)", 0f),
                        new Matrix4f(), 0f, 0f, null, new JointPalette());
                // The chain above the head really is at rest in both.
                int torso = set.rig().joints().get(head).parent();
                assertTrue(rotationDiff(body.palette(), rest, torso) < 1e-6f, "torso at rest");
                float worst = rotationDiff(body.palette(), old, head);
                report.add(look + " " + String.format("%.2g", worst));
                assertTrue(worst <= ROT_EPS, "rest pose, look " + look + ": head rotation differs by " + worst);
            }
        }
        System.out.println("[PlayerHeadLookTest] rest pose, old vs new head rotation " + report);
    }

    @Test
    void theLocalBodyLooksAlongTheLookWithinTheLimits() {
        PlayerAnimationSet set = PlayerAnimationSet.player();
        List<String> report = new ArrayList<>();
        for (Look look : LOOKS) {
            PlayerBodyAnimation body = new PlayerBodyAnimation();
            // Walk toward -Z (body model yaw 0) with the camera turned look.yaw off it - as Player does.
            Vector3f velocity = new Vector3f(0f, 0f, -WALK);
            double cam = Math.toRadians(look.yaw() - 180f);
            Vector3f front = new Vector3f((float) Math.sin(cam), 0f, (float) Math.cos(cam));
            for (int f = 0; f < 60; f++) {
                body.orient(DT, velocity, front, false, 0f);
                PlayerBodyOrientation o = body.getBodyOrientation();
                float headYaw = o.getHeadYaw(PlayerBodyOrientation.modelYawFromDirection(front.x, front.z));
                body.animate(DT, (short) PlayerStateFlags.ON_GROUND, WALK, false, headYaw,
                        o.getHeadPitch(look.pitch()), 0, 0f, PlayerBodyAnimator.Events.NONE);
            }
            assertEquals(look.yaw(), body.getBodyOrientation()
                    .getHeadYaw(PlayerBodyOrientation.modelYawFromDirection(front.x, front.z)), 1e-3f,
                    "the local look offset reaches the graph unclamped");
            report.add(checkLook("local", set, body.animator().palette(), look));
            body.animator().close();
        }
        System.out.println("[PlayerHeadLookTest] local " + report);
    }

    @Test
    void theRemoteBodyLooksAlongTheLookWithinTheLimits() {
        PlayerAnimationSet set = PlayerAnimationSet.player();
        List<String> report = new ArrayList<>();
        for (Look look : LOOKS) {
            RemotePlayer remote = new RemotePlayer(null, new Vector3d(0, 64, 0), 7, "remote");
            // Walking along +X (body model yaw 270); a replicated camera yaw of -look.yaw is look.yaw off the body.
            double x = 0;
            for (int f = 0; f < 60; f++) {
                x += WALK * DT;
                remote.applyNetworkState(x, 64, 0, -look.yaw(), look.pitch());
                remote.setStateFlags((short) PlayerStateFlags.ON_GROUND);
                remote.updateClientVisuals(DT);
            }
            assertEquals(look.yaw(), remote.getHeadYaw(), 1e-3f, "the replicated look offset reaches the graph");
            assertEquals(look.pitch(), remote.getHeadPitch(), 1e-3f, "the replicated pitch reaches the graph");
            report.add(checkLook("remote", set, remote.bodyAnimator().palette(), look));
            remote.onRemovedFromWorld();
        }
        System.out.println("[PlayerHeadLookTest] remote " + report);
    }

    /**
     * The look is limited relative to the head's parent (the torso, which the clip moves): the look direction seen
     * from the torso, its yaw clamped to +-70 and pitch to +-45, turned back out, is where the head's forward axis
     * points. Inside the limits that is exactly the look ({@code Ry(yaw) Rx(pitch)} on the model's -Z).
     */
    private static String checkLook(String who, PlayerAnimationSet set, JointPalette palette, Look look) {
        int head = set.rig().headJoint();
        Vector3f forward = palette.worldMatrix(head, new Matrix4f()).get3x3(new Matrix3f())
                .transform(new Vector3f(0, 0, -1)).normalize();
        Vector3f lookDir = new Vector3f(0, 0, -1).rotateX((float) Math.toRadians(look.pitch()))
                .rotateY((float) Math.toRadians(look.yaw()));
        Quaternionf torso = palette.worldMatrix(set.rig().joints().get(head).parent(), new Matrix4f())
                .getNormalizedRotation(new Quaternionf());
        Vector3f local = new Quaternionf(torso).conjugate().transform(new Vector3f(lookDir));
        double pitch = Math.toDegrees(Math.asin(Math.clamp(local.y, -1f, 1f)));
        double yaw = Math.toDegrees(Math.atan2(-local.x, -local.z));
        Vector3f want = torso.transform(new Vector3f(0, 0, -1)
                .rotateX((float) Math.toRadians(Math.clamp(pitch, -45.0, 45.0)))
                .rotateY((float) Math.toRadians(Math.clamp(yaw, -70.0, 70.0))));
        double degrees = Math.toDegrees(forward.angle(want.normalize()));
        double offLook = Math.toDegrees(forward.angle(lookDir));
        String line = look + String.format(" %.3g deg (torso-frame look %.1f,%.1f)", degrees, yaw, pitch);
        assertTrue(degrees <= LOOK_EPS_DEG, who + " look " + line);
        if (look.inside()) assertTrue(offLook <= LOOK_EPS_DEG, who + " look " + look + " off the look by " + offLook);
        return line;
    }

    private static float rotationDiff(JointPalette a, JointPalette b, int joint) {
        Matrix3f ma = a.worldMatrix(joint, new Matrix4f()).get3x3(new Matrix3f());
        Matrix3f mb = b.worldMatrix(joint, new Matrix4f()).get3x3(new Matrix3f());
        float worst = 0f;
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) worst = Math.max(worst, Math.abs(ma.get(c, r) - mb.get(c, r)));
        }
        return worst;
    }
}
