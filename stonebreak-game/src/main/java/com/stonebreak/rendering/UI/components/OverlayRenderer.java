package com.stonebreak.rendering.UI.components;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.menus.BlockIconRenderer;
import com.stonebreak.rendering.UI.menus.ItemIconRenderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

/**
 * Handles rendering of UI overlay elements that need to appear above all other UI components.
 * This includes tooltips, item icons, block icons, underwater overlay, and other UI elements that should render on the top layer.
 */
public class OverlayRenderer {
    
    private final BlockIconRenderer blockIconRenderer;
    private final ItemIconRenderer itemIconRenderer;
    private final UnderwaterOverlayRenderer underwaterOverlayRenderer;
    
    public OverlayRenderer(BlockIconRenderer blockIconRenderer, ItemIconRenderer itemIconRenderer) {
        this.blockIconRenderer = blockIconRenderer;
        this.itemIconRenderer = itemIconRenderer;
        this.underwaterOverlayRenderer = new UnderwaterOverlayRenderer();
    }
    
    /**
     * Renders all overlay UI elements for the current game state.
     * This includes tooltips and other elements that should appear above all other UI.
     * This should be called after all other UI rendering is complete.
     * Note: Underwater overlay is NOT rendered here - it's rendered earlier in the pipeline before UI.
     */
    public void renderOverlay(Game game, int windowWidth, int windowHeight) {
        if (game == null) return;

        // Render inventory tooltips (both full inventory and hotbar)
        renderInventoryTooltips(game, windowWidth, windowHeight);

        // Render recipe book tooltips if visible
        renderRecipeBookTooltips(game);

        // Render workbench tooltips if visible
        renderWorkbenchTooltips(game);

        // Render dragged items last (highest z-index) so they appear above everything
        renderDraggedItems(game, windowWidth, windowHeight);
    }
    
    /**
     * Renders inventory-related tooltips including both full inventory and hotbar tooltips.
     */
    private void renderInventoryTooltips(Game game, int windowWidth, int windowHeight) {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null) {
            if (inventoryScreen.isVisible()) {
                inventoryScreen.renderTooltipsOnly(windowWidth, windowHeight);
            } else {
                inventoryScreen.renderHotbarTooltipsOnly(windowWidth, windowHeight);
            }
        }
    }
    
    /**
     * Renders recipe book tooltips if the recipe book is visible.
     */
    private void renderRecipeBookTooltips(Game game) {
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible()) {
            recipeScreen.renderTooltipsOnly();
        }
    }
    
    /**
     * Renders workbench tooltips if the workbench is visible.
     */
    private void renderWorkbenchTooltips(Game game) {
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.renderTooltipsOnly();
        }
    }

    /**
     * Renders dragged items from all active UI screens.
     * This ensures dragged items appear above all other UI elements.
     */
    private void renderDraggedItems(Game game, int windowWidth, int windowHeight) {
        // Render inventory dragged items
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            inventoryScreen.renderDraggedItemOnly(windowWidth, windowHeight);
        }

        // Render workbench dragged items
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.renderDraggedItemOnly(windowWidth, windowHeight);
        }
    }

    /**
     * Renders the underwater overlay effect if the player is underwater.
     * This is a public method that should be called BEFORE UI rendering, not after.
     * It updates and renders the underwater tint effect behind all UI elements.
     */
    public void renderUnderwaterOverlay(Game game, int windowWidth, int windowHeight) {
        Player player = game.getPlayer();
        if (player != null) {
            underwaterOverlayRenderer.update(player, game.getDeltaTime());
            underwaterOverlayRenderer.render(windowWidth, windowHeight);
        }
    }
    
    /**
     * Get the block icon renderer for rendering block icons in tooltips.
     */
    public BlockIconRenderer getBlockIconRenderer() {
        return blockIconRenderer;
    }
    
    /**
     * Get the item icon renderer for rendering item icons in tooltips.
     */
    public ItemIconRenderer getItemIconRenderer() {
        return itemIconRenderer;
    }
    
    // ===== Icon Rendering Methods (Overlay Layer) =====
    
    /**
     * Renders an item icon on the overlay layer (above all other UI).
     */
    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        if (itemIconRenderer != null) {
            itemIconRenderer.renderItemIcon(x, y, w, h, item, textureAtlas);
        }
    }
    
    /**
     * Renders an item icon for a block type on the overlay layer (above all other UI).
     */
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, TextureAtlas textureAtlas) {
        if (itemIconRenderer != null) {
            itemIconRenderer.renderItemIcon(x, y, w, h, blockTypeId, textureAtlas);
        }
    }
    
    /**
     * Renders a 3D block icon on the overlay layer (above all other UI).
     */
    public void renderBlockIcon(BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight, ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        if (blockIconRenderer != null) {
            blockIconRenderer.draw3DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas);
        }
    }
    
    /**
     * Get the underwater overlay renderer for access to underwater effects.
     */
    public UnderwaterOverlayRenderer getUnderwaterOverlayRenderer() {
        return underwaterOverlayRenderer;
    }
    
    /**
     * Cleanup method to release any resources.
     */
    public void cleanup() {
        // Currently no resources to cleanup, but method exists for consistency
    }
}