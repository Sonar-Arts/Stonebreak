package com.stonebreak.mobs.goblin;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.GaitSpeeds;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import com.stonebreak.mobs.sbe.MobAnimationSet;
import com.stonebreak.mobs.sbe.MobBodyAnimator;
import com.stonebreak.mobs.sbe.MobStateMapping;
import com.stonebreak.player.PlayerCharacter;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #460 acceptance: goblins walk and run on the player's locomotion graph with the player's clips retargeted onto them,
 * and their own clips (attacks) play as montages.
 *
 * <ul>
 *   <li>The goblin's SBE carries its character (characterized in Open Mason, humanoid, locked) naming the player's
 *   graph; the graph's walk and run slots bind SB_Player's {@code walking} / {@code sprinting}.</li>
 *   <li>Contact-foot drift per step (the planted foot's slide over the ground, OpenMason #159's bench measure; here the
 *   contact is the goblin foot's own lowest band, as the clips carry no lock curves) stays under the #159 threshold
 *   for the walk AND the run - at the clips' own speed, and at the play rates the goblin's real paces need (the slide
 *   over the ground does not depend on the rate: {@code gaitRate} keeps the feet planted at any pace).</li>
 *   <li>The goblin's wander pace is the graph's walk point and its chase pace the run point; the gait rate is the pace
 *   over the clip's speed on the goblin.</li>
 *   <li>An attack state plays its own clip as a full-body montage: mid swing the pose is the old named-clip pose.</li>
 * </ul>
 */
class GoblinLocomotionTest {

    /** OpenMason #159's bench threshold for contact-foot drift per step (model units). */
    private static final double DRIFT_THRESHOLD = 0.005;
    private static final float DT = 1f / 60f;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    private static MobAnimationSet set() {
        return new MobAnimationSet(EntityType.GOBLIN, GameSbeAssets.load("/sbe/Mobs/SB_Goblin.sbe"));
    }

    @Test
    void theGoblinCarriesItsCharacterAndThePlayersGraph() {
        EntityAsset goblin = GameSbeAssets.load("/sbe/Mobs/SB_Goblin.sbe");
        assertTrue(goblin.hasCharacter(), "SB_Goblin.sbe embeds its character (SBE 1.7)");
        MobAnimationSet set = set();
        assertEquals("animgraphs/SB_Player.omanimgraph", set.character().animGraph());
        assertEquals("humanoid", set.character().profile());
        assertEquals(PlayerCharacter.ASSET_ID, set.spec().clipSource());
        for (String role : List.of("hips", "spine", "head", "upper_leg_l", "lower_leg_l", "foot_l", "upper_leg_r",
                "lower_leg_r", "foot_r", "upper_arm_l", "hand_l", "upper_arm_r", "hand_r")) {
            assertNotNull(set.character().roles().get(role), "role " + role);
        }
        assertNotNull(set.slotClip("walking"), "walk slot bound");
        assertNotNull(set.slotClip("sprinting"), "run slot bound");
        assertEquals(EnumSet.of(MobBehaviorState.STAB, MobBehaviorState.SMASH, MobBehaviorState.DRAW_BOW,
                MobBehaviorState.RELEASE_BOW), EnumSet.copyOf(set.montageStates()), "its own one-shot clips");
        assertTrue(set.walkNatural() > 0f && set.runNatural() > set.walkNatural(),
                "walk " + set.walkNatural() + " / run " + set.runNatural() + " u/s");
    }

    @Test
    void walkAndRunContactDriftStaysUnderTheBenchThreshold() {
        MobAnimationSet set = set();
        StringBuilder report = new StringBuilder();
        for (String slot : List.of("walking", "sprinting")) {
            AnimClipDocument clip = set.slotClip(slot);
            float natural = slot.equals("walking") ? set.walkNatural() : set.runNatural();
            try (Retargeter rt = set.newRetargeter()) {
                // The player's own clip on the player, for reference.
                PlayerCharacter pc = PlayerCharacter.get();
                GaitSpeeds.Drift player = GaitSpeeds.drift(
                        pc.asset().geometryFor(EntityAsset.DEFAULT_VARIANT).rig().rig(), pc.definition(), clip, null, 1f,
                        natural / rt.scale(), 6f);
                for (float rate : new float[]{1f, 2.8f, 4f}) {
                    GaitSpeeds.Drift d = GaitSpeeds.drift(set.rig(), set.character(), clip, rt, rate, natural * rate, 6f);
                    report.append(String.format("%s x%.1f: %.5f over %d steps (player %.5f); ", slot, rate, d.perStep(),
                            d.steps(), player.perStep()));
                    assertTrue(d.steps() >= 4, slot + " x" + rate + ": steps " + d.steps());
                    assertTrue(d.perStep() < DRIFT_THRESHOLD, slot + " x" + rate + ": contact drift per step "
                            + d.perStep() + " " + d.each());
                }
            }
        }
        System.out.println("[GoblinLocomotionTest] walk " + set.walkNatural() + " u/s, run " + set.runNatural()
                + " u/s; " + report);
    }

    @Test
    void wanderAndChasePacesAreTheGraphsWalkAndRun() {
        MobAnimationSet set = set();
        float move = EntityType.GOBLIN.getMoveSpeed();
        try (MobBodyAnimator body = new MobBodyAnimator(set)) {
            float wander = move * Goblin.WANDER_SPEED, chase = move * Goblin.CHASE_SPEED;
            body.update(DT, wander, move, Goblin.MODEL_SCALE, MobBehaviorState.WANDERING, false);
            assertEquals(set.walkPoint(), body.lastSpeedParam(), 1e-4f);
            assertEquals(wander / (set.walkNatural() * Goblin.MODEL_SCALE), body.lastGaitRate(), 1e-4f);
            body.update(DT, chase, move, Goblin.MODEL_SCALE, MobBehaviorState.WANDERING, false);
            assertEquals(set.runPoint(), body.lastSpeedParam(), 1e-4f);
            assertEquals(chase / (set.runNatural() * Goblin.MODEL_SCALE), body.lastGaitRate(), 1e-4f);
            body.update(DT, 0f, move, Goblin.MODEL_SCALE, MobBehaviorState.IDLE, false);
            assertEquals(0f, body.lastSpeedParam(), 1e-6f);
            System.out.printf("[GoblinLocomotionTest] wander %.2f b/s -> rate %.2f, chase %.2f b/s -> rate %.2f%n",
                    wander, wander / (set.walkNatural() * Goblin.MODEL_SCALE), chase,
                    chase / (set.runNatural() * Goblin.MODEL_SCALE));
        }
    }

    @Test
    void anAttackPlaysItsOwnClipAsAMontage() {
        MobAnimationSet set = set();
        int slot = set.graph().montageSlot(MobAnimationSet.MONTAGE_SLOT);
        for (MobBehaviorState attack : List.of(MobBehaviorState.STAB, MobBehaviorState.SMASH, MobBehaviorState.DRAW_BOW,
                MobBehaviorState.RELEASE_BOW)) {
            try (MobBodyAnimator body = new MobBodyAnimator(set)) {
                for (int f = 0; f < 30; f++) {
                    body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, MobBehaviorState.IDLE, false);
                }
                // Mid swing: past the blend in, before the blend out.
                for (int f = 0; f < 12; f++) {
                    body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, attack, false);
                }
                AnimGraphInstance.MontageStatus status = body.instance().montageStatus(slot);
                assertTrue(status.weight() > 0.999f, attack + " montage weight " + status.weight());
                String clip = MobStateMapping.sbeState(EntityType.GOBLIN, attack);
                JointPalette old = SbePoseSolver.pose(set.asset().geometryFor(EntityAsset.DEFAULT_VARIANT), set.asset(),
                        AnimState.single(clip, status.time()), new Matrix4f(), 0f, 0f, null, new JointPalette());
                float worst = 0f;
                Matrix4f a = new Matrix4f(), b = new Matrix4f();
                float[] fa = new float[16], fb = new float[16];
                for (int j = 0; j < old.count(); j++) {
                    old.skinMatrix(j, a).get(fa);
                    body.palette().skinMatrix(j, b).get(fb);
                    for (int k = 0; k < 16; k++) worst = Math.max(worst, Math.abs(fa[k] - fb[k]));
                }
                assertTrue(worst <= 2e-5f, attack + " at " + status.time() + ": montage differs from the old clip by "
                        + worst);
                // The state ends: back to the locomotion graph.
                for (int f = 0; f < 30; f++) {
                    body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, MobBehaviorState.IDLE, false);
                }
                assertTrue(body.instance().montageStatus(slot).weight() < 1e-3f, attack + " montage stopped");
            }
        }
    }

    /** A state that outlives its clip: the montage has left the slot on its own, and leaving the state is fine. */
    @Test
    void anAttackStateOutlivingItsClipEndsQuietly() {
        MobAnimationSet set = set();
        int slot = set.graph().montageSlot(MobAnimationSet.MONTAGE_SLOT);
        try (MobBodyAnimator body = new MobBodyAnimator(set)) {
            for (int f = 0; f < 90; f++) {
                body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, MobBehaviorState.RELEASE_BOW, false);  // 1.5 s > 0.4 s
            }
            assertEquals(-1, body.instance().montageStatus(slot).montage(), "the montage ran out");
            body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, MobBehaviorState.IDLE, false);
            body.update(DT, 0f, 1.3f, Goblin.MODEL_SCALE, MobBehaviorState.STAB, false);
            assertTrue(body.instance().montageStatus(slot).montage() >= 0, "the next attack plays");
        }
    }
}
