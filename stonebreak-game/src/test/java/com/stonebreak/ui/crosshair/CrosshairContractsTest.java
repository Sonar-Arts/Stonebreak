package com.stonebreak.ui.crosshair;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.runtime.contracts.CrosshairContracts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code stonebreak:crosshair} 1 (#301): the applied configuration, as drawn, republished only on change. */
class CrosshairContractsTest {

    private static UiValue.Obj read(UiHost host) {
        return (UiValue.Obj) ((DataCell) host.data().root("crosshair").source()).value();
    }

    @Test
    void publishesTheAppliedCrosshairAsDrawn() {
        UiHost host = new UiHost();
        CrosshairSettings applied = CrosshairFixtures.crosshair("custom");
        CrosshairContracts contracts = new CrosshairContracts(host, new CrosshairContracts.Services() {
            @Override
            public CrosshairSettings crosshair() {
                return applied;
            }
        });
        assertEquals(UiValue.of("SIMPLE_CROSS"), read(host).get("style"), "defaults before the first poll");

        contracts.poll();
        UiValue.Obj c = read(host);
        assertEquals(UiValue.of("PLUS_DOT"), c.get("style"));
        assertEquals(37, ((UiValue.Num) c.get("size")).value(), 0);
        assertEquals(1.5, ((UiValue.Num) c.get("outlineThickness")).value(), 0);
        // the fill at opacity 0.6: round(0.9 x 255) = E6, round(0.2 x 255) = 33, round(0.35 x 255) = 59, alpha 99
        assertEquals(UiValue.of("#E6335999"), c.get("color"));
        assertEquals(UiValue.of("#00000099"), c.get("edge"), "the edge shares the opacity");

        contracts.poll();
        assertSame(c, read(host), "unchanged: nothing republished");
        applied.setOutline(false);
        contracts.poll();
        assertEquals(UiValue.FALSE, read(host).get("outline"));
    }
}
