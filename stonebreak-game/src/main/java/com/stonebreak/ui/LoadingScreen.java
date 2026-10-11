package com.stonebreak.ui;

import java.util.Arrays;
import java.util.List;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;

/**
 * The world loading screen. Show/hide, the LOADING state and the stage reports stay here; it shows
 * as the shipped UI document {@value #DOCUMENT_ID} ({@link #setPresentation}), to which the UI host
 * publishes the stage and progress.
 */
public class LoadingScreen {

    /** The shipped document's screen id ({@code ui/documents/loading.sbui}). */
    public static final String DOCUMENT_ID = "loading";

    private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
            new com.stonebreak.ui.runtime.screens.PresentationSlot();
    // Reported from generation threads, read by the render thread and the UI host.
    private volatile String currentStageName = "Initializing...";
    private volatile int currentStageIndex = 0;
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

    public void show() {
        showProgress();
        Game.getInstance().setState(GameState.LOADING);
    }

    /** Resets to the first stage and shows, without entering LOADING (fixtures; the game calls {@link #show}). */
    public void showProgress() {
        this.currentStageIndex = 0;
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

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        presentation.install(p);
    }

    /**
     * Returns the current stage name (also published by the UI host).
     */
    public String getCurrentStageName() {
        return currentStageName;
    }

    /**
     * Returns the normalized progress value (0-1) for the progress bar.
     */
    public float getProgress() {
        return totalStages > 0 ? (float) (currentStageIndex + 1) / totalStages : 0f;
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
