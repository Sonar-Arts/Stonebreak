package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MFloatingText;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MWorldMarker;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E11: which event raises which words in which look, and where on screen they are born. How a
 * floater then moves, fades, staggers and is capped is {@code MFloatingText}'s own and tested there.
 */
class BattleFloatersTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;
    private static final float SCALE = FocusBattleLayout.effectiveScale(W, H, UI);

    private FakeBattleView view;
    private BattleFloaters floaters;

    @BeforeEach
    void freshFloaters() {
        view = new FakeBattleView();
        floaters = new BattleFloaters();
        floaters.setStage(FakeBattleView.crucibleLayout());
    }

    private static BattleFloaters.Spawn words(BattleEvent event) {
        return BattleFloaters.spawnFor(event, true);
    }

    /** One event through the adapter, given its screen position by {@code camera}. */
    private MFloatingText.Floater only(BattleEvent event, Matrix4f camera) {
        floaters.reset();
        floaters.update(DT, view, List.of(event));
        assertEquals(1, floaters.count(), String.valueOf(event));
        floaters.resolvePending(W, H, UI, camera);
        assertEquals(1, floaters.live().size(), "released by the first camera it meets");
        return floaters.live().get(0);
    }

    private static MWorldMarker.Anchor project(Matrix4f camera, CombatantId who, FakeBattleView view) {
        return MWorldMarker.project(camera, FakeBattleView.crucibleLayout()
                .bodyPoint(who, view.combatant(who).pose(), BattleFloaters.ANCHOR_HEIGHT), W, H);
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

    // ── the vocabulary: event → (words, look, origin) ────────────────────────

    @Test
    void damageFlavorsMapToTheirOwnLook() {
        BattleFloaters.Spawn normal = words(new BattleEvent.DamageDealt(CombatantId.ARCHON, 128.4f, DamageFlavor.NORMAL));
        assertEquals("128", normal.text());
        assertEquals(BattleFloaters.DAMAGE, normal.style());
        assertEquals(MStyle.TEXT_PRIMARY, normal.style().color(), "plain damage in the house text colour");
        assertEquals(BattleFloaters.Origin.ARCHON, normal.origin());

        BattleFloaters.Spawn crit = words(new BattleEvent.DamageDealt(CombatantId.ARCHON, 300f, DamageFlavor.CRITICAL));
        assertEquals("300!", crit.text());
        assertEquals(BattleFloaters.CRITICAL, crit.style());
        assertTrue(crit.style().fontSize() > normal.style().fontSize(), "criticals are larger");
        assertEquals(MStyle.TEXT_ACCENT, crit.style().color(), "and gold");

        BattleFloaters.Spawn blocked = words(new BattleEvent.DamageDealt(CombatantId.MONK, 12f, DamageFlavor.BLOCKED));
        assertEquals("BLOCK 12", blocked.text());
        assertEquals(BattleFloaters.Origin.MONK, blocked.origin());
        assertTrue(blocked.style().fontSize() < normal.style().fontSize(), "a block is small");
        assertEquals(MFloatingText.Lane.NUMBER, blocked.style().lane(), "but still a number");

        BattleFloaters.Spawn parried = words(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED));
        assertEquals("PARRY!", parried.text(), "no number on a parry");
        assertEquals(BattleFloaters.PARRIED, parried.style());
        assertEquals(MFloatingText.Lane.WORD, parried.style().lane());

        assertEquals("1", words(new BattleEvent.DamageDealt(CombatantId.ARCHON, 0.2f, DamageFlavor.NORMAL)).text(),
                "anything that hurt shows at least 1");
    }

    @Test
    void theOtherEventsMapToWords() {
        BattleFloaters.Spawn heal = words(new BattleEvent.Healed(CombatantId.MONK, 54f));
        assertEquals("+54", heal.text());
        assertEquals(BattleFloaters.HEAL, heal.style());
        assertEquals(MStyle.VITAL_OK, heal.style().color());

        BattleFloaters.Spawn qi = words(new BattleEvent.QiChanged(1, 4));
        assertEquals("+1 Qi", qi.text());
        assertEquals(BattlePalette.QI, qi.style().color());
        assertEquals(BattleFloaters.Origin.QI_ROW, qi.origin());

        assertEquals("FOCUS MAX", words(new BattleEvent.FocusFull()).text());
        assertEquals(BattlePalette.FOCUS, words(new BattleEvent.FocusFull()).style().color());

        BattleFloaters.Spawn perfect = words(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0));
        assertEquals("PERFECT", perfect.text());
        assertEquals(BattlePalette.grade(TimedGrade.PERFECT), perfect.style().color());
        assertEquals(MFloatingText.Lane.WORD, perfect.style().lane());
        assertEquals("GOOD", words(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.GOOD, 2)).text());
        BattleFloaters.Spawn miss = words(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, 1));
        assertEquals(BattlePalette.grade(TimedGrade.MISS), miss.style().color());
        assertTrue(miss.style().fontSize() < perfect.style().fontSize(), "a miss is said quietly");

        BattleFloaters.Spawn stun = words(new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 4f));
        assertEquals("Stunned", stun.text());
        assertEquals(BattlePalette.status(BattleStatus.STUNNED), stun.style().color());
        assertEquals(MFloatingText.Lane.STATUS, stun.style().lane());
        assertEquals(BattlePalette.status(BattleStatus.HASTE),
                words(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.HASTE, 15f)).style().color());

        BattleFloaters.Spawn rejected = words(new BattleEvent.CommandRejected(BattleCommand.SWIFT_STEP, "Not enough Qi."));
        assertEquals("Not enough Qi.", rejected.text());
        assertEquals(MStyle.TEXT_ERROR, rejected.style().color());
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
        assertEquals(0, floaters.count(), "a parry's word comes from its PARRIED damage, not twice");
        assertNull(BattleFloaters.spawnFor(null, true));

        floaters.setGradeWordsEnabled(false);
        floaters.update(DT, view, List.of(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0)));
        assertEquals(0, floaters.count(), "grade words can be left to the ring's own layer");
    }

    // ── placement ────────────────────────────────────────────────────────────

    @Test
    void aWorldFloaterStartsAtItsProjectedBodyPoint() {
        Matrix4f camera = wideCamera();
        floaters.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL)));
        assertTrue(floaters.live().isEmpty(), "no camera yet: it waits");
        assertEquals(1, floaters.count());
        floaters.resolvePending(W, H, UI, camera);
        MFloatingText.Floater f = floaters.live().get(0);

        MWorldMarker.Anchor expected = project(camera, CombatantId.ARCHON, view);
        float[] band = BattleFloaters.band(W, H, UI, SCALE);
        assertTrue(expected.onScreen() && expected.y() > band[1] + 80f && expected.y() < band[3],
                "fixture sanity: anchor well inside the band");
        float[] at = f.position(SCALE);
        assertEquals(expected.x(), at[0], 1f, "the first number of a burst sits on the anchor");
        assertEquals(expected.y(), at[1], 1f);
    }

    @Test
    void theScreenPositionIsCapturedOnceAndIgnoresLaterCameras() {
        MFloatingText.Floater f = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL), wideCamera());

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
        assertArrayEquals(twin.live().get(0).position(SCALE), f.position(SCALE), 0f, "a cut never teleports a number");
    }

    @Test
    void anOffScreenAnchorFallsBackToTheOwnersHudWindow() {
        MFloatingText.Floater archon = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL),
                awayCamera());
        float[] enemyPoint = BattleFloaters.hudPoint(BattleFloaters.Origin.ARCHON, W, H, UI, SCALE);
        assertEquals(W / 2f, enemyPoint[0], 0f, "top-centre");
        float[] at = archon.position(SCALE);
        assertEquals(enemyPoint[0], at[0], 1f);
        float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
        float[] banner = FocusBattleLayout.actionBannerRect(W, H, UI);
        assertTrue(at[1] > plate[1] + plate[3], "below the enemy plate, never on it");
        assertTrue(at[1] > banner[1] + banner[3], "and clear of the action banner");

        MFloatingText.Floater monk = only(new BattleEvent.DamageDealt(CombatantId.MONK, 50f, DamageFlavor.NORMAL), null);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        float[] from = monk.position(SCALE);
        assertTrue(from[0] > party[0] && from[0] < party[0] + party[2], "over the party window");
        assertTrue(from[1] < party[1]);
    }

    @Test
    void hudFloatersStartAtTheirWindows() {
        float[] qi = only(new BattleEvent.QiChanged(1, 3), wideCamera()).position(SCALE);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        assertTrue(qi[0] >= party[0] && qi[0] <= party[0] + party[2], "near the party window");

        float[] rejected = only(new BattleEvent.CommandRejected(BattleCommand.STRIKE, "Not now."), wideCamera())
                .position(SCALE);
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI);
        assertTrue(rejected[0] >= cmd[0] && rejected[0] <= cmd[0] + cmd[2], "near the command window");
    }

    @Test
    void theWholeArcStaysBetweenTheBannerAndThePartyWindowAtEveryResolution() {
        int[][] windows = {{1024, 600}, {1280, 720}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            float scale = FocusBattleLayout.effectiveScale(win[0], win[1], UI);
            float[] band = BattleFloaters.band(win[0], win[1], UI, scale);
            float[] banner = FocusBattleLayout.actionBannerRect(win[0], win[1], UI);
            float[] party = FocusBattleLayout.partyWindowRect(win[0], win[1], UI);
            String where = win[0] + "x" + win[1];
            assertTrue(band[1] > banner[1] + banner[3] && band[3] < party[1] && band[3] > band[1], where);
            assertTrue(band[0] > 0f && band[2] < win[0] && band[2] > band[0], where);
            for (BattleFloaters.Origin origin : BattleFloaters.Origin.values()) {
                float[] p = BattleFloaters.hudPoint(origin, win[0], win[1], UI, scale);
                assertTrue(p[0] >= band[0] && p[0] <= band[2] && p[1] >= band[1] && p[1] <= band[3],
                        where + " " + origin + " starts inside the band");
            }

            // Thrown from the very top of the band, a number still never rises into the banner.
            floaters.reset();
            floaters.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 9f, DamageFlavor.CRITICAL)));
            floaters.resolvePending(win[0], win[1], UI, awayCamera());
            MFloatingText.Floater f = floaters.live().get(0);
            float top = Float.MAX_VALUE;
            for (int i = 0; i < 50; i++) {
                top = Math.min(top, f.position(scale)[1]);
                floaters.update(DT, view, List.of());
            }
            assertTrue(top >= band[1] - 0.5f, where + " apex " + top + " stays under " + band[1]);
        }
    }

    @Test
    void aNumberAWordAndAStatusFromOneBlowStartInDifferentLanes() {
        floaters.update(DT, view, List.of(
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 204f, DamageFlavor.CRITICAL),
                new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0),
                new BattleEvent.StatusApplied(CombatantId.ARCHON, BattleStatus.STUNNED, 4f)));
        floaters.resolvePending(W, H, UI, wideCamera());
        List<MFloatingText.Floater> live = floaters.live();
        assertEquals(3, live.size());
        for (MFloatingText.Floater f : live) assertTrue(f.started(), "different lanes do not queue behind each other");
        float number = live.get(0).position(SCALE)[1], word = live.get(1).position(SCALE)[1],
                status = live.get(2).position(SCALE)[1];
        assertTrue(word < number - 40f, "the grade word starts above the number");
        assertTrue(status > number + 30f, "the status name below it");
    }

    @Test
    void simultaneousHitsOnOneBodyQueueButADifferentBodyDoesNot() {
        floaters.update(DT, view, List.of(
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 40f, DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 41f, DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.MONK, 9f, DamageFlavor.NORMAL)));
        floaters.resolvePending(W, H, UI, wideCamera());
        List<MFloatingText.Floater> live = floaters.live();
        assertTrue(live.get(0).started());
        assertFalse(live.get(1).started(), "the second number on the Archon waits its turn");
        assertTrue(live.get(2).started(), "the monk's is a different anchor and is not held up");
    }

    // ── lifetime ─────────────────────────────────────────────────────────────

    @Test
    void floatersAgeThroughTheAdapterAndAreGoneAfterTheirLifetime() {
        MFloatingText.Floater f = only(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL), wideCamera());
        float startY = f.position(SCALE)[1];
        for (int i = 0; i < 20; i++) floaters.update(DT, view, List.of());
        assertTrue(f.position(SCALE)[1] < startY - 15f, "update(dt) is what moves it");
        for (int i = 0; i < 40; i++) floaters.update(DT, view, List.of());
        assertEquals(0, floaters.count(), "gone after its lifetime");
    }

    @Test
    void theCountIsCappedWhetherWaitingOrInFlightAndTheOldestGoFirst() {
        for (int i = 0; i < BattleFloaters.MAX_LIVE + 10; i++) {
            floaters.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, i + 1f, DamageFlavor.NORMAL)));
        }
        assertEquals(BattleFloaters.MAX_LIVE, floaters.count(), "a HUD that is never drawn cannot queue without bound");
        floaters.resolvePending(W, H, UI, wideCamera());
        assertEquals(BattleFloaters.MAX_LIVE, floaters.live().size());
        assertEquals("11", floaters.live().get(0).text(), "the first ten were dropped");
        assertEquals(String.valueOf(BattleFloaters.MAX_LIVE + 10), floaters.live().get(BattleFloaters.MAX_LIVE - 1).text());
    }

    @Test
    void resetClearsEverything() {
        floaters.update(DT, view, List.of(new BattleEvent.FocusFull(), new BattleEvent.QiChanged(1, 2)));
        floaters.resolvePending(W, H, UI, wideCamera());
        floaters.update(DT, view, List.of(new BattleEvent.FocusFull()));
        assertEquals(3, floaters.count());
        floaters.reset();
        assertEquals(0, floaters.count(), "in flight and waiting alike");
        assertTrue(only(new BattleEvent.FocusFull(), wideCamera()).started(), "the stagger memory is cleared too");
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
        assertTrue(fresh.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H) > 80, "in the house text colour");
        float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
        assertEquals(0, fresh.countPainted(plate), "and not over the enemy plate");

        for (int i = 0; i < 45; i++) floaters.update(DT, view, List.of());
        BattleRasterFixture late = paint(floaters);
        assertTrue(late.diff(fresh) > 300, "it has moved and begun to fade");
        assertEquals(0, late.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H), "nothing fully opaque left");

        BattleFloaters crit = new BattleFloaters();
        crit.setStage(FakeBattleView.crucibleLayout());
        crit.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 128f, DamageFlavor.CRITICAL)));
        BattleRasterFixture critical = paint(crit);
        assertTrue(critical.countPainted(0, 0, W, H) > fresh.countPainted(0, 0, W, H), "a critical is bigger");
        assertTrue(critical.countExactly(MStyle.TEXT_ACCENT, 0, 0, W, H) > 80, "and gold");
    }
}
