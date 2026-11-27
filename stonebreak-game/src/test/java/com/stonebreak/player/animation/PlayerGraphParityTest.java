package com.stonebreak.player.animation;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #457 parity: the player graph ({@code animgraphs/SB_Player.omanimgraph} on the shipped SB_Player) shows the very
 * pose the old Java selection did ({@code PlayerStateMapping} + the attack overlay builders, deleted) once a state has
 * settled - every joint's skin matrix against {@link SbePoseSolver} (the old path, still the mobs' path) given the
 * same clip, the same clip time (read from the graph) and the same head look. What changed on purpose: transitions now
 * blend (the old code cut), and the clips play from their own clocks.
 *
 * <p><b>Head look (#460, reviewed re-derivation)</b>: the look is the graph's {@code aim} on {@code role:head} toward a
 * look target, no longer a pre-rotation on top of the clip's head. Every shipped body clip moves the head chain a
 * little (idle breathes, the walk sways the torso ~1 degree), which the old pre-rotation rode and the aim now holds
 * still: so every joint OUTSIDE the head's subtree must still match the old pose exactly, and the head's forward axis
 * is the look direction. {@code com.stonebreak.player.PlayerHeadLookTest} pins the look itself against the old
 * pipeline over the rest pose (limits included) and on local and remote bodies.
 */
class PlayerGraphParityTest {

    private static final float DT = 1f / 60f;
    private static final float HEAD_YAW = 25f;
    private static final float HEAD_PITCH = -12f;
    /** Native graph (double compose) vs ABI 1 rig pose over the same float clip math. */
    private static final float EPS = 2e-5f;
    /** The aimed head's forward vs the look direction, degrees (target 64 units out, origin one frame old). */
    private static final double LOOK_EPS_DEG = 0.01;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    private final PlayerAnimationSet set = PlayerAnimationSet.player();

    @Test
    void settledStatesMatchTheOldSelection() {
        record Case(String name, short flags, float speed, boolean seated, float seconds, String node, String state) {}
        short ground = PlayerStateFlags.ON_GROUND;
        short air = PlayerStateFlags.AIRBORNE;
        List<Case> cases = List.of(
                new Case("idle", ground, 0f, false, 1.0f, "idle", "idle"),
                new Case("walk", ground, 4f, false, 0.7f, "walk", "walking"),
                new Case("sprint", (short) (ground | PlayerStateFlags.SPRINTING), 6f, false, 0.9f, "sprint", "sprinting"),
                new Case("jump", air, 0f, false, 0.4f, "jump", "jumping"),
                new Case("fall", air, 0f, false, 1.6f, "fall", "jumping"),
                new Case("sit", ground, 0f, true, 0.8f, "sit", "sitting"));
        List<String> report = new ArrayList<>();
        for (Case c : cases) {
            try (PlayerBodyAnimator body = new PlayerBodyAnimator(set)) {
                PlayerPoseInputs in = new PlayerPoseInputs().set(c.flags, c.speed, c.seated, HEAD_YAW, HEAD_PITCH);
                run(body, in, c.seconds);
                float t = time(body, c.node);
                float worst = compare(body.palette(), AnimState.single(c.state, t));
                report.add(c.name + " t=" + t + " worst=" + worst);
                assertTrue(worst <= EPS, c.name + ": graph differs from the old pose by " + worst);
                assertLooksAlongTheLook(body.palette(), c.name);
            }
        }
        System.out.println("[PlayerGraphParityTest] " + report);
    }

    @Test
    void thePunchOverIdleAndWalkingMatchesTheOldOverlay() {
        for (float speed : new float[]{0f, 4f}) {
            try (PlayerBodyAnimator body = new PlayerBodyAnimator(set)) {
                PlayerPoseInputs in = new PlayerPoseInputs().set((short) PlayerStateFlags.ON_GROUND, speed, false,
                        HEAD_YAW, HEAD_PITCH);
                run(body, in, 0.8f);
                in.attacking = true;  // a two-frame pulse: the whole punch still plays
                run(body, in, 2 * DT);
                in.attacking = false;
                run(body, in, 0.3f);
                String base = speed == 0f ? "idle" : "walk";
                float punch = time(body, "punch");
                assertTrue(punch > 0.2f && punch < 0.6f, "mid swing: " + punch);
                AnimState old = new AnimState(speed == 0f ? "idle" : "walking", time(body, base),
                        List.of(new AnimState.Overlay("attacking", punch, 1f)));
                float worst = compare(body.palette(), old);
                assertTrue(worst <= EPS, base + " + punch differs from the old overlay by " + worst);
                assertLooksAlongTheLook(body.palette(), base + " + punch");
            }
        }
    }

    @Test
    void theFirstPersonArmPunchMatchesItsOldOverlay() {
        PlayerAnimationSet arm = PlayerAnimationSet.firstPersonArm();
        try (PlayerBodyAnimator body = new PlayerBodyAnimator(arm)) {
            PlayerPoseInputs in = new PlayerPoseInputs().standing();
            run(body, in, 0.5f);
            assertEquals(0f, compare(arm, body.palette(), AnimState.single("idle", time(arm, body, "idle")), 0f, 0f,
                    true, false));
            in.attacking = true;
            run(body, in, DT);
            in.attacking = false;
            run(body, in, 0.3f);
            AnimState old = new AnimState("idle", time(arm, body, "idle"),
                    List.of(new AnimState.Overlay("attacking", time(arm, body, "punch"), 1f)));
            // Joints that draw: the arm's three bones bind no geometry (parallel to its parts, unweighted), and the
            // graph's part-addressed mask leaves them at the idle pose where the old name mask also moved them.
            float worst = compare(arm, body.palette(), old, 0f, 0f, true, false);
            assertTrue(worst <= EPS, "first-person punch differs from the old overlay by " + worst);
        }
    }

    @Test
    void theShippedCharacterNamesItsGraphAndEverySlotHasAClip() {
        assertEquals("animgraphs/SB_Player.omanimgraph",
                com.stonebreak.player.PlayerCharacter.get().definition().animGraph());
        for (PlayerAnimationSet s : List.of(set, PlayerAnimationSet.firstPersonArm())) {
            for (String slot : s.graph().slots()) {
                // The first-person view layer's slots (#458) wait for the rebuilt player's clips (#462); nothing
                // draws that view until then (its weight param stays 0) and the body pose never reads it.
                if (slot.startsWith("fp_")) continue;
                assertTrue(s.asset().clipBytesFor(slot) != null, s.asset().objectId() + " has no clip for " + slot);
            }
        }
    }

    private static void run(PlayerBodyAnimator body, PlayerPoseInputs in, float seconds) {
        int frames = Math.round(seconds / DT);
        for (int i = 0; i < frames; i++) body.update(DT, in, PlayerBodyAnimator.Events.NONE);
    }

    private float time(PlayerBodyAnimator body, String node) {
        return time(set, body, node);
    }

    private static float time(PlayerAnimationSet s, PlayerBodyAnimator body, String node) {
        float[] out = new float[3];
        // (Influence may read 2: the body pose also feeds the attack layer's "ready" state - one evaluation.)
        assertTrue(body.instance().nodeDebug(s.graph().node(node), out), node + " is not active");
        return out[1];
    }

    /** Every joint outside the head's subtree (the head is the look's; see the class doc). */
    private float compare(JointPalette graph, AnimState old) {
        return compare(set, graph, old, HEAD_YAW, HEAD_PITCH, false, true);
    }

    /** The head's forward axis (-Z) points along Ry(HEAD_YAW) Rx(HEAD_PITCH) applied to the model's forward (-Z). */
    private void assertLooksAlongTheLook(JointPalette graph, String what) {
        Vector3f forward = graph.worldMatrix(set.headJoint, new Matrix4f()).transformDirection(new Vector3f(0, 0, -1))
                .normalize();
        Vector3f look = new Vector3f(0, 0, -1).rotateX((float) Math.toRadians(HEAD_PITCH))
                .rotateY((float) Math.toRadians(HEAD_YAW));
        double angle = Math.toDegrees(forward.angle(look));
        assertTrue(angle <= LOOK_EPS_DEG, what + ": head looks " + angle + " degrees off the look direction");
    }

    /** Whether joint {@code j} is {@code root} or below it. */
    static boolean inSubtree(com.openmason.engine.rig.Rig rig, int j, int root) {
        for (int k = j; k >= 0; k = rig.joints().get(k).parent()) {
            if (k == root) return true;
        }
        return false;
    }

    /** Worst absolute difference over every joint's skin matrix (model space). */
    private static float compare(PlayerAnimationSet s, JointPalette graph, AnimState old, float headYaw,
                                 float headPitch, boolean drawnJointsOnly, boolean skipHead) {
        JointPalette expected = SbePoseSolver.pose(s.geometry(), s.asset(), old, new Matrix4f(), headYaw, headPitch,
                null, new JointPalette());
        assertEquals(expected.count(), graph.count(), "joint count");
        Matrix4f a = new Matrix4f();
        Matrix4f b = new Matrix4f();
        float worst = 0f;
        float[] fa = new float[16];
        float[] fb = new float[16];
        for (int j = 0; j < expected.count(); j++) {
            if (drawnJointsOnly && !s.rig().joints().get(j).hasGeometry()) continue;
            if (skipHead && inSubtree(s.rig(), j, s.headJoint)) continue;
            expected.skinMatrix(j, a).get(fa);
            graph.skinMatrix(j, b).get(fb);
            for (int k = 0; k < 16; k++) worst = Math.max(worst, Math.abs(fa[k] - fb[k]));
        }
        return worst;
    }
}
