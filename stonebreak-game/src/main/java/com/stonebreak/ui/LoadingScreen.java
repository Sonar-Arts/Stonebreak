package com.stonebreak.ui;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import org.lwjgl.glfw.GLFW;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

public class LoadingScreen {
    private final SkijaUIBackend backend;
    private boolean visible = false;
    private String currentStageName = "Initializing...";
    private int currentStageIndex = 0;
    private String errorMessage = null;
    private boolean hasError = false;
    private final List<String> stages = Arrays.asList(
            "Initializing Noise System",
            "Generating Base Terrain Shape", // or "Calculating Terrain Density"
            "Determining Biomes",
            "Applying Biome Materials", // or "Materializing Chunk"
            "Generating Caves",
            "Generating Oceans and Lakes",
            "Generating Rivers",
            "Forming Mountains",
            "Adding Surface Decorations & Details",
            "Meshing Chunk"
    );
    private final int totalStages = stages.size();

    // TODO: Enhanced error-reporting feature - implemented but not yet wired up to any caller.
    // Hook reportError/reportDetailedError/updateDetailedProgress into world generation
    // failure paths, or remove this feature if it stays unused.
    private ErrorSeverity errorSeverity = ErrorSeverity.INFO;
    private String errorCode = null;
    private List<String> recoveryActions = new ArrayList<>();
    private List<String> diagnosticInfo = new ArrayList<>();
    private String currentSubStage = null;
    private int subStageProgress = 0;
    private int totalSubStages = 1;
    private long stageStartTime = 0;
    private String estimatedTimeRemaining = "Calculating...";

    // Lazily built fonts
    private Font fontTitle;
    private Font fontBody;
    private Font fontSmall;
    private Font fontTiny;

    /**
     * Error severity levels for enhanced error reporting.
     */
    public enum ErrorSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    public LoadingScreen(SkijaUIBackend backend) {
        this.backend = backend;
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle = new Font(tf, 48f);
        fontBody  = new Font(tf, 24f);
        fontSmall = new Font(tf, 16f);
        fontTiny  = new Font(tf, 12f);
    }

    public void show() {
        this.visible = true;
        this.currentStageIndex = 0;
        this.errorMessage = null;
        this.hasError = false;
        if (!stages.isEmpty()) {
            this.currentStageName = stages.getFirst();
        } else {
            this.currentStageName = "Loading...";
        }
        Game.getInstance().setState(GameState.LOADING);
    }

    public void hide() {
        this.visible = false;
        Game gameInstance = Game.getInstance();
        gameInstance.setState(GameState.PLAYING);
    }

