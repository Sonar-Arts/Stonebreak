package com.stonebreak.ui.terrainmapper.visualization;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainmapper.visualization.impl.HeightMapVisualizer;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * Renders noise visualizations to the terrain mapper map area.
 *
 * This class handles:
 * - Grayscale heatmap rendering for noise parameters and terrain height
 * - Color-coded rendering for biome distribution
 * - Hover tracking for interactive value inspection
 * - Context-aware value formatting
 * - Caching for performance optimization
 *
 * Rendering Process:
 * 1. Sample noise at reduced resolution (2-4 pixels) for performance
 * 2. Normalize values to [0, 1] using visualizer's min/max
 * 3. Render as grayscale (0=black, 1=white) or color-coded (for biomes)
 * 4. Cache samples to avoid recomputing every frame
 * 5. Invalidate cache on zoom/pan/seed/visualizer changes
 *
 * Performance Optimizations:
 * - Sample resolution: 2 pixels (configurable)
 * - Caching: Stores normalized values in 2D array
 * - Memory: ~1-4 MB for 1920x1080 at 2-pixel resolution
 *
 * Thread Safety: Not thread-safe (designed for single-threaded UI rendering)
 */
public class NoiseRenderer {

    private final UIRenderer uiRenderer;

    // Sampling configuration
    private static final int SAMPLE_RESOLUTION = 2; // Sample every 2 pixels

    // Cache for performance
    private double[][] cachedSamples;
    private int cachedSamplesX;
    private int cachedSamplesZ;

    // Hover state
    private int hoverWorldX = -1;
    private int hoverWorldZ = -1;
    private double hoverValue = 0.0;
    private String hoverDisplayText = "";
    private boolean hasHoverData = false;

    /**
     * Creates a new noise renderer.
     *
     * @param uiRenderer UI renderer for NanoVG access
     */
    public NoiseRenderer(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }

