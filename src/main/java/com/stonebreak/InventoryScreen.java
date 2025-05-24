package com.stonebreak;

import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

public class InventoryScreen {

    private final Inventory inventory;
    private boolean visible;
    private final Font font;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final RecipeManager recipeManager;
    private final RecipeBookScreen recipeBookScreen; // Added

    // Drag and drop state
    private ItemStack draggedItemStack;
    private int draggedItemOriginalSlotIndex; 
    private boolean isDraggingFromHotbar;
    private ItemStack hoveredItemStack; 
    private int draggedItemOriginalCraftingSlotIndex = -1; 

    // Crafting Grid UI
    private static final int CRAFTING_GRID_SIZE = 4; // 2x2
    private static final int CRAFTING_GRID_ROWS = 2;
    private static final int CRAFTING_GRID_COLS = 2;
    private final ItemStack[] craftingGridSlots = new ItemStack[CRAFTING_GRID_SIZE];
    private ItemStack craftingOutputSlot;
    private static final int CRAFTING_OUTPUT_SLOT_INDEX = -10; 
    private static final int CRAFTING_GRID_START_INDEX = -20; 

    // Hotbar selection tooltip state
    private String hotbarSelectedItemName;
    private float hotbarSelectedItemTooltipAlpha;
    private float hotbarSelectedItemTooltipTimer;
    private static final float HOTBAR_TOOLTIP_DISPLAY_DURATION = 1.5f; 
    private static final float HOTBAR_TOOLTIP_FADE_DURATION = 0.5f;   

    // UI constants
    private static final int HOTBAR_Y_OFFSET = 20;
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int TITLE_HEIGHT = 30;
    
    // Colors
    private static final int BORDER_COLOR_R = 100;
    private static final int BORDER_COLOR_G = 100;
    private static final int BORDER_COLOR_B = 100;
    private static final int BACKGROUND_COLOR_R = 50;
    private static final int BACKGROUND_COLOR_G = 50;
    private static final int BACKGROUND_COLOR_B = 50;
    private static final int SLOT_BACKGROUND_R = 70;
    private static final int SLOT_BACKGROUND_G = 70;
    private static final int SLOT_BACKGROUND_B = 70;
    private static final int TEXT_COLOR_R = 255;
    private static final int TEXT_COLOR_G = 255;
    private static final int TEXT_COLOR_B = 255;

    // Tooltip Colors
    private static final int TOOLTIP_BORDER_R = 20;
    private static final int TOOLTIP_BORDER_G = 20;
    private static final int TOOLTIP_BORDER_B = 20;
    private static final int TOOLTIP_BORDER_A = 230;
    private static final int TOOLTIP_BACKGROUND_R = 50;
    private static final int TOOLTIP_BACKGROUND_G = 50;
    private static final int TOOLTIP_BACKGROUND_B = 50;
    private static final int TOOLTIP_BACKGROUND_A = 220;
    private static final int TOOLTIP_TEXT_R = 220;
    private static final int TOOLTIP_TEXT_G = 220;
    private static final int TOOLTIP_TEXT_B = 220;
    
