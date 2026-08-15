package com.stonebreak.ui.characterCreation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;

/**
 * Guards the layout geometry of {@link CharacterCreationLayout}: left/right panels
 * and footer span the full window, no two panels overlap, and tabBar/tabContent
 * stay inside the right panel.
 *
 * <p>Regression: a change to the panel ratio or footer height that makes two panels
 * overlap or slide off screen would break hit-testing without a visible rendering
 * defect catching it.
 */
class CharacterCreationLayoutTest {

    private final CharacterCreationLayout layout = new CharacterCreationLayout();

    /** Convert a production Rect to UiLayoutAssert.Rect for invariant helpers. */
    private static UiLayoutAssert.Rect toAssertRect(Rect r) {
        return new UiLayoutAssert.Rect(
            (int) Math.round(r.x()),
            (int) Math.round(r.y()),
            (int) Math.round(r.width()),
            (int) Math.round(r.height()));
    }

    // ---- positive extent at every resolution --------------------------------------------------

    @Test
    void panelsHavePositiveExtentAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect left = layout.leftPanel(size.width(), size.height());
            Rect right = layout.rightPanel(size.width(), size.height());
            Rect footer = layout.footer(size.width(), size.height());

            assertTrue(left.width() > 0 && left.height() > 0,
                size + ": leftPanel must have positive extent, got " + left);
            assertTrue(right.width() > 0 && right.height() > 0,
                size + ": rightPanel must have positive extent, got " + right);
            assertTrue(footer.width() > 0 && footer.height() > 0,
                size + ": footer must have positive extent, got " + footer);
        }
    }

    // ---- leftPanel and rightPanel do not overlap ----------------------------------------------

    @Test
    void leftAndRightPanelsDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect left = layout.leftPanel(size.width(), size.height());
            Rect right = layout.rightPanel(size.width(), size.height());

            assertFalse(toAssertRect(left).overlaps(toAssertRect(right)),
                size + ": leftPanel must not overlap rightPanel");
        }
    }

    // ---- leftPanel and footer do not overlap --------------------------------------------------

    @Test
    void leftPanelAndFooterDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect left = layout.leftPanel(size.width(), size.height());
            Rect footer = layout.footer(size.width(), size.height());

            assertFalse(toAssertRect(left).overlaps(toAssertRect(footer)),
                size + ": leftPanel must not overlap footer");
        }
    }

    // ---- rightPanel and footer do not overlap -------------------------------------------------

    @Test
    void rightPanelAndFooterDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect right = layout.rightPanel(size.width(), size.height());
            Rect footer = layout.footer(size.width(), size.height());

            assertFalse(toAssertRect(right).overlaps(toAssertRect(footer)),
                size + ": rightPanel must not overlap footer");
        }
    }

    // ---- leftPanel + rightPanel span the full width -------------------------------------------

    @Test
    void leftAndRightPanelsSpanFullWidth() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect left = layout.leftPanel(size.width(), size.height());
            Rect right = layout.rightPanel(size.width(), size.height());
            float totalWidth = left.width() + right.width();

            assertTrue(Math.abs(totalWidth - size.width()) <= 0.5f,
                size + ": leftPanel.width + rightPanel.width (" + totalWidth +
                ") must span the full width (" + size.width() + ")");
        }
    }

    // ---- leftPanel + footer span the full height ----------------------------------------------

    @Test
    void leftPanelAndFooterSpanFullHeight() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect left = layout.leftPanel(size.width(), size.height());
            Rect footer = layout.footer(size.width(), size.height());
            float totalHeight = left.height() + footer.height();

            assertTrue(Math.abs(totalHeight - size.height()) <= 0.5f,
                size + ": leftPanel.height + footer.height (" + totalHeight +
                ") must span the full height (" + size.height() + ")");
        }
    }

    // ---- all panels stay on screen ------------------------------------------------------------

    @Test
    void allPanelsStayOnScreen() {
        for (Resolutions.Size size : Resolutions.ALL) {
            int w = size.width(), h = size.height();

            UiLayoutAssert.assertOnScreen(toAssertRect(layout.leftPanel(w, h)), w, h,
                size + ": leftPanel");
            UiLayoutAssert.assertOnScreen(toAssertRect(layout.rightPanel(w, h)), w, h,
                size + ": rightPanel");
            UiLayoutAssert.assertOnScreen(toAssertRect(layout.footer(w, h)), w, h,
                size + ": footer");
        }
    }

    // ---- tabBar is contained by rightPanel ----------------------------------------------------

    @Test
    void tabBarIsContainedByRightPanel() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect right = layout.rightPanel(size.width(), size.height());
            Rect tabBar = layout.tabBar(right);

            UiLayoutAssert.assertContains(toAssertRect(right), toAssertRect(tabBar),
                size + ": tabBar must be contained by rightPanel");
        }
    }

    // ---- tabContent is contained by rightPanel ------------------------------------------------

    @Test
    void tabContentIsContainedByRightPanel() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect right = layout.rightPanel(size.width(), size.height());
            Rect tabBar = layout.tabBar(right);
            Rect tabContent = layout.tabContent(right, tabBar);

            UiLayoutAssert.assertContains(toAssertRect(right), toAssertRect(tabContent),
                size + ": tabContent must be contained by rightPanel");
        }
    }

    // ---- tabBar and tabContent do not overlap -------------------------------------------------

    @Test
    void tabBarAndTabContentDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            Rect right = layout.rightPanel(size.width(), size.height());
            Rect tabBar = layout.tabBar(right);
            Rect tabContent = layout.tabContent(right, tabBar);

            assertFalse(toAssertRect(tabBar).overlaps(toAssertRect(tabContent)),
                size + ": tabBar must not overlap tabContent");
        }
    }

    // ---- Rect.contains on its own edges -------------------------------------------------------

    @Test
    void rectContainsAtExactCorners() {
        Rect r = new Rect(10f, 20f, 100f, 50f);

        assertTrue(r.contains(10f, 20f), "top-left corner must be contained");
        assertTrue(r.contains(r.right(), 20f), "top-right corner must be contained");
        assertTrue(r.contains(10f, r.bottom()), "bottom-left corner must be contained");
        assertTrue(r.contains(r.right(), r.bottom()), "bottom-right corner must be contained");
    }

    @Test
    void rectContainsAtCenter() {
        Rect r = new Rect(10f, 20f, 100f, 50f);
        assertTrue(r.contains(r.centerX(), r.centerY()),
            "center point must be contained");
    }

    @Test
    void rectContainsRejectsJustOutsideEachSide() {
        Rect r = new Rect(10f, 20f, 100f, 50f);

        assertFalse(r.contains(9.99f, 20f), "point just left of left edge must be outside");
        assertFalse(r.contains(r.right() + 0.01f, 20f), "point just right of right edge must be outside");
        assertFalse(r.contains(10f, 19.99f), "point just above top edge must be outside");
        assertFalse(r.contains(10f, r.bottom() + 0.01f), "point just below bottom edge must be outside");
    }
}