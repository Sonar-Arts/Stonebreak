package com.openmason.ui.textureCreator.panels;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.CommandHistory;
import com.openmason.ui.textureCreator.commands.DrawCommand;
import com.openmason.ui.textureCreator.coordinators.FilterCoordinator;
import com.openmason.ui.textureCreator.filters.noise.*;
import com.openmason.ui.textureCreator.layers.LayerManager;
import com.openmason.ui.textureCreator.layers.Layer;
import com.openmason.ui.textureCreator.selection.SelectionRegion;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Noise filter panel for applying procedural noise to layers.
 */
public class NoiseFilterPanel {

    private static final Logger logger = LoggerFactory.getLogger(NoiseFilterPanel.class);

    // Noise algorithm selection
    private int selectedAlgorithm = 0; // 0=Simplex, 1=Value, 2=White
    private final String[] algorithmNames = {"Simplex", "Value", "White"};

    // Noise parameters
    private float strength = 0.5f;
    private boolean gradient = false;
    private final float scale = 1.0f;
    private long noiseSeed = System.nanoTime(); // Consistent seed for preview
    private final ImString seedInput = new ImString(32); // Buffer for manual seed input

    // Diffusion parameters
    private float blur = 0.3f;
    private int octaves = 4;
    private float spread = 0.5f;
    private float edgeSoftness = 0.2f;

    // Preview state
    private boolean previewActive = false;
    private Map<Integer, Integer> originalPixels = null; // Backup of original pixels
    private Layer activeLayer = null;
    private SelectionRegion activeSelection = null;

    // Preview constants
    private static final int PREVIEW_RESOLUTION = 64;

    // Dependencies (injected)
    private FilterCoordinator filterCoordinator;
    private LayerManagerProvider layerManagerProvider;
    private CommandHistoryProvider commandHistoryProvider;

    /**
     * Create noise filter panel.
     */
    public NoiseFilterPanel() {
        seedInput.set(String.valueOf(noiseSeed)); // Sync input buffer with initial seed
        logger.debug("Noise filter panel created");
    }

    /**
     * Set dependencies for filter application and preview.
     * Uses provider pattern to avoid stale references when LayerManager is replaced (e.g., when loading OMT files).
     *
     * @param layerManagerProvider Provider for current LayerManager instance
     * @param commandHistoryProvider Provider for current CommandHistory instance
     */
    public void setDependencies(LayerManagerProvider layerManagerProvider, CommandHistoryProvider commandHistoryProvider) {
        this.layerManagerProvider = layerManagerProvider;
        this.commandHistoryProvider = commandHistoryProvider;
    }

    /**
     * Provider interface for LayerManager.
     * Allows dynamic retrieval to avoid stale references.
     */
    @FunctionalInterface
    public interface LayerManagerProvider {
        LayerManager getLayerManager();
    }

    /**
     * Provider interface for CommandHistory.
     * Allows dynamic retrieval to avoid stale references.
     */
    @FunctionalInterface
    public interface CommandHistoryProvider {
        CommandHistory getCommandHistory();
    }

    /**
     * Render the noise filter panel.
     *
     * @param selectionRegion Current selection region (may be null)
     */
    public void render(SelectionRegion selectionRegion) {
        ImGui.beginChild("##noise_filter_panel", 0, 0, false);

        // Store current selection
        this.activeSelection = selectionRegion;

        ImGui.spacing();
        boolean algorithmChanged = renderAlgorithmSelector();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        boolean strengthChanged = renderStrengthSlider();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        boolean modeChanged = renderModeToggle();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        boolean seedChanged = renderSeedControl();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        boolean diffusionChanged = renderDiffusionSliders();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        renderPreview();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        renderActionButtons();

        ImGui.spacing();

        // Update preview if any parameter changed
        if (algorithmChanged || strengthChanged || modeChanged || seedChanged || diffusionChanged) {
            updatePreview();
        }

        ImGui.endChild();
    }

    private boolean renderAlgorithmSelector() {
        ImGui.text("Algorithm");

        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(
                "Simplex: Smooth, natural noise (improved algorithm)\n" +
                "Value: Simpler interpolated noise\n" +
                "White: Pure random static"
            );
        }

        ImGui.spacing();

        boolean changed = false;

        // Algorithm buttons
        float panelWidth = ImGui.getContentRegionAvailX();
        float buttonWidth = (panelWidth - 4.0f) / 3.0f; // 3 buttons with small gaps

