package com.stonebreak.ui.glossaryScreen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glossary's "nothing ever spills" contract. The previous screen
 * hard-coded a 3-column card grid, so the fourth glossary entity rendered
 * off the panel and card content overran the fixed card height — this suite
 * pins the property that broke: every rect nests inside its container, the
 * panes never overlap, and the guarantees hold across window sizes and UI
 * scales, including hostile combinations (small window × large scale).
 */
class GlossaryLayoutTest {

    private static final int[][] WINDOWS = {{1920, 1080}, {1600, 900}, {1280, 720}, {1024, 600}};
    private static final float[] SCALES = {1f, 1.25f, 1.5f, 2f};
    private static final float EPS = 0.5f;

    private static void assertInside(String what, float[] inner, float[] outer) {
        assertTrue(inner[0] >= outer[0] - EPS
                        && inner[1] >= outer[1] - EPS
                        && inner[0] + inner[2] <= outer[0] + outer[2] + EPS
                        && inner[1] + inner[3] <= outer[1] + outer[3] + EPS,
                what + " must nest inside its container");
    }

    @Test
    void everyRectNestsInItsContainerAtAllSizesAndScales() {
        for (int[] win : WINDOWS) {
            for (float s : SCALES) {
                int w = win[0], h = win[1];
                float[] window = {0, 0, w, h};
                float[] panel = GlossaryLayout.panelRect(w, h, s);
                float[] content = GlossaryLayout.contentRect(w, h, s);
                float[] sidebar = GlossaryLayout.sidebarRect(w, h, s);
                float[] detail = GlossaryLayout.detailRect(w, h, s);
                float[] preview = GlossaryLayout.previewRect(w, h, s);
                float[] back = GlossaryLayout.backButtonRect(w, h, s);

                assertInside("panel", panel, window);
                assertInside("content", content, panel);
                assertInside("sidebar", sidebar, content);
                assertInside("detail", detail, content);
                assertInside("preview", preview, detail);
                assertInside("back button", back, panel);
                assertInside("left arrow", GlossaryLayout.leftArrowRect(w, h, s), preview);
                assertInside("right arrow", GlossaryLayout.rightArrowRect(w, h, s), preview);

                for (int i = 0; i < GlossaryLayout.rowCount(); i++) {
                    assertInside("list row " + i, GlossaryLayout.listRowRect(i, w, h, s), sidebar);
                }
            }
        }
    }

    @Test
    void sidebarAndDetailNeverOverlap() {
        for (int[] win : WINDOWS) {
            for (float s : SCALES) {
                float[] sidebar = GlossaryLayout.sidebarRect(win[0], win[1], s);
                float[] detail = GlossaryLayout.detailRect(win[0], win[1], s);
                assertTrue(sidebar[0] + sidebar[2] <= detail[0] + EPS,
                        "the detail pane must start right of the sidebar");
            }
        }
    }

    @Test
    void backButtonSitsBelowTheContent() {
        for (int[] win : WINDOWS) {
            for (float s : SCALES) {
                float[] content = GlossaryLayout.contentRect(win[0], win[1], s);
                float[] back = GlossaryLayout.backButtonRect(win[0], win[1], s);
                assertTrue(content[1] + content[3] <= back[1] + EPS,
                        "content must end before the back button begins");
            }
        }
    }

    @Test
    void listRowsCompressRatherThanOverflow() {
        // Hostile case: small window, huge scale. Full-height rows cannot fit,
        // so the row height must shrink until they do — never spill.
        float rowH = GlossaryLayout.listRowHeight(1024, 600, 2f);
        assertTrue(rowH > 0f, "rows must stay drawable");
        assertTrue(rowH < 56f * 2f, "rows must compress below the design height here");

        int last = GlossaryLayout.rowCount() - 1;
        float[] sidebar = GlossaryLayout.sidebarRect(1024, 600, 2f);
        float[] lastRow = GlossaryLayout.listRowRect(last, 1024, 600, 2f);
        assertTrue(lastRow[1] + lastRow[3] <= sidebar[1] + sidebar[3] + EPS,
                "the final row must still end inside the sidebar");
    }

    @Test
    void rowsDoNotOverlapEachOther() {
        for (int i = 0; i + 1 < GlossaryLayout.rowCount(); i++) {
            float[] a = GlossaryLayout.listRowRect(i, 1920, 1080, 1f);
            float[] b = GlossaryLayout.listRowRect(i + 1, 1920, 1080, 1f);
            assertTrue(a[1] + a[3] <= b[1] + EPS, "row " + i + " must end before row " + (i + 1));
        }
    }

    @Test
    void arrowsDoNotCollideOnANarrowPreview() {
        for (int[] win : WINDOWS) {
            for (float s : SCALES) {
                float[] left = GlossaryLayout.leftArrowRect(win[0], win[1], s);
                float[] right = GlossaryLayout.rightArrowRect(win[0], win[1], s);
                assertTrue(left[0] + left[2] < right[0],
                        "the cycler arrows must never overlap");
            }
        }
    }

    @Test
    void hitTestMatchesRectEdges() {
        float[] r = {10f, 20f, 30f, 40f};
        assertTrue(GlossaryLayout.contains(10f, 20f, r), "top-left corner is inside");
        assertTrue(GlossaryLayout.contains(40f, 60f, r), "bottom-right corner is inside");
        assertEquals(false, GlossaryLayout.contains(41f, 30f, r));
        assertEquals(false, GlossaryLayout.contains(20f, 61f, r));
    }
}
