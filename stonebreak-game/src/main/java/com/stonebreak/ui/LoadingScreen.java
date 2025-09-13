package com.stonebreak.ui;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT;
import static org.lwjgl.nanovg.NanoVG.nvgBeginPath;
import static org.lwjgl.nanovg.NanoVG.nvgFill;
import static org.lwjgl.nanovg.NanoVG.nvgFillColor;
import static org.lwjgl.nanovg.NanoVG.nvgFontFace;
import static org.lwjgl.nanovg.NanoVG.nvgFontSize;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign;
import org.lwjgl.system.MemoryStack;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import static org.lwjgl.glfw.GLFW.*;

public class LoadingScreen {
    private final UIRenderer uiRenderer;
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
    
    // Enhanced error reporting fields
    private ErrorSeverity errorSeverity = ErrorSeverity.INFO;
    private String errorCode = null;
    private List<String> recoveryActions = new ArrayList<>();
    private List<String> diagnosticInfo = new ArrayList<>();
    private String currentSubStage = null;
    private int subStageProgress = 0;
    private int totalSubStages = 1;
    private long stageStartTime = 0;
    private String estimatedTimeRemaining = "Calculating...";
    
    /**
     * Error severity levels for enhanced error reporting.
     */
    public enum ErrorSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    public LoadingScreen(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }

    public void show() {
        this.visible = true;
        this.currentStageIndex = 0;
        this.errorMessage = null;
        this.hasError = false;
        if (!stages.isEmpty()) {
            this.currentStageName = stages.get(0);
        } else {
            this.currentStageName = "Loading...";
        }
        // Potentially reset or set initial game state here if needed
        Game.getInstance().setState(GameState.LOADING);
    }

    public void hide() {
        this.visible = false;
        
        // Transition to PLAYING state
        Game gameInstance = Game.getInstance();
        gameInstance.setState(GameState.PLAYING);
    }

    public void updateProgress(String stageName) {
        this.currentStageName = stageName;
        // Update currentStageIndex based on stageName
        int stageIndex = stages.indexOf(stageName); // Check if stageName exists in the list
        if (stageIndex != -1) { // If stageName is found
          this.currentStageIndex = stageIndex; // Update the currentStageIndex
        } else { // If stageName is not found in the predefined list
          // Heuristic: if the stageName is not in the list, maybe it's a custom sub-stage
          // For simplicity, we'll keep the progress bar based on known stages,
          // but display the custom name.
          // Or, if it's one of the "alternative" names, try to match it.
          switch (stageName) {
              case "Calculating Terrain Density" -> this.currentStageIndex = stages.indexOf("Generating Base Terrain Shape");
              case "Materializing Chunk" -> this.currentStageIndex = stages.indexOf("Applying Biome Materials");
              default -> {
                  // If truly unknown, we might decide not to advance the progress bar visually
                  // based on index, but the text will still update.
                  // For now, let's not change currentStageIndex for unknown stages not matched.
              }
          }
        }
    }


    public boolean isVisible() {
        return visible;
    }

    /**
     * Reports an error during world loading and displays it on the loading screen.
     */
    public void reportError(String error) {
        reportDetailedError(error, ErrorSeverity.ERROR, null, null, null);
    }
    
    /**
     * Reports a detailed error with severity, error code, recovery actions, and diagnostic info.
     */
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
    
