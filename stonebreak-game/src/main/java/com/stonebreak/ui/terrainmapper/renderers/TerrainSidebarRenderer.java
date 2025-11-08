package com.stonebreak.ui.terrainmapper.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
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
        }
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
