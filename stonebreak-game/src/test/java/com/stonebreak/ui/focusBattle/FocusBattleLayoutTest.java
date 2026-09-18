package com.stonebreak.ui.focusBattle;

import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;
import com.stonebreak.ui.support.UiLayoutAssert.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The battle HUD's "nothing spills, nothing collides" contract, swept over every reference
 * resolution and the whole UI-scale range — including the hostile corner (1024x600 at scale 2.0)
 * where the unscaled design would need twice the window.
 */
class FocusBattleLayoutTest {

    private static final float[] SCALES = {0.75f, 1f, 1.5f, 2f};

    /** Outward-rounded integer rect: if these do not overlap, the float rects cannot either. */
    private static Rect rect(float[] r) {
        int x = (int) Math.floor(r[0]);
        int y = (int) Math.floor(r[1]);
        return new Rect(x, y, (int) Math.ceil(r[0] + r[2]) - x, (int) Math.ceil(r[1] + r[3]) - y);
    }

    private interface Case {
        void check(int w, int h, float scale, String context);
    }

    private static void sweep(Case c) {
        for (Resolutions.Size size : Resolutions.ALL) {
            for (float scale : SCALES) {
                c.check(size.width(), size.height(), scale, size + " @" + scale);
            }
        }
    }

    private static List<float[]> hudRects(int w, int h, float s) {
        List<float[]> rects = new ArrayList<>();
        rects.add(FocusBattleLayout.commandWindowRect(w, h, s));   // E1
        rects.add(FocusBattleLayout.submenuRect(w, h, s));         // E2
        rects.add(FocusBattleLayout.partyWindowRect(w, h, s));     // E3
        rects.add(FocusBattleLayout.helpStripRect(w, h, s));       // E4
        rects.add(FocusBattleLayout.enemyPlateRect(w, h, s));      // E5
        rects.add(FocusBattleLayout.actionBannerRect(w, h, s));    // E7
        rects.add(FocusBattleLayout.modeTagRect(w, h, s));         // E13
        rects.add(FocusBattleLayout.comboStripRect(w, h, s));      // E10 (reserved)
        return rects;
    }

    @Test
    void everyElementIsOnScreenWithPositiveSize() {
        sweep((w, h, s, context) -> {
            List<float[]> rects = hudRects(w, h, s);
            rects.add(FocusBattleLayout.resultPanelRect(w, h, s));
            rects.add(FocusBattleLayout.letterboxTopRect(w, h, 1f));
            rects.add(FocusBattleLayout.letterboxBottomRect(w, h, 1f));
            for (int i = 0; i < rects.size(); i++) {
                UiLayoutAssert.assertPositiveSize(rect(rects.get(i)), context + " element " + i);
                UiLayoutAssert.assertOnScreen(rect(rects.get(i)), w, h, context + " element " + i);
            }
        });
    }

    @Test
    void hudElementsNeverOverlap() {
        sweep((w, h, s, context) -> {
            List<Rect> rects = new ArrayList<>();
            for (float[] r : hudRects(w, h, s)) rects.add(rect(r));
            UiLayoutAssert.assertNoOverlap(rects, context);
        });
    }

    @Test
    void commandRowsNestInTheCommandWindowInOrder() {
        sweep((w, h, s, context) -> {
            Rect window = rect(FocusBattleLayout.commandWindowRect(w, h, s));
            List<Rect> rows = new ArrayList<>();
            for (int i = 0; i < FocusBattleLayout.commandRowCount(); i++) {
                Rect row = rect(FocusBattleLayout.commandRowRect(i, w, h, s));
                UiLayoutAssert.assertPositiveSize(row, context + " command row " + i);
                UiLayoutAssert.assertContains(window, row, context + " command row " + i);
                rows.add(row);
            }
            assertEquals(6, rows.size(), "the root menu has six rows");
            UiLayoutAssert.assertOrderedVertically(rows, context);
            UiLayoutAssert.assertNoOverlap(rows, context + " command rows");
        });
    }