    /**
     * Updates progress with detailed sub-stage information.
     */
    public void updateDetailedProgress(String stageName, String subStage, int subProgress, 
                                     int totalSubStages, String timeRemaining) {
        updateProgress(stageName);
        this.currentSubStage = subStage;
        this.subStageProgress = subProgress;
        this.totalSubStages = totalSubStages;
        this.estimatedTimeRemaining = timeRemaining != null ? timeRemaining : "Calculating...";
        
        // Update stage start time if this is a new stage
        if (!stageName.equals(this.currentStageName)) {
            this.stageStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Checks if there is currently an error being displayed.
     */
    public boolean hasError() {
        return hasError;
    }

    /**
     * Gets the current error message, if any.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Gets the current error severity.
     */
    public ErrorSeverity getErrorSeverity() {
        return errorSeverity;
    }
    
    /**
     * Gets the current error code.
     */
    public String getErrorCode() {
        return errorCode;
    }
    
    /**
     * Gets recovery actions for the current error.
     */
    public List<String> getRecoveryActions() {
        return new ArrayList<>(recoveryActions);
    }
    
    /**
     * Gets diagnostic information for the current error.
     */
    public List<String> getDiagnosticInfo() {
        return new ArrayList<>(diagnosticInfo);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible || uiRenderer == null) {
            return;
        }

        long vg = uiRenderer.getVG();
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Full screen semi-transparent black background
            uiRenderer.renderQuad(0, 0, windowWidth, windowHeight, 0.0f, 0.0f, 0.0f, 0.85f);

            // "Loading..." Text (Optional, can be game title)
            String loadingTitle = "STONEBREAK"; // Or "Loading World..."
            float titleFontSize = 48;
            String titleFont = (uiRenderer.getTextWidth("Test", titleFontSize, "minecraft") > 0) ? "minecraft" : "sans-bold";
            
            nvgFontSize(vg, titleFontSize);
            nvgFontFace(vg, titleFont);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Simple 3D effect for title
            nvgFillColor(vg, uiRenderer.nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX + 2, centerY - 100 + 2, loadingTitle);
            nvgFillColor(vg, uiRenderer.nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY - 100, loadingTitle);


            // Current Stage Name Text
            float stageFontSize = 24;
            String stageFont = (uiRenderer.getTextWidth("Test", stageFontSize, "sans") > 0) ? "sans" : "minecraft";
            
            nvgFontSize(vg, stageFontSize);
            nvgFontFace(vg, stageFont);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(200, 200, 200, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY - 10, currentStageName);
            
            // Sub-stage information (if available)
            if (currentSubStage != null && !currentSubStage.trim().isEmpty()) {
                float subStageFontSize = 16;
                nvgFontSize(vg, subStageFontSize);
                nvgFillColor(vg, uiRenderer.nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
                
                String subStageText = currentSubStage;
                if (totalSubStages > 1) {
                    subStageText += String.format(" (%d/%d)", subStageProgress + 1, totalSubStages);
                }
                nvgText(vg, centerX, centerY + 15, subStageText);
            }
            
            // Estimated time remaining
            if (!estimatedTimeRemaining.equals("Calculating...") && !estimatedTimeRemaining.isEmpty()) {
                float timeFontSize = 14;
                nvgFontSize(vg, timeFontSize);
                nvgFillColor(vg, uiRenderer.nvgRGBA(140, 140, 140, 255, NVGColor.malloc(stack)));
                nvgText(vg, centerX, centerY + 35, "Time remaining: " + estimatedTimeRemaining);
            }

            // Progress Bar
            float barWidth = 400;
            float barHeight = 30;
            float barX = centerX - barWidth / 2;
            float barY = centerY + 50;
            float progress = totalStages > 0 ? (float) (currentStageIndex +1) / totalStages : 0;
            float filledWidth = barWidth * progress;

            // Background of the progress bar
            nvgBeginPath(vg);
            nvgRect(vg, barX, barY, barWidth, barHeight);
            nvgFillColor(vg, uiRenderer.nvgRGBA(50, 50, 50, 255, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Filled part of the progress bar
            if (filledWidth > 0) {
                nvgBeginPath(vg);
                nvgRect(vg, barX + 2, barY + 2, filledWidth - 4, barHeight - 4); // Small inner padding
                nvgFillColor(vg, uiRenderer.nvgRGBA(80, 120, 200, 255, NVGColor.malloc(stack))); // Blueish progress
                nvgFill(vg);
            }

            // Border of the progress bar
            nvgBeginPath(vg);
            nvgRect(vg, barX, barY, barWidth, barHeight);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, uiRenderer.nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);

            // Progress percentage text (optional)
            String progressText = String.format("%d%%", (int)(progress * 100));
            float percentFontSize = 16;
            String percentFont = (uiRenderer.getTextWidth("Test", percentFontSize, "sans") > 0) ? "sans" : "minecraft";

            nvgFontSize(vg, percentFontSize);
            nvgFontFace(vg, percentFont);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, barY + barHeight / 2, progressText);

            // Enhanced error message display (if there's an error)
            if (hasError && errorMessage != null) {
                renderDetailedError(vg, stack, centerX, centerY, windowWidth, windowHeight);
            }

        } catch (Exception e) {
            System.err.println("Error rendering loading screen: " + e.getMessage());
            // Log the exception rather than printing stack trace directly
        }
    }
    
    /**
     * Renders detailed error information including severity, error code, recovery actions, and diagnostics.
     */
    private void renderDetailedError(long vg, MemoryStack stack, float centerX, float centerY, 
                                   int windowWidth, int windowHeight) {
        float errorBoxWidth = Math.min(700, windowWidth - 100);
        float baseErrorBoxHeight = 120;
        
        // Calculate additional height needed for recovery actions and diagnostics
        float additionalHeight = 0;
        if (!recoveryActions.isEmpty()) {
            additionalHeight += 20 + (recoveryActions.size() * 18);
        }
        if (!diagnosticInfo.isEmpty()) {
            additionalHeight += 20 + Math.min(diagnosticInfo.size() * 16, 80); // Limit diagnostic display
        }
        
        float errorBoxHeight = baseErrorBoxHeight + additionalHeight;
        float errorBoxX = centerX - errorBoxWidth / 2;
        float errorBoxY = centerY + 120;
        
        // Determine colors based on severity
        NVGColor bgColor, borderColor, titleColor, textColor;
        String severityText;
        
        switch (errorSeverity) {
            case CRITICAL:
                bgColor = uiRenderer.nvgRGBA(140, 20, 20, 220, NVGColor.malloc(stack));
                borderColor = uiRenderer.nvgRGBA(220, 50, 50, 255, NVGColor.malloc(stack));
                titleColor = uiRenderer.nvgRGBA(255, 180, 180, 255, NVGColor.malloc(stack));
                textColor = uiRenderer.nvgRGBA(255, 200, 200, 255, NVGColor.malloc(stack));
                severityText = "CRITICAL ERROR";
                break;
            case ERROR:
                bgColor = uiRenderer.nvgRGBA(120, 20, 20, 200, NVGColor.malloc(stack));
                borderColor = uiRenderer.nvgRGBA(200, 50, 50, 255, NVGColor.malloc(stack));
                titleColor = uiRenderer.nvgRGBA(255, 180, 180, 255, NVGColor.malloc(stack));
                textColor = uiRenderer.nvgRGBA(255, 200, 200, 255, NVGColor.malloc(stack));
                severityText = "ERROR";
                break;
            case WARNING:
                bgColor = uiRenderer.nvgRGBA(120, 80, 20, 200, NVGColor.malloc(stack));
                borderColor = uiRenderer.nvgRGBA(200, 150, 50, 255, NVGColor.malloc(stack));
                titleColor = uiRenderer.nvgRGBA(255, 220, 140, 255, NVGColor.malloc(stack));
                textColor = uiRenderer.nvgRGBA(255, 230, 180, 255, NVGColor.malloc(stack));
                severityText = "WARNING";
                break;
            default: // INFO
                bgColor = uiRenderer.nvgRGBA(20, 80, 120, 200, NVGColor.malloc(stack));
                borderColor = uiRenderer.nvgRGBA(50, 150, 200, 255, NVGColor.malloc(stack));
                titleColor = uiRenderer.nvgRGBA(180, 220, 255, 255, NVGColor.malloc(stack));
                textColor = uiRenderer.nvgRGBA(200, 230, 255, 255, NVGColor.malloc(stack));
                severityText = "INFO";
                break;
        }
        
        // Error background
        nvgBeginPath(vg);
        nvgRect(vg, errorBoxX, errorBoxY, errorBoxWidth, errorBoxHeight);
        nvgFillColor(vg, bgColor);
        nvgFill(vg);
        
        // Error border
        nvgBeginPath(vg);
        nvgRect(vg, errorBoxX, errorBoxY, errorBoxWidth, errorBoxHeight);
        nvgStrokeWidth(vg, 2.0f);
        nvgStrokeColor(vg, borderColor);
        nvgStroke(vg);
        
        float currentY = errorBoxY + 15;
        
        // Severity and error code header
        nvgFontSize(vg, 16);
        nvgFontFace(vg, "sans");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, titleColor);
        
        String headerText = severityText;
        if (errorCode != null) {
            headerText += " [" + errorCode + "]";
        }
        nvgText(vg, centerX, currentY, headerText);
        currentY += 25;
        
        // Main error message
        nvgFontSize(vg, 14);
        nvgFillColor(vg, textColor);
        nvgText(vg, centerX, currentY, errorMessage);
        currentY += 25;
        
        // Recovery actions
        if (!recoveryActions.isEmpty()) {
            nvgFontSize(vg, 13);
            nvgFillColor(vg, titleColor);
            nvgText(vg, centerX, currentY, "Suggested Actions:");
            currentY += 20;
            
            nvgFontSize(vg, 12);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, textColor);
            
            for (int i = 0; i < Math.min(recoveryActions.size(), 4); i++) {
                String action = "• " + recoveryActions.get(i);
                nvgText(vg, errorBoxX + 20, currentY, action);
                currentY += 18;
            }
            
            if (recoveryActions.size() > 4) {
                nvgText(vg, errorBoxX + 20, currentY, "• ... and " + (recoveryActions.size() - 4) + " more actions");
                currentY += 18;
            }
        }
        
