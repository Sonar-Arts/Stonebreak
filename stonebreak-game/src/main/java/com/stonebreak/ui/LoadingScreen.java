package com.stonebreak.ui;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import org.lwjgl.glfw.GLFW;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * The world loading screen. Since #299 it may be shown as the shipped UI document
 * {@value #DOCUMENT_ID} ({@link #setPresentation}); show/hide, the LOADING state and the stage
 * reports stay here, and the UI host publishes the stage and progress.
 */
public class LoadingScreen {

    /** The shipped document's screen id ({@code ui/documents/loading.sbui}). */
    public static final String DOCUMENT_ID = "loading";

    private final SkijaUIBackend backend;
    private final SkijaLoadingScreenRenderer renderer;
    private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
            new com.stonebreak.ui.runtime.screens.PresentationSlot();
    // Reported from generation threads, read by the render thread and the UI host.
    private volatile String currentStageName = "Initializing...";
    private volatile int currentStageIndex = 0;
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

    // Error state fields — read by SkijaLoadingScreenRenderer to style the error panel.
    // Defaults hold when no error is active (errorSeverity=INFO, empty lists, null strings).
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
        this.renderer = new SkijaLoadingScreenRenderer(backend);
    }

    public void show() {
        showProgress();
        Game.getInstance().setState(GameState.LOADING);
    }

    /** Resets to the first stage and shows, without entering LOADING (fixtures; the game calls {@link #show}). */
    public void showProgress() {
        this.currentStageIndex = 0;
        this.errorMessage = null;
        this.hasError = false;
        if (!stages.isEmpty()) {
            this.currentStageName = stages.getFirst();
        } else {
            this.currentStageName = "Loading...";
        }
        presentation.setVisible(true);
    }

    /** Stops showing without touching the game state (the game already left LOADING another way). */
    public void dismiss() {
        presentation.setVisible(false);
    }

    public void hide() {
        presentation.setVisible(false);
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
        return presentation.isVisible();
    }

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        presentation.install(p);
    }

    /** The legacy renderer (fixtures read its layout sink). */
    public SkijaLoadingScreenRenderer renderer() {
        return renderer;
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
     * Returns the current stage name (also published by the UI host).
     */
    public String getCurrentStageName() {
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
    public float getProgress() {
        return totalStages > 0 ? (float) (currentStageIndex + 1) / totalStages : 0f;
    }

    /**
     * Renders this loading screen using the Skija backend.
     */
    public void render(int windowWidth, int windowHeight) {
        if (presentation.paint(windowWidth, windowHeight)) {
            return;
        }
        renderer.render(this, windowWidth, windowHeight);
    }

    /** The progress bar's percentage, as the legacy screen writes it. */
    public static String percentText(float progress) {
        return String.format("%d%%", (int) (progress * 100));
    }
    
    /**
     * Handles input for the loading screen, primarily for error recovery.
     */
    public void handleInput(long window) {
        if (hasError) {
            boolean escPressed = com.stonebreak.input.PolledKeys.isDown(window, GLFW.GLFW_KEY_ESCAPE);
            if (escPressed) {
                System.out.println("LoadingScreen: ESC pressed during error, returning to main menu");
                Game.getInstance().setState(GameState.MAIN_MENU);
            }
        }
    }
}