    @Test
    void submenuRowsNestInTheSubmenuInOrder() {
        sweep((w, h, s, context) -> {
            Rect window = rect(FocusBattleLayout.submenuRect(w, h, s));
            List<Rect> rows = new ArrayList<>();
            for (int i = 0; i < FocusBattleLayout.submenuRowCount(); i++) {
                Rect row = rect(FocusBattleLayout.submenuRowRect(i, w, h, s));
                UiLayoutAssert.assertPositiveSize(row, context + " submenu row " + i);
                UiLayoutAssert.assertContains(window, row, context + " submenu row " + i);
                rows.add(row);
            }
            UiLayoutAssert.assertOrderedVertically(rows, context);
            UiLayoutAssert.assertNoOverlap(rows, context + " submenu rows");
        });
    }

    @Test
    void theSubmenuOpensBesideTheQiArtsRow() {
        sweep((w, h, s, context) -> {
            float[] cmd = FocusBattleLayout.commandWindowRect(w, h, s);
            float[] sub = FocusBattleLayout.submenuRect(w, h, s);
            float[] opener = FocusBattleLayout.commandRowRect(FocusBattleLayout.qiArtsRowIndex(), w, h, s);
            float[] first = FocusBattleLayout.submenuRowRect(0, w, h, s);
            assertTrue(sub[0] >= cmd[0] + cmd[2], context + ": the submenu attaches to E1's right edge");
            assertTrue(Math.abs(first[1] - opener[1]) <= 2f,
                    context + ": its first row is level with the Qi Arts row");
        });
    }

    @Test
    void theEnemyGaugeNestsInTheEnemyPlate() {
        sweep((w, h, s, context) -> {
            float[] plate = FocusBattleLayout.enemyPlateRect(w, h, s);
            float k = FocusBattleLayout.effectiveScale(w, h, s);
            Rect outer = rect(plate);
            float[] gauge = FocusBattleLayout.enemyGaugeRect(w, h, s);
            UiLayoutAssert.assertPositiveSize(rect(gauge), context + " E6");
            UiLayoutAssert.assertContains(outer, rect(gauge), context + " E6");
            UiLayoutAssert.assertContains(outer, rect(FocusBattleLayout.enemyNameRect(plate, k)), context + " name");
            UiLayoutAssert.assertContains(outer, rect(FocusBattleLayout.enemyHpBarRect(plate, k)), context + " hp");
            UiLayoutAssert.assertNoOverlap(List.of(rect(FocusBattleLayout.enemyNameRect(plate, k)),
                    rect(FocusBattleLayout.enemyHpBarRect(plate, k)), rect(gauge)), context + " plate lines");
            for (boolean telegraph : new boolean[]{false, true}) {
                UiLayoutAssert.assertContains(rect(gauge), rect(FocusBattleLayout.gaugeBarRect(gauge, telegraph)),
                        context + " gauge bar");
            }
        });
    }

    @Test
    void partyCellsNestInThePartyWindowAndDoNotCollide() {
        sweep((w, h, s, context) -> {
            float[] party = FocusBattleLayout.partyWindowRect(w, h, s);
            float k = FocusBattleLayout.effectiveScale(w, h, s);
            List<Rect> rows = new ArrayList<>();
            for (int row = 0; row < FocusBattleLayout.PARTY_ROWS; row++) {
                Rect full = rect(FocusBattleLayout.partyRowRect(party, row, k));
                UiLayoutAssert.assertContains(rect(party), full, context + " party row " + row);
                rows.add(full);
                float[] label = FocusBattleLayout.partyLabelRect(party, row, k);
                float[] bar = FocusBattleLayout.partyBarRect(party, row, k);
                float[] value = FocusBattleLayout.partyValueRect(party, row, k);
                assertTrue(label[0] + label[2] <= bar[0] + 0.01f && bar[0] + bar[2] <= value[0] + 0.01f,
                        context + ": caption, gauge and value columns run left to right without overlap");
                assertTrue(bar[2] > label[2], context + ": the gauge is the widest column");
            }
            UiLayoutAssert.assertOrderedVertically(rows, context);
        });
    }

