package com.stonebreak.ui;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

/**
 * Skija-backed renderer for the loading screen. Replaces the former NanoVG-
 * based rendering in {@link LoadingScreen}.
 *
 * Draws a semi-transparent overlay, the "STONEBREAK" title, current stage
 * name, optional sub-stage details, a progress bar, and an error box when
 * world generation fails.
 */
public final class SkijaLoadingScreenRenderer {

    private static final float TITLE_SIZE   = 48f;
    private static final float STAGE_SIZE   = 24f;
    private static final float SUB_STAGE_SIZE = 16f;
    private static final float TIME_SIZE    = 14f;
    private static final float PERCENT_SIZE = 16f;

    private static final float BAR_WIDTH   = 400f;
    private static final float BAR_HEIGHT  = 30f;

    // Colors (ARGB)
    private static final int COLOR_TITLE_TEXT     = 0xFFDCDCDC;
    private static final int COLOR_TITLE_SHADOW   = 0xFF505050;
    private static final int COLOR_STAGE_TEXT     = 0xFFC8C8C8;
    private static final int COLOR_SUB_STAGE_TEXT = 0xFFA0A0A0;
    private static final int COLOR_TIME_TEXT      = 0xFF8C8C8C;
    private static final int COLOR_PROGRESS_TEXT  = 0xFFDCDCDC;

    private static final int COLOR_BAR_BG     = 0xFF323232;
    private static final int COLOR_BAR_FILL   = 0xFF5078C8;
    private static final int COLOR_BAR_BORDER = 0xFF969696;

    private static final int COLOR_INSTRUCTION = 0xFFB4B4B4;

    // Error severity colors
    private static final int COLOR_CRITICAL_BG      = 0x8C1414;
    private static final int COLOR_CRITICAL_BORDER  = 0xFFDC3232;
    private static final int COLOR_CRITICAL_TITLE   = 0xFFFFB4B4;
    private static final int COLOR_CRITICAL_TEXT    = 0xFFFFC8C8;

    private static final int COLOR_ERROR_BG         = 0x781414;
    private static final int COLOR_ERROR_BORDER     = 0xFFC83232;
    private static final int COLOR_ERROR_TITLE      = 0xFFFFB4B4;
    private static final int COLOR_ERROR_TEXT       = 0xFFFFC8C8;

    private static final int COLOR_WARNING_BG       = 0x785014;
    private static final int COLOR_WARNING_BORDER   = 0xFFC89632;
    private static final int COLOR_WARNING_TITLE    = 0xFFFFDC8C;
    private static final int COLOR_WARNING_TEXT     = 0xFFE6B4;

    private static final int COLOR_INFO_BG          = 0x145078;
    private static final int COLOR_INFO_BORDER      = 0xFF3296C8;
    private static final int COLOR_INFO_TITLE       = 0xFFB4DCFF;
    private static final int COLOR_INFO_TEXT        = 0xFFC8E6FF;

    private static final int COLOR_DIAGNOSTIC_TEXT  = 0xFFB4B4B4;

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontStage;
    private Font fontSubStage;
    private Font fontTime;
    private Font fontPercent;
    private Font fontErrorTitle;
    private Font fontErrorText;
    private Font fontDiagnostic;
    private Font fontInstruction;

    public SkijaLoadingScreenRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    private void ensureFonts() {
        Typeface tf = backend.getMinecraftTypeface();
        if (tf == null) return;

        fontTitle   = new Font(tf).setSize(TITLE_SIZE);
        fontStage   = new Font(tf).setSize(STAGE_SIZE);
        fontSubStage = new Font(tf).setSize(SUB_STAGE_SIZE);
        fontTime    = new Font(tf).setSize(TIME_SIZE);
        fontPercent = new Font(tf).setSize(PERCENT_SIZE);
        fontErrorTitle = new Font(tf).setSize(16f);
        fontErrorText  = new Font(tf).setSize(14f);
        fontDiagnostic = new Font(tf).setSize(11f);
        fontInstruction = new Font(tf).setSize(14f);
    }

