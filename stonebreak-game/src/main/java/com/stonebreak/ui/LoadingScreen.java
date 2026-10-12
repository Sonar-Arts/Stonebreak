package com.stonebreak.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;

/**
 * The world loading screen. Show/hide, the LOADING state and the load's phase tracker stay here; it
 * shows as the shipped UI document {@value #DOCUMENT_ID} ({@link #setPresentation}), to which the UI
 * host publishes the phase, its detail line and the bar's progress.
 */
public class LoadingScreen {

    /** The shipped document's screen id ({@code ui/documents/loading.sbui}). */
    public static final String DOCUMENT_ID = "loading";

    /** Per-profile phase durations from past loads; sits beside settings.json. */
    private static final Path LOAD_TIMINGS_FILE = Path.of("loading-timings.properties");

    /** How fast the drawn fill closes the gap to the real progress, per second. */
    private static final float BAR_CATCH_UP_RATE = 8f;

    private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
            new com.stonebreak.ui.runtime.screens.PresentationSlot();
    // Reported from generation threads; read by the render thread and the UI host.
    private final LoadProgressTracker tracker;
    /** Taken once per frame ({@link #refreshSnapshot}) so every getter agrees within a frame. */
    private volatile LoadProgressTracker.Snapshot snapshot = new LoadProgressTracker.Snapshot(null, null, 0.0, 0.0);
    /** The fill as drawn, eased toward the tracker's progress so phase changes don't snap. */
    private float displayedProgress;
    private long lastFrameNanos;

    public LoadingScreen() {
        this(new LoadProgressTracker(LOAD_TIMINGS_FILE, System::nanoTime));
    }

    /** @param tracker the load's phase tracker (fixtures pass one on a fixed clock and store) */
    public LoadingScreen(LoadProgressTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Shows the screen and starts timing a new load. Idempotent while a load is in progress:
     * a singleplayer start calls this at boot and again when WelcomeS2C arrives, and the second
     * call must not reset the phases the server boot already reported.
     */
    public void show() {
        Game game = Game.getInstance();
        boolean loadInProgress = isVisible() && tracker.isRunning() && game.getState() == GameState.LOADING;
        if (!loadInProgress) {
            showProgress();
        }
        presentation.setVisible(true);
        game.setState(GameState.LOADING);
    }

    /** Starts timing a new load and shows, without entering LOADING (fixtures; the game calls {@link #show}). */
    public void showProgress() {
        tracker.start();
        snapshot = tracker.snapshot();
        displayedProgress = 0f;
        lastFrameNanos = 0L;
        presentation.setVisible(true);
    }

    /** Stops showing without touching the game state (the game already left LOADING another way). */
    public void dismiss() {
        presentation.setVisible(false);
    }

    public void hide() {
        tracker.finish();
        presentation.setVisible(false);
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
        if (screen != null && screen.isVisible()) {
            update.accept(screen.tracker);
        }
    }

    /** The tracker itself (fixtures drive phases through it). */
    public LoadProgressTracker tracker() {
        return tracker;
    }

    public boolean isVisible() {
        return presentation.isVisible();
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        presentation.install(p);
    }

    /**
     * Takes this frame's snapshot and eases the drawn fill toward it. Called once per frame before
     * the UI host publishes the screen (and again by {@link #render}; a second call in the same
     * frame only re-reads the tracker).
     */
    public void refreshSnapshot() {
        snapshot = tracker.snapshot();
        float target = (float) snapshot.fraction();
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (target < displayedProgress) {
            displayedProgress = target; // a new load started
        } else {
            displayedProgress += (target - displayedProgress) * Math.min(1f, dt * BAR_CATCH_UP_RATE);
        }
    }

    /** Takes this frame's snapshot with the drawn fill caught up to it (fixtures: no easing). */
    public void settleProgress() {
        refreshSnapshot();
        displayedProgress = (float) snapshot.fraction();
    }

    /** The running phase's name (published by the UI host). */
    public String getCurrentStageName() {
        LoadProgressTracker.Snapshot s = snapshot;
        return s.phase() != null ? s.phase().displayName() : "Preparing world";
    }

    /** The running phase's detail line, e.g. "Chunks 37/81", or null. */
    public String getCurrentSubStage() {
        return snapshot.detail();
    }

    /** The bar's fill (0-1) as drawn: the tracker's progress, eased. */
    public float getProgress() {
        return displayedProgress;
    }

    /** Paints the loading screen's presentation. */
    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    /** The progress bar's percentage text. */
    public static String percentText(float progress) {
        return String.format("%d%%", (int) (progress * 100));
    }
}
