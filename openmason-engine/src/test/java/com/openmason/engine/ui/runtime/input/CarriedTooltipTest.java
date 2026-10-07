package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #298: container screens show a slot's tooltip at once (a per-view {@link InputSettings} the host
 * sets) and never while a stack rides the cursor (a displayed {@code -sb-anchor: pointer} element),
 * as the legacy screens hid it while an item was held.
 */
class CarriedTooltipTest {

    private static OmuiArchive slots() {
        return screen("t:ui/slots", box("root").style("width", 400).style("height", 300).kids(
            node("slot", "Box").style("width", 40).style("height", 40).prop("tooltip", "Sand"),
            node("carried", "Box").style("position", "absolute").style("-sb-anchor", "pointer")
                .style("width", 36).style("height", 36).style("picking-mode", "ignore").style("display", "none")));
    }

    @Test
    void aViewCanShowTooltipsAtOnce() {
        try (InputRig r = new InputRig(slots())) {
            r.router.setSettings(InputSettings.DEFAULTS.withTooltipDelay(0));
            assertEquals(0, r.router.settings().tooltipDelay());
            r.router.pointerMove(r.cx("slot"), r.cy("slot"));
            r.router.tick(0);
            assertEquals("Sand", r.router.tooltips().current().text(), "no hover delay");
        }
    }

    @Test
    void noHoverTooltipWhileSomethingRidesTheCursor() {
        try (InputRig r = new InputRig(slots(), 400, 300, InputSettings.DEFAULTS.withTooltipDelay(0))) {
            float x = r.cx("slot");
            float y = r.cy("slot");
            r.ui.setPointer(x, y);
            r.router.pointerMove(x, y);
            r.frame();
            r.router.tick(0);
            assertFalse(r.ui.pointerGhostShown());
            assertEquals("Sand", r.router.tooltips().current().text());

            r.el("carried").setStyle("display", UiValue.of("flex")); // a stack was picked up
            r.frame();
            assertTrue(r.ui.pointerGhostShown());
            r.router.tick(0.1);
            assertNull(r.router.tooltips().current(), "hidden while carrying");

            r.el("carried").setStyle("display", UiValue.of("none")); // placed
            r.frame();
            r.router.tick(0.1);
            assertEquals("Sand", r.router.tooltips().current().text(), "back once the cursor is empty");
        }
    }

    @Test
    void aGhostOutsideTheFrameHidesNothing() {
        try (InputRig r = new InputRig(slots())) {
            r.el("carried").setStyle("display", UiValue.of("flex"));
            r.frame();
            assertFalse(r.ui.pointerGhostShown(), "the cursor layer paints only while the pointer is over the frame");
        }
    }
}