    public void updateProgress(String stageName) {
        this.currentStageName = stageName;
        int stageIndex = stages.indexOf(stageName);
        if (stageIndex != -1) {
          this.currentStageIndex = stageIndex;
        } else {
          switch (stageName) {
              case "Calculating Terrain Density" -> this.currentStageIndex = stages.indexOf("Generating Base Terrain Shape");
              case "Materializing Chunk" -> this.currentStageIndex = stages.indexOf("Applying Biome Materials");
              default -> { }
          }
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void reportError(String error) {
        reportDetailedError(error, ErrorSeverity.ERROR, null, null, null);
    }

    public void reportDetailedError(String error, ErrorSeverity severity, String errorCode,
                                   List<String> recoveryActions, List<String> diagnosticInfo) {
        this.errorMessage = error;
        this.hasError = true;
        this.errorSeverity = severity;
        this.errorCode = errorCode;
        this.recoveryActions = recoveryActions != null ? new ArrayList<>(recoveryActions) : new ArrayList<>();
        this.diagnosticInfo = diagnosticInfo != null ? new ArrayList<>(diagnosticInfo) : new ArrayList<>();

        System.err.println("LoadingScreen: Reported " + severity + " error - " + error);
        if (errorCode != null) {
            System.err.println("LoadingScreen: Error code - " + errorCode);
        }
    }

    public void updateDetailedProgress(String stageName, String subStage, int subProgress,
                                     int totalSubStages, String timeRemaining) {
        updateProgress(stageName);
        this.currentSubStage = subStage;
        this.subStageProgress = subProgress;
        this.totalSubStages = totalSubStages;
        this.estimatedTimeRemaining = timeRemaining != null ? timeRemaining : "Calculating...";

        if (!stageName.equals(this.currentStageName)) {
            this.stageStartTime = System.currentTimeMillis();
        }
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible || backend == null || !backend.isAvailable()) {
            return;
        }

        ensureFonts();

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            float centerX = windowWidth / 2.0f;
            float centerY = windowHeight / 2.0f;

            // Full screen semi-transparent black background (~85% alpha)
            MPainter.fillRect(canvas, 0, 0, windowWidth, windowHeight, 0xD9000000);

            // "STONEBREAK" Title with 3D shadow effect
            String loadingTitle = "STONEBREAK";
            float titleY = centerY - 100;

            // Shadow layer (dark grey, offset +2/+2)
            MPainter.drawCenteredString(canvas, loadingTitle, centerX + 2, titleY + 2, fontTitle, 0xFF505050);
            // Main layer (light grey, centered)
            MPainter.drawCenteredString(canvas, loadingTitle, centerX, titleY, fontTitle, 0xFFDCDCDC);

            // Current stage name text
            MPainter.drawCenteredString(canvas, currentStageName, centerX, centerY - 10, fontBody, 0xFFC8C8C8);

            // Sub-stage information (if available)
            if (currentSubStage != null && !currentSubStage.trim().isEmpty()) {
                String subStageText = currentSubStage;
                if (totalSubStages > 1) {
                    subStageText += String.format(" (%d/%d)", subStageProgress + 1, totalSubStages);
                }
                MPainter.drawCenteredString(canvas, subStageText, centerX, centerY + 15, fontSmall, 0xFFA0A0A0);
            }

            // Estimated time remaining
            if (!estimatedTimeRemaining.equals("Calculating...") && !estimatedTimeRemaining.isEmpty()) {
                MPainter.drawCenteredString(canvas, "Time remaining: " + estimatedTimeRemaining,
                        centerX, centerY + 35, fontTiny, 0xFF8C8C8C);
            }

            // Progress bar
            float barWidth = 400f;
            float barHeight = 30f;
            float barX = centerX - barWidth / 2f;
            float barY = centerY + 50f;
            float progress = totalStages > 0 ? (float) (currentStageIndex + 1) / totalStages : 0f;
            float filledWidth = barWidth * progress;

            // Background of the progress bar
            MPainter.fillRect(canvas, barX, barY, barWidth, barHeight, 0xFF323232);

            // Filled part of the progress bar (blue-ish)
            if (filledWidth > 0) {
                MPainter.fillRect(canvas, barX + 2, barY + 2, filledWidth - 4, barHeight - 4,
                        0xFF5078C8);
            }

            // Border of the progress bar
            drawStrokeRect(canvas, barX, barY, barWidth, barHeight, 0xFF969696);

            // Progress percentage text (centered on top of bar)
            String progressText = String.format("%d%%", (int)(progress * 100));
            MPainter.drawCenteredString(canvas, progressText, centerX, barY + barHeight / 2f,
                    fontSmall, 0xFFDCDCDC);

            // Enhanced error message display (if there's an error)
            if (hasError && errorMessage != null) {
                renderDetailedError(canvas, centerX, centerY, windowWidth);
            }
        } catch (Exception e) {
            System.err.println("Error rendering loading screen: " + e.getMessage());
        } finally {
            backend.endFrame();
        }
    }