        // Diagnostic information
        if (!diagnosticInfo.isEmpty()) {
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFontSize(vg, 13);
            nvgFillColor(vg, titleColor);
            nvgText(vg, centerX, currentY, "Technical Details:");
            currentY += 20;
            
            nvgFontSize(vg, 11);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            
            for (int i = 0; i < Math.min(diagnosticInfo.size(), 5); i++) {
                String diagnostic = diagnosticInfo.get(i);
                // Truncate long diagnostic messages
                if (diagnostic.length() > 80) {
                    diagnostic = diagnostic.substring(0, 77) + "...";
                }
                nvgText(vg, errorBoxX + 20, currentY, diagnostic);
                currentY += 16;
            }
        }
        
        // Instructions
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFontSize(vg, 14);
        nvgFillColor(vg, uiRenderer.nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
        
        String instructionText = "Press ESC to return to main menu";
        if (errorSeverity == ErrorSeverity.WARNING || errorSeverity == ErrorSeverity.INFO) {
            instructionText += " or wait for auto-recovery";
        }
        
        nvgText(vg, centerX, errorBoxY + errorBoxHeight + 25, instructionText);
    }

    /**
     * Handles input for the loading screen, primarily for error recovery.
     */
    public void handleInput(long window) {
        // Only handle input if there's an error displayed
        if (hasError) {
            boolean escPressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
            if (escPressed) {
                // Return to main menu when ESC is pressed during error
                System.out.println("LoadingScreen: ESC pressed during error, returning to main menu");
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
    }
}