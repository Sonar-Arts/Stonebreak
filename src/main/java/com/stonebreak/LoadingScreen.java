package com.stonebreak;

import java.util.Arrays;
import java.util.List;

import org.lwjgl.nanovg.NVGColor;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
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

public class LoadingScreen {
    private final UIRenderer uiRenderer;
    private boolean visible = false;
    private String currentStageName = "Initializing...";
    private int currentStageIndex = 0;
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

    public LoadingScreen(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }

    public void show() {
        this.visible = true;
        this.currentStageIndex = 0;
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
        // Potentially transition to PLAYING state or other appropriate state
        // This will likely be called after world generation completes.
        Game gameInstance = Game.getInstance();
        gameInstance.setState(GameState.PLAYING);
        
        // Critical fix: Force the input handler to reset firstMouse AFTER the state transition
        // This ensures the next mouse movement will be processed properly
        InputHandler inputHandler = gameInstance.getInputHandler();
        if (inputHandler != null) {
            inputHandler.clearMouseButtonStates();
            // Don't call resetMousePosition here as it sets firstMouse=true
            // Instead, we need to ensure mouse position tracking continues properly
        }
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
                  System.out.println("LoadingScreen: Unknown stage name: " + stageName);
              }
          }
        }
    }


    public boolean isVisible() {
        return visible;
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
            nvgText(vg, centerX, centerY, currentStageName);

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

        } catch (Exception e) {
            System.err.println("Error rendering loading screen: " + e.getMessage());
            // Log the exception rather than printing stack trace directly
        }
    }
}