package com.stonebreak.ui.crosshair;

import com.stonebreak.ui.runtime.screens.ScreenPresentation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crosshair's document opens and closes with its visibility, on changes only (#301). */
class CrosshairPresentationTest {

    private static final class Recording implements ScreenPresentation {
        final List<String> calls = new ArrayList<>();
        boolean paints = true;

        @Override public void shown() { calls.add("shown"); }
        @Override public void hidden() { calls.add("hidden"); }
        @Override public boolean paint(int w, int h) { calls.add("paint"); return paints; }
        @Override public boolean showing() { return paints; }
    }

    @Test
    void showsOnlyWhileVisibleAndReportsWhenTheDocumentDoesNotPaint() {
        Crosshair crosshair = new Crosshair();
        Recording doc = new Recording();
        crosshair.setPresentation(doc);
        assertFalse(crosshair.paint(1920, 1080), "hidden: nothing paints");

        crosshair.sync(true);
        crosshair.sync(true);
        assertTrue(crosshair.paint(1920, 1080));
        crosshair.sync(false);
        assertEquals(List.of("shown", "paint", "hidden"), doc.calls, "one show per change, one hide");

        crosshair.sync(true);
        doc.paints = false; // refused or failed: no crosshair this frame
        assertFalse(crosshair.paint(1920, 1080));
        assertFalse(crosshair.showing());
    }
}
