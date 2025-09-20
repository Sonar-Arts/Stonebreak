package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryPanelRenderer;

/**
 * Handles basic panel and title rendering for recipe screens.
 * Delegates to InventoryPanelRenderer to eliminate code duplication.
 */
public class RecipePanelRenderer {

    private RecipePanelRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws the main recipe book panel background and border.
     * Delegates to InventoryPanelRenderer to avoid code duplication.
     *
     * @param uiRenderer UI renderer instance
     * @param x X position of panel
     * @param y Y position of panel
     * @param width Panel width
     * @param height Panel height
     */
    public static void drawRecipePanel(UIRenderer uiRenderer, int x, int y, int width, int height) {
        // Delegate to existing inventory panel renderer to eliminate DRY violation
        InventoryPanelRenderer.drawInventoryPanel(uiRenderer, x, y, width, height);
    }

    /**
     * Draws the recipe book title text.
     * Delegates to InventoryPanelRenderer to avoid code duplication.
     *
     * @param uiRenderer UI renderer instance
     * @param centerX Center X position for title
     * @param centerY Center Y position for title
     * @param title Title text to display
     */
    public static void drawRecipeTitle(UIRenderer uiRenderer, float centerX, float centerY, String title) {
        // Delegate to existing inventory title renderer to eliminate DRY violation
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, centerX, centerY, title);
    }
}