    @Test
    void theHelpStripSitsAboveTheBottomWindowsAndTheComboStripAboveIt() {
        sweep((w, h, s, context) -> {
            float[] help = FocusBattleLayout.helpStripRect(w, h, s);
            float[] cmd = FocusBattleLayout.commandWindowRect(w, h, s);
            float[] party = FocusBattleLayout.partyWindowRect(w, h, s);
            float[] combo = FocusBattleLayout.comboStripRect(w, h, s);
            float[] banner = FocusBattleLayout.actionBannerRect(w, h, s);
            assertTrue(help[1] + help[3] <= Math.min(cmd[1], party[1]), context + ": help strip above E1/E3");
            assertTrue(combo[1] + combo[3] <= help[1], context + ": combo strip above the help strip");
            assertTrue(banner[1] + banner[3] <= combo[1], context + ": the stage stays clear between them");
        });
    }

    @Test
    void theScaleGrowsWithTheWindowAndIsCappedToFit() {
        // Authored for 1280x720; larger windows scale the HUD up on top of the UI scale.
        assertEquals(1f, FocusBattleLayout.effectiveScale(1280, 720, 1f), 1e-6f);
        assertEquals(1.5f, FocusBattleLayout.effectiveScale(1920, 1080, 1f), 1e-6f);
        assertEquals(3f, FocusBattleLayout.effectiveScale(3840, 2160, 1f), 1e-6f);
        // ...but never past what fits the window.
        assertEquals(1920f / FocusBattleLayout.REF_W, FocusBattleLayout.effectiveScale(1920, 1080, 1.5f), 1e-6f);
        assertTrue(FocusBattleLayout.effectiveScale(1024, 600, 2f) < 1.1f,
                "a 2.0 scale cannot fit a 1024x600 window and must be capped");
    }

    @Test
    void rowsCompressRatherThanOverflowAShortWindow() {
        float[] squat = {20f, 20f, 240f, 110f};   // far too short for six 30px rows
        float rowH = FocusBattleLayout.rowHeight(squat, 6, 1f);
        assertTrue(rowH > 0f && rowH < 30f, "rows compress below the design height, was " + rowH);
        float[] last = FocusBattleLayout.rowRect(squat, 5, 6, 1f);
        assertTrue(last[1] + last[3] <= squat[1] + squat[3], "the last row still ends inside the window");
        float[] first = FocusBattleLayout.rowRect(squat, 0, 6, 1f);
        assertTrue(first[1] >= squat[1], "the first row starts inside the window");
    }

    @Test
    void letterboxBarsScaleWithTheirAmount() {
        assertEquals(0f, FocusBattleLayout.letterboxTopRect(1920, 1080, 0f)[3], 1e-6f);
        float[] top = FocusBattleLayout.letterboxTopRect(1920, 1080, 1f);
        float[] bottom = FocusBattleLayout.letterboxBottomRect(1920, 1080, 1f);
        assertTrue(top[3] > 0f && top[3] == bottom[3], "bars are symmetric");
        assertEquals(1080f, bottom[1] + bottom[3], 1e-6f, "the bottom bar is flush with the window");
    }

    @Test
    void hitTestMatchesRectEdges() {
        float[] r = {10f, 20f, 30f, 40f};
        assertTrue(FocusBattleLayout.contains(10f, 20f, r), "top-left corner is inside");
        assertTrue(FocusBattleLayout.contains(40f, 60f, r), "bottom-right corner is inside");
        assertFalse(FocusBattleLayout.contains(41f, 30f, r));
        assertFalse(FocusBattleLayout.contains(20f, 61f, r));
    }
}
