package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Render and hit regions agree against PAINTED pixels (replaces a check that compared each
 * element's rect with itself): at fractional UI scales, with translation, rotation and scale
 * about origins, every pixel whose 3x3 neighbourhood hits one element shows that element's
 * colour. Edge pixels (anti-aliased) are skipped; everything else must match.
 */
class RenderHitAgreementTest {

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static OmuiArchive transformed() {
        return screen("t:ui/hits", box("root").style("background-color", "#101010").style("flex-direction", "row")
            .style("flex-wrap", "wrap").style("padding-left", 6).style("padding-top", 6).style("column-gap", 9)
            .style("row-gap", 9).kids(
                box("plain").style("width", 40).style("height", 30).style("background-color", "#C02020"),
                box("moved").style("width", 33).style("height", 21).style("background-color", "#20C020")
                    .style("translate-x", 5.5).style("translate-y", 2.25),
                box("turned").style("width", 36).style("height", 24).style("background-color", "#2020C0")
                    .style("rotate", 30),
                box("grown").style("width", 20).style("height", 20).style("background-color", "#C0C020")
                    .style("scale", 1.4).style("transform-origin-x", 0).style("transform-origin-y", 0),
                box("parent").style("width", 50).style("height", 40).style("background-color", "#20C0C0")
                    .style("rotate", -15).kids(
                        box("kid").style("width", 20).style("height", 12).style("background-color", "#C020C0")
                            .style("translate-x", 8).style("translate-y", 6))));
    }

    @Test
    void everyInteriorPixelShowsTheElementAHitReturnsAtFractionalScales() {
        for (float scale : new float[]{0.75f, 1f, 1.25f, 1.5f}) {
            try (RasterDocHost host = new RasterDocHost(transformed(), UiRuntimeContext.basic(), 300, 160)) {
                host.render(scale);
                int compared = 0;
                for (int y = 1; y < host.height - 1; y++) {
                    for (int x = 1; x < host.width - 1; x++) {
                        UiElement hit = host.ui().hitTest(x + 0.5f, y + 0.5f);
                        if (hit == null || !uniform(host, x, y, hit)) {
                            continue;
                        }
                        int expected = background(hit);
                        int actual = host.color(x, y);
                        assertTrue(close(expected, actual), String.format(
                            "scale %s pixel %d,%d hits %s (#%08X) but shows #%08X", scale, x, y, hit.key(),
                            expected, actual));
                        compared++;
                    }
                }
                assertTrue(compared > host.width * host.height / 2, "compared " + compared);
            }
        }
    }

    /** True when all 8 neighbours hit the same element (the pixel is not on an anti-aliased edge). */
    private static boolean uniform(RasterDocHost host, int x, int y, UiElement hit) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (host.ui().hitTest(x + dx + 0.5f, y + dy + 0.5f) != hit) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int background(UiElement el) {
        UiValue v = el.computedStyle().get("background-color");
        String hex = ((UiValue.Str) v).value().substring(1);
        return 0xFF000000 | Integer.parseInt(hex.substring(0, 6), 16);
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift < 32; shift += 8) {
            if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > 2) {
                return false;
            }
        }
        return true;
    }
}
