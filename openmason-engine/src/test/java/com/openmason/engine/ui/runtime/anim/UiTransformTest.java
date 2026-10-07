package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.UiCoordinates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@code scale} and {@code rotate} about the rect centre in painting bounds, hit tests and local coordinates (#295). */
class UiTransformTest {

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    /** A 100×40 card at (50, 50) with a 20×20 child in its top-left corner, and a popup overlay inside. */
    static OmuiArchive card() {
        return screen("t:ui/card", box("root").style("width", 400).style("height", 300).kids(
            box("card").style("position", "absolute").style("left", 50).style("top", 50).style("width", 100)
                .style("height", 40).kids(
                    box("corner").style("width", 20).style("height", 20),
                    box("popup").style("position", "absolute").style("left", 0).style("top", 40).style("width", 100)
                        .style("height", 10).style("-sb-layer", 1))));
    }

    private static UiDocumentInstance laid() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(card(),
            UiRuntimeContext.basic().withPixelGrid(UiRuntimeContext.NO_PIXEL_GRID));
        ui.setMetrics(UiMetrics.of(400, 300, 1));
        ui.update();
        return ui;
    }

    @Test
    void scaleGrowsHitsAndBoundsAboutTheCentre() {
        UiDocumentInstance ui = laid();
        UiElement card = ui.find("card");
        ui.consumeDirtyRegion();
        card.setStyle("scale", UiValue.of(2));
        ui.update();
        assertEquals(new UiRect(50, 50, 100, 40), card.rect(), "the layout rect does not move");
        assertEquals(new UiRect(0, 30, 200, 80), card.paintBounds());
        assertSame(card, ui.hitTest(10, 85), "outside the layout rect, inside the scaled card");
        assertSame(ui.find("corner"), ui.hitTest(5, 35), "the child scales with it: (0..40, 30..70)");
        assertSame(ui.find("popup"), ui.hitTest(100, 115), "a lifted overlay scales with its ancestors");
        float[] local = UiCoordinates.toLocal(ui.find("corner"), 40, 70);
        assertEquals(20, local[0], 1e-3);
        assertEquals(20, local[1], 1e-3);
        assertTrue(ui.consumeDirtyRegion().contains(1, 31), "repaints the grown area");
    }

    @Test
    void rotateTurnsClockwiseOnScreen() {
        UiDocumentInstance ui = laid();
        UiElement card = ui.find("card");
        card.setStyle("rotate", UiValue.of(90));
        ui.update();
        // The 100×40 card turned a quarter about (100, 70) now spans x 80..120, y 20..120.
        assertEquals(new UiRect(80, 20, 40, 100), card.paintBounds());
        assertSame(card, ui.hitTest(100, 110), "below the layout rect, inside the turned card");
        assertSame(ui.find("corner"), ui.hitTest(115, 25), "the top-left corner went to the top-right");
        assertSame(ui.root(), ui.hitTest(60, 55), "the layout rect's own corner now shows the root");
    }

    @Test
    void scaleZeroCollapsesHitsWithoutErrors() {
        UiDocumentInstance ui = laid();
        ui.find("card").setStyle("scale", UiValue.of(0));
        ui.update();
        assertSame(ui.root(), ui.hitTest(100, 70), "the card and its subtree are gone; the root is hit");
        float[] local = UiCoordinates.toLocal(ui.find("corner"), 100, 70);
        assertTrue(Float.isNaN(local[0]));
    }
}