        for (int i = 0; i < algorithmNames.length; i++) {
            if (i > 0) {
                ImGui.sameLine(0, 2.0f);
            }

            boolean isSelected = (selectedAlgorithm == i);

            if (isSelected) {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.31f, 0.55f, 0.91f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.20f, 0.44f, 0.80f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f); // White text
            } else {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.95f, 0.95f, 0.96f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.90f, 0.91f, 0.92f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.82f, 0.84f, 0.86f, 1.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.0f, 0.0f, 0.0f, 1.0f); // Black text
            }

            if (ImGui.button(algorithmNames[i], buttonWidth, 0)) {
                selectedAlgorithm = i;
                changed = true;
            }

            ImGui.popStyleColor(4);
        }

        return changed;
    }

    private boolean renderStrengthSlider() {
        ImGui.text("Strength");

        ImGui.sameLine();
        ImGui.text(String.format("%.0f%%", strength * 100));

        ImGui.spacing();

        float[] strengthArray = {strength};
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        boolean changed = ImGui.sliderFloat("##strength", strengthArray, 0.0f, 1.0f, "");
        if (changed) {
            strength = strengthArray[0];
        }

        return changed;
    }

    private boolean renderModeToggle() {
        ImGui.text("Mode");

        ImGui.spacing();

        boolean changed = false;
        float panelWidth = ImGui.getContentRegionAvailX();
        float buttonWidth = (panelWidth - 2.0f) / 2.0f;

        // Uniform button
        boolean isUniform = !gradient;
        if (isUniform) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.31f, 0.55f, 0.91f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.20f, 0.44f, 0.80f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f); // White text
        } else {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.95f, 0.95f, 0.96f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.90f, 0.91f, 0.92f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.82f, 0.84f, 0.86f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.0f, 0.0f, 0.0f, 1.0f); // Black text
        }

        if (ImGui.button("Uniform", buttonWidth, 0)) {
            if (gradient) {
                gradient = false;
                changed = true;
            }
        }

        ImGui.popStyleColor(4);

        ImGui.sameLine(0, 2.0f);

        // Gradient button
        boolean isGradient = gradient;
        if (isGradient) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.31f, 0.55f, 0.91f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.20f, 0.44f, 0.80f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f); // White text
        } else {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.95f, 0.95f, 0.96f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.90f, 0.91f, 0.92f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.82f, 0.84f, 0.86f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.0f, 0.0f, 0.0f, 1.0f); // Black text
        }

        if (ImGui.button("Gradient", buttonWidth, 0)) {
            if (!gradient) {
                gradient = true;
                changed = true;
            }
        }

        ImGui.popStyleColor(4);

        return changed;
    }

    private boolean renderSeedControl() {
        ImGui.text("Seed");

        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Controls the noise pattern. Click Randomize for a new pattern, or enter a seed value manually.");
        }

        ImGui.spacing();

        boolean changed = false;
        float panelWidth = ImGui.getContentRegionAvailX();

        // Row 1: Current seed display
        ImGui.text("Current: " + noiseSeed);

        ImGui.spacing();

        // Row 2: Manual input field
        ImGui.setNextItemWidth(panelWidth);
        if (ImGui.inputText("##seedInput", seedInput,
            imgui.flag.ImGuiInputTextFlags.CharsDecimal |
            imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {

            try {
                long newSeed = Long.parseLong(seedInput.get());
                noiseSeed = newSeed;
                changed = true;
            } catch (NumberFormatException e) {
                // Invalid input - restore previous value
                seedInput.set(String.valueOf(noiseSeed));
            }
        }

        // Show hint for manual input
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Enter a seed value and press Enter to apply");
        }

        ImGui.spacing();

        // Row 3: Randomize button
        if (ImGui.button("Randomize", panelWidth, 0)) {
            noiseSeed = System.nanoTime();
            seedInput.set(String.valueOf(noiseSeed)); // Sync input buffer
            changed = true;
        }

        return changed;
    }

    private boolean renderDiffusionSliders() {
        boolean changed = false;

        // Blur slider - always visible
        ImGui.text("Blur/Smoothing");

        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Applies blur to the generated noise pattern.\n0 = no blur, 1 = maximum blur");
        }

        ImGui.sameLine();
        ImGui.text(String.format("%.0f%%", blur * 100));

        ImGui.spacing();

        float[] blurArray = {blur};
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        if (ImGui.sliderFloat("##blur", blurArray, 0.0f, 1.0f, "")) {
            blur = blurArray[0];
            changed = true;
        }

        ImGui.spacing();

        // Collapsible section for additional diffusion options
        if (ImGui.collapsingHeader("More Diffusions", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();

            // Octaves slider
            ImGui.text("Octaves/Fractal");

            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Number of noise layers (Fractal Brownian Motion).\nHigher values create more detailed, complex patterns.");
            }

            ImGui.sameLine();
            ImGui.text(String.valueOf(octaves));

            ImGui.spacing();

            int[] octavesArray = {octaves};
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            if (ImGui.sliderInt("##octaves", octavesArray, 1, 8, "")) {
                octaves = octavesArray[0];
                changed = true;
            }

            ImGui.spacing();
            ImGui.spacing();

            // Spread slider
            ImGui.text("Spread/Dispersion");

            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Controls contrast and value distribution.\n0 = flat/minimal spread, 1 = full range");
            }

            ImGui.sameLine();
            ImGui.text(String.format("%.0f%%", spread * 100));

            ImGui.spacing();

            float[] spreadArray = {spread};
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            if (ImGui.sliderFloat("##spread", spreadArray, 0.0f, 1.0f, "")) {
                spread = spreadArray[0];
                changed = true;
            }

            ImGui.spacing();
            ImGui.spacing();

            // Edge Softness slider
            ImGui.text("Edge Softness");

            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Softens transitions between noise values.\n0 = sharp edges, 1 = very soft gradients");
            }

            ImGui.sameLine();
            ImGui.text(String.format("%.0f%%", edgeSoftness * 100));

            ImGui.spacing();

            float[] edgeSoftnessArray = {edgeSoftness};
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
            if (ImGui.sliderFloat("##edgeSoftness", edgeSoftnessArray, 0.0f, 1.0f, "")) {
                edgeSoftness = edgeSoftnessArray[0];
                changed = true;
            }

            ImGui.unindent();
        }

        return changed;
    }

    private void renderPreview() {
        ImGui.text("Preview");

        ImGui.spacing();

        float panelWidth = ImGui.getContentRegionAvailX();
        float previewSize = Math.min(panelWidth, 200.0f);

        // Center the preview
        float offsetX = (panelWidth - previewSize) / 2.0f;
        if (offsetX > 0) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + offsetX);
        }

        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Background
        drawList.addRectFilled(
            cursorPos.x, cursorPos.y,
            cursorPos.x + previewSize, cursorPos.y + previewSize,
            ImColor.rgba(255, 255, 255, 255)
        );

        // Generate and draw noise preview
        renderNoisePreview(drawList, cursorPos.x, cursorPos.y, previewSize);

        // Border
        drawList.addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + previewSize, cursorPos.y + previewSize,
            ImColor.rgba(200, 200, 200, 255), 0, 0, 1.0f
        );

        ImGui.dummy(previewSize, previewSize);
    }

    private void renderNoisePreview(ImDrawList drawList, float x, float y, float previewSize) {
        NoiseGenerator generator = createGenerator(); // Uses consistent noiseSeed
        float cellSize = previewSize / PREVIEW_RESOLUTION;

        for (int py = 0; py < PREVIEW_RESOLUTION; py++) {
            for (int px = 0; px < PREVIEW_RESOLUTION; px++) {
                // Generate noise value
                float sx = px * scale;
                float sy = py * scale;
                float noise = generator.generate(sx, sy);

                // Apply gradient if enabled
                if (gradient) {
                    float gradientFactor = ((float) px / PREVIEW_RESOLUTION +
                                          (float) py / PREVIEW_RESOLUTION) * 0.5f;
                    noise = lerp(noise, gradientFactor, 0.5f);
                }

                // Convert to grayscale color
                int gray = (int) (noise * 255);
                int color = ImColor.rgba(gray, gray, gray, 255);

                // Draw cell
                float cellX = x + px * cellSize;
                float cellY = y + py * cellSize;
                drawList.addRectFilled(cellX, cellY,
                                      cellX + cellSize + 1, cellY + cellSize + 1,
                                      color);
            }
        }
    }

    private void renderActionButtons() {
        LayerManager layerManager = getLayerManager();
        boolean canApply = layerManager != null && layerManager.getActiveLayer() != null;

        float panelWidth = ImGui.getContentRegionAvailX();
        float buttonWidth = (panelWidth - 2.0f) / 2.0f;

        // Accept button (green)
        if (previewActive) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.7f, 0.3f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.25f, 0.8f, 0.35f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.15f, 0.6f, 0.25f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);

            if (ImGui.button("Accept", buttonWidth, 0)) {
                acceptNoise();
            }

            ImGui.popStyleColor(4);
        } else {
            if (!canApply) {
                ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.Alpha, 0.5f);
            }

            if (ImGui.button("Start Preview", buttonWidth, 0)) {
                if (canApply) {
                    startPreview();
                }
            }

            if (!canApply) {
                ImGui.popStyleVar();
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("No active layer selected");
                }
            }
        }

        ImGui.sameLine(0, 2.0f);

        // Cancel button (red)
        if (previewActive) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.8f, 0.2f, 0.2f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.9f, 0.25f, 0.25f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.7f, 0.15f, 0.15f, 1.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);

            if (ImGui.button("Cancel", buttonWidth, 0)) {
                cancelPreview();
            }

            ImGui.popStyleColor(4);
        }
    }

    private void startPreview() {
        LayerManager layerManager = getLayerManager();
        if (layerManager == null) return;

        activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) return;

        // Backup original pixels
        PixelCanvas canvas = activeLayer.getCanvas();
        originalPixels = new HashMap<>();
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (activeSelection == null || activeSelection.contains(x, y)) {
                    int key = y * width + x;
                    originalPixels.put(key, canvas.getPixel(x, y));
                }
            }
        }

        previewActive = true;
        updatePreview();

        logger.info("Started noise preview");
    }

    private void updatePreview() {
        if (!previewActive || activeLayer == null || originalPixels == null) return;

        PixelCanvas canvas = activeLayer.getCanvas();
        int width = canvas.getWidth();

        // First restore original pixels
        for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % width;
            int y = key / width;
            canvas.setPixel(x, y, entry.getValue());
        }

        // Then apply noise to canvas in real-time
        NoiseGenerator generator = createGenerator();
        NoiseConfig config = new NoiseConfig(generator, strength, gradient, scale, noiseSeed,
                                              blur, octaves, spread, edgeSoftness);
        NoiseFilter filter = new NoiseFilter(config);

        filter.apply(canvas, activeSelection);

        // Mark composite dirty
        LayerManager currentLayerManager = getLayerManager();
        if (currentLayerManager != null) {
            currentLayerManager.markCompositeDirty();
        }
    }

    private void acceptNoise() {
        CommandHistory commandHistory = getCommandHistory();
        if (!previewActive || activeLayer == null || commandHistory == null) return;

        // Create command for undo/redo
        PixelCanvas canvas = activeLayer.getCanvas();
        DrawCommand command = new DrawCommand(canvas, "Apply Noise");

        int width = canvas.getWidth();

        // Record changes
        for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % width;
            int y = key / width;
            int oldColor = entry.getValue();
            int newColor = canvas.getPixel(x, y);
            command.recordPixelChange(x, y, oldColor, newColor);
        }

        // Add to command history
        commandHistory.executeCommand(command);

        // Clean up preview state
        previewActive = false;
        originalPixels = null;
        activeLayer = null;

        logger.info("Accepted {} noise filter (strength: {}%, mode: {})",
                   algorithmNames[selectedAlgorithm], (int)(strength * 100),
                   gradient ? "gradient" : "uniform");
    }

    private void cancelPreview() {
        if (!previewActive || activeLayer == null) return;

        // Restore original pixels
        PixelCanvas canvas = activeLayer.getCanvas();
        int width = canvas.getWidth();

        for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % width;
            int y = key / width;
            canvas.setPixel(x, y, entry.getValue());
        }

        // Mark composite dirty
        LayerManager layerManager = getLayerManager();
        if (layerManager != null) {
            layerManager.markCompositeDirty();
        }

        // Clean up preview state
        previewActive = false;
        originalPixels = null;
        activeLayer = null;

        logger.info("Cancelled noise preview");
    }

    private NoiseGenerator createGenerator() {
        switch (selectedAlgorithm) {
            case 0:
                return new SimplexNoiseGenerator(noiseSeed);
            case 1:
                return new ValueNoiseGenerator(noiseSeed);
            case 2:
                return new WhiteNoiseGenerator(noiseSeed);
            default:
                return new SimplexNoiseGenerator(noiseSeed);
        }
    }

    private float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    /**
     * Get current LayerManager from provider.
     * This ensures we always have the latest LayerManager instance, even after loading OMT files.
     */
    private LayerManager getLayerManager() {
        return layerManagerProvider != null ? layerManagerProvider.getLayerManager() : null;
    }

    /**
     * Get current CommandHistory from provider.
     * This ensures we always have the latest CommandHistory instance, even after loading OMT files.
     */
    private CommandHistory getCommandHistory() {
        return commandHistoryProvider != null ? commandHistoryProvider.getCommandHistory() : null;
    }
}
