package com.stonebreak.ui.statisticsScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * Skija/MasonryUI renderer for the in-game statistics screen.
 * Mirrors the pattern used by {@code SkijaPauseMenuRenderer}.
 */
public final class SkijaStatisticsRenderer {

    public static final float BUTTON_WIDTH  = 360f;
    public static final float BUTTON_HEIGHT = 50f;
    public static final float PANEL_HEIGHT  = 580f;
    public static final float BACK_BUTTON_BOTTOM_MARGIN = 30f;

    private static final float BASE_PANEL_WIDTH  = 520f;
    private static final float BASE_PANEL_HEIGHT = PANEL_HEIGHT;

    private static final float BASE_TITLE_SIZE    = 36f;
    private static final float BASE_HEADER_SIZE   = 16f;
    private static final float BASE_STAT_SIZE     = 15f;
    private static final float BASE_BUTTON_SIZE   = 20f;

    private static final int COLOR_OVERLAY = 0x78000000;

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontHeader;
    private Font fontStat;
    private Font fontButton;
    private float lastFontScale = -1f;

    public SkijaStatisticsRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(int windowWidth, int windowHeight, boolean backHovered) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);

        float panelW = BASE_PANEL_WIDTH  * scale;
        float panelH = BASE_PANEL_HEIGHT * scale;
        float bw     = BUTTON_WIDTH  * scale;
        float bh     = BUTTON_HEIGHT * scale;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float cx = windowWidth  / 2f;
            float cy = windowHeight / 2f;
            float panelX = cx - panelW / 2f;
            float panelY = cy - panelH / 2f;

            MPainter.panel(canvas, panelX, panelY, panelW, panelH);

            // Title
            float titleY = panelY + 68f * scale;
            drawTitle(canvas, cx, titleY);

            // Separator line under title
            float sepY = panelY + 88f * scale;
            try (Paint linePaint = new Paint().setColor(0x55FFFFFF)) {
                canvas.drawRect(Rect.makeXYWH(panelX + 20f * scale, sepY, panelW - 40f * scale, 1f), linePaint);
            }

            // Stats
            Player player = Game.getPlayer();
            PlayerStats stats = (player != null) ? player.getStats() : null;

            float leftX  = panelX + 28f * scale;
            float rightX = panelX + panelW - 28f * scale;
            float rowY   = panelY + 115f * scale;
            float rowGap = 30f * scale;
            float headerGap = 20f * scale;

            // COMBAT
            drawCategoryHeader(canvas, "COMBAT", leftX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Entities Killed", formatLong(stats != null ? stats.getEntitiesKilled() : 0), leftX, rightX, rowY);
            rowY += rowGap;
            float indentX = leftX + 20f * scale;
            drawStatRow(canvas, "Cows",     formatLong(getKillCount(stats, EntityType.COW)),     indentX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Sheep",    formatLong(getKillCount(stats, EntityType.SHEEP)),   indentX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Chickens", formatLong(getKillCount(stats, EntityType.CHICKEN)), indentX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Damage Dealt",    formatDamage(stats != null ? stats.getDamageDealt() : 0),   leftX, rightX, rowY);
            rowY += rowGap + headerGap;

            // Separator
            try (Paint sep2 = new Paint().setColor(0x33FFFFFF)) {
                canvas.drawRect(Rect.makeXYWH(leftX, rowY - headerGap / 2f, rightX - leftX, 1f), sep2);
            }

            // MOVEMENT
            drawCategoryHeader(canvas, "MOVEMENT", leftX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Total Distance",     formatDist(stats != null ? stats.getTotalDistance()   : 0), leftX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Walked",             formatDist(stats != null ? stats.getDistanceWalked()  : 0), leftX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Sprinted",           formatDist(stats != null ? stats.getDistanceSprinted(): 0), leftX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Distance in Air",    formatDist(stats != null ? stats.getDistanceInAir()   : 0), leftX, rightX, rowY);
            rowY += rowGap;
            drawStatRow(canvas, "Time in Air",        formatTime(stats != null ? stats.getTimeInAir()       : 0), leftX, rightX, rowY);

            // Back button
            float panelBottom = panelY + panelH;
            float backBtnY = panelBottom - BACK_BUTTON_BOTTOM_MARGIN * scale - bh;
            float backBtnX = cx - bw / 2f;
            int fill = backHovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, backBtnX, backBtnY, bw, bh, MStyle.BUTTON_RADIUS,
                    fill, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            int btnTextColor = backHovered ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            float btnTx = backBtnX + bw / 2f;
            float btnTy = backBtnY + bh / 2f + 7f * scale;
            MPainter.drawCenteredStringWithShadow(canvas, "Back", btnTx, btnTy, fontButton, btnTextColor, MStyle.TEXT_SHADOW);

        } finally {
            backend.endFrame();
        }
    }

    private void ensureFonts(float scale) {
        if (fontTitle != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, BASE_TITLE_SIZE  * scale);
        fontHeader = new Font(tf, BASE_HEADER_SIZE * scale);
        fontStat   = new Font(tf, BASE_STAT_SIZE   * scale);
        fontButton = new Font(tf, BASE_BUTTON_SIZE * scale);
    }

    private void disposeFonts() {
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontHeader != null) { fontHeader.close(); fontHeader = null; }
        if (fontStat   != null) { fontStat.close();   fontStat   = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
    }

    private void drawTitle(Canvas canvas, float cx, float cy) {
        for (int i = 4; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = 0xFFFFDC64;
                case 1 -> color = 0xFFDCB450;
                default -> {
                    int v = Math.max(30, 100 - i * 20);
                    color = (0xC8 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            float offset = i * 2.0f;
            MPainter.drawCenteredString(canvas, "STATISTICS", cx + offset, cy + offset, fontTitle, color);
        }
    }

    private void drawCategoryHeader(Canvas canvas, String label, float x, float y) {
        MPainter.drawStringWithShadow(canvas, label, x, y, fontHeader, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    private void drawStatRow(Canvas canvas, String label, String value, float leftX, float rightX, float y) {
        MPainter.drawStringWithShadow(canvas, label, leftX, y, fontStat, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        float vw = MPainter.measureWidth(fontStat, value);
        MPainter.drawStringWithShadow(canvas, value, rightX - vw, y, fontStat, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
    }

    // --- Formatters ---

    private static String formatLong(long v) {
        return String.format("%,d", v);
    }

    private static String formatDamage(double v) {
        return String.format("%.1f", v);
    }

    private static String formatDist(double meters) {
        if (meters >= 1000.0) {
            return String.format("%.2f km", meters / 1000.0);
        }
        return String.format("%.1f m", meters);
    }

    private static String formatTime(double seconds) {
        long total = (long) seconds;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }

    private static long getKillCount(PlayerStats stats, EntityType type) {
        if (stats == null) return 0L;
        return stats.getKillsByType().getOrDefault(type, 0L);
    }

    public void dispose() {
        disposeFonts();
    }
}
