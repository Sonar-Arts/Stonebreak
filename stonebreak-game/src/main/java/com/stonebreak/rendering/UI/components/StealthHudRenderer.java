package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.stealth.StealthController;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Screen-space HUD for the universal stealth system: a "STEALTH" indicator while entering/active,
 * and a depleting re-entry cooldown bar after stealth breaks. Drawn top-centre, under the
 * crosshair. Skija-backed like {@link QuarryMarkerRenderer}.
 */
public class StealthHudRenderer {

    private static final StealthHudRenderer INSTANCE = new StealthHudRenderer();

    private static final float LABEL_FONT_SIZE = 16f;
    private static final float TOP_Y           = 64f;
    private static final float BAR_WIDTH        = 160f;
    private static final float BAR_HEIGHT       = 8f;
    private static final float BAR_GAP          = 8f;

    private static final int ACTIVE_COLOR    = 0xDFAEE3F0; // soft cyan-white
    private static final int ENTERING_COLOR  = 0x88AEE3F0; // dimmer while entering
    private static final int LABEL_SHADOW    = 0xB0101820;
    private static final int BAR_BG          = 0xC83C3C3C;
    private static final int BAR_FILL        = 0xE06AA8C8;
    private static final int BAR_BORDER      = 0xFF000000;
    private static final int COOLDOWN_LABEL  = 0xCCBFC8CC;

    private SkijaUIBackend backend;
    private MFonts fonts;

    private StealthHudRenderer() {}

    public static StealthHudRenderer getInstance() {
        return INSTANCE;
    }

    public void setBackend(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
    }

    public void render(int screenW, int screenH) {
        if (backend == null || !backend.isAvailable() || fonts == null) return;

        Player player = Game.getPlayer();
        if (player == null) return;
        StealthController stealth = player.getStealth();

        boolean active = stealth.isStealthed();
        boolean entering = stealth.isEntering();
        boolean cooling = stealth.isReentryCoolingDown();
        if (!active && !entering && !cooling) return;

        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;

            float cx = screenW * 0.5f;
            Font font = fonts.get(LABEL_FONT_SIZE);

            if ((active || entering) && font != null) {
                String label = active ? "STEALTH" : "ENTERING STEALTH…";
                int color = active ? ACTIVE_COLOR : ENTERING_COLOR;
                MPainter.drawCenteredStringWithShadow(canvas, label, cx, TOP_Y, font, color, LABEL_SHADOW);
            }

            if (cooling) {
                float fraction = stealth.getReentryRemainingFraction(); // 1 → 0 as it expires
                float barX = cx - BAR_WIDTH * 0.5f;
                float barY = TOP_Y + BAR_GAP;
                MPainter.fillRect(canvas, barX, barY, BAR_WIDTH, BAR_HEIGHT, BAR_BG);
                if (fraction > 0f) {
                    MPainter.fillRect(canvas, barX, barY, BAR_WIDTH * fraction, BAR_HEIGHT, BAR_FILL);
                }
                MPainter.strokeRect(canvas, barX, barY, BAR_WIDTH, BAR_HEIGHT, BAR_BORDER, 1f);
                if (font != null) {
                    MPainter.drawCenteredStringWithShadow(canvas, "Re-entry", cx,
                            barY + BAR_HEIGHT + LABEL_FONT_SIZE, font, COOLDOWN_LABEL, LABEL_SHADOW);
                }
            }
        } finally {
            backend.endFrame();
        }
    }
}