    // Recipe Book Button
    private static final int RECIPE_BUTTON_WIDTH = 80;
    private static final int RECIPE_BUTTON_HEIGHT = 20;
    private int recipeButtonX, recipeButtonY; // Calculated in render

    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, InputHandler inputHandler, RecipeManager recipeManager) {
        this.inventory = inventory;
        this.visible = false;
        this.font = font;
        this.renderer = renderer;
        this.inputHandler = inputHandler;
        this.recipeManager = recipeManager;
        this.draggedItemStack = null;
        this.draggedItemOriginalSlotIndex = -1;
        this.hoveredItemStack = null;
        this.hotbarSelectedItemName = null;
        this.hotbarSelectedItemTooltipAlpha = 0.0f;
        this.draggedItemOriginalCraftingSlotIndex = -1;
        for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
            craftingGridSlots[i] = ItemStack.empty();
        }
        this.craftingOutputSlot = ItemStack.empty();
        this.hotbarSelectedItemTooltipTimer = 0.0f;
        this.recipeBookScreen = new RecipeBookScreen(Game.getInstance().getUIRenderer()); // Initialize RecipeBookScreen
    }

    public void toggleVisibility() {
        this.visible = !this.visible;
        if (!visible) { // If inventory is being closed
            if (draggedItemStack != null) {
                tryReturnToOriginalSlot(); 
                if (draggedItemStack != null && !draggedItemStack.isEmpty()) { 
                    System.out.println("Inventory closed with item: " + draggedItemStack.getDisplayName() + " - Item cleared from drag.");
                    draggedItemStack = null; // Clear from hand if not returned to any slot
                }
            }
            resetDragState(); 
            if (recipeBookScreen.isVisible()) { 
                recipeBookScreen.hide();
            }
        }
    }
    
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) { // If inventory is being closed/hidden
            if (draggedItemStack != null) {
                tryReturnToOriginalSlot(); 
                 if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                    System.out.println("Inventory set invisible with item: " + draggedItemStack.getDisplayName() + " - Item cleared from drag.");
                    draggedItemStack = null;
                }
            }
            resetDragState(); 
            if (recipeBookScreen.isVisible()) {
                recipeBookScreen.hide();
            }
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isRecipeBookVisible() {
        return recipeBookScreen.isVisible();
    }

    public void update(float deltaTime) {
        if (hotbarSelectedItemTooltipTimer > 0) {
            hotbarSelectedItemTooltipTimer -= deltaTime;
            if (hotbarSelectedItemTooltipTimer <= 0) {
                hotbarSelectedItemTooltipTimer = 0;
                hotbarSelectedItemTooltipAlpha = 0;
                hotbarSelectedItemName = null; 
            } else if (hotbarSelectedItemTooltipTimer <= HOTBAR_TOOLTIP_FADE_DURATION) {
                hotbarSelectedItemTooltipAlpha = Math.max(0.0f, hotbarSelectedItemTooltipTimer / HOTBAR_TOOLTIP_FADE_DURATION);
            } else {
                hotbarSelectedItemTooltipAlpha = 1.0f;
            }
        }
    }

    public void displayHotbarItemTooltip(BlockType blockType) {
        if (blockType != null && blockType != BlockType.AIR) {
            this.hotbarSelectedItemName = blockType.getName();
            this.hotbarSelectedItemTooltipTimer = HOTBAR_TOOLTIP_DISPLAY_DURATION + HOTBAR_TOOLTIP_FADE_DURATION;
            this.hotbarSelectedItemTooltipAlpha = 1.0f;
        } else {
            this.hotbarSelectedItemName = null;
            this.hotbarSelectedItemTooltipTimer = 0.0f;
            this.hotbarSelectedItemTooltipAlpha = 0.0f;
        }
    }

    public void render(int screenWidth, int screenHeight) {
        if (recipeBookScreen.isVisible() && !this.visible) { // Only recipe book is showing
            recipeBookScreen.render(screenWidth, screenHeight);
            return;
        }
        
        if (!this.visible && !recipeBookScreen.isVisible()) { // Neither is visible
             return;
        }
        
        // If inventory is visible, render it first
        if (this.visible) {
            hoveredItemStack = null; // Reset hover for main inventory panel rendering

            boolean blendWasEnabled = glIsEnabled(GL_BLEND);
            ShaderProgram shaderProgram = renderer.getShaderProgram();
            boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
            boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);

            glDisable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (cullFaceEnabled) {
                glDisable(GL_CULL_FACE);
            }

            shaderProgram.bind();
            Matrix4f uiProjection = new Matrix4f().ortho(0, screenWidth, screenHeight, 0, -1, 1);
            Matrix4f identityView = new Matrix4f();
            shaderProgram.setUniform("projectionMatrix", uiProjection);
            shaderProgram.setUniform("viewMatrix", identityView);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0);

            int craftingAreaHeight_render = CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING * 3;
            int inventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
            int mainInvAndHotbarHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
            
            int invPartHeight = TITLE_HEIGHT + craftingAreaHeight_render + mainInvAndHotbarHeight + SLOT_PADDING;
            int recipeButtonAreaHeight = RECIPE_BUTTON_HEIGHT + SLOT_PADDING * 2;
            int inventoryPanelHeight = invPartHeight + recipeButtonAreaHeight;

            int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
            int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

            shaderProgram.setUniform("u_useSolidColor", true);
            shaderProgram.setUniform("u_isText", false);
            renderer.drawQuad(panelStartX - 5, panelStartY - 5, inventoryPanelWidth + 10, inventoryPanelHeight + 10,
                    BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
            renderer.drawQuad(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                    BACKGROUND_COLOR_R, BACKGROUND_COLOR_G, BACKGROUND_COLOR_B, 200);

            String title = "Inventory";
            float titleWidth = font.getTextWidth(title);
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", true);
            font.drawString(panelStartX + (inventoryPanelWidth - titleWidth) / 2,
                    panelStartY + 10, title, TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, shaderProgram);
            shaderProgram.setUniform("u_isText", false);

            int currentContentRenderY = panelStartY + TITLE_HEIGHT + SLOT_PADDING;

            // --- Draw Crafting Area ---
            int craftingGridSectionWidth = CRAFTING_GRID_COLS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
            int outputSlotRenderWidth = SLOT_SIZE;
            int arrowRenderWidth = SLOT_SIZE / 2; 
            int totalCraftingRenderWidth = craftingGridSectionWidth + arrowRenderWidth + outputSlotRenderWidth + SLOT_PADDING * 2;
            int craftingGroupStartX_render = panelStartX + (inventoryPanelWidth - totalCraftingRenderWidth) / 2;
            recipeButtonX = craftingGroupStartX_render; // Align recipe button with crafting grid horizontally for now
            int craftingGridStartY_render = currentContentRenderY + SLOT_PADDING;

            for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
                int row = i / CRAFTING_GRID_COLS;
                int col = i % CRAFTING_GRID_COLS;
                int slotX = craftingGroupStartX_render + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = craftingGridStartY_render + row * (SLOT_SIZE + SLOT_PADDING);
                drawSlot(craftingGridSlots[i], slotX, slotY, shaderProgram, uiProjection, identityView, false, CRAFTING_GRID_START_INDEX + i); 
                checkHover(craftingGridSlots[i], slotX, slotY);
            }

            int arrowX_render = craftingGroupStartX_render + craftingGridSectionWidth + SLOT_PADDING;
            int arrowY_render = craftingGridStartY_render + (CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING) / 2 - (SLOT_SIZE/4) / 2; 
            shaderProgram.setUniform("u_useSolidColor", true);
            renderer.drawQuad(arrowX_render, arrowY_render, arrowRenderWidth, SLOT_SIZE / 4, 150, 150, 150, 255);

            int outputSlotX_render = arrowX_render + arrowRenderWidth + SLOT_PADDING;
            int outputSlotY_render = craftingGridStartY_render + (CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_SIZE - SLOT_PADDING) / 2;
            drawSlot(craftingOutputSlot, outputSlotX_render, outputSlotY_render, shaderProgram, uiProjection, identityView, false, CRAFTING_OUTPUT_SLOT_INDEX);
            checkHover(craftingOutputSlot, outputSlotX_render, outputSlotY_render);
            
            currentContentRenderY = craftingGridStartY_render + CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING * 2;
            
            // Draw Main Inventory Slots
            ItemStack[] mainSlots = inventory.getMainInventorySlots();
            for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                int row = i / Inventory.MAIN_INVENTORY_COLS;
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = currentContentRenderY + row * (SLOT_SIZE + SLOT_PADDING);
                drawSlot(mainSlots[i], slotX, slotY, shaderProgram, uiProjection, identityView, false, i);
                checkHover(mainSlots[i], slotX, slotY);
            }
            currentContentRenderY += Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
            
            ItemStack[] hotbarSlots = inventory.getHotbarSlots();
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = currentContentRenderY;
                drawSlot(hotbarSlots[i], slotX, slotY, shaderProgram, uiProjection, identityView, true, i);
                checkHover(hotbarSlots[i], slotX, slotY);
            }
            currentContentRenderY += (SLOT_SIZE + SLOT_PADDING); // After hotbar for recipe button

            // --- Recipe Book Button ---
            // recipeButtonX already set
            recipeButtonY = currentContentRenderY + SLOT_PADDING; 
            
            shaderProgram.setUniform("u_useSolidColor", true); // Button background
            renderer.drawQuad(recipeButtonX, recipeButtonY, RECIPE_BUTTON_WIDTH, RECIPE_BUTTON_HEIGHT, 100, 120, 180, 255);
            String recipeButtonText = recipeBookScreen.isVisible() ? "Close Recipes" : "Recipes";
            float recipeTextWidth = font.getTextWidth(recipeButtonText);
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", true);
            font.drawString(recipeButtonX + (RECIPE_BUTTON_WIDTH - recipeTextWidth) / 2,
                            recipeButtonY + (RECIPE_BUTTON_HEIGHT - font.getLineHeight()) / 2 + (font.getLineHeight()*0.75f),
                            recipeButtonText, 255, 255, 255, shaderProgram);
            shaderProgram.setUniform("u_isText", false);
            // --- End Recipe Book Button ---

            // Dragged Item Rendering
            if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                Vector2f mousePos = inputHandler.getMousePosition();
                int itemRenderX = (int) (mousePos.x - (SLOT_SIZE -4) / 2.0f);
                int itemRenderY = (int) (mousePos.y - (SLOT_SIZE -4) / 2.0f);
                boolean renderedSpecific = false;
                if (draggedItemStack.isBlock()) {
                    BlockType type = draggedItemStack.getBlockType();
                    if (type != null && type != BlockType.AIR && type.getAtlasX() != -1) {
                        renderer.draw3DItemInSlot(type, itemRenderX, itemRenderY, SLOT_SIZE - 4, SLOT_SIZE - 4);
                        renderedSpecific = true;
                    }
                } else { 
                    ItemType type = draggedItemStack.getItemType();
                    if (type != null) {
                        shaderProgram.setUniform("u_useSolidColor", true);
                        shaderProgram.setUniform("u_isText", false);
                        switch (type) {
                            case STICK -> renderer.drawQuad(itemRenderX + (SLOT_SIZE - 8) / 2, itemRenderY + 2, 8, SLOT_SIZE - 8, 139, 69, 19, 255);
                            case WOODEN_PICKAXE -> {
                                renderer.drawQuad(itemRenderX + 4, itemRenderY + SLOT_SIZE/2 - 2, SLOT_SIZE - 8, 4, 139, 69, 19, 255);
                                renderer.drawQuad(itemRenderX + SLOT_SIZE/2 - 2, itemRenderY + 4, 4, SLOT_SIZE - 8, 100, 100, 100, 255);
                            }
                            default -> renderer.drawQuad(itemRenderX, itemRenderY, SLOT_SIZE - 4, SLOT_SIZE - 4, 128, 128, 128, 255);
                        }
                        shaderProgram.setUniform("u_useSolidColor", false);
                        renderedSpecific = true;
                    }
                }

                if(renderedSpecific){ 
                    shaderProgram.bind(); 
                    shaderProgram.setUniform("projectionMatrix", uiProjection);
                    shaderProgram.setUniform("viewMatrix", identityView);
                    if (draggedItemStack.getCount() > 1) {
                        String countText = String.valueOf(draggedItemStack.getCount());
                        float textWidth = font.getTextWidth(countText);
                        float textHeight = font.getLineHeight();
                        shaderProgram.setUniform("u_isText", true);
                        font.drawString(itemRenderX + SLOT_SIZE - 4 - textWidth - 3,
                                        itemRenderY + SLOT_SIZE - 4 - textHeight - 1,
                                        countText, 255, 255, 255, shaderProgram);
                        shaderProgram.setUniform("u_isText", false);
                    }
                }
            }
            
            // Tooltip Rendering
            if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && draggedItemStack == null) { 
                String itemName = hoveredItemStack.getDisplayName();
                if (!"Empty".equals(itemName) && !"Unknown".equals(itemName)) { 
                    float textWidth = font.getTextWidth(itemName);
                    float textHeight = font.getLineHeight();
                    float tooltipPadding = 7.0f;
                    float tooltipWidth = textWidth + 2 * tooltipPadding;
                    float tooltipHeight = textHeight + 2 * tooltipPadding;
                    Vector2f mousePos = inputHandler.getMousePosition();
                    float tooltipX = mousePos.x + 15; 
                    float tooltipY = mousePos.y + 15;

                    if (tooltipX + tooltipWidth > screenWidth) tooltipX = screenWidth - tooltipWidth;
                    if (tooltipY + tooltipHeight > screenHeight) tooltipY = screenHeight - tooltipHeight;
                    if (tooltipX < 0) tooltipX = 0;
                    if (tooltipY < 0) tooltipY = 0;

                    shaderProgram.setUniform("u_useSolidColor", true);
                    shaderProgram.setUniform("u_isText", false);
                    renderer.drawQuad((int)tooltipX, (int)tooltipY, (int)tooltipWidth, (int)tooltipHeight, TOOLTIP_BORDER_R, TOOLTIP_BORDER_G, TOOLTIP_BORDER_B, TOOLTIP_BORDER_A);
                    renderer.drawQuad((int)tooltipX + 1, (int)tooltipY + 1, (int)tooltipWidth - 2, (int)tooltipHeight - 2, TOOLTIP_BACKGROUND_R, TOOLTIP_BACKGROUND_G, TOOLTIP_BACKGROUND_B, TOOLTIP_BACKGROUND_A);
                    
                    shaderProgram.setUniform("u_useSolidColor", false);
                    shaderProgram.setUniform("u_isText", true);
                    float textDrawX = tooltipX + tooltipPadding;
                    float textDrawY = tooltipY + tooltipPadding + (textHeight * 0.75f); 
                    font.drawString(textDrawX, textDrawY, itemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, shaderProgram);
                    shaderProgram.setUniform("u_isText", false);
                }
            }

            if (!blendWasEnabled) glDisable(GL_BLEND);
            if (depthTestEnabled) glEnable(GL_DEPTH_TEST);
            if (cullFaceEnabled) glEnable(GL_CULL_FACE);
        } // End if (this.visible) for main panel rendering
        
        // Render Recipe Book Screen if it's visible (could be overlaying inventory)
        if (recipeBookScreen.isVisible()) {
             recipeBookScreen.render(screenWidth, screenHeight);
        }
    }

    private void drawSlot(ItemStack itemStack, int slotX, int slotY, ShaderProgram shaderProgram, Matrix4f uiProjection, Matrix4f identityView, boolean isHotbarSlot, int slotIdentifier) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shaderProgram.setUniform("u_useSolidColor", true); 
        shaderProgram.setUniform("u_isText", false);

        if (isHotbarSlot && inventory.getSelectedHotbarSlotIndex() == slotIdentifier) {
            renderer.drawQuad(slotX - 2, slotY - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, 255, 255, 255, 255); 
        } else {
             renderer.drawQuad(slotX, slotY, SLOT_SIZE, SLOT_SIZE, BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
        }
        renderer.drawQuad(slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 255);

        if (itemStack != null && !itemStack.isEmpty()) {
            int count = itemStack.getCount();
            boolean renderedSpecificInSlot = false;

            if (itemStack.isBlock()) {
                BlockType type = itemStack.getBlockType();
                if (type != null && type != BlockType.AIR && type.getAtlasX() != -1) {
                    renderer.draw3DItemInSlot(type, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4);
                    renderedSpecificInSlot = true;
                }
            } else { 
                ItemType type = itemStack.getItemType();
                if (type != null) {
                    shaderProgram.setUniform("u_useSolidColor", true);
                    shaderProgram.setUniform("u_isText", false);
                    int itemRenderXInSlot = slotX + 2;
                    int itemRenderYInSlot = slotY + 2;
                    switch (type) {
                         case STICK -> renderer.drawQuad(itemRenderXInSlot + (SLOT_SIZE - 8) / 2, itemRenderYInSlot + 2, 8, SLOT_SIZE - 8, 139, 69, 19, 255);
                        case WOODEN_PICKAXE -> {
                            renderer.drawQuad(itemRenderXInSlot + 4, itemRenderYInSlot + SLOT_SIZE/2 - 2, SLOT_SIZE - 8, 4, 139, 69, 19, 255);
                            renderer.drawQuad(itemRenderXInSlot + SLOT_SIZE/2 - 2, itemRenderYInSlot + 4, 4, SLOT_SIZE - 8, 100,100,100,255);
                        }
                        default -> renderer.drawQuad(itemRenderXInSlot, itemRenderYInSlot, SLOT_SIZE - 4, SLOT_SIZE - 4, 128, 128, 128, 255);
                    }
                    shaderProgram.setUniform("u_useSolidColor", false);
                    renderedSpecificInSlot = true;
                }
            }

            if (renderedSpecificInSlot) {
                shaderProgram.bind(); 
                shaderProgram.setUniform("projectionMatrix", uiProjection);
                shaderProgram.setUniform("viewMatrix", identityView);
                if (count > 1) {
                    String countText = String.valueOf(count);
                    float textWidth = font.getTextWidth(countText);
                    float textHeight = font.getLineHeight();
                    shaderProgram.setUniform("u_isText", true);
                    shaderProgram.setUniform("u_useSolidColor", false); 
                    font.drawString(slotX + SLOT_SIZE - textWidth - 3,
                                    slotY + SLOT_SIZE - textHeight - 1,
                                    countText, 255, 255, 255, shaderProgram);
                    shaderProgram.setUniform("u_isText", false);
                }
            }
        }
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render standalone hotbar if full inventory is up (recipe book check implicitly handled)

        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        ShaderProgram shaderProgram = renderer.getShaderProgram();

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (cullFaceEnabled) glDisable(GL_CULL_FACE);

        shaderProgram.bind();
        Matrix4f uiProjection = new Matrix4f().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f();
        shaderProgram.setUniform("projectionMatrix", uiProjection);
        shaderProgram.setUniform("viewMatrix", identityView);

        int hotbarWidth = Inventory.HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarStartX = (screenWidth - hotbarWidth) / 2;
        int hotbarStartY = screenHeight - SLOT_SIZE - HOTBAR_Y_OFFSET;

        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        renderer.drawQuad(hotbarStartX, hotbarStartY - SLOT_PADDING, hotbarWidth, SLOT_SIZE + SLOT_PADDING * 2, SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 180);

        ItemStack[] hotbarItems = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + SLOT_PADDING + i * (SLOT_SIZE + SLOT_PADDING);
            drawSlot(hotbarItems[i], slotX, hotbarStartY, shaderProgram, uiProjection, identityView, true, i);
        }

        if (hotbarSelectedItemName != null && hotbarSelectedItemTooltipAlpha > 0.0f && !visible) {
            float textWidth = font.getTextWidth(hotbarSelectedItemName);
            float textHeight = font.getLineHeight();
            float tooltipPadding = 7.0f;
            float tooltipWidth = textWidth + 2 * tooltipPadding;
            float tooltipHeight = textHeight + 2 * tooltipPadding;
            int selectedSlotIndex = inventory.getSelectedHotbarSlotIndex();
            float selectedSlotX = hotbarStartX + SLOT_PADDING + selectedSlotIndex * (SLOT_SIZE + SLOT_PADDING);
            float selectedSlotCenterX = selectedSlotX + SLOT_SIZE / 2.0f;
            float tooltipX = selectedSlotCenterX - tooltipWidth / 2.0f;
            float tooltipY = hotbarStartY - tooltipHeight - (SLOT_PADDING * 2); 

            if (tooltipX < 0) tooltipX = 0;
            if (tooltipX + tooltipWidth > screenWidth) tooltipX = screenWidth - tooltipWidth;

            int currentTooltipBorderA = (int)(TOOLTIP_BORDER_A * hotbarSelectedItemTooltipAlpha);
            int currentTooltipBackgroundA = (int)(TOOLTIP_BACKGROUND_A * hotbarSelectedItemTooltipAlpha);

            shaderProgram.setUniform("u_useSolidColor", true);
            renderer.drawQuad((int)tooltipX, (int)tooltipY, (int)tooltipWidth, (int)tooltipHeight, TOOLTIP_BORDER_R, TOOLTIP_BORDER_G, TOOLTIP_BORDER_B, currentTooltipBorderA);
            renderer.drawQuad((int)tooltipX + 1, (int)tooltipY + 1, (int)tooltipWidth - 2, (int)tooltipHeight - 2, TOOLTIP_BACKGROUND_R, TOOLTIP_BACKGROUND_G, TOOLTIP_BACKGROUND_B, currentTooltipBackgroundA);
            
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", true);
            if (hotbarSelectedItemTooltipAlpha > 0.1f) {
                 font.drawString(tooltipX + tooltipPadding, tooltipY + tooltipPadding + (textHeight*0.75f), hotbarSelectedItemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, shaderProgram);
            }
            shaderProgram.setUniform("u_isText", false);
        }

        if (!blendWasEnabled) glDisable(GL_BLEND);
        if (depthTestEnabled) glEnable(GL_DEPTH_TEST);
        if (cullFaceEnabled) glEnable(GL_CULL_FACE);
    }

    public void handleMouseInput(int screenWidth, int screenHeight) {
        if (!visible && !recipeBookScreen.isVisible()) {
            return;
        }

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Panel Coordinates for interaction (used for both click pickup and drag release)
        int numDisplayCols_panel = Inventory.MAIN_INVENTORY_COLS;
        int craftingAreaHeight_panel = CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING * 3;
        int inventoryPanelWidth_panel = numDisplayCols_panel * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int mainInvAndHotbarHeight_panel = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int invPartHeight_panel = TITLE_HEIGHT + craftingAreaHeight_panel + mainInvAndHotbarHeight_panel + SLOT_PADDING;
        int recipeButtonAreaHeight_panel = RECIPE_BUTTON_HEIGHT + SLOT_PADDING * 2;
        int inventoryPanelHeight_panel = invPartHeight_panel + recipeButtonAreaHeight_panel;

        int panelStartX_panel = (screenWidth - inventoryPanelWidth_panel) / 2;
        int panelStartY_panel = (screenHeight - inventoryPanelHeight_panel) / 2;
        
        int currentContentY_panel = panelStartY_panel + TITLE_HEIGHT + SLOT_PADDING; // Y after title
        int craftingGridSectionWidth_panel = CRAFTING_GRID_COLS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int arrowWidth_panel = SLOT_SIZE / 2;
        int totalCraftingWidth_panel = craftingGridSectionWidth_panel + arrowWidth_panel + SLOT_SIZE + SLOT_PADDING * 2;
        int craftingGroupStartX_panel = panelStartX_panel + (inventoryPanelWidth_panel - totalCraftingWidth_panel) / 2;
        int craftingGridStartY_panel = currentContentY_panel + SLOT_PADDING; // Y for crafting grid
        
        int outputSlotX_panel = craftingGroupStartX_panel + craftingGridSectionWidth_panel + SLOT_PADDING + arrowWidth_panel + SLOT_PADDING;
        int outputSlotY_panel = craftingGridStartY_panel + (CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_SIZE - SLOT_PADDING) / 2;

        currentContentY_panel = craftingGridStartY_panel + CRAFTING_GRID_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING * 2; // Y after crafting
        int mainInventoryStartY_panel = currentContentY_panel;
        
        currentContentY_panel = mainInventoryStartY_panel + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // Y after main inv
        int hotbarRowY_panel = currentContentY_panel;
        
        currentContentY_panel = hotbarRowY_panel + (SLOT_SIZE + SLOT_PADDING); // Y after hotbar
        // recipeButtonX is from render (craftingGroupStartX_panel), recipeButtonY for click needs to match render logic.
        int currentRecipeButtonX = craftingGroupStartX_panel; // As set in render
        int currentRecipeButtonY = currentContentY_panel + SLOT_PADDING; // As set in render

        // Delegate to recipe book first if it's visible
        if (recipeBookScreen.isVisible()) {
            recipeBookScreen.handleInput(inputHandler, Game.getInstance());
            // If the recipe book is still visible after handling input, assume it consumed the relevant click.
            // This prevents the underlying inventory from reacting to the same click event.
            if (recipeBookScreen.isVisible()) {
                if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT) || inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                     // If any mouse button was pressed, and the recipe book is still visible (meaning it handled it internally
                     // or the click was outside but we prioritize recipe book when open), consume it for inventory screen.
                     inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                     inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                }
                return;
            }
        }
        
        // After recipe book, proceed with inventory logic only if visible and no recipe book active or just closed it.
        if (!visible) return;

        boolean processLeftClick = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean processRightClick = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean processDragRelease = draggedItemStack != null && !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        if (!processLeftClick && !processRightClick && !processDragRelease) {
            return; // Nothing to do for inventory itself this frame.
        }


        // Check recipe book button first because it's part of the inventory panel
        if ((processLeftClick || processRightClick) && mouseX >= currentRecipeButtonX && mouseX <= currentRecipeButtonX + RECIPE_BUTTON_WIDTH &&
            mouseY >= currentRecipeButtonY && mouseY <= currentRecipeButtonY + RECIPE_BUTTON_HEIGHT) {
            recipeBookScreen.toggleVisibility();
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT); // Consume both to avoid further inventory actions
            return; // Click handled
        }

        if (processDragRelease) {
            placeDraggedItem(mouseX, mouseY, panelStartX_panel, mainInventoryStartY_panel, hotbarRowY_panel, craftingGroupStartX_panel, craftingGridStartY_panel, outputSlotX_panel, outputSlotY_panel);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        // --- Handle Right Click: drop one item OR pick up one if dragged stack exists AND it's empty ---
        if (processRightClick) {
            boolean handledRightClick = handleRightClickInSlot(mouseX, mouseY,
                panelStartX_panel, mainInventoryStartY_panel, hotbarRowY_panel,
                craftingGroupStartX_panel, craftingGridStartY_panel,
                outputSlotX_panel, outputSlotY_panel);
            if (handledRightClick) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                return; 
            }
        }
        
        // --- Slot Interaction Logic (Pickup an item with Left Click IF NOT ALREADY DRAGGING) ---
        if (processLeftClick && draggedItemStack == null) {
            // Check Crafting Output Slot (Take all from crafted output with left click only)
            if (mouseX >= outputSlotX_panel && mouseX <= outputSlotX_panel + SLOT_SIZE && mouseY >= outputSlotY_panel && mouseY <= outputSlotY_panel + SLOT_SIZE) {
                if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                    ItemStack takenItem = craftingOutputSlot.copy();
                    // Try to add to inventory
                    boolean addedToInv = inventory.addItem(takenItem); 
                    if (addedToInv) { 
                        // If output fully added to inventory, then consume ingredients
                        CraftingRecipe matchedRecipe = recipeManager.findMatchingRecipe(craftingGridSlots); 
                        if (matchedRecipe != null) { 
                            if (matchedRecipe.isShapeless()) {
                                java.util.List<ItemStack> ingredients = matchedRecipe.getShapelessIngredients();
                                if (ingredients != null) {
                                    for (ItemStack recipeIngredient : ingredients) {
                                        int countToConsumeForThisType = recipeIngredient.getCount();
                                        for (int k = 0; k < CRAFTING_GRID_SIZE; k++) {
                                            if (countToConsumeForThisType == 0) break;
                                            ItemStack gridSlotItem = craftingGridSlots[k];
                                            if (gridSlotItem != null && !gridSlotItem.isEmpty() &&
                                                gridSlotItem.equalsTypeAndIgnoreCount(recipeIngredient)) {
                                                
                                                int amountToDecrementFromSlot = Math.min(countToConsumeForThisType, gridSlotItem.getCount());
                                                gridSlotItem.decrementCount(amountToDecrementFromSlot);
                                                if (gridSlotItem.isEmpty()) craftingGridSlots[k] = ItemStack.empty();
                                                countToConsumeForThisType -= amountToDecrementFromSlot;
                                            }
                                        }
                                    }
                                }
                            } else { // Shaped recipe
                                ItemStack[] pattern = matchedRecipe.getShapedInputPattern();
                                if (pattern != null) {
                                    for (int k_slot = 0; k_slot < CRAFTING_GRID_SIZE; k_slot++) {
                                        int patternIndex = k_slot;
                                        if (patternIndex < pattern.length && pattern[patternIndex] != null && !pattern[patternIndex].isEmpty()) {
                                            if (craftingGridSlots[k_slot] != null && !craftingGridSlots[k_slot].isEmpty() &&
                                                craftingGridSlots[k_slot].equalsTypeAndIgnoreCount(pattern[patternIndex])) {
                                                craftingGridSlots[k_slot].decrementCount(1); 
                                                if (craftingGridSlots[k_slot].isEmpty()) craftingGridSlots[k_slot] = ItemStack.empty();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        updateCraftingOutput(); 
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return; 
                    }
                }
            }
            
            // Check Crafting Grid Slots
            for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
                int r = i / CRAFTING_GRID_COLS;
                int c = i % CRAFTING_GRID_COLS;
                int slotX_cg = craftingGroupStartX_panel + c * (SLOT_SIZE + SLOT_PADDING);
                int slotY_cg = craftingGridStartY_panel + r * (SLOT_SIZE + SLOT_PADDING);
                if (mouseX >= slotX_cg && mouseX <= slotX_cg + SLOT_SIZE && mouseY >= slotY_cg && mouseY <= slotY_cg + SLOT_SIZE) {
                    if (craftingGridSlots[i] != null && !craftingGridSlots[i].isEmpty()) {
                        draggedItemStack = craftingGridSlots[i].copy();
                        craftingGridSlots[i] = ItemStack.empty();
                        draggedItemOriginalCraftingSlotIndex = i; 
                        draggedItemOriginalSlotIndex = -1; 
                        isDraggingFromHotbar = false; 
                        updateCraftingOutput();
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }

            // Check main inventory slots
            for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                int row = i / Inventory.MAIN_INVENTORY_COLS;
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX_panel + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = mainInventoryStartY_panel + row * (SLOT_SIZE + SLOT_PADDING);
                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack currentStack = inventory.getMainInventorySlot(i);
                    if (currentStack != null && !currentStack.isEmpty()) {
                        draggedItemStack = currentStack.copy(); 
                        inventory.setMainInventorySlot(i, ItemStack.empty()); 
                        draggedItemOriginalSlotIndex = i;
                        draggedItemOriginalCraftingSlotIndex = -1; 
                        isDraggingFromHotbar = false;
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }

            // Check hotbar slots
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX_panel + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = hotbarRowY_panel;
                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack currentStack = inventory.getHotbarSlot(i); 
                    if (currentStack != null && !currentStack.isEmpty()) {
                        draggedItemStack = currentStack.copy();
                        inventory.setHotbarSlot(i, ItemStack.empty()); 
                        draggedItemOriginalSlotIndex = i;
                        draggedItemOriginalCraftingSlotIndex = -1; 
                        isDraggingFromHotbar = true;
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }
        }
    }

    private void placeDraggedItem(float mouseX, float mouseY,
                                  int panelStartX, 
                                  int mainInventoryGridStartY, int hotbarGridY,
                                  int craftingGroupGridStartX, int craftingGridGridStartY,
                                  int outputSlotX, int outputSlotY) { 
        if (draggedItemStack == null || draggedItemStack.isEmpty()) return;

        // Try to place in crafting output slot (only if it matches or is empty)
        // Note: Full stack movement from crafting output is handled by left-click in handleMouseInput
        // Here, we consider dropping a dragged item INTO the output (unlikely/bad UX)
        // or stacking if dragged item is exactly the output result and there's space.
        // For simplicity, typically users cannot "drop" into output.
        // Assuming current logic from line 633 means you take full stack with Left-Click
        // and cannot manually drop items here. If change needed, this is the spot.
        // For now, disallow dropping into output directly unless specific "shift-click take all" or "right-click take one" is enabled from RecipeManager.
        // But for consistency of drag&drop handling for the item *currently dragged*:
        if (mouseX >= outputSlotX && mouseX <= outputSlotX + SLOT_SIZE && mouseY >= outputSlotY && mouseY <= outputSlotY + SLOT_SIZE) {
            // Can't drop *into* output slot to swap, you can only *take* from it.
            // If we're dragging, and it's over the output slot, effectively "cancel" the drop to output.
            // But we must still try to return it or place it back on player if no other valid slot is found.
            // So don't return here, let it fall through to original slot return.
            // (Or decide: if user drags onto output, is it error, or does it try to place it in inventory if output matches and full? Complex for MVP)
            // For now, output slot is special and does not accept dropped items for swap/combine, only 'take'.
        }
        
        // Try to place in crafting grid slots
        for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
            int r = i / CRAFTING_GRID_COLS;
            int c = i % CRAFTING_GRID_COLS;
            int slotX_cg = craftingGroupGridStartX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY_cg = craftingGridGridStartY + r * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX_cg && mouseX <= slotX_cg + SLOT_SIZE &&
                mouseY >= slotY_cg && mouseY <= slotY_cg + SLOT_SIZE) {
                
                ItemStack targetCraftingSlot = craftingGridSlots[i];
                final int finalI_crafting = i; 
                boolean success = tryPlaceItem(targetCraftingSlot, (newItem) -> craftingGridSlots[finalI_crafting] = newItem);
                updateCraftingOutput();
                if (success) { // if the item was fully placed or swapped (new item now dragged), return
                    if (draggedItemStack == null || draggedItemStack.isEmpty()) resetDragState();
                    return;
                }
            }
        }

        // Try to place in main inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX_mi = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY_mi = mainInventoryGridStartY + row * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX_mi && mouseX <= slotX_mi + SLOT_SIZE &&
                mouseY >= slotY_mi && mouseY <= slotY_mi + SLOT_SIZE) {
                final int finalI = i; 
                boolean success = handleDropInPlayerSlot(inventory.getMainInventorySlot(i), finalI, false);
                if (success) {
                    if (draggedItemStack == null || draggedItemStack.isEmpty()) resetDragState();
                    return;
                }
            }
        }

        // Try to place in hotbar
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX_hb = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY_hb = hotbarGridY;

            if (mouseX >= slotX_hb && mouseX <= slotX_hb + SLOT_SIZE &&
                mouseY >= slotY_hb && mouseY <= slotY_hb + SLOT_SIZE) {
                final int finalI = i; 
                boolean success = handleDropInPlayerSlot(inventory.getHotbarSlot(i), finalI, true);
                if (success) {
                    if (draggedItemStack == null || draggedItemStack.isEmpty()) resetDragState();
                    return;
                }
            }
        }

        // If code reaches here, it means no slot was hit by the mouse during drag release.
        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            tryReturnToOriginalSlot();
        }
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            resetDragState();
        }
    }

    private boolean handleDropInPlayerSlot(ItemStack targetStackInSlot, int slotIndexInInventory, boolean isSlotHotbar) {
        boolean success = tryPlaceItem(targetStackInSlot, (newItem) -> {
            if (isSlotHotbar) inventory.setHotbarSlot(slotIndexInInventory, newItem);
            else inventory.setMainInventorySlot(slotIndexInInventory, newItem);
        });
        if (success && draggedItemStack != null && !draggedItemStack.isEmpty()) {
        }
        return success; 
    }

    // Helper for dropping a single item or the entire stack based on context
    private boolean tryPlaceItem(ItemStack targetSlotContent, Consumer<ItemStack> setTargetSlot) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) return false;

        if (targetSlotContent.isEmpty()) {
            // Target is empty: place 1 if right-click-drop, else all
            if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                ItemStack singleItem = draggedItemStack.copy(1); // One item
                setTargetSlot.accept(singleItem);
                draggedItemStack.decrementCount(1);
            } else {
                setTargetSlot.accept(draggedItemStack.copy());
                draggedItemStack.clear();
            }
            return true;
        } else if (targetSlotContent.equalsTypeAndIgnoreCount(draggedItemStack) && targetSlotContent.getCount() < targetSlotContent.getMaxStackSize()) {
            // Target has same item type and is not full: stack
            if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                // Right click: try to add one
                if (targetSlotContent.getCount() < targetSlotContent.getMaxStackSize()) {
                    targetSlotContent.incrementCount(1);
                    setTargetSlot.accept(targetSlotContent);
                    draggedItemStack.decrementCount(1);
                }
            } else {
                // Left click: try to add all
                int canAdd = targetSlotContent.getMaxStackSize() - targetSlotContent.getCount();
                int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                targetSlotContent.incrementCount(toAdd);
                setTargetSlot.accept(targetSlotContent);
                draggedItemStack.decrementCount(toAdd);
            }
            if (draggedItemStack.isEmpty()) draggedItemStack.clear();
            return true;
        } else { 
            // Different item types, SWAP.
            if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                ItemStack itemFromTargetSlot = targetSlotContent.copy();
                setTargetSlot.accept(draggedItemStack.copy());
                draggedItemStack = itemFromTargetSlot; // Now dragging this item
                draggedItemOriginalCraftingSlotIndex = -1; 
                draggedItemOriginalSlotIndex = -1; 
                isDraggingFromHotbar = false; 
                return true; 
            }
            return false; 
        }
    }

    // New method for right-click interaction
    private boolean handleRightClickInSlot(float mouseX, float mouseY,
                                           int panelStartX, int mainInventoryGridStartY, int hotbarGridY,
                                           int craftingGroupGridStartX, int craftingGridGridStartY,
                                           int outputSlotX, int outputSlotY) {
        
        // Right click on crafting output slot (take one)
        if (mouseX >= outputSlotX && mouseX <= outputSlotX + SLOT_SIZE && mouseY >= outputSlotY && mouseY <= outputSlotY + SLOT_SIZE) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                if (draggedItemStack == null || draggedItemStack.isEmpty()) { 
                    ItemStack singleOutputItem = craftingOutputSlot.copy(1); // Take one
                    boolean added = inventory.addItem(singleOutputItem);
                    if (added) {
                        return true; 
                    }
                }
            }
        }

        // Iterate through all possible inventory slots (main inventory, hotbar, crafting grid)
        // Main Inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = mainInventoryGridStartY + row * (SLOT_SIZE + SLOT_PADDING);
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                final int currentSlotIndex = i; 
                return handleRightClickDrop(inventory.getMainInventorySlot(currentSlotIndex), 
                                            (newItem) -> inventory.setMainInventorySlot(currentSlotIndex, newItem));
            }
        }

        // Hotbar Slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarGridY;
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                final int currentSlotIndex = i; 
                return handleRightClickDrop(inventory.getHotbarSlot(currentSlotIndex), 
                                            (newItem) -> inventory.setHotbarSlot(currentSlotIndex, newItem));
            }
        }
        
        // Crafting Grid Slots
        for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
            int r = i / CRAFTING_GRID_COLS;
            int c = i % CRAFTING_GRID_COLS;
            int slotX_cg = craftingGroupGridStartX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY_cg = craftingGridGridStartY + r * (SLOT_SIZE + SLOT_PADDING);
            if (mouseX >= slotX_cg && mouseX <= slotX_cg + SLOT_SIZE && mouseY >= slotY_cg && mouseY <= slotY_cg + SLOT_SIZE) {
                final int currentSlotIndex = i; 
                return handleRightClickDrop(craftingGridSlots[currentSlotIndex], 
                                            (newItem) -> { 
                                                craftingGridSlots[currentSlotIndex] = newItem; 
                                                updateCraftingOutput();
                                            });
            }
        }

        return false; // No relevant slot clicked
    }

    // Helper for dropping a single item onto a slot with right-click or picking up one if hand empty
    private boolean handleRightClickDrop(ItemStack targetSlotStack, Consumer<ItemStack> setSlotAction) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            // Hand is empty: Pick up half of the target stack, or one if only one exists
            if (!targetSlotStack.isEmpty()) {
                int countToTake = targetSlotStack.getCount() / 2;
                if (countToTake == 0) countToTake = 1; // Always take at least one if present
                
                draggedItemStack = targetSlotStack.copy(countToTake); // Start dragging these items
                targetSlotStack.decrementCount(countToTake);
                if (targetSlotStack.isEmpty()) {
                    setSlotAction.accept(ItemStack.empty());
                } else {
                    setSlotAction.accept(targetSlotStack); // Update slot with remaining
                }
                resetDragOrigin(); // The origin is now the hovered slot (or no specific origin from 'pickup')
                return true;
            }
        } else { // Hand is not empty
            if (targetSlotStack.isEmpty()) {
                // Target is empty: Drop one from hand
                setSlotAction.accept(draggedItemStack.copy(1));
                draggedItemStack.decrementCount(1);
                if (draggedItemStack.isEmpty()) resetDragState();
                return true;
            } else if (targetSlotStack.equalsTypeAndIgnoreCount(draggedItemStack) && targetSlotStack.getCount() < targetSlotStack.getMaxStackSize()) {
                // Target has same type and space: Add one from hand
                targetSlotStack.incrementCount(1);
                setSlotAction.accept(targetSlotStack);
                draggedItemStack.decrementCount(1);
                if (draggedItemStack.isEmpty()) resetDragState();
                return true;
            } else {
                // Target has different item or is full: do nothing (don't swap or drop one of same type onto full)
            }
        }
        return false;
    }

    private void resetDragOrigin() {
        draggedItemOriginalSlotIndex = -1;
        draggedItemOriginalCraftingSlotIndex = -1;
        isDraggingFromHotbar = false;
    }
    
    private void resetDragState() {
        draggedItemStack = null;
        draggedItemOriginalSlotIndex = -1;
        draggedItemOriginalCraftingSlotIndex = -1;
        isDraggingFromHotbar = false;
    }

    private void tryReturnToOriginalSlot() {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            resetDragState(); 
            return;
        }

        if (draggedItemOriginalCraftingSlotIndex != -1) { 
            ItemStack targetCraftingSlot = craftingGridSlots[draggedItemOriginalCraftingSlotIndex];
            if (targetCraftingSlot == null || targetCraftingSlot.isEmpty()) {
                craftingGridSlots[draggedItemOriginalCraftingSlotIndex] = draggedItemStack.copy();
                draggedItemStack.clear();
            } else if (targetCraftingSlot.equalsTypeAndIgnoreCount(draggedItemStack) && targetCraftingSlot.getCount() < targetCraftingSlot.getMaxStackSize()) {
                int canAdd = targetCraftingSlot.getMaxStackSize() - targetCraftingSlot.getCount();
                int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                targetCraftingSlot.incrementCount(toAdd);
                draggedItemStack.decrementCount(toAdd);
                if (draggedItemStack.isEmpty()) draggedItemStack.clear();
            }
            updateCraftingOutput();
        } else if (draggedItemOriginalSlotIndex != -1) { 
            ItemStack targetSlot;
            
            if (isDraggingFromHotbar) {
                targetSlot = inventory.getHotbarSlot(draggedItemOriginalSlotIndex);
                if (targetSlot.isEmpty()) {
                    inventory.setHotbarSlot(draggedItemOriginalSlotIndex, draggedItemStack.copy());
                    draggedItemStack.clear();
                } else if (targetSlot.equalsTypeAndIgnoreCount(draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
                    int canAdd = targetSlot.getMaxStackSize() - targetSlot.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    
                    ItemStack modifiedTarget = targetSlot.copy();
                    modifiedTarget.incrementCount(toAdd);
                    inventory.setHotbarSlot(draggedItemOriginalSlotIndex, modifiedTarget);
                    
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) draggedItemStack.clear();
                } else { 
                    if (!inventory.addItem(draggedItemStack)) {
                        System.err.println("Failed to return dragged item to inventory or original slot. Item may be lost (Hotbar).");
                    }
                    draggedItemStack.clear();
                }
            } else { // Main Inventory
                targetSlot = inventory.getMainInventorySlot(draggedItemOriginalSlotIndex);
                if (targetSlot.isEmpty()) {
                    inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, draggedItemStack.copy());
                    draggedItemStack.clear();
                } else if (targetSlot.equalsTypeAndIgnoreCount(draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
                    int canAdd = targetSlot.getMaxStackSize() - targetSlot.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    
                    ItemStack modifiedTarget = targetSlot.copy();
                    modifiedTarget.incrementCount(toAdd);
                    inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, modifiedTarget);
                    
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) draggedItemStack.clear();
                } else { 
                     if (!inventory.addItem(draggedItemStack)) {
                        System.err.println("Failed to return dragged item to inventory or original slot. Item may be lost (Main Inv).");
                    }
                    draggedItemStack.clear();
                }
            }
        } else {
            if (!inventory.addItem(draggedItemStack)) {
                 System.err.println("Dragged item could not be returned to inventory. Item may be lost.");
            }
            draggedItemStack.clear();
        }

        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            resetDragState();
        }
    }
    
    private void checkHover(ItemStack itemStack, int slotX, int slotY) {
        if (itemStack == null || itemStack.isEmpty() || !visible) {
            return;
        }
        Vector2f mousePos = inputHandler.getMousePosition(); 
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
            mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
            hoveredItemStack = itemStack;
        }
    }
    
    private void updateCraftingOutput() {
        if (recipeManager == null) {
            System.err.println("InventoryScreen: RecipeManager not initialized!");
            craftingOutputSlot = ItemStack.empty();
            return;
        }
        
        ItemStack[] currentGridForMatching = new ItemStack[CRAFTING_GRID_SIZE];
        boolean potentialMatch = false;
        for(int i=0; i < CRAFTING_GRID_SIZE; i++){
            currentGridForMatching[i] = (craftingGridSlots[i] == null || craftingGridSlots[i].isEmpty()) ? ItemStack.empty() : craftingGridSlots[i];
            if (!currentGridForMatching[i].isEmpty()) potentialMatch = true;
        }

        if (potentialMatch) {
             CraftingRecipe recipe = recipeManager.findMatchingRecipe(currentGridForMatching); 
            if (recipe != null) {
                craftingOutputSlot = recipe.getOutput().copy();
            } else {
                craftingOutputSlot = ItemStack.empty();
            }
        } else {
             craftingOutputSlot = ItemStack.empty();
        }
    }
}