    /**
     * Renders noise visualization to the map area.
     *
     * Samples the visualizer at each screen position, normalizes values,
     * and renders as either grayscale or color-coded depending on the visualizer type.
     *
     * @param visualizer Noise visualizer to use
     * @param seed World seed
     * @param mapX Screen X of map area
     * @param mapY Screen Y of map area
     * @param mapWidth Width of map area in pixels
     * @param mapHeight Height of map area in pixels
     * @param worldMinX Minimum world X coordinate
     * @param worldMinZ Minimum world Z coordinate
     * @param worldMaxX Maximum world X coordinate
     * @param worldMaxZ Maximum world Z coordinate
     */
    public void render(
            NoiseVisualizer visualizer,
            long seed,
            float mapX, float mapY,
            float mapWidth, float mapHeight,
            int worldMinX, int worldMinZ,
            int worldMaxX, int worldMaxZ
    ) {
        long vg = uiRenderer.getVG();

        // Calculate sampling grid
        int samplesX = (int) (mapWidth / SAMPLE_RESOLUTION);
        int samplesZ = (int) (mapHeight / SAMPLE_RESOLUTION);

        // Initialize or resize cache if needed
        if (cachedSamples == null ||
                cachedSamplesX != samplesX ||
                cachedSamplesZ != samplesZ) {
            cachedSamples = new double[samplesX][samplesZ];
            cachedSamplesX = samplesX;
            cachedSamplesZ = samplesZ;
        }

        // Sample and cache
        for (int sx = 0; sx < samplesX; sx++) {
            for (int sz = 0; sz < samplesZ; sz++) {
                // Map screen pixel to world coordinate
                float relativeX = (sx * SAMPLE_RESOLUTION) / mapWidth;
                float relativeZ = (sz * SAMPLE_RESOLUTION) / mapHeight;
                int worldX = worldMinX + (int) (relativeX * (worldMaxX - worldMinX));
                int worldZ = worldMinZ + (int) (relativeZ * (worldMaxZ - worldMinZ));

                // Sample and store raw value
                double rawValue = visualizer.sample(worldX, worldZ, seed);
                cachedSamples[sx][sz] = rawValue;
            }
        }

        // Determine if this is a biome visualizer (for color-coded rendering)
        boolean isColorBiomeMode = (visualizer instanceof BiomeVisualizer);

        // Render using NanoVG
        for (int sx = 0; sx < samplesX; sx++) {
            for (int sz = 0; sz < samplesZ; sz++) {
                double rawValue = cachedSamples[sx][sz];
                double normalized = visualizer.normalize(rawValue);

                // Use try-with-resources INSIDE the loop to properly manage stack allocations
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    // Choose rendering mode
                    if (isColorBiomeMode) {
                        // Color-coded biome rendering
                        BiomeVisualizer biomeViz = (BiomeVisualizer) visualizer;
                        BiomeType biome = biomeViz.getBiomeFromValue(rawValue);
                        int[] color = biomeViz.getBiomeColor(biome);

                        NVGColor nvgColor = NVGColor.malloc(stack);
                        uiRenderer.nvgRGBA(color[0], color[1], color[2], 255, nvgColor);
                        nvgFillColor(vg, nvgColor);
                    } else {
                        // Grayscale rendering for parameters and height
                        float gray = (float) normalized;
                        int grayByte = (int) (gray * 255);

                        NVGColor nvgColor = NVGColor.malloc(stack);
                        uiRenderer.nvgRGBA(grayByte, grayByte, grayByte, 255, nvgColor);
                        nvgFillColor(vg, nvgColor);
                    }
                }

                // Draw rectangle
                nvgBeginPath(vg);
                nvgRect(vg,
                        mapX + sx * SAMPLE_RESOLUTION,
                        mapY + sz * SAMPLE_RESOLUTION,
                        SAMPLE_RESOLUTION,
                        SAMPLE_RESOLUTION);
                nvgFill(vg);
            }
        }
    }

    /**
     * Updates hover state by sampling the visualizer at the given screen position.
     *
     * Called on every mouse move to update the hover value display.
     *
     * @param visualizer Current noise visualizer
     * @param seed World seed
     * @param mouseX Mouse X position (screen coordinates)
     * @param mouseY Mouse Y position (screen coordinates)
     * @param mapX Screen X of map area
     * @param mapY Screen Y of map area
     * @param mapWidth Width of map area in pixels
     * @param mapHeight Height of map area in pixels
     * @param worldMinX Minimum world X coordinate
     * @param worldMinZ Minimum world Z coordinate
     * @param worldMaxX Maximum world X coordinate
     * @param worldMaxZ Maximum world Z coordinate
     */
    public void updateHover(
            NoiseVisualizer visualizer,
            long seed,
            int mouseX, int mouseY,
            float mapX, float mapY,
            float mapWidth, float mapHeight,
            int worldMinX, int worldMinZ,
            int worldMaxX, int worldMaxZ
    ) {
        // Check if mouse is within map bounds
        if (mouseX < mapX || mouseX > mapX + mapWidth ||
                mouseY < mapY || mouseY > mapY + mapHeight) {
            hasHoverData = false;
            return;
        }

        // Convert screen to world coordinates
        float relativeX = (mouseX - mapX) / mapWidth;
        float relativeZ = (mouseY - mapY) / mapHeight;

        if (relativeX < 0 || relativeX > 1 || relativeZ < 0 || relativeZ > 1) {
            hasHoverData = false;
            return;
        }

        int worldX = worldMinX + (int) (relativeX * (worldMaxX - worldMinX));
        int worldZ = worldMinZ + (int) (relativeZ * (worldMaxZ - worldMinZ));

        // Sample value at this position
        double rawValue = visualizer.sample(worldX, worldZ, seed);

        // Update hover state
        hoverWorldX = worldX;
        hoverWorldZ = worldZ;
        hoverValue = rawValue;
        hoverDisplayText = formatContextAwareValue(visualizer, rawValue, worldX, worldZ);
        hasHoverData = true;
    }

    /**
     * Formats the hover value based on the visualizer type.
     *
     * Context-aware formatting:
     * - HeightMapVisualizer: "Height: 85 (World: 1024, -512)"
     * - BiomeVisualizer: "Biome: Desert (World: 1024, -512)"
     * - Parameter visualizers: "Continentalness: 0.742 (World: 1024, -512)"
     *
     * @param visualizer Current visualizer
     * @param rawValue Raw value from sample
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Formatted display text
     */
    private String formatContextAwareValue(NoiseVisualizer visualizer, double rawValue, int worldX, int worldZ) {
        if (visualizer instanceof HeightMapVisualizer) {
            // Height map: show integer height
            return String.format("Height: %.0f (World: %d, %d)", rawValue, worldX, worldZ);
        } else if (visualizer instanceof BiomeVisualizer biomeViz) {
            // Biome: show biome name
            BiomeType biome = biomeViz.getBiomeFromValue(rawValue);
            String biomeName = biomeViz.getBiomeName(biome);
            return String.format("Biome: %s (World: %d, %d)", biomeName, worldX, worldZ);
        } else {
            // Parameters: show name and value with 3 decimal places
            return String.format("%s: %.3f (World: %d, %d)", visualizer.getName(), rawValue, worldX, worldZ);
        }
    }

    /**
     * Renders the hover overlay showing the value at the cursor position.
     *
     * Displays a tooltip near the mouse cursor with:
     * - Parameter name or "Height" or "Biome"
     * - Value (formatted based on visualizer type)
     * - World coordinates
     *
     * @param mouseX Mouse X position (screen coordinates)
     * @param mouseY Mouse Y position (screen coordinates)
     */
    public void renderHoverOverlay(int mouseX, int mouseY) {
        if (!hasHoverData) {
            return;
        }

        long vg = uiRenderer.getVG();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Measure text width
            float[] bounds = new float[4];
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "minecraft");
            nvgTextBounds(vg, 0, 0, hoverDisplayText, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];

            // Calculate tooltip position (offset from cursor to avoid obscuring view)
            float tooltipX = mouseX + 15;
            float tooltipY = mouseY - 30;
            float padding = 8;

            // Draw background
            nvgBeginPath(vg);
            nvgRect(vg, tooltipX, tooltipY, textWidth + padding * 2, textHeight + padding * 2);
            NVGColor bgColor = NVGColor.malloc(stack);
            uiRenderer.nvgRGBA(0, 0, 0, 220, bgColor);
            nvgFillColor(vg, bgColor);
            nvgFill(vg);

            // Draw border
            nvgBeginPath(vg);
            nvgRect(vg, tooltipX, tooltipY, textWidth + padding * 2, textHeight + padding * 2);
            NVGColor borderColor = NVGColor.malloc(stack);
            uiRenderer.nvgRGBA(100, 100, 100, 255, borderColor);
            nvgStrokeColor(vg, borderColor);
            nvgStrokeWidth(vg, 1);
            nvgStroke(vg);

            // Draw text
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
            NVGColor textColor = NVGColor.malloc(stack);
            uiRenderer.nvgRGBA(255, 255, 255, 255, textColor);
            nvgFillColor(vg, textColor);
            nvgText(vg, tooltipX + padding, tooltipY + padding, hoverDisplayText);
        }
    }

    /**
     * Invalidates the sample cache.
     *
     * Call this when zoom, pan, seed, or visualizer changes to force resampling.
     */
    public void invalidateCache() {
        cachedSamples = null;
        hasHoverData = false;
    }

    /**
     * Checks if hover data is available.
     *
     * @return true if the mouse is over the map and hover data is valid
     */
    public boolean hasHoverData() {
        return hasHoverData;
    }

    /**
     * Gets the current hover display text.
     *
     * @return Formatted hover text (empty if no hover data)
     */
    public String getHoverDisplayText() {
        return hoverDisplayText;
    }
}
