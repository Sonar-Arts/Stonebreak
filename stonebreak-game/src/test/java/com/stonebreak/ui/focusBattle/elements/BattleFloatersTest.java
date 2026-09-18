package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FlowLayout;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.WorldProjection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E11: which event makes which floater, where it starts, how it moves, and when it goes. */
class BattleFloatersTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;

    private FakeBattleView view;
    private BattleFloaters floaters;

    @BeforeEach
    void freshFloaters() {
        view = new FakeBattleView();
        floaters = new BattleFloaters();
        floaters.setStage(FakeBattleView.crucibleLayout());
    }

    private BattleFloaters.Floater only(BattleEvent event) {
        floaters.reset();
        floaters.update(DT, view, List.of(event));
        assertEquals(1, floaters.live().size(), String.valueOf(event));
        return floaters.live().get(0);
    }

    /** Camera behind the monk: both actors on screen. */
    private static Matrix4f wideCamera() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) W / H, 0.1f, 200f)
                .lookAt(9f, 3f, 16f, 0f, 1.6f, 0f, 0f, 1f, 0f);
    }

    /** Camera facing away from the arena: nothing on screen. */
    private static Matrix4f awayCamera() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) W / H, 0.1f, 200f)
                .lookAt(0f, 3f, 30f, 0f, 3f, 60f, 0f, 1f, 0f);
    }

    // ── spawn mapping ────────────────────────────────────────────────────────

    @Test
    void damageFlavorsMapToTheirOwnLook() {
        BattleFloaters.Floater normal = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 128.4f, DamageFlavor.NORMAL));
        assertEquals("128", normal.text());
        assertEquals(BattleFloaters.Style.DAMAGE, normal.style());
        assertEquals(BattleFloaters.Origin.ARCHON, normal.origin());

        BattleFloaters.Floater crit = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 300f, DamageFlavor.CRITICAL));
        assertEquals("300!", crit.text());
        assertEquals(BattleFloaters.Style.CRITICAL, crit.style());
        assertTrue(crit.style().fontSize() > normal.style().fontSize(), "criticals are larger");
        assertNotEquals(normal.style().color(), crit.style().color(), "and gold");

        BattleFloaters.Floater blocked = only(new BattleEvent.DamageDealt(CombatantId.MONK, 12f, DamageFlavor.BLOCKED));
        assertEquals("BLOCK 12", blocked.text());
        assertEquals(BattleFloaters.Origin.MONK, blocked.origin());
        assertTrue(blocked.style().fontSize() < normal.style().fontSize(), "a block is small");

        BattleFloaters.Floater parried = only(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED));
        assertEquals("PARRY!", parried.text(), "no number on a parry");
        assertEquals(BattleFloaters.Style.PARRIED, parried.style());

        assertEquals("1", only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 0.2f, DamageFlavor.NORMAL)).text(),
                "anything that hurt shows at least 1");
    }

    @Test
    void theOtherEventsMapToWords() {
        BattleFloaters.Floater heal = only(new BattleEvent.Healed(CombatantId.MONK, 54f));
        assertEquals("+54", heal.text());
        assertEquals(BattleFloaters.Style.HEAL, heal.style());

        BattleFloaters.Floater qi = only(new BattleEvent.QiChanged(1, 4));
        assertEquals("+1 Qi", qi.text());
        assertEquals(BattleFloaters.Origin.QI_ROW, qi.origin());

        assertEquals("FOCUS MAX", only(new BattleEvent.FocusFull()).text());
        assertEquals("PERFECT", only(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0)).text());
        assertEquals("GOOD", only(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.GOOD, 2)).text());
        assertEquals(BattleFloaters.Style.MISS,
                only(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, 1)).style());

        BattleFloaters.Floater stun = only(new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 4f));
        assertEquals("Stunned", stun.text());
        assertEquals(BattleFloaters.Style.STATUS_BAD, stun.style());
        assertEquals(BattleFloaters.Style.STATUS_GOOD,
                only(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.HASTE, 15f)).style());

        BattleFloaters.Floater rejected = only(new BattleEvent.CommandRejected(BattleCommand.SWIFT_STEP, "Not enough Qi."));
        assertEquals("Not enough Qi.", rejected.text());
        assertEquals(BattleFloaters.Origin.COMMAND_WINDOW, rejected.origin());
    }

    @Test
    void quietEventsSpawnNothing() {
        floaters.update(DT, view, List.of(
                new BattleEvent.QiChanged(-2, 1),
                new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0),
                new BattleEvent.CommandRejected(BattleCommand.STRIKE, ""),
                new BattleEvent.TurnReady(CombatantId.MONK),
                new BattleEvent.FocusChanged(5f, 40f),
                new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 0, 1)));
        assertTrue(floaters.live().isEmpty(), "a parry's word comes from its PARRIED damage, not twice");

        floaters.setGradeWordsEnabled(false);
        floaters.update(DT, view, List.of(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0)));
        assertTrue(floaters.live().isEmpty(), "grade words can be left to the ring's own layer");
    }

    // ── placement ────────────────────────────────────────────────────────────

    @Test
    void aWorldFloaterStartsAtItsProjectedBodyPoint() {
        Matrix4f camera = wideCamera();
        BattleFloaters.Floater f = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL));
        assertFalse(f.placed(), "no camera yet");
        floaters.resolvePending(W, H, UI, camera);
        assertTrue(f.placed());

        float[] expected = WorldProjection.toScreen(camera, FakeBattleView.crucibleLayout()
                .bodyPoint(CombatantId.ARCHON, view.archon.pose, BattleFloaters.ANCHOR_HEIGHT), W, H, 0f);
        float[] bounds = FlowLayout.floaterBounds(W, H, UI);
        assertTrue(expected[1] > bounds[1] && expected[1] < bounds[3], "fixture sanity: anchor inside the band");
        float[] at = f.position();
        assertEquals(expected[0], at[0], 1f, "the first number of a burst sits on the anchor");
        assertEquals(expected[1], at[1], 1f);
    }

    @Test
    void theScreenPositionIsCapturedOnceAndIgnoresLaterCameras() {
        BattleFloaters.Floater f = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL));
        floaters.resolvePending(W, H, UI, wideCamera());

        BattleFloaters twin = new BattleFloaters();
        twin.setStage(FakeBattleView.crucibleLayout());
        twin.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL)));
        twin.resolvePending(W, H, UI, wideCamera());

        // The camera cuts away mid-flight for one of them only.
        for (int i = 0; i < 20; i++) {
            floaters.update(DT, view, List.of());
            floaters.resolvePending(W, H, UI, awayCamera());
            twin.update(DT, view, List.of());
            twin.resolvePending(W, H, UI, wideCamera());
        }
        assertArrayEquals(twin.live().get(0).position(), f.position(), 0f, "a cut never teleports a number");
    }

    @Test
    void anOffScreenAnchorFallsBackToTheOwnersHudWindow() {
        BattleFloaters.Floater archon = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL));
        floaters.resolvePending(W, H, UI, awayCamera());
        float[] enemyPoint = FlowLayout.enemyFallbackPoint(W, H, UI);
        assertEquals(enemyPoint[1], archon.position()[1], 1f);
        assertEquals(enemyPoint[0], archon.position()[0], 1f);
        float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
        float[] banner = FocusBattleLayout.actionBannerRect(W, H, UI);
        assertTrue(archon.position()[1] > plate[1] + plate[3], "below the enemy plate, never on it");
        assertTrue(archon.position()[1] > banner[1] + banner[3], "and clear of the action banner");

        BattleFloaters.Floater monk = only(new BattleEvent.DamageDealt(CombatantId.MONK, 50f, DamageFlavor.NORMAL));
        floaters.resolvePending(W, H, UI, null);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        assertTrue(monk.position()[0] > party[0] && monk.position()[0] < party[0] + party[2], "over the party window");
        assertTrue(monk.position()[1] < party[1]);
    }

    @Test
    void hudFloatersStartAtTheirWindows() {
        BattleFloaters.Floater qi = only(new BattleEvent.QiChanged(1, 3));
        floaters.resolvePending(W, H, UI, wideCamera());
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        assertTrue(qi.position()[0] >= party[0] && qi.position()[0] <= party[0] + party[2], "near the party window");

        BattleFloaters.Floater rejected = only(new BattleEvent.CommandRejected(BattleCommand.STRIKE, "Not now."));
        floaters.resolvePending(W, H, UI, wideCamera());
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI);
        assertTrue(rejected.position()[0] >= cmd[0] && rejected.position()[0] <= cmd[0] + cmd[2],
                "near the command window");
    }

    @Test
    void aNumberAWordAndAStatusFromOneBlowStartInDifferentLanes() {
        floaters.update(DT, view, List.of(
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 204f, DamageFlavor.CRITICAL),
                new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0),
                new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 4f)));
        floaters.resolvePending(W, H, UI, wideCamera());
        List<BattleFloaters.Floater> live = floaters.live();
        for (BattleFloaters.Floater f : live) assertTrue(f.started(), "different lanes do not queue behind each other");
        float number = live.get(0).position()[1], word = live.get(1).position()[1], status = live.get(2).position()[1];
        assertTrue(word < number - 40f, "the grade word starts above the number");
        assertTrue(status > number + 30f, "the status name below it");
    }

    // ── motion ───────────────────────────────────────────────────────────────

    @Test
    void floatersRiseArcBackAndFadeOutOverTheirLifetime() {
        BattleFloaters.Floater f = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL));
        floaters.resolvePending(W, H, UI, wideCamera());
        float startY = f.position()[1];
        float startX = f.position()[0];
        float minY = startY;
        for (int i = 0; i < 30; i++) {
            floaters.update(DT, view, List.of());
            minY = Math.min(minY, f.position()[1]);
            if (i == 10) assertEquals(1f, f.alpha(), 0f, "opaque for the first half");
        }
        assertTrue(minY < startY - 15f, "it is thrown upward");
        assertNotEquals(startX, f.position()[0], "and sideways");
        for (int i = 0; i < 20; i++) floaters.update(DT, view, List.of());
        assertTrue(f.position()[1] > minY, "gravity brings it back down");
        assertTrue(f.alpha() < 1f && f.alpha() > 0f, "fading at the end");

        for (int i = 0; i < 10; i++) floaters.update(DT, view, List.of());
        assertTrue(floaters.live().isEmpty(), "gone after " + BattleFloaters.LIFETIME_SECONDS + " s");
    }

    @Test
    void simultaneousHitsAreStaggeredInTimeAndSpace() {
        floaters.update(DT, view, List.of(
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 40f, DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 41f, DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 42f, DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.MONK, 9f, DamageFlavor.NORMAL)));
        floaters.resolvePending(W, H, UI, wideCamera());
        List<BattleFloaters.Floater> live = floaters.live();
        assertTrue(live.get(0).started());
        assertFalse(live.get(1).started(), "the second number waits its turn");
        assertFalse(live.get(2).started());
        assertTrue(live.get(3).started(), "a different anchor is not held up");
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                float[] a = live.get(i).position(), b = live.get(j).position();
                assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) > 20.0, i + " and " + j + " do not overlap exactly");
            }
        }
        for (int i = 0; i < 12; i++) floaters.update(DT, view, List.of());
        assertTrue(live.get(2).started(), "everything is on screen within a fifth of a second");
    }

    @Test
    void theLiveCountIsCappedAndTheOldestGoFirst() {
        for (int i = 0; i < BattleFloaters.MAX_LIVE + 10; i++) {
            floaters.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, i + 1f, DamageFlavor.NORMAL)));
        }
        assertEquals(BattleFloaters.MAX_LIVE, floaters.live().size());
        assertEquals("11", floaters.live().get(0).text(), "the first ten were dropped");
        assertEquals(String.valueOf(BattleFloaters.MAX_LIVE + 10), floaters.live().get(BattleFloaters.MAX_LIVE - 1).text());
    }

    @Test
    void resetClearsEverything() {
        floaters.update(DT, view, List.of(new BattleEvent.FocusFull(), new BattleEvent.QiChanged(1, 2)));
        floaters.reset();
        assertTrue(floaters.live().isEmpty());
        BattleFloaters.Floater first = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 5f, DamageFlavor.NORMAL));
        assertTrue(first.started(), "the stagger memory is cleared too");
    }

    // ── raster ───────────────────────────────────────────────────────────────

    private BattleRasterFixture paint(BattleFloaters which) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        which.paint(fx.ui, fx.canvas, W, H, FocusBattleLayout.effectiveScale(W, H, UI), UI, view, null, wideCamera());
        return fx;
    }

    @Test
    void floatersPaintAndDifferByStyleAndAge() {
        BattleRasterFixture empty = paint(floaters);
        assertEquals(0, empty.countPainted(0, 0, W, H), "nothing live, nothing drawn");

        floaters.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 128f, DamageFlavor.NORMAL)));
        BattleRasterFixture fresh = paint(floaters);
        assertTrue(fresh.countPainted(0, 0, W, H) > 400, "the number is drawn");
        assertTrue(fresh.countExactly(0xFFFFFFFF, 0, 0, W, H) > 80, "in white");
        float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
        assertEquals(0, fresh.countPainted(plate), "and not over the enemy plate");

        for (int i = 0; i < 45; i++) floaters.update(DT, view, List.of());
        BattleRasterFixture late = paint(floaters);
        assertTrue(late.diff(fresh) > 300, "it has moved and begun to fade");
        assertEquals(0, late.countExactly(0xFFFFFFFF, 0, 0, W, H), "no fully opaque white left");

        BattleFloaters crit = new BattleFloaters();
        crit.setStage(FakeBattleView.crucibleLayout());
        crit.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 128f, DamageFlavor.CRITICAL)));
        BattleRasterFixture critical = paint(crit);
        assertTrue(critical.countPainted(0, 0, W, H) > fresh.countPainted(0, 0, W, H), "a critical is bigger");
        assertTrue(critical.countExactly(0xFFFFD75A, 0, 0, W, H) > 80, "and gold");
    }
}