    private void drawStrokeRect(Canvas canvas, float x, float y, float w, float h, int color) {
        try (Paint paint = new Paint()) {
            paint.setMode(PaintMode.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(2.0f);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
        }
    }

    private void renderDetailedError(Canvas canvas, float centerX, float centerY, int windowWidth) {
        float errorBoxWidth = Math.min(700, windowWidth - 100);
        float baseErrorBoxHeight = 120f;

        // Calculate additional height needed for recovery actions and diagnostics
        float additionalHeight = 0;
        if (!recoveryActions.isEmpty()) {
            additionalHeight += 20 + (recoveryActions.size() * 18);
        }
        if (!diagnosticInfo.isEmpty()) {
            additionalHeight += 20 + Math.min(diagnosticInfo.size() * 16, 80);
        }

        float errorBoxHeight = baseErrorBoxHeight + additionalHeight;
        float errorBoxX = centerX - errorBoxWidth / 2;
        float errorBoxY = centerY + 120;

        // Determine colors based on severity
        int bgColor, borderColor, titleColor, textColor;
        String severityText;

        switch (errorSeverity) {
            case CRITICAL:
                bgColor     = 0xDC8C1414;
                borderColor = 0xFFDC3232;
                titleColor  = 0xFFFFB4B4;
                textColor   = 0xFFFFC8C8;
                severityText = "CRITICAL ERROR";
                break;
            case ERROR:
                bgColor     = 0xC8782C14;
                borderColor = 0xFFC83232;
                titleColor  = 0xFFFFB4B4;
                textColor   = 0xFFFFC8C8;
                severityText = "ERROR";
                break;
            case WARNING:
                bgColor     = 0xC8785014;
                borderColor = 0xFFC89632;
                titleColor  = 0xFFFFDC8C;
                textColor   = 0xFFEEE6B4;
                severityText = "WARNING";
                break;
            default: // INFO
                bgColor     = 0xC8145078;
                borderColor = 0xFF3296C8;
                titleColor  = 0xFFB4DCFF;
                textColor   = 0xFFC8E6FF;
                severityText = "INFO";
                break;
        }

        // Error background
        MPainter.fillRect(canvas, errorBoxX, errorBoxY, errorBoxWidth, errorBoxHeight, bgColor);

        // Error border
        drawStrokeRect(canvas, errorBoxX, errorBoxY, errorBoxWidth, errorBoxHeight, borderColor);

        float currentY = errorBoxY + 15;

        // Severity and error code header
        String headerText = severityText;
        if (errorCode != null) {
            headerText += " [" + errorCode + "]";
        }
        MPainter.drawCenteredString(canvas, headerText, centerX, currentY, fontSmall, titleColor);
        currentY += 25;

        // Main error message
        MPainter.drawCenteredString(canvas, errorMessage, centerX, currentY, fontTiny, textColor);
        currentY += 25;

        // Recovery actions
        if (!recoveryActions.isEmpty()) {
            MPainter.drawCenteredString(canvas, "Suggested Actions:", centerX, currentY, fontTiny, titleColor);
            currentY += 20;

            for (int i = 0; i < Math.min(recoveryActions.size(), 4); i++) {
                String action = "• " + recoveryActions.get(i);
                MPainter.drawString(canvas, action, errorBoxX + 20, currentY, fontTiny, textColor);
                currentY += 18;
            }

            if (recoveryActions.size() > 4) {
                String more = "• ... and " + (recoveryActions.size() - 4) + " more actions";
                MPainter.drawString(canvas, more, errorBoxX + 20, currentY, fontTiny, textColor);
                currentY += 18;
            }
        }

        // Diagnostic information
        if (!diagnosticInfo.isEmpty()) {
            MPainter.drawCenteredString(canvas, "Technical Details:", centerX, currentY, fontTiny, titleColor);
            currentY += 20;

            for (int i = 0; i < Math.min(diagnosticInfo.size(), 5); i++) {
                String diagnostic = diagnosticInfo.get(i);
                if (diagnostic.length() > 80) {
                    diagnostic = diagnostic.substring(0, 77) + "...";
                }
                MPainter.drawString(canvas, diagnostic, errorBoxX + 20, currentY,
                        fontTiny, 0xFFB4B4B4);
                currentY += 16;
            }
        }

        // Instructions
        String instructionText = "Press ESC to return to main menu";
        if (errorSeverity == ErrorSeverity.WARNING || errorSeverity == ErrorSeverity.INFO) {
            instructionText += " or wait for auto-recovery";
        }
        MPainter.drawCenteredString(canvas, instructionText, centerX, errorBoxY + errorBoxHeight + 25,
                fontTiny, 0xFFB4B4B4);
    }

    /**
     * Handles input for the loading screen, primarily for error recovery.
     */
    public void handleInput(long window) {
        if (hasError) {
            boolean escPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
            if (escPressed) {
                System.out.println("LoadingScreen: ESC pressed during error, returning to main menu");
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
    }
}
