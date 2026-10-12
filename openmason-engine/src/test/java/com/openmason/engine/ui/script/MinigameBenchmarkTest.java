package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.AllocationMeter;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.paint.MasonryContentMeasurer;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The #292 minigame acceptance: 1,000 animated sprites plus 200 eased tweens run in a UI document
 * inside the frame budget with no per-frame Java garbage from the script runtime, and the canvas
 * paints what the script drew.
 *
 * <p>Budget, from the milestone-0 measurement (#283/#292: the same frame took 76 µs in Lua 5.5 and
 * 75 µs through FFM): the script frame must stay under 2 ms at the median and 4 ms at p95 here, a
 * ~25× margin for slow CI machines; a 60 Hz frame is 16.7 ms. The measured numbers are printed.
 */
@Tag("regression")
class MinigameBenchmarkTest {

    private static final int WARMUP = 600;
    private static final int FRAMES = Integer.getInteger("minigame.frames", 600);

    @Test
    void thousandSpritesStayInBudgetWithoutJavaGarbage() {
        OmuiArchive doc = ScriptSamples.minigame();
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            for (int i = 0; i < WARMUP; i++) {
                rig.rt.update(1 / 60.0);
            }
            long[] nanos = new long[FRAMES];
            long before = AllocationMeter.allocatedBytes();
            for (int i = 0; i < FRAMES; i++) {
                long t0 = System.nanoTime();
                rig.rt.update(1 / 60.0);
                nanos[i] = System.nanoTime() - t0;
            }
            long garbage = AllocationMeter.allocatedBytes() - before;
            Arrays.sort(nanos);
            double p50 = nanos[FRAMES / 2] / 1e3;
            double p95 = nanos[FRAMES * 95 / 100] / 1e3;
            double max = nanos[FRAMES - 1] / 1e3;
            int floats = rig.ui.canvas("game").size();
            System.out.printf(Locale.ROOT, "[minigame] 1000 sprites + 200 tweens: p50 %.1f us, p95 %.1f us, "
                    + "max %.1f us, Java garbage %d B over %d frames, %d floats/frame, Lua heap %d KiB%n",
                p50, p95, max, garbage, FRAMES, floats, rig.rt.memoryUsed() / 1024);
            assertEquals(1000 * 7 + 200 * 6 + 7 + 6 + 7 + 8, floats, "every sprite and tween drew this frame");
            assertTrue(p50 < 2000, "median script frame " + p50 + " us");
            assertTrue(p95 < 4000, "p95 script frame " + p95 + " us");
            if (AllocationMeter.available()) {
                assertTrue(garbage / FRAMES < 16, "per-frame Java garbage: " + garbage + " B over " + FRAMES
                    + " frames");
            }
        }
    }

    @Test
    void theCanvasPaintsWhatTheScriptDrew() {
        ScriptRig.assumeLua();
        Typeface typeface = typeface();
        assumeTrue(typeface != null, "pinned test font missing");
        int w = 800;
        int h = 480;
        RasterMasonryBackend backend = new RasterMasonryBackend(w, h, typeface, false);
        MasonryUI masonry = new MasonryUI(backend);
        MasonryContentMeasurer text = new MasonryContentMeasurer(() -> typeface, UiPaintHost.NONE);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(ScriptSamples.minigame(),
            UiRuntimeContext.basic().withMeasurer(text));
        UiDocumentView view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, text));
        try {
            ui.setMetrics(UiMetrics.of(w, h, 1));
            ui.update();
            UiScripts.open(view, null, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
            view.frame(1 / 60.0);
            assertTrue(masonry.beginFrame(w, h, 1f));
            try {
                masonry.canvas().clear(0xFF000000);
                view.render(masonry, w, h, 1f, 1f);
            } finally {
                masonry.endFrame();
            }
            UiRect r = ui.find("game").rect();
            // The script's background rect (0x101820) fills the canvas; outside it stays black.
            int inside = backend.colorAt((int) r.x() + 2, (int) (r.y() + r.height()) - 2);
            int outside = backend.colorAt((int) r.x() - 4, (int) r.y() + 10);
            assertEquals(0x101820, inside & 0xFFFFFF, Integer.toHexString(inside));
            assertEquals(0x000000, outside & 0xFFFFFF, Integer.toHexString(outside));
            int colored = 0;
            for (int y = (int) r.y(); y < r.y() + r.height(); y += 3) {
                for (int x = (int) r.x(); x < r.x() + r.width(); x += 3) {
                    int c = backend.colorAt(x, y) & 0xFFFFFF;
                    if (c != 0x101820 && c != 0) {
                        colored++;
                    }
                }
            }
            assertTrue(colored > 200, "sprites and sparkles are visible: " + colored + " sampled pixels");
        } finally {
            view.close();
            text.close();
            masonry.dispose();
            backend.dispose();
        }
    }

    static Typeface typeface() {
        try (InputStream in = MinigameBenchmarkTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            return in == null ? null : FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            return null;
        }
    }
}
