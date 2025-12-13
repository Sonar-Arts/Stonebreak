package com.stonebreak.ui.terrainmapper.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.world.generation.TerrainGeneratorType;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the left sidebar of the Terrain Mapper screen.
 * Includes the title, world name input, and seed input fields.
 */
public class TerrainSidebarRenderer {

    private final UIRenderer uiRenderer;
    private final TerrainStateManager stateManager;

    public TerrainSidebarRenderer(UIRenderer uiRenderer, TerrainStateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
    }

    /**
     * Renders the complete sidebar UI.
     */
    public void render(int windowWidth, int windowHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Draw sidebar background
            renderBackground(vg, windowHeight, stack);

            // Draw title
            renderTitle(vg, stack);

            // Calculate input field positions
            int fieldY = TerrainMapperConfig.TITLE_HEIGHT + TerrainMapperConfig.PADDING;

            // Set bounds for world name field
            stateManager.getWorldNameField().setBounds(
                    TerrainMapperConfig.PADDING,
                    fieldY,
                    TerrainMapperConfig.INPUT_FIELD_WIDTH,
                    TerrainMapperConfig.INPUT_FIELD_HEIGHT
            );

            // Set bounds for seed field
            stateManager.getSeedField().setBounds(
                    TerrainMapperConfig.PADDING,
                    fieldY + TerrainMapperConfig.INPUT_FIELD_HEIGHT + TerrainMapperConfig.COMPONENT_SPACING,
                    TerrainMapperConfig.INPUT_FIELD_WIDTH,
                    TerrainMapperConfig.INPUT_FIELD_HEIGHT
            );

            // Render labels
            renderFieldLabel(vg, "World Name:", TerrainMapperConfig.PADDING, fieldY - 5, stack);
            renderFieldLabel(vg, "Seed (Optional):", TerrainMapperConfig.PADDING,
                    fieldY + TerrainMapperConfig.INPUT_FIELD_HEIGHT + TerrainMapperConfig.COMPONENT_SPACING - 5, stack);

            // Render text input fields
            stateManager.getWorldNameField().render(uiRenderer, stack);
            stateManager.getSeedField().render(uiRenderer, stack);

            // Render generator type selector
            int generatorSelectorY = fieldY + (TerrainMapperConfig.INPUT_FIELD_HEIGHT + TerrainMapperConfig.COMPONENT_SPACING) * 2 + 30;
            renderGeneratorSelector(vg, generatorSelectorY, stack);

            // Render visualization mode selector
            int visualizationSelectorY = generatorSelectorY + (TerrainGeneratorType.values().length * 28) + 40;
            renderVisualizationSelector(vg, visualizationSelectorY, stack);
        }
    }

    /**
     * Renders the visualization mode selector.
     */
    private void renderVisualizationSelector(long vg, int selectorY, MemoryStack stack) {
        // Draw label
        renderFieldLabel(vg, "Visualization Mode:", TerrainMapperConfig.PADDING, selectorY - 5, stack);

        // Draw mode options
        int modeY = selectorY + 5;
        int modeHeight = 24;
        int modeSpacing = 4;

        for (TerrainStateManager.VisualizationMode mode : TerrainStateManager.VisualizationMode.values()) {
            boolean isSelected = (mode == stateManager.getSelectedVisualizationMode());

            // Draw background
            nvgBeginPath(vg);
            nvgRect(vg, TerrainMapperConfig.PADDING, modeY, TerrainMapperConfig.INPUT_FIELD_WIDTH, modeHeight);
            if (isSelected) {
                nvgFillColor(vg, uiRenderer.nvgRGBA(100, 120, 140, 200, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, uiRenderer.nvgRGBA(60, 60, 60, 180, NVGColor.malloc(stack)));
            }
            nvgFill(vg);

            // Draw border
            nvgBeginPath(vg);
            nvgRect(vg, TerrainMapperConfig.PADDING, modeY, TerrainMapperConfig.INPUT_FIELD_WIDTH, modeHeight);
            if (isSelected) {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(150, 180, 200, 255, NVGColor.malloc(stack)));
            } else {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(100, 100, 100, 180, NVGColor.malloc(stack)));
            }
            nvgStrokeWidth(vg, isSelected ? 2.0f : 1.0f);
            nvgStroke(vg);

            // Draw text
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, TerrainMapperConfig.PADDING + 8, modeY + modeHeight / 2.0f, mode.getDisplayName());

            modeY += modeHeight + modeSpacing;
        }
    }

    /**
     * Checks if a click is within a visualization mode option bounds.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param selectorY Starting Y position of selector
     * @return The clicked visualization mode, or null if no mode was clicked
     */
    public TerrainStateManager.VisualizationMode getClickedMode(double mouseX, double mouseY, int selectorY) {
        int modeY = selectorY + 5;
        int modeHeight = 24;
        int modeSpacing = 4;

        for (TerrainStateManager.VisualizationMode mode : TerrainStateManager.VisualizationMode.values()) {
            if (mouseX >= TerrainMapperConfig.PADDING &&
                    mouseX <= TerrainMapperConfig.PADDING + TerrainMapperConfig.INPUT_FIELD_WIDTH &&
                    mouseY >= modeY &&
                    mouseY <= modeY + modeHeight) {
                return mode;
            }
            modeY += modeHeight + modeSpacing;
        }

        return null;
    }

    /**
     * Renders the terrain generator type selector.
     */
    private void renderGeneratorSelector(long vg, int selectorY, MemoryStack stack) {
        // Draw label
        renderFieldLabel(vg, "Terrain Generator:", TerrainMapperConfig.PADDING, selectorY - 5, stack);

        // Draw generator type options
        int generatorY = selectorY + 5;
        int generatorHeight = 24;
        int generatorSpacing = 4;

        for (TerrainGeneratorType type : TerrainGeneratorType.values()) {
            boolean isSelected = (type == stateManager.getSelectedGeneratorType());

            // Draw background
            nvgBeginPath(vg);
            nvgRect(vg, TerrainMapperConfig.PADDING, generatorY, TerrainMapperConfig.INPUT_FIELD_WIDTH, generatorHeight);
            if (isSelected) {
                nvgFillColor(vg, uiRenderer.nvgRGBA(100, 120, 140, 200, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, uiRenderer.nvgRGBA(60, 60, 60, 180, NVGColor.malloc(stack)));
            }
            nvgFill(vg);

            // Draw border
            nvgBeginPath(vg);
            nvgRect(vg, TerrainMapperConfig.PADDING, generatorY, TerrainMapperConfig.INPUT_FIELD_WIDTH, generatorHeight);
            if (isSelected) {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(150, 180, 200, 255, NVGColor.malloc(stack)));
            } else {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(100, 100, 100, 180, NVGColor.malloc(stack)));
            }
            nvgStrokeWidth(vg, isSelected ? 2.0f : 1.0f);
            nvgStroke(vg);

            // Draw text with generator name
            nvgFontSize(vg, 14);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, TerrainMapperConfig.PADDING + 8, generatorY + generatorHeight / 2.0f, type.getDisplayName());

            // Draw description in smaller font below
            nvgFontSize(vg, 11);
            nvgFillColor(vg, uiRenderer.nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            String description = type == TerrainGeneratorType.HYBRID_SDF ? "(Recommended)" : "(Alternative)";
            nvgText(vg, TerrainMapperConfig.PADDING + TerrainMapperConfig.INPUT_FIELD_WIDTH - 85,
                    generatorY + generatorHeight / 2.0f, description);

            generatorY += generatorHeight + generatorSpacing;
        }
    }

    /**
     * Checks if a click is within a generator type option bounds.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param selectorY Starting Y position of selector
     * @return The clicked generator type, or null if no type was clicked
     */
    public TerrainGeneratorType getClickedGeneratorType(double mouseX, double mouseY, int selectorY) {
        int generatorY = selectorY + 5;
        int generatorHeight = 24;
        int generatorSpacing = 4;

        for (TerrainGeneratorType type : TerrainGeneratorType.values()) {
            if (mouseX >= TerrainMapperConfig.PADDING &&
                    mouseX <= TerrainMapperConfig.PADDING + TerrainMapperConfig.INPUT_FIELD_WIDTH &&
                    mouseY >= generatorY &&
                    mouseY <= generatorY + generatorHeight) {
                return type;
            }
            generatorY += generatorHeight + generatorSpacing;
        }

        return null;
    }

    /**
     * Renders the sidebar background with dirt texture.
     */
    private void renderBackground(long vg, int windowHeight, MemoryStack stack) {
        // Draw dirt texture background instead of solid color
        uiRenderer.drawDirtBackgroundAt(0, 0, TerrainMapperConfig.SIDEBAR_WIDTH, windowHeight, 40);

        // Draw subtle border on the right
        nvgBeginPath(vg);
        nvgMoveTo(vg, TerrainMapperConfig.SIDEBAR_WIDTH, 0);
        nvgLineTo(vg, TerrainMapperConfig.SIDEBAR_WIDTH, windowHeight);
        nvgStrokeColor(vg, uiRenderer.nvgRGBA((int)(0.1f * 255), (int)(0.1f * 255), (int)(0.1f * 255), 255, NVGColor.malloc(stack)));
        nvgStrokeWidth(vg, 2.0f);
        nvgStroke(vg);
    }

    /**
     * Renders the "New World" title at the top of the sidebar.
     */
    private void renderTitle(long vg, MemoryStack stack) {
        nvgFontSize(vg, TerrainMapperConfig.TITLE_FONT_SIZE);
        nvgFontFace(vg, "minecraft");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.TEXT_COLOR[0],
                TerrainMapperConfig.TEXT_COLOR[1],
                TerrainMapperConfig.TEXT_COLOR[2],
                TerrainMapperConfig.TEXT_COLOR[3],
                NVGColor.malloc(stack)
        ));
        nvgText(vg, TerrainMapperConfig.PADDING, TerrainMapperConfig.PADDING, "New World");
    }

    /**
     * Renders a label above an input field.
     */
    private void renderFieldLabel(long vg, String label, float x, float y, MemoryStack stack) {
        nvgFontSize(vg, TerrainMapperConfig.LABEL_FONT_SIZE);
        nvgFontFace(vg, "minecraft");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_BOTTOM);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[0],
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[1],
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[2],
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[3],
                NVGColor.malloc(stack)
        ));
        nvgText(vg, x, y, label);
    }
}
