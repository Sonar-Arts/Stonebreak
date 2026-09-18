package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E15: flash → radial wipe → name card over the intro, and a clean exit when the intro is skipped. */
class EncounterTransitionTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;
    private static final float SCALE = FocusBattleLayout.effectiveScale(W, H, UI);

    private FakeBattleView view;
    private EncounterTransition transition;

    @BeforeEach
    void freshTransition() {
        view = new FakeBattleView();
        view.phase = BattlePhase.INTRO;
        transition = new EncounterTransition();
    }

    private void run(float seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) transition.update(DT, view);
    }

    private BattleRasterFixture paint() {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        transition.paint(fx.ui, fx.canvas, W, H, FocusBattleLayout.effectiveScale(W, H, UI), UI, view, null, null);
        return fx;
    }

    @Test
    void itOpensOnAFullWhiteFlashThenIrisesTheArenaIn() {
        transition.update(0f, view);
        assertEquals(1f, transition.flashAlpha(), 1e-6f);
        BattleRasterFixture first = paint();
        assertEquals(W * H, first.countExactly(EncounterTransition.FLASH_COLOR, 0, 0, W, H),
                "the first frame is one wash of the house white");
        assertEquals(MStyle.TEXT_PRIMARY, EncounterTransition.FLASH_COLOR);

        run(0.4f);
        assertEquals(0f, transition.flashAlpha(), 0f, "the flash is over");
        assertTrue(transition.wipeVisible());
        BattleRasterFixture mid = paint();
        int centre = mid.countPainted(W / 2 - 20, H / 2 - 20, W / 2 + 20, H / 2 + 20);
        int corner = mid.countPainted(0, 0, 40, 40);
        assertEquals(0, centre, "the iris has opened in the middle");
        assertEquals(1600, corner, "while the corners are still covered");

        run(0.2f);
        BattleRasterFixture later = paint();
        assertTrue(later.countPainted(0, 0, W, H) < mid.countPainted(0, 0, W, H), "and it keeps opening");

        run(0.3f);
        assertFalse(transition.wipeVisible(), "done by " + EncounterTransition.WIPE_SECONDS + " s");
        float[] card = EncounterTransition.nameCardRect(W, H, SCALE);
        assertEquals(0, paint().countPainted(0, 0, W, (int) card[1]), "the arena is clear above the card band");
    }

    @Test
    void theNameCardAppearsForTheArchonHeroShotAndFadesOut() {
        run(EncounterTransition.CARD_IN_SECONDS - 0.1f);
        assertEquals(0f, transition.cardAlpha(), 0f, "not before the hero shot");

        run(0.1f + EncounterTransition.CARD_FADE_IN_SECONDS + 0.1f);
        assertEquals(1f, transition.cardAlpha(), 1e-4f);
        float[] card = EncounterTransition.nameCardRect(W, H, SCALE);
        BattleRasterFixture shown = paint();
        assertTrue(shown.countPainted(card) > card[2] * card[3] * 0.95f, "a HUD frame fills the card");
        assertTrue(shown.countExactly(MStyle.HUD_BORDER, (int) card[0], (int) card[1], (int) (card[0] + card[2]),
                (int) (card[1] + card[3])) > 500, "with the house near-black border");
        assertTrue(shown.countExactly(MStyle.TEXT_ACCENT, (int) card[0], (int) card[1], (int) (card[0] + card[2]),
                (int) (card[1] + card[3] * 0.6f)) > 1500, "large gold title glyphs");
        assertTrue(shown.countExactly(BattlePalette.ACCENT_ARCHON, (int) card[0], (int) (card[1] + card[3] * 0.55f),
                (int) (card[0] + card[2]), (int) (card[1] + card[3] * 0.75f)) > 200, "the rule in the Archon's accent");

        view.archon.displayName = "Frost Warden";
        assertTrue(paint().diff(shown, card) > 500, "the title is the enemy's name");
        view.archon.displayName = "Ice Archon";

        run(EncounterTransition.CARD_OUT_SECONDS - EncounterTransition.CARD_IN_SECONDS - 0.6f);
        assertTrue(transition.cardAlpha() > 0f && transition.cardAlpha() < 1f, "fading out");
        assertTrue(paint().diff(shown, card) > 2000);
        run(0.3f);
        assertEquals(0f, transition.cardAlpha(), 0f);
        assertEquals(0, paint().countPainted(card), "gone by " + EncounterTransition.CARD_OUT_SECONDS + " s");
    }

    @Test
    void theCardSitsAboveTheBottomLetterboxAndBelowTheEnemyPlate() {
        int[][] windows = {{1024, 600}, {1280, 720}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            float[] card = EncounterTransition.nameCardRect(win[0], win[1],
                    FocusBattleLayout.effectiveScale(win[0], win[1], UI));
            float[] bar = FocusBattleLayout.letterboxBottomRect(win[0], win[1], 1f);
            float[] plate = FocusBattleLayout.enemyPlateRect(win[0], win[1], UI);
            assertTrue(card[1] + card[3] <= bar[1], win[0] + "x" + win[1] + " clear of the letterbox");
            assertTrue(card[1] >= plate[1] + plate[3], win[0] + "x" + win[1] + " clear of the enemy plate");
            assertTrue(card[0] >= 0f && card[0] + card[2] <= win[0]);
        }
    }

    @Test
    void skippingTheIntroRemovesEverythingCleanly() {
        run(EncounterTransition.CARD_IN_SECONDS + EncounterTransition.CARD_FADE_IN_SECONDS);
        assertTrue(transition.cardAlpha() > 0.9f);

        view.phase = BattlePhase.RUNNING;   // the player pressed confirm
        transition.update(DT, view);
        float fading = transition.cardAlpha();
        assertTrue(fading < 0.95f, "the card starts leaving on the very next frame");
        run(EncounterTransition.SKIP_FADE_SECONDS + DT);
        assertFalse(transition.active());
        assertEquals(0f, transition.cardAlpha(), 0f);
        assertEquals(0, paint().countPainted(0, 0, W, H), "nothing of the transition is left on screen");

        run(3f);
        assertFalse(transition.active(), "and the card's time window passing later does not bring it back");
        assertEquals(0, paint().countPainted(0, 0, W, H));
    }

    @Test
    void skippingDuringTheWipeDropsTheWipeToo() {
        run(0.2f);
        assertTrue(transition.wipeVisible());
        view.phase = BattlePhase.RUNNING;
        run(EncounterTransition.SKIP_FADE_SECONDS + 2 * DT);
        assertFalse(transition.wipeVisible());
        assertEquals(0f, transition.flashAlpha(), 0f);
        assertEquals(0, paint().countPainted(0, 0, W, H));
    }

    @Test
    void aBattleFirstSeenOutsideItsIntroHasNoTransition() {
        view.phase = BattlePhase.RUNNING;
        transition.update(DT, view);
        assertFalse(transition.active());
        assertEquals(0, paint().countPainted(0, 0, W, H));
    }

    @Test
    void resetReplaysItForARetry() {
        run(5f);
        view.phase = BattlePhase.RUNNING;
        run(1f);
        assertFalse(transition.active());

        transition.reset();
        view.phase = BattlePhase.INTRO;
        transition.update(0f, view);
        assertTrue(transition.active());
        assertEquals(1f, transition.flashAlpha(), 1e-6f, "the retry opens on the flash again");
    }

    @Test
    void theSkipHintShowsOnceTheWipeIsDone() {
        run(1.0f);
        float[] row = EncounterTransition.skipHintRect(W, H, SCALE);
        float[] bar = FocusBattleLayout.letterboxBottomRect(W, H, 1f);
        assertEquals(bar[1], row[1], 0f, "the help strip is slid out during the intro, so the hint lives in the bottom bar");
        assertEquals(bar[3], row[3], 0f);
        BattleRasterFixture fx = paint();
        int right = (int) (row[0] + row[2]);
        assertTrue(fx.countPainted(right - 160, (int) row[1], right + 2, (int) (row[1] + row[3])) > 400,
                "a keycap and its label, right-aligned");
        assertEquals(0, fx.countPainted(0, 0, W, (int) row[1]), "and nothing else");
        assertTrue(fx.countExactly(MStyle.BUTTON_BORDER, right - 160, (int) row[1], right + 2, (int) (row[1] + row[3])) > 30,
                "the keycap is a house button surface");
    }
}
