package com.openmason.main.systems.menus.animationEditor.panels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineLayoutTest {

    private static final float EPS = 1e-4f;

    @Test
    void zoomOneShowsWholeClip() {
        TimelineLayout layout = new TimelineLayout(100f, 400f, 2f, 1f, 0f);
        assertEquals(0f, layout.visibleStart(), EPS);
        assertEquals(2f, layout.visibleEnd(), EPS);
        assertEquals(100f, layout.timeToX(0f), EPS);
        assertEquals(500f, layout.timeToX(2f), EPS);
        assertEquals(300f, layout.timeToX(1f), EPS);
    }

    @Test
    void timePixelRoundTripUnderZoomAndScroll() {
        TimelineLayout layout = new TimelineLayout(50f, 300f, 4f, 4f, 1.5f);
        // window = 1s starting at 1.5
        assertEquals(1.5f, layout.visibleStart(), EPS);
        assertEquals(2.5f, layout.visibleEnd(), EPS);
        for (float t = 1.5f; t <= 2.5f; t += 0.1f) {
            assertEquals(t, layout.xToTime(layout.timeToX(t)), EPS);
        }
    }

    @Test
    void xToTimeClampsToClip() {
        TimelineLayout layout = new TimelineLayout(0f, 100f, 1f, 1f, 0f);
        assertEquals(0f, layout.xToTime(-50f), EPS);
        assertEquals(1f, layout.xToTime(500f), EPS);
    }

    @Test
    void scrollClampsToWindow() {
        // zoom 2 on a 2s clip -> window 1s -> max scroll 1s
        assertEquals(1f, TimelineLayout.clampScroll(5f, 2f, 2f), EPS);
        assertEquals(0f, TimelineLayout.clampScroll(-1f, 2f, 2f), EPS);
        // zoom 1 -> no scroll possible
        assertEquals(0f, TimelineLayout.clampScroll(0.5f, 1f, 2f), EPS);
    }

    @Test
    void snapRoundsToFrameGrid() {
        assertEquals(0.5f, TimelineLayout.snap(0.51f, 30f), 1e-5f);
        assertEquals(0.5f, TimelineLayout.snap(0.49f, 30f), 1e-5f);
        assertEquals(0.5333333f, TimelineLayout.snap(0.52f, 30f), 1e-5f);
        // fps <= 0 -> unchanged
        assertEquals(0.517f, TimelineLayout.snap(0.517f, 0f), 1e-6f);
    }

    @Test
    void zoomAnchorKeepsTimeUnderMouse() {
        float x0 = 100f, width = 400f, duration = 4f;
        float mouseX = 300f; // middle of the bar
        TimelineLayout before = new TimelineLayout(x0, width, duration, 1f, 0f);
        float anchorTime = before.xToTime(mouseX);

        float newZoom = 2f;
        float scroll = TimelineLayout.scrollForZoomAnchor(anchorTime, mouseX, x0, width, newZoom, duration);
        TimelineLayout after = new TimelineLayout(x0, width, duration, newZoom, scroll);
        assertEquals(anchorTime, after.xToTime(mouseX), 1e-3f);
    }

    @Test
    void deltaPixelsToDeltaTimeScalesWithZoom() {
        TimelineLayout z1 = new TimelineLayout(0f, 100f, 1f, 1f, 0f);
        TimelineLayout z2 = new TimelineLayout(0f, 100f, 1f, 2f, 0f);
        assertEquals(0.5f, z1.deltaXToDeltaTime(50f), EPS);
        assertEquals(0.25f, z2.deltaXToDeltaTime(50f), EPS);
    }

    @Test
    void visibilityRespectsWindow() {
        TimelineLayout layout = new TimelineLayout(0f, 100f, 4f, 4f, 1f);
        assertTrue(layout.isTimeVisible(1f));
        assertTrue(layout.isTimeVisible(2f));
        assertFalse(layout.isTimeVisible(0.5f));
        assertFalse(layout.isTimeVisible(2.5f));
    }
}
