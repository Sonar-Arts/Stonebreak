package com.stonebreak.ui.terrainmapper.visualization;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainmapper.visualization.impl.HeightMapVisualizer;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;
import java.util.Map;

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

    // Tooltip positioning configuration
    private static final float TOOLTIP_OFFSET_X = 15.0f;
    private static final float TOOLTIP_OFFSET_Y = 15.0f;
    private static final float TOOLTIP_MARGIN = 8.0f;

    // Cache for performance
    private double[][] cachedSamples;
    private int cachedSamplesX;
    private int cachedSamplesZ;

    // Hover lookup cache for O(1) world coord → value mapping
    private Map<Long, Double> hoverLookupCache;

    // Hover state
    private int hoverWorldX = -1;
    private int hoverWorldZ = -1;
    private double hoverValue = 0.0;
    private String hoverDisplayText = "";
    private boolean hasHoverData = false;

    // Hover cache for performance optimization
    private int cachedHoverWorldX = Integer.MIN_VALUE;
    private int cachedHoverWorldZ = Integer.MIN_VALUE;
    private double cachedHoverRawValue = 0.0;
    private String cachedHoverDisplayText = "";

    // Text measurement cache to avoid redundant NanoVG calls
    private float cachedTextWidth = 0.0f;
    private float cachedTextHeight = 0.0f;
    private String lastMeasuredText = "";

    /**
     * Holds calculated tooltip position and dimensions.
     */
    private static class TooltipBounds {
        final float x, y, width, height;

        TooltipBounds(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Creates a new noise renderer.
     *
     * @param uiRenderer UI renderer for NanoVG access
     */
    public NoiseRenderer(UIRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }

    /**
     * Packs world X/Z coordinates into a single long for HashMap key.
     * Upper 32 bits: worldX, Lower 32 bits: worldZ
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Packed coordinate key
     */
    private long packCoords(int worldX, int worldZ) {
        return ((long)worldX << 32) | (worldZ & 0xFFFFFFFFL);
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

        // Build hover lookup table for O(1) world coord → value mapping
        hoverLookupCache = new HashMap<>(samplesX * samplesZ);
        for (int sx = 0; sx < samplesX; sx++) {
            for (int sz = 0; sz < samplesZ; sz++) {
                // Recalculate world coordinates using EXACT same formula as render loop
                float relativeX = (sx * SAMPLE_RESOLUTION) / mapWidth;
                float relativeZ = (sz * SAMPLE_RESOLUTION) / mapHeight;
                int worldX = worldMinX + (int)(relativeX * (worldMaxX - worldMinX));
                int worldZ = worldMinZ + (int)(relativeZ * (worldMaxZ - worldMinZ));

                long key = packCoords(worldX, worldZ);
                hoverLookupCache.put(key, cachedSamples[sx][sz]);
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
        // Guard clause: Check if mouse is within map bounds
        if (mouseX < mapX || mouseX > mapX + mapWidth ||
                mouseY < mapY || mouseY > mapY + mapHeight) {
            hasHoverData = false;
            return;
        }

        // Convert screen to world coordinates
        float relativeX = (mouseX - mapX) / mapWidth;
        float relativeZ = (mouseY - mapY) / mapHeight;

        // Guard clause: Check relative coordinates
        if (relativeX < 0 || relativeX > 1 || relativeZ < 0 || relativeZ > 1) {
            hasHoverData = false;
            return;
        }

        int worldX = worldMinX + (int) (relativeX * (worldMaxX - worldMinX));
        int worldZ = worldMinZ + (int) (relativeZ * (worldMaxZ - worldMinZ));

        // Layer 1: Check if world coordinates unchanged (cache hit)
        if (worldX == cachedHoverWorldX && worldZ == cachedHoverWorldZ) {
            hoverWorldX = worldX;
            hoverWorldZ = worldZ;
            hoverValue = cachedHoverRawValue;
            hoverDisplayText = cachedHoverDisplayText;
            hasHoverData = true;
            return;
        }

        // Layer 2: Try to reuse hover cache
        double rawValue = tryLookupFromHoverCache(worldX, worldZ);

        // Layer 3: Sample fresh if not in cache
        if (Double.isNaN(rawValue)) {
            rawValue = visualizer.sample(worldX, worldZ, seed);
        }

        // Update hover state
        hoverWorldX = worldX;
        hoverWorldZ = worldZ;
        hoverValue = rawValue;
        hoverDisplayText = formatContextAwareValue(visualizer, rawValue, worldX, worldZ);
        hasHoverData = true;

        // Update hover cache
        cachedHoverWorldX = worldX;
        cachedHoverWorldZ = worldZ;
        cachedHoverRawValue = rawValue;
        cachedHoverDisplayText = hoverDisplayText;
    }

    /**
     * Looks up a cached hover value using exact world coordinate matching.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Cached raw value if found, NaN if not in cache
     */
    private double tryLookupFromHoverCache(int worldX, int worldZ) {
        // Guard clause: No cache available
        if (hoverLookupCache == null) {
            return Double.NaN;
        }

        long key = packCoords(worldX, worldZ);
        Double cachedValue = hoverLookupCache.get(key);
        return (cachedValue != null) ? cachedValue : Double.NaN;
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
     * Calculates optimal tooltip position with terrain mapper layout awareness.
     *
     * Handles boundary detection and smart repositioning to keep tooltips visible
     * within the map area (accounting for sidebar and footer).
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param textWidth Measured text width
     * @param textHeight Measured text height
     * @param padding Tooltip padding
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @return Calculated tooltip bounds
     */
    private TooltipBounds calculateTooltipPosition(
            float mouseX, float mouseY,
            float textWidth, float textHeight,
            float padding,
            int windowWidth, int windowHeight) {

        // Calculate tooltip dimensions
        float tooltipWidth = textWidth + (padding * 2);
        float tooltipHeight = textHeight + (padding * 2);

        // Define map area bounds (excluding sidebar and footer)
        float mapAreaLeft = TerrainMapperConfig.SIDEBAR_WIDTH;
        float mapAreaTop = 0;
        float mapAreaRight = windowWidth;
        float mapAreaBottom = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

        // Start with default position (right and above cursor)
        float finalX = mouseX + TOOLTIP_OFFSET_X;
        float finalY = mouseY - TOOLTIP_OFFSET_Y - tooltipHeight;

        // Smart positioning: flip to opposite side if exceeding bounds
        if (finalX + tooltipWidth > mapAreaRight - TOOLTIP_MARGIN) {
            finalX = mouseX - tooltipWidth - TOOLTIP_OFFSET_X;  // Show to left
        }

        if (finalY < mapAreaTop + TOOLTIP_MARGIN) {
            finalY = mouseY + TOOLTIP_OFFSET_Y;  // Show below cursor
        }

        if (finalY + tooltipHeight > mapAreaBottom - TOOLTIP_MARGIN) {
            finalY = mouseY - tooltipHeight - TOOLTIP_OFFSET_Y;  // Show above cursor
        }

        // Ensure minimum margins are maintained (final clamping)
        finalX = Math.max(mapAreaLeft + TOOLTIP_MARGIN,
                Math.min(finalX, mapAreaRight - tooltipWidth - TOOLTIP_MARGIN));
        finalY = Math.max(mapAreaTop + TOOLTIP_MARGIN,
                Math.min(finalY, mapAreaBottom - tooltipHeight - TOOLTIP_MARGIN));

        return new TooltipBounds(finalX, finalY, tooltipWidth, tooltipHeight);
    }

    /**
     * Renders the hover overlay showing the value at the cursor position.
     *
     * Displays a tooltip near the mouse cursor with:
     * - Parameter name or "Height" or "Biome"
     * - Value (formatted based on visualizer type)
     * - World coordinates
     *
     * Smart positioning keeps the tooltip visible within the map area,
     * automatically repositioning when near window edges.
     *
     * @param mouseX Mouse X position (screen coordinates)
     * @param mouseY Mouse Y position (screen coordinates)
     * @param windowWidth Window width
     * @param windowHeight Window height
     */
    public void renderHoverOverlay(int mouseX, int mouseY, int windowWidth, int windowHeight) {
        // Guard clause: No hover data available
        if (!hasHoverData) {
            return;
        }

        long vg = uiRenderer.getVG();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Only measure text if content changed
            if (!hoverDisplayText.equals(lastMeasuredText)) {
                float[] bounds = new float[4];
                nvgFontSize(vg, 14);
                nvgFontFace(vg, "minecraft");
                nvgTextBounds(vg, 0, 0, hoverDisplayText, bounds);
                cachedTextWidth = bounds[2] - bounds[0];
                cachedTextHeight = bounds[3] - bounds[1];
                lastMeasuredText = hoverDisplayText;
            }

            float textWidth = cachedTextWidth;
            float textHeight = cachedTextHeight;
            float padding = 8;

            // Calculate smart tooltip position with boundary handling
            TooltipBounds tooltip = calculateTooltipPosition(
                    mouseX, mouseY, textWidth, textHeight, padding,
                    windowWidth, windowHeight
            );

            // Draw background
            nvgBeginPath(vg);
            nvgRect(vg, tooltip.x, tooltip.y, tooltip.width, tooltip.height);
            NVGColor bgColor = NVGColor.malloc(stack);
            uiRenderer.nvgRGBA(0, 0, 0, 220, bgColor);
            nvgFillColor(vg, bgColor);
            nvgFill(vg);

            // Draw border
            nvgBeginPath(vg);
            nvgRect(vg, tooltip.x, tooltip.y, tooltip.width, tooltip.height);
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
            nvgText(vg, tooltip.x + padding, tooltip.y + padding, hoverDisplayText);
        }
    }

    /**
     * Invalidates the sample cache.
     *
     * Call this when zoom, pan, seed, or visualizer changes to force resampling.
     */
    public void invalidateCache() {
        cachedSamples = null;
        hoverLookupCache = null;
        hasHoverData = false;
        // Invalidate hover cache
        cachedHoverWorldX = Integer.MIN_VALUE;
        cachedHoverWorldZ = Integer.MIN_VALUE;
        // Invalidate text measurement cache
        lastMeasuredText = "";
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
