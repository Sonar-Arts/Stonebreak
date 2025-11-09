package com.stonebreak.ui.terrainmapper.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the footer section with Back and Create World buttons.
 */
public class TerrainFooterRenderer {

    private final UIRenderer uiRenderer;
    private final TerrainStateManager stateManager;

    public TerrainFooterRenderer(UIRenderer uiRenderer, TerrainStateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
    }

    /**
     * Renders the footer with action buttons.
     */
    public void render(int windowWidth, int windowHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float footerY = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

            // Draw footer background
            renderBackground(vg, windowWidth, windowHeight, stack);

            // Calculate button positions
            float backButtonX = TerrainMapperConfig.SIDEBAR_WIDTH + TerrainMapperConfig.PADDING;
            float createButtonX = windowWidth - TerrainMapperConfig.BUTTON_WIDTH - TerrainMapperConfig.PADDING;
            float simulateSeedButtonX = (backButtonX + TerrainMapperConfig.BUTTON_WIDTH + createButtonX) / 2.0f - TerrainMapperConfig.BUTTON_WIDTH / 2.0f;
            float buttonY = footerY + (TerrainMapperConfig.FOOTER_HEIGHT - TerrainMapperConfig.BUTTON_HEIGHT) / 2.0f;

            // Render buttons
            renderButton(vg, "Back", backButtonX, buttonY, stateManager.isBackButtonHovered(), stack);
            renderButton(vg, "Simulate Seed", simulateSeedButtonX, buttonY, stateManager.isSimulateSeedButtonHovered(), stack);
            renderButton(vg, "Create World", createButtonX, buttonY, stateManager.isCreateButtonHovered(), stack);
        }
    }

    /**
     * Renders the footer background.
     */
    private void renderBackground(long vg, int windowWidth, int windowHeight, MemoryStack stack) {
        float footerY = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

        nvgBeginPath(vg);
        nvgRect(vg, 0, footerY, windowWidth, TerrainMapperConfig.FOOTER_HEIGHT);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.FOOTER_BG_COLOR[0],
                TerrainMapperConfig.FOOTER_BG_COLOR[1],
                TerrainMapperConfig.FOOTER_BG_COLOR[2],
                TerrainMapperConfig.FOOTER_BG_COLOR[3],
                NVGColor.malloc(stack)
        ));
        nvgFill(vg);

        // Draw subtle border on top
        nvgBeginPath(vg);
        nvgMoveTo(vg, 0, footerY);
        nvgLineTo(vg, windowWidth, footerY);
        nvgStrokeColor(vg, uiRenderer.nvgRGBA((int)(0.1f * 255), (int)(0.1f * 255), (int)(0.1f * 255), 255, NVGColor.malloc(stack)));
        nvgStrokeWidth(vg, 2.0f);
        nvgStroke(vg);
    }

    /**
     * Renders a button with the given text at the specified position.
     */
    private void renderButton(long vg, String text, float x, float y, boolean hovered, MemoryStack stack) {
        // Draw button background
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, TerrainMapperConfig.BUTTON_WIDTH, TerrainMapperConfig.BUTTON_HEIGHT, 4);

        // Change color based on hover state
        if (hovered) {
            nvgFillColor(vg, uiRenderer.nvgRGBA((int)(0.4f * 255), (int)(0.4f * 255), (int)(0.4f * 255), 255, NVGColor.malloc(stack)));
        } else {
            nvgFillColor(vg, uiRenderer.nvgRGBA((int)(0.3f * 255), (int)(0.3f * 255), (int)(0.3f * 255), 255, NVGColor.malloc(stack)));
        }
        nvgFill(vg);

        // Draw button border
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, TerrainMapperConfig.BUTTON_WIDTH, TerrainMapperConfig.BUTTON_HEIGHT, 4);
        nvgStrokeColor(vg, uiRenderer.nvgRGBA((int)(0.5f * 255), (int)(0.5f * 255), (int)(0.5f * 255), 255, NVGColor.malloc(stack)));
        nvgStrokeWidth(vg, 2.0f);
        nvgStroke(vg);

        // Draw button text
        nvgFontSize(vg, 18);
        nvgFontFace(vg, "minecraft");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.TEXT_COLOR[0],
                TerrainMapperConfig.TEXT_COLOR[1],
                TerrainMapperConfig.TEXT_COLOR[2],
                TerrainMapperConfig.TEXT_COLOR[3],
                NVGColor.malloc(stack)
        ));
        nvgText(vg,
                x + TerrainMapperConfig.BUTTON_WIDTH / 2.0f,
                y + TerrainMapperConfig.BUTTON_HEIGHT / 2.0f,
                text);
    }

    /**
     * Checks if the Back button is hovered at the given mouse position.
     */
    public boolean isBackButtonHovered(int windowWidth, int windowHeight, double mouseX, double mouseY) {
        float backButtonX = TerrainMapperConfig.SIDEBAR_WIDTH + TerrainMapperConfig.PADDING;
        float footerY = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;
        float buttonY = footerY + (TerrainMapperConfig.FOOTER_HEIGHT - TerrainMapperConfig.BUTTON_HEIGHT) / 2.0f;

        return mouseX >= backButtonX && mouseX <= backButtonX + TerrainMapperConfig.BUTTON_WIDTH &&
                mouseY >= buttonY && mouseY <= buttonY + TerrainMapperConfig.BUTTON_HEIGHT;
    }

    /**
     * Checks if the Create World button is hovered at the given mouse position.
     */
    public boolean isCreateButtonHovered(int windowWidth, int windowHeight, double mouseX, double mouseY) {
        float createButtonX = windowWidth - TerrainMapperConfig.BUTTON_WIDTH - TerrainMapperConfig.PADDING;
        float footerY = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;
        float buttonY = footerY + (TerrainMapperConfig.FOOTER_HEIGHT - TerrainMapperConfig.BUTTON_HEIGHT) / 2.0f;

        return mouseX >= createButtonX && mouseX <= createButtonX + TerrainMapperConfig.BUTTON_WIDTH &&
                mouseY >= buttonY && mouseY <= buttonY + TerrainMapperConfig.BUTTON_HEIGHT;
    }

    /**
     * Checks if the Simulate Seed button is hovered at the given mouse position.
     */
    public boolean isSimulateSeedButtonHovered(int windowWidth, int windowHeight, double mouseX, double mouseY) {
        float backButtonX = TerrainMapperConfig.SIDEBAR_WIDTH + TerrainMapperConfig.PADDING;
        float createButtonX = windowWidth - TerrainMapperConfig.BUTTON_WIDTH - TerrainMapperConfig.PADDING;
        float simulateSeedButtonX = (backButtonX + TerrainMapperConfig.BUTTON_WIDTH + createButtonX) / 2.0f - TerrainMapperConfig.BUTTON_WIDTH / 2.0f;
        float footerY = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;
        float buttonY = footerY + (TerrainMapperConfig.FOOTER_HEIGHT - TerrainMapperConfig.BUTTON_HEIGHT) / 2.0f;

        return mouseX >= simulateSeedButtonX && mouseX <= simulateSeedButtonX + TerrainMapperConfig.BUTTON_WIDTH &&
                mouseY >= buttonY && mouseY <= buttonY + TerrainMapperConfig.BUTTON_HEIGHT;
    }
}
