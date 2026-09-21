package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real pixels from the Ability Score tab: the [-] and [+] buttons must actually show a dash and a
 * cross. The UI typeface has no U+2212, and its {@code .notdef} is a hollow rectangle, so the
 * decrement button used to paint a tofu box; these probes fail for a tofu box (ink on the box's
 * top and bottom rows) just as they fail for an empty button.
 */
class AbilityScoreTabRenderTest {

    private static final int W = 600;
    private static final int H = 400;
    private static final int BACKGROUND = 0xFF101010;

    /**
     * Geometry of the first tile's buttons, from the renderer's own constants: the 3x90 grid with
     * 12px gaps is centred in a 600-wide content area, so tile 0 starts at x=153, y=36, and the
     * buttons sit 44px down, 20x16, inset 2px from each tile edge.
     */
    private static final int MINUS_X = 155, PLUS_X = 153 + 90 - 20 - 2, BTN_Y = 80;
    private static final int BTN_W = 20, BTN_H = 16;

    @Test
    void theDecrementButtonShowsADashAndTheIncrementButtonACross() throws Exception {
        CharacterStats stats = mock(CharacterStats.class);
        when(stats.getAbilityScores()).thenReturn(new int[] {10, 10, 10, 10, 10, 10});
        when(stats.getRemainingAp()).thenReturn(3);
        when(stats.getSelectedBackground()).thenReturn("none");

        try (var input = getClass().getResourceAsStream("/fonts/Minecraft.ttf");
             var data = Data.makeFromBytes(input.readAllBytes());
             var typeface = FontMgr.getDefault().makeFromData(data);
             var bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.makeN32Premul(W, H));
            try (var canvas = new Canvas(bitmap)) {
                canvas.clear(BACKGROUND);
                var ui = new MasonryUI(new SkijaUIBackend() {
                    @Override public Typeface getMinecraftTypeface() { return typeface; }
                    @Override public Canvas getCanvas() { return canvas; }
                    @Override public boolean isAvailable() { return true; }
                });
                try {
                    new AbilityScoreTabRenderer()
                            .render(canvas, ui, stats, new Rect(0, 0, W, H), -1, -1);

                    int midY = BTN_Y + BTN_H / 2;
                    assertTrue(inkRun(bitmap, MINUS_X, midY, BTN_W) >= 6,
                            "the [-] button paints a horizontal bar across its middle");
                    assertEquals(0, inkRun(bitmap, MINUS_X, BTN_Y + 2, BTN_W)
                                  + inkRun(bitmap, MINUS_X, BTN_Y + BTN_H - 3, BTN_W),
                            "and nothing near its top or bottom edge — a dash, not a tofu box");

                    assertTrue(inkRun(bitmap, PLUS_X, midY, BTN_W) >= 6,
                            "the [+] button paints its horizontal bar");
                    assertTrue(inkRun(bitmap, PLUS_X, BTN_Y + 3, BTN_W) >= 2,
                            "and the upper arm of its vertical bar, so a plus is not a dash");
                } finally {
                    ui.fonts().dispose();
                }
            }
        }
    }

    /**
     * Count of bright (label-coloured) pixels along one row of a button. The button face itself is
     * mid-grey stone with noise, so a high luminance threshold isolates the symbol's own ink.
     */
    private static int inkRun(Bitmap bitmap, int x0, int y, int width) {
        int ink = 0;
        for (int x = x0; x < x0 + width; x++) {
            int color = bitmap.getColor(x, y);
            int luminance = (((color >> 16) & 0xFF) * 30 + ((color >> 8) & 0xFF) * 59 + (color & 0xFF) * 11) / 100;
            if (luminance > 180) ink++;
        }
        return ink;
    }
}
