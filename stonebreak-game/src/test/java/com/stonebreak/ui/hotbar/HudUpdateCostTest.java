package com.stonebreak.ui.hotbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #300: the HUD's high-frequency path. A regenerating stamina bar changes every frame, so the
 * {@code hud} record is republished every frame and the bound fill re-laid out; nothing else may be.
 * Measures the per-frame cost (host drain, bindings, code-behind, layout; painting is the raster
 * stage's, not the game's) and checks that an unchanged frame republishes nothing.
 */
@Tag("regression")
class HudUpdateCostTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final int FRAMES = 600;

    @Test
    void aChangingBarCostsLittleAndAnUnchangedFramePublishesNothing() throws Exception {
        assumeTrue(CendaLua.isAvailable(), "Cenda Lua host unavailable");
        HudFixtures.Hud fixture = HudFixtures.hud("hurt");
        float[] stamina = {15f};
        when(fixture.player().getStamina()).thenAnswer(i -> stamina[0]);
        try (DocumentHudCapture.Stage hud = new DocumentHudCapture.Stage(
                GameUiDocuments.readScreen(DocumentHudCapture.ID), W, H, 1f, fixture)) {
            var root = hud.host.host().data().root("hud").source();

            // unchanged frames: the record is not republished
            UiValue before = ((com.openmason.engine.ui.data.DataCell) root).value();
            for (int i = 0; i < 60; i++) {
                frame(hud);
            }
            assertSame(before, ((com.openmason.engine.ui.data.DataCell) root).value(), "an unchanged HUD republished");

            // a regenerating bar: republished every frame, and the fill follows
            for (int i = 0; i < 60; i++) { // warm-up (JIT, Lua)
                stamina[0] = 15f + (i % 25);
                frame(hud);
            }
            long total = 0;
            long worst = 0;
            for (int i = 0; i < FRAMES; i++) {
                stamina[0] = 15f + (i % 25) * 0.5f;
                long t0 = System.nanoTime();
                frame(hud);
                long dt = System.nanoTime() - t0;
                total += dt;
                worst = Math.max(worst, dt);
            }
            assertNotSame(before, ((com.openmason.engine.ui.data.DataCell) root).value());
            float fill = hud.q("#stamina-fill").rect().width();
            float bar = hud.q("#stamina").rect().width();
            assertEquals(bar * (stamina[0] / 40f), fill, 0.01f, "the fill follows the last value");
            double meanMs = total / 1e6 / FRAMES;
            System.out.printf("[hud-cost] %d frames with a changing bar: mean %.3f ms, worst %.3f ms%n",
                FRAMES, meanMs, worst / 1e6);
            assertTrue(meanMs < 1.0, "HUD update mean " + meanMs + " ms (budget 1 ms; UiBudgets.MENU Lua is 0.5)");
        }
    }

    private static void frame(DocumentHudCapture.Stage hud) {
        hud.host.drain();
        GameUiDocuments.frame(hud.view, 0.016, 0);
        hud.view.layout(W, H, 1f, 1f);
    }
}
