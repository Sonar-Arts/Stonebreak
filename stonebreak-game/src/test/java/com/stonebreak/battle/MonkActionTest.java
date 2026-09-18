package com.stonebreak.battle;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stonebreak.battle.BattleDriver.DASH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strike, Flurry, Stunning Strike, Swift Step and Meditate, on the authored clip timelines. */
class MonkActionTest {

    private static final float SPEED = BattleConfig.Flurry.DEFAULTS.playbackSpeed();
    private static final float LEAD = 0.42f;
    private static final float KICK_STANDOFF = BattleConfig.Melee.DEFAULTS.kickStandoff();

    private static BattleDriver monkOnly() {
        return monkOnly(BattleDriver.exact());
    }

    private static BattleDriver monkOnly(BattleConfig config) {
        return new BattleDriver(BattleDriver.passiveArchon(config), 1).start();
    }

    /** Action time of the flurry clip's {@code hit}-th contact at playback speed {@code speed}. */
    private static float flurryContact(int hit, float speed) {
        return DASH + MonkClips.FLURRY.cue(hit) / speed;
    }

    private static float flurryClipEnd() {
        return DASH + MonkClips.FLURRY.duration() / SPEED;
    }

    /** Flurry submitted, nothing stepped yet (action clock 0). */
    private static BattleDriver flurry(boolean surged) {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        if (surged) {
            assertTrue(d.sim.submit(BattleCommand.MARTIAL_SURGE));
            d.finishAction();
        }
        assertTrue(d.sim.submit(BattleCommand.FLURRY));
        d.forget();
        return d;
    }

    private static void pressAt(BattleDriver d, float actionTime) {
        d.runActionTo(actionTime);
        d.sim.pressConfirm();
    }

    private static List<TimedGrade> grades(BattleDriver d) {
        return d.all(BattleEvent.PromptResolved.class).stream().map(BattleEvent.PromptResolved::grade).toList();
    }

    private static List<Float> damage(BattleDriver d) {
        return d.all(BattleEvent.DamageDealt.class).stream().map(BattleEvent.DamageDealt::amount).toList();
    }

    // ---- Strike ---------------------------------------------------------------------------------

