package com.stonebreak.ui;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import static org.lwjgl.glfw.GLFW.*;

public class LoadingScreen {
    private final SkijaUIBackend backend;
    private final SkijaLoadingScreenRenderer renderer;
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

    public LoadingScreen(SkijaUIBackend backend) {
        this.backend = backend;
        this.renderer = new SkijaLoadingScreenRenderer(backend);
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

    /**
     * Returns the current stage name (package-private for the Skija renderer).
     */
    String getCurrentStageName() {
        return currentStageName;
    }

    /**
     * Returns the current sub-stage description.
     */
    String getCurrentSubStage() {
        return currentSubStage;
    }

    /**
     * Returns the current sub-stage progress index.
     */
    int getSubStageProgress() {
        return subStageProgress;
    }

    /**
     * Returns the total number of sub-stages.
     */
    int getTotalSubStages() {
        return totalSubStages;
    }

    /**
     * Returns the estimated time remaining string.
     */
    String getEstimatedTimeRemaining() {
        return estimatedTimeRemaining;
    }

    /**
     * Returns the normalized progress value (0-1) for the progress bar.
     */
    float getProgress() {
        return totalStages > 0 ? (float) (currentStageIndex + 1) / totalStages : 0f;
    }

    /**
     * Renders this loading screen using the Skija backend.
     */
    public void render(int windowWidth, int windowHeight) {
        renderer.render(this, windowWidth, windowHeight);
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
