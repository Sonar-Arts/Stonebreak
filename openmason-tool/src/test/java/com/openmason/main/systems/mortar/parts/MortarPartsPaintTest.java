package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.MortarRasterFixture;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.core.PartStateFixtures;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.MortarType;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Mortar part painted on a CPU-raster canvas with a fixed dark theme: how the animated
 * hover/press/selected blends map to fills, insets, lifts and accent bars, and the layout
 * guarantees (centred pills, trailing labels that never collide with ellipsized titles, rules
 * that start after their label). Probes sit in part interiors so anti-aliased edges and glyphs
 * never decide a result.
 */
class MortarPartsPaintTest {

    private static final int CLEAR = MortarRasterFixture.CLEAR;

    private MortarRasterFixture fx;
    private MortarPainter g;
    private MortarTheme t;

    @BeforeEach
    void setUp() {
        fx = new MortarRasterFixture(240, 110);
        g = fx.painter;
        t = fx.theme;
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private static PartState rest() {
        return PartStateFixtures.rest();
    }

    private static PartState hovered() {
        return PartStateFixtures.settled(true, false, false);
    }

    private static PartState pressed() {
        return PartStateFixtures.settled(true, true, false);
    }

    private static PartState selected() {
        return PartStateFixtures.settled(false, false, true);
    }

    // ---- button: (20,20) 120x32; probe (24,36) is inside the straight left edge, clear of the label

    private void paintButton(MortarButton b, PartState s) {
        fx.clear();
        b.paint(g, 20, 20, 120, 32, s);
    }

    @Test
    void primaryButtonFillsWithAccentAndBrightensOnHover() {
        MortarButton b = new MortarButton("OK", MortarButton.Variant.PRIMARY);
        paintButton(b, rest());
        assertEquals(t.accent, fx.at(24, 36));
        assertEquals(t.accent, fx.at(20, 36), "at rest the fill reaches the rect edge");

        paintButton(b, hovered());
        assertEquals(t.accentHover, fx.at(24, 36));
    }

    @Test
    void pressedButtonSettlesOnePixelInAndDarkens() {
        MortarButton b = new MortarButton("OK", MortarButton.Variant.PRIMARY);
        paintButton(b, pressed());
        assertEquals(Argb.shade(t.accentHover, -0.10f), fx.at(24, 36));
        assertEquals(CLEAR, fx.at(20, 36), "the 1 px press inset leaves the outer column empty");
        assertEquals(CLEAR, fx.at(80, 20), "and the outer row");
    }

    @Test
    void secondaryButtonIsASurfaceWithAHairlineBorder() {
        MortarButton b = new MortarButton("Cancel", MortarButton.Variant.SECONDARY);
        paintButton(b, rest());
        assertEquals(t.surface, fx.at(24, 36));
        assertEquals(t.border, fx.at(20, 36));

        paintButton(b, hovered());
        assertEquals(t.surfaceHover, fx.at(24, 36));
    }

    @Test
    void disabledButtonIsWashedOutAndIgnoresHoverAndPress() {
        MortarButton b = new MortarButton("OK", MortarButton.Variant.PRIMARY, false);
        assertFalse(b.isEnabled());
        paintButton(b, rest());
        int[] atRest = fx.snapshot();
        int probe = fx.at(24, 36);
        assertEquals(128, probe >>> 24, 1, "half-alpha surface");
        assertTrue(MortarRasterFixture.nearRgb(t.surface, probe, 2));
        assertNotEquals(t.accent, probe, "a disabled primary loses its accent");

        paintButton(b, pressed());
        assertArrayEquals(atRest, fx.snapshot(), "hover/press do not change a disabled button");
    }

    // ---- card: (20,20) 150x50; title/description start at (34,34), so (150,45) is bare fill

    private void paintCard(PartState s) {
        fx.clear();
        new MortarCard("Title", "desc", "footer").paint(g, 20, 20, 150, 50, s);
    }

    @Test
    void restCardHasNoLiftAndNoShadow() {
        paintCard(rest());
        assertEquals(t.surface, fx.at(150, 45));
        assertEquals(CLEAR, fx.at(95, 19), "nothing above the card's own rect");
        assertEquals(CLEAR, fx.at(95, 76), "no shadow at rest");
    }

    @Test
    void hoveredCardLiftsTwoPixelsOverASoftShadow() {
        paintCard(hovered());
        assertEquals(t.surfaceHover, fx.at(150, 45));
        assertEquals(t.surfaceHover, fx.at(95, 19), "the card body moved up 2 px");
        assertTrue(fx.alphaAt(95, 76) > 0, "a shadow appears below the card");
    }

    @Test
    void pressedCardDarkensTheHoverFill() {
        paintCard(pressed());
        assertEquals(Argb.shade(t.surfaceHover, -0.05f), fx.at(150, 45));
    }

    @Test
    void selectedCardTintsItsBorderTowardTheAccent() {
        paintCard(rest());
        assertEquals(t.border, fx.at(20, 45));
        paintCard(selected());
        int expected = MortarRasterFixture.over(t.surface, t.borderStrong);
        assertTrue(MortarRasterFixture.near(expected, fx.at(20, 45), 2),
                Integer.toHexString(fx.at(20, 45)) + " vs " + Integer.toHexString(expected));
    }

    // ---- nav item: (20,20) 150x32; bar column x=25, a full bar spans y 27..45, a hover bar 31.15..42.85

    private void paintNav(PartState s) {
        fx.clear();
        new MortarNavItem("Home").paint(g, 20, 20, 150, 32, s);
    }

    @Test
    void restNavRowIsFlat() {
        paintNav(rest());
        assertEquals(CLEAR, fx.at(25, 36), "no accent bar");
        assertEquals(CLEAR, fx.at(160, 36), "no background tint");
    }

    @Test
    void selectedNavRowShowsAFullAccentBarOverAFaintTint() {
        paintNav(selected());
        assertEquals(t.accent, fx.at(25, 29), "rows 29..42 are the bar's straight run (1.5 px corners)");
        assertEquals(t.accent, fx.at(25, 42));
        int bg = fx.at(160, 36);
        assertEquals(128, bg >>> 24, 1);
        assertTrue(MortarRasterFixture.nearRgb(t.surfaceHover, bg, 2));
    }

    @Test
    void hoveredNavRowGrowsAShorterTranslucentBar() {
        paintNav(hovered());
        assertTrue(MortarRasterFixture.nearRgb(t.surfaceHover, fx.at(25, 29), 2),
                "row 29 (inside a selected bar) is past a hover bar's reach: only the tint");
        assertNotEquals(t.accent, fx.at(25, 36), "the hover bar is half-alpha");
        assertFalse(MortarRasterFixture.nearRgb(t.surfaceHover, fx.at(25, 36), 2), "but present");
    }

    // ---- list row: (10,10) 220x40

    @Test
    void selectedListRowAddsALeadingAccentBar() {
        MortarListRow row = new MortarListRow("Project", "~/projects", "5m");
        row.paint(g, 10, 10, 220, 40, rest());
        assertEquals(t.surface, fx.at(15, 30));

        fx.clear();
        row.paint(g, 10, 10, 220, 40, selected());
        assertEquals(t.accent, fx.at(15, 30));
    }

    @Test
    void longListRowTitlesStopShortOfTheTrailingLabel() {
        String trailing = "12 minutes ago";
        MortarListRow row = new MortarListRow(
                "An extraordinarily long project title that cannot possibly fit",
                "/a/very/long/path/to/the/project/directory/that/overflows/too",
                trailing);
        row.paint(g, 10, 10, 220, 40, rest());

        float trailW = g.measureWidth(trailing, Weight.REGULAR, MortarType.LABEL);
        float trailLeft = 10 + 220 - 14 - trailW;
        float titleRight = trailLeft - 16; // TRAIL_GAP
        int from = (int) Math.ceil(titleRight) + 1;
        int to = (int) Math.floor(trailLeft) - 2;
        assertTrue(to > from, "the probe band is non-empty");
        for (int y = 12; y < 48; y++) {
            for (int x = from; x <= to; x++) {
                assertEquals(t.surface, fx.at(x, y), "text in the trailing gap at " + x + "," + y);
            }
        }
        assertTrue(paintedOtherThan(t.surface, (int) trailLeft, 12, 216, 48), "trailing label drawn");
        assertTrue(paintedOtherThan(t.surface, 24, 12, from - 1, 48), "title drawn");
    }

    private boolean paintedOtherThan(int fill, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (fx.at(x, y) != fill) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- badge

    @Test
    void badgeWidthIsTextPlusPadding() {
        float textW = g.measureWidth("TAG", Weight.MEDIUM, MortarType.CAPTION);
        assertEquals(textW + 16f, MortarBadge.measureWidth(g, "TAG"), 1e-4f);
        assertEquals(MortarBadge.measureWidth(g, "TAG"), MortarBadge.paint(g, 10, 40, "TAG"), 1e-4f);
    }

    @Test
    void standaloneBadgeIsCentredInItsRect() {
        new MortarBadge("TAG").paint(g, 0, 20, 240, 40, rest());
        float pillW = MortarBadge.measureWidth(g, "TAG");
        float left = (240 - pillW) / 2f;
        float right = left + pillW;
        assertEquals(t.badgeBg, fx.at((int) Math.ceil(left) + 2, 40));
        assertEquals(t.badgeBg, fx.at((int) Math.floor(right) - 3, 40));
        assertEquals(CLEAR, fx.at((int) Math.floor(left) - 2, 40));
        assertEquals(CLEAR, fx.at((int) Math.ceil(right) + 1, 40));
        assertEquals(CLEAR, fx.at(120, 29), "18 px tall around the centre line");
        assertEquals(CLEAR, fx.at(120, 50));
    }

    @Test
    void emptyBadgePaintsNothing() {
        assertEquals(0f, MortarBadge.paint(g, 10, 40, null));
        assertEquals(0f, MortarBadge.paint(g, 10, 40, ""));
        new MortarBadge("").paint(g, 0, 0, 240, 40, rest());
        assertArrayEquals(new int[240 * 110], fx.snapshot());
    }

    // ---- icon button: (20,20) 32x32; probe (23,36) is inside the left edge, clear of the glyph

    @Test
    void iconButtonFillAlphaFollowsHoverAndPress() {
        MortarIconButton b = new MortarIconButton("+");
        b.paint(g, 20, 20, 32, 32, rest());
        assertEquals(Math.round(0.4f * 255), fx.alphaAt(23, 36), 1);
        assertTrue(MortarRasterFixture.nearRgb(t.surfaceHover, fx.at(23, 36), 2));

        fx.clear();
        b.paint(g, 20, 20, 32, 32, hovered());
        assertEquals(Math.round(0.9f * 255), fx.alphaAt(23, 36), 1);

        fx.clear();
        b.paint(g, 20, 20, 32, 32, pressed());
        assertEquals(Math.round(0.8f * 255), fx.alphaAt(23, 36), 1);
    }

    // ---- rules at y=0, h=11: the 1 px line lands exactly on row 5

    @Test
    void separatorIsAOnePixelRuleOnTheCentreLine() {
        new MortarSeparator(false).paint(g, 10, 0, 100, 11, rest());
        assertEquals(t.separator, fx.at(10, 5));
        assertEquals(t.separator, fx.at(109, 5));
        assertEquals(CLEAR, fx.at(110, 5));
        assertEquals(CLEAR, fx.at(50, 4));
        assertEquals(CLEAR, fx.at(50, 6));
    }

    @Test
    void accentTickLeadsTheRuleAndClampsToItsWidth() {
        new MortarSeparator(true).paint(g, 10, 0, 100, 11, rest());
        assertEquals(t.accent, fx.at(10, 5));
        assertEquals(t.accent, fx.at(25, 5));
        assertEquals(t.separator, fx.at(26, 5), "the tick is 16 px");

        fx.clear();
        new MortarSeparator(true).paint(g, 10, 0, 8, 11, rest());
        assertEquals(t.accent, fx.at(17, 5));
        assertEquals(CLEAR, fx.at(18, 5), "a narrow rule never grows past its rect");
    }

    @Test
    void sectionRuleStartsAfterTheLabelAndGap() {
        new MortarSectionLabel(null).paint(g, 10, 0, 200, 11, rest());
        assertEquals(CLEAR, fx.at(19, 5), "an empty label still keeps the 10 px gap");
        assertEquals(t.separator, fx.at(20, 5));
        assertEquals(t.separator, fx.at(209, 5));
        assertEquals(CLEAR, fx.at(210, 5));

        fx.clear();
        new MortarSectionLabel("recent").paint(g, 10, 0, 200, 11, rest());
        float labelW = g.measureWidth("RECENT", Weight.BOLD, MortarType.CAPTION);
        float lineX = 10 + labelW + 10;
        assertEquals(t.separator, fx.at((int) Math.ceil(lineX) + 1, 5));
        assertEquals(CLEAR, fx.at((int) Math.floor(lineX) - 3, 5), "the gap before the rule is empty");
    }
}
