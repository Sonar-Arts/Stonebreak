package com.stonebreak.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;

public class LoadingScreen {
    /** Per-profile phase durations from past loads; sits beside settings.json. */
    private static final Path LOAD_TIMINGS_FILE = Path.of("loading-timings.properties");

    private final SkijaLoadingScreenRenderer renderer;
    private final LoadProgressTracker tracker = new LoadProgressTracker(LOAD_TIMINGS_FILE, System::nanoTime);
    /** Taken once per frame in {@link #render} so every getter the renderer calls agrees. */
    private LoadProgressTracker.Snapshot snapshot = new LoadProgressTracker.Snapshot(null, null, 0.0, 0.0);
    private volatile boolean visible = false;
    private String errorMessage = null;
    private boolean hasError = false;

    // Error state fields — read by SkijaLoadingScreenRenderer to style the error panel.
    // Defaults hold when no error is active (errorSeverity=INFO, empty lists, null strings).
    private ErrorSeverity errorSeverity = ErrorSeverity.INFO;
    private String errorCode = null;
    private List<String> recoveryActions = new ArrayList<>();
    private List<String> diagnosticInfo = new ArrayList<>();

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
        this.renderer = new SkijaLoadingScreenRenderer(backend);
    }

    /**
     * Shows the screen and starts timing a new load. Idempotent while a load is in progress:
     * a singleplayer start calls this at boot and again when WelcomeS2C arrives, and the second
     * call must not reset the phases the server boot already reported.
     */
    public void show() {
        Game game = Game.getInstance();
        boolean loadInProgress = visible && tracker.isRunning() && game.getState() == GameState.LOADING;
        if (!loadInProgress) {
            tracker.start();
            this.errorMessage = null;
            this.hasError = false;
        }
        this.visible = true;
        game.setState(GameState.LOADING);
    }

    public void hide() {
        tracker.finish();
        this.visible = false;
        Game gameInstance = Game.getInstance();
        gameInstance.setState(GameState.PLAYING);
    }

    /**
     * Applies {@code update} to the running load's progress tracker, from any thread. A no-op
     * when no loading screen is up (e.g. chunk loads during play reusing the same code paths).
     */
    public static void report(Consumer<LoadProgressTracker> update) {
        Game game = Game.getInstance();
        LoadingScreen screen = game != null ? game.getLoadingScreen() : null;
        if (screen != null && screen.visible) {
            update.accept(screen.tracker);
        }
    }

    public boolean isVisible() {
        return visible;
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
     * Returns the running phase's name (package-private for the Skija renderer).
     */
    String getCurrentStageName() {
        return snapshot.phase() != null ? snapshot.phase().displayName() : "Preparing world";
    }

    /**
     * Returns the running phase's detail line, e.g. "Chunks 37/81", or null.
     */
    String getCurrentSubStage() {
        return snapshot.detail();
    }

    /**
     * Returns the normalized progress value (0-1) for the progress bar.
     */
    float getProgress() {
        return (float) snapshot.fraction();
    }

    /**
     * Renders this loading screen using the Skija backend.
     */
    public void render(int windowWidth, int windowHeight) {
        snapshot = tracker.snapshot();
        renderer.render(this, windowWidth, windowHeight);
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