    @Test
    void strikeFollowsItsPoseTimelineAndLandsOnTheContact() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.forget();
        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());

        d.step(0.2f);
        ActorPose dashing = d.sim.monk().pose();
        assertEquals("combat_dash", dashing.sbeState());
        assertEquals(0.2f, dashing.clipTime(), 1.0e-5f);
        assertTrue(dashing.dashProgress() > 0f && dashing.dashProgress() < 1f);

        d.step(0.3f); // t = 0.5: the strike clip started as the dash ended
        ActorPose punching = d.sim.monk().pose();
        assertEquals("strike", punching.sbeState());
        assertEquals(0.05f, punching.clipTime(), 1.0e-5f);
        assertEquals(1f, punching.dashProgress(), "the gait is only played while the monk is moving");
        assertEquals(0f, punching.recoil());

        d.assertImpactAt(DASH + 0.38f);
        assertEquals(0.38f, d.sim.monk().pose().clipTime(), 2f * BattleDriver.EPS, "fist and event coincide");
        assertEquals(List.of(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 0, 1)),
                d.all(BattleEvent.Impact.class));
        assertEquals(List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 42f, DamageFlavor.NORMAL)),
                d.all(BattleEvent.DamageDealt.class));
        assertEquals(List.of(new BattleEvent.FocusChanged(6f, 6f)), d.all(BattleEvent.FocusChanged.class));
        assertEquals(858f, d.sim.archon().hp());

        d.runActionTo(1.3f);
        assertEquals("strike", d.sim.monk().pose().sbeState(), "the clip plays out before the monk turns back");
        assertEquals(1f, d.sim.monk().pose().dashProgress());

        d.runActionTo(1.6f); // dashing home since 0.45 + 0.96
        ActorPose returning = d.sim.monk().pose();
        assertEquals("combat_dash", returning.sbeState());
        assertEquals(1.6f - (DASH + 0.96f), returning.clipTime(), 1.0e-4f);
        assertTrue(returning.dashProgress() > 0f && returning.dashProgress() < 1f);
        assertEquals(BattlePhase.ACTION, d.sim.phase());

        d.step(0.4f); // over at 1.86
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertNull(d.sim.currentAction());
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        assertEquals(0f, d.sim.monk().pose().dashProgress());
        assertEquals(new BattleEvent.ActionFinished(CombatantId.MONK), d.last(BattleEvent.ActionFinished.class));
        BattleEvent.ActionStarted started = d.all(BattleEvent.ActionStarted.class).get(0);
        assertEquals(CombatantId.MONK, started.actor());
        assertEquals("Strike", started.displayName());
        assertEquals(BattleCommand.STRIKE, started.command());
        assertNull(started.enemyAction());
        assertEquals(DASH + 0.96f + DASH, started.duration(), 1.0e-5f);
        assertEquals(42f, d.sim.stats().damageDealt());
    }

    @Test
    void aSurgedStrikeAddsAKickThatLandsOnItsOwnContactFromFartherBack() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.sim.submit(BattleCommand.MARTIAL_SURGE);
        d.finishAction();
        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        d.forget();

        d.assertImpactAt(DASH + 0.38f);
        float kickStart = DASH + 0.96f;
        d.runActionTo(kickStart + 0.06f);
        ActorPose steppingOut = d.sim.monk().pose();
        assertEquals("kick", steppingOut.sbeState());
        assertTrue(steppingOut.recoil() > 0f && steppingOut.recoil() < KICK_STANDOFF, "eases out to kicking distance");

        d.assertImpactAt(kickStart + 0.60f);
        ActorPose kicking = d.sim.monk().pose();
        assertEquals("kick", kicking.sbeState());
        assertEquals(0.60f, kicking.clipTime(), 2f * BattleDriver.EPS);
        assertEquals(KICK_STANDOFF, kicking.recoil(), "a kick reaches farther than a punch");
        assertEquals(1f, kicking.dashProgress());
        assertEquals(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 1, 2), d.last(BattleEvent.Impact.class));

        d.runActionTo(kickStart + 1.42f - 0.06f);
        assertTrue(d.sim.monk().pose().recoil() < KICK_STANDOFF, "and steps back in before the clip ends");
        d.runActionTo(kickStart + 1.42f + 0.1f);
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());
        assertEquals(0f, d.sim.monk().pose().recoil());
        d.finishAction();
        assertEquals(900f - 84f, d.sim.archon().hp());
    }

    @Test
    void strikeRecoversFasterThanFlurry() {
        BattleDriver strike = monkOnly();
        strike.command(BattleCommand.STRIKE);
        assertEquals(0f, strike.sim.monk().atb(), "spent as the action starts");
        strike.runUntil(s -> s.phase() == BattlePhase.RUNNING, 5f);
        assertEquals(0.25f, strike.sim.monk().atb(), 0.01f);

        BattleDriver flurry = monkOnly();
        flurry.command(BattleCommand.FLURRY);
        flurry.runUntil(s -> s.phase() == BattlePhase.RUNNING, 10f);
        assertEquals(0f, flurry.sim.monk().atb(), 0.01f);
    }

    @Test
    void damageVariesByTenPercentAndSometimesCrits() {
        BattleConfig config = BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10));
        BattleDriver d = new BattleDriver(config.withArchon(config.archon().withMaxHp(1.0e6f)), 5).start();
        for (int i = 0; i < 150; i++) {
            d.command(BattleCommand.STRIKE);
            d.finishAction();
        }
        int crits = 0;
        boolean varied = false;
        for (BattleEvent.DamageDealt hit : d.all(BattleEvent.DamageDealt.class)) {
            if (hit.flavor() == DamageFlavor.CRITICAL) {
                crits++;
                assertTrue(hit.amount() >= 57f && hit.amount() <= 69f, () -> "crit " + hit.amount());
            } else {
                assertEquals(DamageFlavor.NORMAL, hit.flavor());
                assertTrue(hit.amount() >= 38f && hit.amount() <= 46f, () -> "hit " + hit.amount());
            }
            assertEquals(Math.rint(hit.amount()), hit.amount(), "damage is whole numbers");
            varied |= hit.amount() != 42f;
        }
        assertEquals(150, d.count(BattleEvent.DamageDealt.class));
        assertTrue(varied);
        assertTrue(crits >= 3 && crits <= 40, "about 10% crits, got " + crits);
    }

    // ---- Flurry ---------------------------------------------------------------------------------

    @Test
    void flurryIsOneContinuousClipWhoseHitsLandOnTheAuthoredContacts() {
        for (float speed : new float[] {SPEED, 1.0f, 0.6f}) {
            BattleConfig config = BattleDriver.exact();
            BattleDriver d = monkOnly(config.withFlurry(config.flurry().withPlaybackSpeed(speed)));
            d.command(BattleCommand.FLURRY);
            d.forget();
            for (int hit = 0; hit < 3; hit++) {
                d.assertImpactAt(flurryContact(hit, speed));
                ActorPose pose = d.sim.monk().pose();
                assertEquals("flurry", pose.sbeState());
                assertEquals(MonkClips.FLURRY.cue(hit), pose.clipTime(), 3f * BattleDriver.EPS,
                        "the clip is never restarted: its own contact frame is showing");
                assertEquals(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, hit, 3),
                        d.last(BattleEvent.Impact.class));
            }
            float clipEnd = DASH + MonkClips.FLURRY.duration() / speed;
            d.runActionTo(clipEnd - 0.05f);
            assertEquals("flurry", d.sim.monk().pose().sbeState());
            d.runActionTo(clipEnd + 0.05f);
            assertEquals("combat_dash", d.sim.monk().pose().sbeState());
            assertEquals(clipEnd + DASH, d.all(BattleEvent.ActionStarted.class).get(0).duration(), 1.0e-4f);
            d.finishAction();
            assertEquals(3, d.count(BattleEvent.Impact.class));
        }
    }

    @Test
    void eachRingIsCentredOnItsContact() {
        BattleDriver d = flurry(false);
        float contact = flurryContact(0, SPEED);
        d.runActionTo(contact - LEAD - 0.01f);
        assertNull(d.sim.prompt(), "the ring opens 0.42 s before the fist lands");
        assertEquals(0, d.count(BattleEvent.PromptOpened.class));

        d.runActionTo(contact - LEAD + 0.01f);
        PromptView.Ring ring = assertInstanceOf(PromptView.Ring.class, d.sim.prompt());
        assertEquals(List.of(new BattleEvent.PromptOpened(PromptKind.RING)), d.all(BattleEvent.PromptOpened.class));
        assertEquals(0, ring.hitIndex());
        assertEquals(3, ring.hitCount());
        assertEquals(0.01f, ring.elapsed(), 1.0e-4f);
        assertEquals(LEAD + 0.13f, ring.duration(), 1.0e-5f, "closes with the GOOD window");
        assertEquals(LEAD - 0.06f, ring.perfectStart(), 1.0e-5f);
        assertEquals(LEAD + 0.06f, ring.perfectEnd(), 1.0e-5f);
        assertEquals(LEAD - 0.13f, ring.goodStart(), 1.0e-5f);
        assertEquals(LEAD + 0.13f, ring.goodEnd(), 1.0e-5f);
        assertTrue(d.sim.promptSafeRequired());

        d.runActionTo(contact);
        ring = (PromptView.Ring) d.sim.prompt();
        assertEquals((ring.perfectStart() + ring.perfectEnd()) * 0.5f, ring.elapsed(), 1.0e-4f,
                "the HUD ring converges exactly as the fist lands");
    }

    @Test
    void aPressGradesAtOnceButTheBlowStillLandsOnTheContact() {
        BattleDriver d = flurry(false);
        float c0 = flurryContact(0, SPEED);
        float c1 = flurryContact(1, SPEED);
        float c2 = flurryContact(2, SPEED);

        pressAt(d, c0 - 0.05f); // early half of PERFECT
        assertNull(d.sim.prompt(), "one press per ring");
        d.step(BattleDriver.EPS);
        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(0, d.count(BattleEvent.DamageDealt.class), "graded, not yet landed");
        d.sim.pressConfirm(); // the ring is spent: ignored
        d.assertImpactAt(c0);
        assertEquals(List.of(33f), damage(d), "fist and number coincide");

        pressAt(d, c1 - 0.10f); // GOOD, early side
        d.assertImpactAt(c1);
        pressAt(d, c2 - 0.30f); // before the GOOD window: a MISS, and the ring is spent
        d.sim.pressConfirm();
        d.assertImpactAt(c2);
        d.finishAction();

        assertEquals(List.of(TimedGrade.PERFECT, TimedGrade.GOOD, TimedGrade.MISS), grades(d));
        assertEquals(List.of(33f, 22f, 11f), damage(d));
        assertEquals(3, d.count(BattleEvent.PromptOpened.class));
        // Focus: 8 + 6 + 4
        assertEquals(18f, d.sim.focus());
        assertEquals(1, d.sim.stats().perfectRings());
    }

    @Test
    void lateHalfOfTheWindowStillGradesAndNeverMovesTheBlow() {
        BattleDriver d = flurry(false);
        float c0 = flurryContact(0, SPEED);
        float c1 = flurryContact(1, SPEED);
        float c2 = flurryContact(2, SPEED);

        d.assertImpactAt(c0);
        assertEquals(0, d.count(BattleEvent.DamageDealt.class), "the fist has landed; its grade is still open");
        pressAt(d, c0 + 0.05f); // late half of PERFECT
        d.step(BattleDriver.EPS);
        assertEquals(List.of(33f), damage(d), "paid the moment the grade is known");

        d.assertImpactAt(c1);
        pressAt(d, c1 + 0.10f); // GOOD, late side
        d.assertImpactAt(c2);
        d.runActionTo(c2 + 0.12f);
        assertEquals(2, d.count(BattleEvent.PromptResolved.class), "still open until contact + 0.13");
        d.runActionTo(c2 + 0.14f);
        assertNull(d.sim.prompt());

        assertEquals(List.of(TimedGrade.PERFECT, TimedGrade.GOOD, TimedGrade.MISS), grades(d));
        assertEquals(List.of(33f, 22f, 11f), damage(d));
        // Whatever was pressed, the three blows landed on the same three contacts (asserted above).
        assertEquals(3, d.count(BattleEvent.Impact.class));
    }

    @Test
    void anUnpressedFlurryMissesEveryRingAndStillHitsThreeTimes() {
        BattleDriver d = flurry(false);
        d.finishAction();
        assertEquals(List.of(TimedGrade.MISS, TimedGrade.MISS, TimedGrade.MISS), grades(d));
        assertEquals(900f - 33f, d.sim.archon().hp());
        assertEquals(12f, d.sim.focus());
        // Per hit the order the camera keys on: the ring opens, the blow lands, then its damage.
        List<BattleEvent> beats = d.log.stream().filter(e -> e instanceof BattleEvent.PromptOpened
                || e instanceof BattleEvent.Impact || e instanceof BattleEvent.DamageDealt).toList();
        assertEquals(9, beats.size());
        for (int hit = 0; hit < 3; hit++) {
            assertInstanceOf(BattleEvent.PromptOpened.class, beats.get(hit * 3));
            assertInstanceOf(BattleEvent.Impact.class, beats.get(hit * 3 + 1));
            assertInstanceOf(BattleEvent.DamageDealt.class, beats.get(hit * 3 + 2));
        }
    }

    @Test
    void aSurgedFlurryAppendsAGradedKickAndStrike() {
        BattleDriver d = flurry(true);
        for (int hit = 0; hit < 3; hit++) pressAt(d, flurryContact(hit, SPEED));
        float kickContact = flurryClipEnd() + 0.60f;
        float strikeStart = flurryClipEnd() + 1.42f;
        float strikeContact = strikeStart + 0.38f;

        d.runActionTo(kickContact - LEAD + 0.01f);
        PromptView.Ring kickRing = assertInstanceOf(PromptView.Ring.class, d.sim.prompt());
        assertEquals(3, kickRing.hitIndex());
        assertEquals(5, kickRing.hitCount());
        pressAt(d, kickContact - 0.02f);
        d.assertImpactAt(kickContact);
        assertEquals("kick", d.sim.monk().pose().sbeState());
        assertEquals(KICK_STANDOFF, d.sim.monk().pose().recoil());

        d.runActionTo(strikeStart + 0.01f);
        PromptView.Ring strikeRing = assertInstanceOf(PromptView.Ring.class, d.sim.prompt());
        assertEquals(4, strikeRing.hitIndex());
        assertEquals(0.38f + 0.13f, strikeRing.duration(), 1.0e-4f, "a ring never opens before its clip does");
        assertEquals(0.38f, (strikeRing.perfectStart() + strikeRing.perfectEnd()) * 0.5f, 1.0e-4f);
        d.assertImpactAt(strikeContact);
        assertEquals("strike", d.sim.monk().pose().sbeState());
        assertEquals(0f, d.sim.monk().pose().recoil());
        d.finishAction();

        assertEquals(List.of(TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.PERFECT,
                TimedGrade.MISS), grades(d));
        assertEquals(List.of(33f, 33f, 33f, 33f, 11f), damage(d));
        assertEquals(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 4, 5), d.last(BattleEvent.Impact.class));
        assertEquals(4, d.sim.stats().perfectRings());
        assertEquals(4, d.sim.stats().bestRingStreak());
    }

    @Test
    void ringsNeverOverlapEvenWhenTheContactsCrowdTogether() {
        // At double speed the contacts are 0.26 s apart: closer than a ring's lead.
        BattleConfig config = BattleDriver.exact();
        BattleDriver d = monkOnly(config.withFlurry(config.flurry().withPlaybackSpeed(2f)));
        d.command(BattleCommand.FLURRY);
        d.forget();
        float c0 = flurryContact(0, 2f);
        float c1 = flurryContact(1, 2f);
        d.runActionTo(c0 + 0.05f);
        assertEquals(0, ((PromptView.Ring) d.sim.prompt()).hitIndex(), "ring 1 is due, but ring 0 is still open");
        d.sim.pressConfirm();
        PromptView.Ring next = assertInstanceOf(PromptView.Ring.class, d.sim.prompt());
        assertEquals(1, next.hitIndex(), "opens the moment the previous one resolves");
        assertEquals(0f, next.elapsed());
        assertEquals(c1 - (c0 + 0.05f), (next.perfectStart() + next.perfectEnd()) * 0.5f, 1.0e-4f,
                "still centred on its own contact");
        d.finishAction();
        assertEquals(3, d.count(BattleEvent.Impact.class));
        assertEquals(3, d.count(BattleEvent.PromptResolved.class));
        assertEquals(3, d.count(BattleEvent.DamageDealt.class));
    }

    @Test
    void perfectStreakIsTheLongestRunOfPerfects() {
        BattleDriver d = flurry(true);
        float kick = flurryClipEnd() + 0.60f;
        float strike = flurryClipEnd() + 1.42f + 0.38f;
        pressAt(d, flurryContact(0, SPEED));
        pressAt(d, flurryContact(1, SPEED) - 0.25f); // far too early
        pressAt(d, flurryContact(2, SPEED));
        pressAt(d, kick);
        pressAt(d, strike);
        assertEquals(4, d.sim.stats().perfectRings());
        assertEquals(3, d.sim.stats().bestRingStreak());
    }

    // ---- Stunning Strike --------------------------------------------------------------------------

    @Test
    void stunningStrikeLandsOnItsContactAndFreezesTheArchonUntilItHasRecovered() {
        BattleDriver d = new BattleDriver(BattleDriver.exact(), 1).start();
        assertTrue(d.command(BattleCommand.STUNNING_STRIKE));
        d.forget();
        d.assertImpactAt(DASH + 0.62f);
        assertEquals("stunning_strike", d.sim.monk().pose().sbeState());
        assertEquals(870f, d.sim.archon().hp());
        assertTrue(d.sim.archon().has(BattleStatus.STUNNED));
        assertTrue(d.log.contains(new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 5f)));
        assertEquals(DASH + 1.38f + DASH, d.all(BattleEvent.ActionStarted.class).get(0).duration(), 1.0e-5f);
        d.finishAction();

        // Stunned 1.07 s into a 2.28 s action: 3.79 s of it remain once the gauges run again.
        float frozen = d.sim.archon().atb();
        d.run(3.7f);
        assertTrue(d.sim.archon().has(BattleStatus.STUNNED));
        assertEquals(frozen, d.sim.archon().atb());
        d.run(0.2f);
        assertFalse(d.sim.archon().has(BattleStatus.STUNNED));
        assertTrue(d.log.contains(new BattleEvent.StatusExpired(CombatantId.ARCHON, BattleStatus.STUNNED)));
        assertEquals("stunned_exit", d.sim.archon().pose().sbeState());
        assertEquals(frozen, d.sim.archon().atb(), "it cannot act until it is back on its feet");
        d.run(0.7f);
        assertEquals(frozen, d.sim.archon().atb());
        d.run(0.2f); // stunned_exit is 0.90 s
        assertTrue(d.sim.archon().atb() > frozen);
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());
    }

    @Test
    void aStunnedArchonOnlyActsOnceTheStunAndItsRecoveryAreOver() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1);
        d.sim.introFinished();
        d.sim.stunArchonNow();
        d.run(4.9f);
        assertNull(d.sim.telegraph());
        assertEquals(0.9f, d.sim.archon().atb(), "frozen where the stun caught it");
        d.run(0.9f); // stun ends at 5.0 s; stunned_exit runs to 5.9 s
        assertNull(d.sim.telegraph());
        assertEquals(0.9f, d.sim.archon().atb());
        assertEquals("stunned_exit", d.sim.archon().pose().sbeState());
        d.run(0.8f); // the last tenth of the gauge takes 0.55 s
        assertEquals(EnemyAction.SLASH, d.sim.telegraph().action());
    }

    @Test
    void stunCancelsATelegraphedAttack() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.OVERHEAD), 1).start();
        d.awaitTelegraph();
        d.sim.submit(BattleCommand.STRIKE); // queued behind the attack
        d.run(0.3f);
        d.forget();

        d.sim.stunArchonNow();
        assertNull(d.sim.telegraph());
        assertNull(d.sim.prompt());
        assertEquals(0f, d.sim.archon().atb());
        assertEquals("stunned_enter", d.sim.archon().pose().sbeState(), "the attack clip is dropped, not resumed");
        assertEquals(0f, d.sim.archon().pose().clipTime());

        d.step(BattleDriver.DT);
        assertEquals(List.of(
                new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 5f),
                new BattleEvent.TelegraphCancelled(EnemyAction.OVERHEAD),
                new BattleEvent.ActionFinished(CombatantId.ARCHON)), d.log.subList(0, 3));
        assertTrue(d.sim.archon().pose().recoil() > 0f, "it flinches out of the swing");
        assertEquals(BattleCommand.STRIKE, d.sim.currentAction().command(), "the queued command takes over");
        assertEquals(0, d.count(BattleEvent.Impact.class));
        assertEquals(200f, d.sim.monk().hp());

        d.finishAction();
        assertTrue(d.sim.archon().pose().dashProgress() < 0.01f, "the Archon glided back home");
        assertEquals("stunned", d.sim.archon().pose().sbeState());
        assertEquals(200f, d.sim.monk().hp(), "the cancelled blow never lands");
    }

    // ---- support --------------------------------------------------------------------------------

    @Test
    void swiftStepAppliesHasteOnItsEffectCue() {
        BattleDriver d = monkOnly();
        d.command(BattleCommand.SWIFT_STEP);
        assertEquals(1.32f, d.sim.currentAction().duration());
        d.runActionTo(0.6f);
        assertFalse(d.sim.monk().has(BattleStatus.HASTE));
        assertEquals("swift_step", d.sim.monk().pose().sbeState());
        assertEquals(0.6f, d.sim.monk().pose().clipTime(), 1.0e-5f);
        assertEquals(0f, d.sim.monk().pose().dashProgress(), "no dash: the clip is played on the home mark");
        d.runActionTo(0.64f);
        assertTrue(d.sim.monk().has(BattleStatus.HASTE));
        d.finishAction();
        assertTrue(d.log.contains(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.HASTE, 15f)));

        float readyAt = d.sim.elapsedSeconds();
        d.awaitWindow();
        assertEquals(4f / 1.5f, d.sim.elapsedSeconds() - readyAt, 2f * BattleDriver.DT);

        d.run(15f);
        assertFalse(d.sim.monk().has(BattleStatus.HASTE));
        assertTrue(d.log.contains(new BattleEvent.StatusExpired(CombatantId.MONK, BattleStatus.HASTE)));
    }

    @Test
    void meditateHealsThirtyPercentOnItsEffectCue() {
        BattleDriver d = monkOnly();
        d.sim.context().monk.takeDamage(100f);
        d.command(BattleCommand.MEDITATE);
        assertEquals(2, d.sim.meditateCharges());
        assertEquals(3.6f, d.sim.currentAction().duration());
        d.runActionTo(1.78f);
        assertEquals(100f, d.sim.monk().hp());
        d.runActionTo(1.82f);
        assertEquals(160f, d.sim.monk().hp());
        assertEquals("meditate", d.sim.monk().pose().sbeState());
        assertEquals(new BattleEvent.Healed(CombatantId.MONK, 60f), d.last(BattleEvent.Healed.class));
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        d.finishAction();

        d.command(BattleCommand.MEDITATE); // only 40 missing: the heal is capped
        d.finishAction();
        assertEquals(200f, d.sim.monk().hp());
        assertEquals(new BattleEvent.Healed(CombatantId.MONK, 40f), d.last(BattleEvent.Healed.class));
        assertEquals(1, d.sim.meditateCharges());
    }
}