    /**
     * Main entry point. The caller must already be in the LOADING game state;
     * we bracket beginFrame/endFrame internally so Main.java no longer needs
     * a NanoVG frame around the LOADING case.
     */
    public void render(LoadingScreen screen, int windowWidth, int windowHeight) {
        if (!backend.isAvailable() || !screen.isVisible()) return;
        ensureFonts();

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            float cx = windowWidth / 2f;
            float cy = windowHeight / 2f;

            drawOverlay(canvas, windowWidth, windowHeight);
            drawTitle(canvas, cx, cy - 100f);
            drawStageName(canvas, cx, cy - 10f, screen.getCurrentStageName());
            drawSubStage(canvas, cx, cy + 15f, screen);
            drawTimeRemaining(canvas, cx, cy + 35f, screen);
            drawProgressBar(canvas, cx, cy + 50f, screen);

            if (screen.hasError() && screen.getErrorMessage() != null) {
                drawErrorBox(canvas, cx, cy + 120f, windowWidth, screen);
            }
        } finally {
            backend.endFrame();
        }
    }

    private void drawOverlay(Canvas canvas, int w, int h) {
        MPainter.fillRect(canvas, 0, 0, w, h, 0xD9000000); // ~0.85 alpha black
    }

    private void drawTitle(Canvas canvas, float cx, float y) {
        if (fontTitle == null) return;
        String title = "STONEBREAK";
        // Shadow layer
        MPainter.drawCenteredString(canvas, title, cx + 2f, y + 2f, fontTitle, COLOR_TITLE_SHADOW);
        // Main layer
        MPainter.drawCenteredString(canvas, title, cx, y, fontTitle, COLOR_TITLE_TEXT);
    }

    private void drawStageName(Canvas canvas, float cx, float y, String stageName) {
        if (fontStage == null) return;
        MPainter.drawCenteredString(canvas, stageName, cx, y, fontStage, COLOR_STAGE_TEXT);
    }

    private void drawSubStage(Canvas canvas, float cx, float y, LoadingScreen screen) {
        if (fontSubStage == null) return;
        String subStage = screen.getCurrentSubStage();
        if (subStage == null || subStage.trim().isEmpty()) return;

        String text = subStage;
        int total = screen.getTotalSubStages();
        if (total > 1) {
            text += String.format(" (%d/%d)", screen.getSubStageProgress() + 1, total);
        }
        MPainter.drawCenteredString(canvas, text, cx, y, fontSubStage, COLOR_SUB_STAGE_TEXT);
    }

    private void drawTimeRemaining(Canvas canvas, float cx, float y, LoadingScreen screen) {
        if (fontTime == null) return;
        String time = screen.getEstimatedTimeRemaining();
        if (time == null || time.equals("Calculating...") || time.isEmpty()) return;

        String text = "Time remaining: " + time;
        MPainter.drawCenteredString(canvas, text, cx, y, fontTime, COLOR_TIME_TEXT);
    }

    private void drawProgressBar(Canvas canvas, float cx, float y, LoadingScreen screen) {
        float barX = cx - BAR_WIDTH / 2f;
        float progress = screen.getProgress();
        float filledWidth = BAR_WIDTH * progress;

        // Background
        MPainter.fillRect(canvas, barX, y, BAR_WIDTH, BAR_HEIGHT, COLOR_BAR_BG);

        // Fill
        if (filledWidth > 0) {
            MPainter.fillRect(canvas, barX + 2f, y + 2f, filledWidth - 4f, BAR_HEIGHT - 4f, COLOR_BAR_FILL);
        }

        // Border
        MPainter.strokeRect(canvas, barX, y, BAR_WIDTH, BAR_HEIGHT, COLOR_BAR_BORDER, 2.0f);

        // Percentage text
        if (fontPercent != null) {
            String text = String.format("%d%%", (int) (progress * 100));
            MPainter.drawCenteredString(canvas, text, cx, y + BAR_HEIGHT / 2f, fontPercent, COLOR_PROGRESS_TEXT);
        }
    }

    private void drawErrorBox(Canvas canvas, float cx, float baseY, int windowWidth, LoadingScreen screen) {
        float boxWidth = Math.min(700f, windowWidth - 100f);
        float baseHeight = 120f;

        // Estimate additional height for recovery + diagnostics
        float additionalHeight = 0f;
        var recoveries = screen.getRecoveryActions();
        if (!recoveries.isEmpty()) {
            additionalHeight += 20f + recoveries.size() * 18f;
        }
        var diagnostics = screen.getDiagnosticInfo();
        if (!diagnostics.isEmpty()) {
            additionalHeight += 20f + Math.min(diagnostics.size() * 16f, 80f);
        }

        float boxHeight = baseHeight + additionalHeight;
        float boxX = cx - boxWidth / 2f;

        // Color selection by severity
        int bg, border, title, text;
        String severityLabel;
        switch (screen.getErrorSeverity()) {
            case CRITICAL:
                bg = COLOR_CRITICAL_BG; border = COLOR_CRITICAL_BORDER;
                title = COLOR_CRITICAL_TITLE; text = COLOR_CRITICAL_TEXT;
                severityLabel = "CRITICAL ERROR";
                break;
            case ERROR:
                bg = COLOR_ERROR_BG; border = COLOR_ERROR_BORDER;
                title = COLOR_ERROR_TITLE; text = COLOR_ERROR_TEXT;
                severityLabel = "ERROR";
                break;
            case WARNING:
                bg = COLOR_WARNING_BG; border = COLOR_WARNING_BORDER;
                title = COLOR_WARNING_TITLE; text = COLOR_WARNING_TEXT;
                severityLabel = "WARNING";
                break;
            default: // INFO
                bg = COLOR_INFO_BG; border = COLOR_INFO_BORDER;
                title = COLOR_INFO_TITLE; text = COLOR_INFO_TEXT;
                severityLabel = "INFO";
                break;
        }

        // Error panel background
        MPainter.fillRect(canvas, boxX, baseY, boxWidth, boxHeight, bg);
        MPainter.strokeRect(canvas, boxX, baseY, boxWidth, boxHeight, border, 2.0f);

        float curY = baseY + 15f;

        // Severity header
        if (fontErrorTitle != null) {
            String header = severityLabel;
            String code = screen.getErrorCode();
            if (code != null) header += " [" + code + "]";
            MPainter.drawCenteredString(canvas, header, cx, curY, fontErrorTitle, title);
            curY += 25f;
        }

        // Main error message
        if (fontErrorText != null) {
            MPainter.drawCenteredString(canvas, screen.getErrorMessage(), cx, curY, fontErrorText, text);
            curY += 25f;
        }

        // Recovery actions
        if (!recoveries.isEmpty() && fontErrorText != null) {
            MPainter.drawCenteredString(canvas, "Suggested Actions:", cx, curY, fontErrorText, title);
            curY += 20f;

            Font bulletFont = new Font(fontErrorText.getTypeface()).setSize(12f);
            int maxActions = Math.min(recoveries.size(), 4);
            for (int i = 0; i < maxActions; i++) {
                String action = "\u2022 " + recoveries.get(i);
                MPainter.drawString(canvas, action, boxX + 20f, curY, bulletFont, text);
                curY += 18f;
            }
            if (recoveries.size() > 4) {
                MPainter.drawString(canvas, "\u2022 ... and " + (recoveries.size() - 4) + " more actions",
                        boxX + 20f, curY, bulletFont, text);
                curY += 18f;
            }
        }

        // Diagnostic information
        if (!diagnostics.isEmpty() && fontDiagnostic != null) {
            if (fontErrorText != null) {
                MPainter.drawCenteredString(canvas, "Technical Details:", cx, curY, fontErrorText, title);
                curY += 20f;
            }

            int maxDx = Math.min(diagnostics.size(), 5);
            for (int i = 0; i < maxDx; i++) {
                String dx = diagnostics.get(i);
                if (dx.length() > 80) dx = dx.substring(0, 77) + "...";
                MPainter.drawString(canvas, dx, boxX + 20f, curY, fontDiagnostic, COLOR_DIAGNOSTIC_TEXT);
                curY += 16f;
            }
        }

        // Instruction text below the box
        if (fontInstruction != null) {
            String instruction = "Press ESC to return to main menu";
            var severity = screen.getErrorSeverity();
            if (severity == LoadingScreen.ErrorSeverity.WARNING || severity == LoadingScreen.ErrorSeverity.INFO) {
                instruction += " or wait for auto-recovery";
            }
            MPainter.drawCenteredString(canvas, instruction, cx, baseY + boxHeight + 25f,
                    fontInstruction, COLOR_INSTRUCTION);
        }
    }
}
