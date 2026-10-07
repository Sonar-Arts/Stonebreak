package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openmason.engine.ui.fidelity.FidelityImage;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fixed-timestamp screenshots of a clip (#295): an intro that fades, slides, scales, turns and
 * recolours a card, painted on the raster reference path at chosen clock readings and compared
 * with committed PNGs ({@code src/test/resources/ui/runtime/visual/anim_*.png}; regenerate with
 * {@code -Dui.visual.write=true}). Hosts that step their clocks differently paint the same pixels.
 */
class UiAnimationVisualFixtureTest {

    private static final GoldenStore STORE = GoldenStore.forTests("ui/runtime/visual", "ui.visual.write");
    private static final int W = 240;
    private static final int H = 160;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static AnimKey key(double t, Object v, UiEasing e) {
        return new AnimKey(t, UiDocs.value(v), e, Map.of());
    }

    private static AnimTrack track(String property, AnimKey... keys) {
        return new AnimTrack("card", property, List.of(keys), Map.of());
    }

    static UiAnimationClip intro() {
        return new UiAnimationClip("intro", 1, LoopMode.ONCE, List.of(
            track("style:opacity", key(0, 0, UiEasing.EASE_OUT), key(0.5, 1, UiEasing.LINEAR)),
            track("style:translate-y", key(0, 30, UiEasing.EASE_OUT), key(1, 0, UiEasing.LINEAR)),
            track("style:scale", key(0, 0.6, UiEasing.EASE_IN_OUT), key(1, 1, UiEasing.LINEAR)),
            track("style:rotate", key(0, -20, UiEasing.EASE_OUT), key(1, 0, UiEasing.LINEAR)),
            track("style:background-color", key(0, "#3060C0FF", UiEasing.LINEAR), key(1, "#C08030FF", UiEasing.LINEAR))),
            List.of(), Map.of());
    }

    static OmuiArchive doc() {
        return screen("t:ui/anim_fixture", box("root").style("width", "100%").style("height", "100%")
            .style("background-color", "#101418").style("align-items", "center").style("justify-content", "center")
            .kids(box("card").style("width", 140).style("height", 70).style("background-color", "#3060C0")
                .style("border-color", "#FFFFFF").style("border-left-width", 2).style("border-top-width", 2)
                .style("border-right-width", 2).style("border-bottom-width", 2).style("border-radius", 6)
                .style("align-items", "center").style("justify-content", "center")
                .kids(label("title", "Stonebreak").style("color", "#FFFFFF").style("font-size", 20))));
    }

    /** Pixels after stepping the UI clock by {@code steps} (each {@code step} seconds) from the clip's start. */
    private static int[] pixelsAt(double step, int steps) {
        try (RasterDocHost host = new RasterDocHost(doc(), UiRuntimeContext.basic(), W, H)) {
            host.render(1f); // first layout, then the clip starts at UI time 0
            host.ui().animator().play(null, intro(), k -> k, UiAnimator.PlayOptions.DEFAULTS, null);
            for (int i = 0; i < steps; i++) {
                host.view.frame(step);
            }
            host.render(1f);
            assertTrue(host.ui().diagnostics().isEmpty(), host.ui().diagnostics().toString());
            assertTrue(host.ui().clocks().now(UiClocks.UI) == step * steps);
            return host.pixels();
        }
    }

    @Test
    void framesAtFixedTimesMatchTheirScreenshots() throws IOException {
        compare("anim_t000", pixelsAt(0, 0));
        compare("anim_t025", pixelsAt(0.25, 1));
        compare("anim_t050", pixelsAt(0.5, 1));
        compare("anim_t100", pixelsAt(1, 1));
    }

    @Test
    void differentlySteppedHostsPaintIdenticalFrames() {
        // The editor preview and the game step their clocks differently; 0.5 s is 0.5 s.
        int[] game = pixelsAt(1 / 64.0, 32);
        int[] preview = pixelsAt(1 / 8.0, 4);
        int[] once = pixelsAt(0.5, 1);
        assertArrayEquals(once, game);
        assertArrayEquals(once, preview);
    }

    private static void compare(String name, int[] actual) throws IOException {
        STORE.verify(name, new FidelityImage(W, H, actual), PixelTolerance.RASTER_DRIFT);
    }
}
