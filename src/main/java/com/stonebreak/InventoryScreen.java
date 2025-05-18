package com.stonebreak;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import org.joml.Matrix4f;

import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;



/**
 * A 2D UI for displaying the player's inventory.
 */
public class InventoryScreen {

    private Inventory inventory;
    private boolean visible;
    private Font font;
    private Renderer renderer;
    private InputHandler inputHandler; // Added for mouse input

    // Drag and drop state
    private ItemStack draggedItemStack;
    private int draggedItemOriginalSlotIndex; // -1 if not dragging, or index in combined (hotbar + main)
    private boolean isDraggingFromHotbar;
    private ItemStack hoveredItemStack; // For tooltip (inventory screen)

    // Hotbar selection tooltip state
    private String hotbarSelectedItemName;
    private float hotbarSelectedItemTooltipAlpha;
    private float hotbarSelectedItemTooltipTimer;
    private static final float HOTBAR_TOOLTIP_DISPLAY_DURATION = 1.5f; // seconds
    private static final float HOTBAR_TOOLTIP_FADE_DURATION = 0.5f;   // seconds


    // UI constants
    // HOTBAR_SLOTS is now defined in Inventory.java
    private static final int HOTBAR_Y_OFFSET = 20;
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    // NUM_COLS is now Inventory.MAIN_INVENTORY_COLS
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
    /**
     * Creates a new inventory screen.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, InputHandler inputHandler) {
        this.inventory = inventory;
        this.visible = false;
        this.font = font;
        this.renderer = renderer;
        this.inputHandler = inputHandler; // Initialize InputHandler
        this.draggedItemStack = null;
        this.draggedItemOriginalSlotIndex = -1;
        this.hoveredItemStack = null;
        this.hotbarSelectedItemName = null;
        this.hotbarSelectedItemTooltipAlpha = 0.0f;
        this.hotbarSelectedItemTooltipTimer = 0.0f;
    }

    /**
     * Toggles the visibility of the inventory screen.
     */
    public void toggleVisibility() {
        this.visible = !this.visible;
    }

    /**
     * Returns whether the inventory screen is currently visible.
     */
    public boolean isVisible() {
        return visible;
    }

    public void update(float deltaTime) {
        if (hotbarSelectedItemTooltipTimer > 0) {
            hotbarSelectedItemTooltipTimer -= deltaTime;
            if (hotbarSelectedItemTooltipTimer <= 0) {
                hotbarSelectedItemTooltipTimer = 0;
                hotbarSelectedItemTooltipAlpha = 0;
                hotbarSelectedItemName = null; // Clear name when timer expires
            } else if (hotbarSelectedItemTooltipTimer <= HOTBAR_TOOLTIP_FADE_DURATION) {
                hotbarSelectedItemTooltipAlpha = Math.max(0.0f, hotbarSelectedItemTooltipTimer / HOTBAR_TOOLTIP_FADE_DURATION);
            } else {
                hotbarSelectedItemTooltipAlpha = 1.0f; // Fully visible during display duration
            }
        }
    }

    /**
     * Call this when a hotbar item is selected to show its name.
     */
    public void displayHotbarItemTooltip(BlockType blockType) {
        if (blockType != null && blockType != BlockType.AIR) {
            this.hotbarSelectedItemName = blockType.getName();
            this.hotbarSelectedItemTooltipTimer = HOTBAR_TOOLTIP_DISPLAY_DURATION + HOTBAR_TOOLTIP_FADE_DURATION;
            this.hotbarSelectedItemTooltipAlpha = 1.0f;
        } else {
            // If AIR or null is selected, effectively hide any current tooltip
            this.hotbarSelectedItemName = null;
            this.hotbarSelectedItemTooltipTimer = 0.0f;
            this.hotbarSelectedItemTooltipAlpha = 0.0f;
        }
    }

    /**
     * Renders the inventory screen.
     */
    public void render(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }

        // Reset hovered item at the start of each render pass
        hoveredItemStack = null;

        // Save GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        ShaderProgram shaderProgram = renderer.getShaderProgram();
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);

        // Setup 2D rendering
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

        // Main Inventory Area (now includes hotbar visually as part of the panel)
        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;

        int inventoryPanelWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        // Height for main inventory slots + one row for hotbar + title
        int inventoryPanelHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + TITLE_HEIGHT;


        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Draw panel background
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        renderer.drawQuad(panelStartX - 5, panelStartY - 5, inventoryPanelWidth + 10, inventoryPanelHeight + 10,
                BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
        renderer.drawQuad(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                BACKGROUND_COLOR_R, BACKGROUND_COLOR_G, BACKGROUND_COLOR_B, 200);

        // Draw title
        String title = "Inventory";
        float titleWidth = font.getTextWidth(title);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", true);
        font.drawString(panelStartX + (inventoryPanelWidth - titleWidth) / 2,
                panelStartY + 10, title, TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, shaderProgram);
        shaderProgram.setUniform("u_isText", false);

        int contentStartY = panelStartY + TITLE_HEIGHT;

        // Draw Main Inventory Slots
        ItemStack[] mainSlots = inventory.getMainInventorySlots(); // Gets copies
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);
            drawSlot(mainSlots[i], slotX, slotY, shaderProgram, uiProjection, identityView, false, -1);
            checkHover(mainSlots[i], slotX, slotY, screenWidth, screenHeight);
        }
        
        // Draw Hotbar Slots (as part of the main inventory panel, visually)
        // Positioned below the main inventory slots within the same panel
        ItemStack[] hotbarSlots = inventory.getHotbarSlots(); // Gets copies
        int hotbarRowYOffset = contentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // Extra padding for separation

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar is a single row
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarRowYOffset;
             // Pass true for isHotbarSlot and the actual hotbar index
            drawSlot(hotbarSlots[i], slotX, slotY, shaderProgram, uiProjection, identityView, true, i);
            checkHover(hotbarSlots[i], slotX, slotY, screenWidth, screenHeight);
        }


        // Draw dragged item on top of everything else
        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            Vector2f mousePos = inputHandler.getMousePosition();
            // Center the item on the mouse cursor
            int itemRenderX = (int) (mousePos.x - (SLOT_SIZE -4) / 2.0f);
            int itemRenderY = (int) (mousePos.y - (SLOT_SIZE -4) / 2.0f);

            BlockType type = BlockType.getById(draggedItemStack.getBlockTypeId());
            if (type != null && type.getAtlasX() != -1 && type.getAtlasY() != -1) {
                 renderer.draw3DItemInSlot(type, itemRenderX, itemRenderY, SLOT_SIZE - 4, SLOT_SIZE - 4);
                // Reset to 2D projection after 3D rendering
                shaderProgram.bind();
                shaderProgram.setUniform("projectionMatrix", uiProjection);
                shaderProgram.setUniform("viewMatrix", identityView);
                glDisable(GL_DEPTH_TEST);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                if (draggedItemStack.getCount() > 1) {
                    String countText = String.valueOf(draggedItemStack.getCount());
                    float textWidth = font.getTextWidth(countText);
                    float textHeight = font.getLineHeight();
                    shaderProgram.setUniform("u_isText", true);
                    font.drawString(itemRenderX + SLOT_SIZE - 4 - textWidth - 3,
                                    itemRenderY + SLOT_SIZE - 4 - textHeight - 1,
                                    countText, 255, 220, 0, shaderProgram);
                    shaderProgram.setUniform("u_isText", false);
                }
            }
        }

        // Draw Tooltip
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && draggedItemStack == null) { // Only show tooltip if not dragging
            BlockType type = BlockType.getById(hoveredItemStack.getBlockTypeId());
            if (type != null && type != BlockType.AIR) {
                String itemName = type.getName();
                float textWidth = font.getTextWidth(itemName);
                float textHeight = font.getLineHeight();
                float tooltipPadding = 7.0f; // Increased padding

                float tooltipWidth = textWidth + 2 * tooltipPadding;
                float tooltipHeight = textHeight + 2 * tooltipPadding;

                Vector2f mousePos = inputHandler.getMousePosition();
                float tooltipX = mousePos.x + 15; // Offset from cursor
                float tooltipY = mousePos.y + 15;

                // Adjust tooltip position to stay within screen bounds
                if (tooltipX + tooltipWidth > screenWidth) {
                    tooltipX = screenWidth - tooltipWidth;
                }
                if (tooltipY + tooltipHeight > screenHeight) {
                    tooltipY = screenHeight - tooltipHeight;
                }
                if (tooltipX < 0) tooltipX = 0;
                if (tooltipY < 0) tooltipY = 0;


                // Draw tooltip background
                shaderProgram.setUniform("u_useSolidColor", true);
                shaderProgram.setUniform("u_isText", false);
                // Draw outer border
                renderer.drawQuad((int)tooltipX, (int)tooltipY, (int)tooltipWidth, (int)tooltipHeight,
                                  TOOLTIP_BORDER_R, TOOLTIP_BORDER_G, TOOLTIP_BORDER_B, TOOLTIP_BORDER_A);
                // Draw inner background
                renderer.drawQuad((int)tooltipX + 1, (int)tooltipY + 1, (int)tooltipWidth - 2, (int)tooltipHeight - 2,
                                  TOOLTIP_BACKGROUND_R, TOOLTIP_BACKGROUND_G, TOOLTIP_BACKGROUND_B, TOOLTIP_BACKGROUND_A);

                // Draw item name
                shaderProgram.setUniform("u_useSolidColor", false);
                shaderProgram.setUniform("u_isText", true);
                float textDrawX = tooltipX + tooltipPadding;
                float textDrawY = tooltipY + tooltipPadding + (textHeight * 0.75f); // Adjusted for better baseline placement
                font.drawString(textDrawX,
                                textDrawY,
                                itemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, shaderProgram);
                shaderProgram.setUniform("u_isText", false);
            }
        }


        // Restore GL state
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        }
    }

    // Helper method to draw a single slot
    private void drawSlot(ItemStack itemStack, int slotX, int slotY, ShaderProgram shaderProgram, Matrix4f uiProjection, Matrix4f identityView, boolean isHotbarSlot, int hotbarIndex) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shaderProgram.setUniform("u_useSolidColor", true); // Make sure this is set before drawing quads
        shaderProgram.setUniform("u_isText", false);


        // Highlight selected hotbar slot
        if (isHotbarSlot && inventory.getSelectedHotbarSlotIndex() == hotbarIndex) {
            renderer.drawQuad(slotX - 2, slotY - 2, SLOT_SIZE + 4, SLOT_SIZE + 4,
                    255, 255, 255, 255); // White highlight
        } else {
             renderer.drawQuad(slotX, slotY, SLOT_SIZE, SLOT_SIZE,
                BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
        }
        renderer.drawQuad(slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2,
                SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 255);

        if (itemStack != null && !itemStack.isEmpty()) {
            BlockType type = BlockType.getById(itemStack.getBlockTypeId());
            int count = itemStack.getCount();

            if (type != null && type.getAtlasX() != -1 && type.getAtlasY() != -1) {
                renderer.draw3DItemInSlot(type, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4);
                // Reset to 2D projection
                shaderProgram.bind(); // Rebind shader if draw3DItemInSlot changes it
                shaderProgram.setUniform("projectionMatrix", uiProjection);
                shaderProgram.setUniform("viewMatrix", identityView);
                glDisable(GL_DEPTH_TEST);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);


                if (count > 1) {
                    String countText = String.valueOf(count);
                    float textWidth = font.getTextWidth(countText);
                    float textHeight = font.getLineHeight();
                    shaderProgram.setUniform("u_isText", true); // Set before drawing text
                    shaderProgram.setUniform("u_useSolidColor", false); // Textures are used for font
                    font.drawString(slotX + SLOT_SIZE - textWidth - 3,
                            slotY + SLOT_SIZE - textHeight - 1,
                            countText, 255, 220, 0, shaderProgram);
                    shaderProgram.setUniform("u_isText", false); // Reset after drawing text
                }
            }
        }
    }


    /**
     * Renders the separate hotbar at the bottom of the screen when inventory is closed.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render separate hotbar if full inventory is open

        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        ShaderProgram shaderProgram = renderer.getShaderProgram();

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

        int hotbarWidth = Inventory.HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarStartX = (screenWidth - hotbarWidth) / 2;
        int hotbarStartY = screenHeight - SLOT_SIZE - HOTBAR_Y_OFFSET;

        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        renderer.drawQuad(hotbarStartX, hotbarStartY - SLOT_PADDING, hotbarWidth, SLOT_SIZE + SLOT_PADDING * 2,
                SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 180); // Semi-transparent

        ItemStack[] hotbarItems = inventory.getHotbarSlots(); // Get copies
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + SLOT_PADDING + i * (SLOT_SIZE + SLOT_PADDING);
            drawSlot(hotbarItems[i], slotX, hotbarStartY, shaderProgram, uiProjection, identityView, true, i);
        }

        // Render Hotbar Selection Tooltip
        if (hotbarSelectedItemName != null && hotbarSelectedItemTooltipAlpha > 0.0f && !visible) {
            float textWidth = font.getTextWidth(hotbarSelectedItemName);
            float textHeight = font.getLineHeight();
            float tooltipPadding = 7.0f;

            float tooltipWidth = textWidth + 2 * tooltipPadding;
            float tooltipHeight = textHeight + 2 * tooltipPadding;

            // Position above the *selected* hotbar slot
            int selectedSlotIndex = inventory.getSelectedHotbarSlotIndex();
            float selectedSlotX = hotbarStartX + SLOT_PADDING + selectedSlotIndex * (SLOT_SIZE + SLOT_PADDING);
            float selectedSlotCenterX = selectedSlotX + SLOT_SIZE / 2.0f;

            float tooltipX = selectedSlotCenterX - tooltipWidth / 2.0f;
            float tooltipY = hotbarStartY - tooltipHeight - (SLOT_PADDING * 2); // Position above the hotbar with some padding

            // Adjust tooltip position to stay within screen bounds (simple horizontal check)
            if (tooltipX < 0) tooltipX = 0;
            if (tooltipX + tooltipWidth > screenWidth) tooltipX = screenWidth - tooltipWidth;


            // Calculate alpha-adjusted colors
            int currentTooltipBorderA = (int)(TOOLTIP_BORDER_A * hotbarSelectedItemTooltipAlpha);
            int currentTooltipBackgroundA = (int)(TOOLTIP_BACKGROUND_A * hotbarSelectedItemTooltipAlpha);
            // For text, we'll need to pass alpha to drawString or use a shader that supports vertex alpha for text.
            // For now, let's assume font.drawString can take an alpha or we modify its color.
            // The font.drawString method takes R,G,B. We'll need to adjust the shader or font rendering for text alpha.
            // As a simpler approach for now, we'll just render text with full alpha but the background will fade.
            // A more robust solution would involve passing alpha to the text rendering.

            shaderProgram.setUniform("u_useSolidColor", true);
            shaderProgram.setUniform("u_isText", false);
            renderer.drawQuad((int)tooltipX, (int)tooltipY, (int)tooltipWidth, (int)tooltipHeight,
                              TOOLTIP_BORDER_R, TOOLTIP_BORDER_G, TOOLTIP_BORDER_B, currentTooltipBorderA);
            renderer.drawQuad((int)tooltipX + 1, (int)tooltipY + 1, (int)tooltipWidth - 2, (int)tooltipHeight - 2,
                              TOOLTIP_BACKGROUND_R, TOOLTIP_BACKGROUND_G, TOOLTIP_BACKGROUND_B, currentTooltipBackgroundA);

            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", true);
            // For text alpha, ideally the shader and font.drawString would support it.
            // If not, the text might not fade perfectly with the background.
            // We'll use a modified text color for the fading effect if the font renderer doesn't directly support alpha.
            // Let's assume the text color is also affected by the alpha for now.
            // The font rendering would need to use this alpha.
            // For now, we pass the base text color, but the background will fade.
            // To make text fade, we'd need to modify Font.drawString or the shader.
            // Let's assume for now the text color's alpha is implicitly handled or we just fade the background.
            // For a visual fade, the text color's alpha component should also be modulated.
            // We will pass the alpha to the font drawing method if it's extended, or use a shader uniform.
            // For now, we'll render text with full opacity and only fade the background.
            // A proper text fade would require changes to Font.java or its shader.
            // Let's try to pass alpha to drawString if it were available:
            // font.drawString(textDrawX, textDrawY, hotbarSelectedItemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, (int)(255 * hotbarSelectedItemTooltipAlpha), shaderProgram);
            // Since it's not, we'll just draw it. The background fade will give the primary effect.

            float textDrawX = tooltipX + tooltipPadding;
            float textDrawY = tooltipY + tooltipPadding + (textHeight * 0.75f);

            // To actually make the text fade, the Font.drawString method or the text shader needs to use an alpha value.
            // We can simulate this by changing the text color if the shader doesn't support per-vertex alpha for text.
            // For now, we'll render the text with its standard color, and the background will fade.
            // If Font.drawString could take an alpha:
            // font.drawString(textDrawX, textDrawY, hotbarSelectedItemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, (int)(255 * hotbarSelectedItemTooltipAlpha), shaderProgram);
            // Since it doesn't, we draw as is. The background fade is the main visual cue.
            if (hotbarSelectedItemTooltipAlpha > 0.1f) { // Only draw text if substantially visible
                 font.drawString(textDrawX,
                                textDrawY,
                                hotbarSelectedItemName, TOOLTIP_TEXT_R, TOOLTIP_TEXT_G, TOOLTIP_TEXT_B, shaderProgram);
            }
            shaderProgram.setUniform("u_isText", false);
        }

        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        }
    }

    // Method to handle mouse clicks for drag and drop
    public void handleMouseInput(int screenWidth, int screenHeight) {
        if (!visible || !inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            if (draggedItemStack != null && !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                 // Mouse button released while dragging - try to place the item
                placeDraggedItem(screenWidth, screenHeight);
            } else if (!inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                // Ensure dragged item is cleared if mouse is released anywhere without a valid drop
                 if (draggedItemStack != null) {
                    // Attempt to return to original slot if not placed
                    tryReturnToOriginalSlot();
                    draggedItemStack = null;
                    draggedItemOriginalSlotIndex = -1;
                 }
            }
            return;
        }

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Calculate panel dimensions again (could be cached or passed)
        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;
        int inventoryPanelWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int inventoryPanelHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + TITLE_HEIGHT;
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;
        int contentStartY = panelStartY + TITLE_HEIGHT;
        int hotbarRowYOffset = contentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;


        if (draggedItemStack == null) { // Not currently dragging, try to pick up an item
            // Check main inventory slots
            for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                int row = i / Inventory.MAIN_INVENTORY_COLS;
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                    mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack currentStack = inventory.getMainInventorySlot(i); // Get direct reference
                    if (currentStack != null && !currentStack.isEmpty()) {
                        draggedItemStack = currentStack.copy(); // Take the whole stack
                        inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(),0)); // Clear original slot
                        draggedItemOriginalSlotIndex = i;
                        isDraggingFromHotbar = false;
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }

            // Check hotbar slots (within the inventory panel)
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = hotbarRowYOffset;

                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                    mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack currentStack = inventory.getHotbarSlot(i); // Get direct reference
                    if (currentStack != null && !currentStack.isEmpty()) {
                        draggedItemStack = currentStack.copy();
                        inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(),0));
                        draggedItemOriginalSlotIndex = i;
                        isDraggingFromHotbar = true;
                        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }
        }
        // If already dragging, the placeDraggedItem will be called on mouse release.
        // We consume the press here to prevent other actions while dragging.
        if (draggedItemStack != null) {
             inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }

    private void placeDraggedItem(int screenWidth, int screenHeight) {
        if (draggedItemStack == null) return;

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Panel dimensions (repeated, consider refactoring to a common calculation point or member vars updated on resize)
        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;
        int inventoryPanelWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int inventoryPanelHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + TITLE_HEIGHT;
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;
        int contentStartY = panelStartY + TITLE_HEIGHT;
        int hotbarRowYOffset = contentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        boolean placed = false;

        // Try to place in main inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack targetStack = inventory.getMainInventorySlot(i); // Direct reference
                if (targetStack.isEmpty()) {
                    inventory.setMainInventorySlot(i, draggedItemStack);
                    draggedItemStack = null; // Item is placed, no longer dragged
                    placed = true;
                } else if (targetStack.getBlockTypeId() == draggedItemStack.getBlockTypeId() &&
                           targetStack.getCount() < targetStack.getMaxStackSize()) {
                    // Try to stack
                    int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    targetStack.incrementCount(toAdd);
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) {
                        draggedItemStack = null; // Fully stacked, clear dragged item
                        placed = true;
                    } else {
                        // Could not place all, item remains dragged or return to original
                    }
                } else { // Swap
                    ItemStack temp = targetStack.copy();
                    inventory.setMainInventorySlot(i, draggedItemStack);
                    draggedItemStack = temp; // Continue dragging the swapped item
                    // No, for simple swap, the dragged item is placed, and the slot's item is now dragged.
                    // For this interaction, we place our dragged item, and pick up the one from the slot.
                    // This means the original draggedItem is now gone.
                    // Let's simplify: if slot is not empty and not stackable, swap.
                    inventory.setMainInventorySlot(i, draggedItemStack); // Place current dragged
                    draggedItemStack = temp; // Pick up the one that was there
                    // This makes the original dragged item placed, and we are now dragging the item from the slot.
                    // To complete the swap in one click:
                    // inventory.setMainInventorySlot(i, draggedItemStack);
                    // if (isDraggingFromHotbar) inventory.setHotbarSlot(draggedItemOriginalSlotIndex, temp);
                    // else inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, temp);
                    // For now, let's just place if empty, or swap if different items.
                    // If same item and stackable, handled above.
                    // If different items, swap:
                    if (targetStack.getBlockTypeId() != draggedItemStack.getBlockTypeId()) {
                         ItemStack itemFromTargetSlot = targetStack.copy(); // Make a copy before targetStack is overwritten
                         inventory.setMainInventorySlot(i, draggedItemStack); // Place current dragged item into the target slot
                         
                         // Place the item originally from the target slot into the original slot of the dragged item
                         if(isDraggingFromHotbar) {
                            inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                         } else {
                            inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                         }
                         draggedItemStack = null; // Successfully swapped, clear dragged item
                         placed = true;
                    }
                }
                break; // Found a slot
            }
        }

        // Try to place in hotbar
        if (!placed) {
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = hotbarRowYOffset;

                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                    mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack targetStack = inventory.getHotbarSlot(i); // Direct reference
                     if (targetStack.isEmpty()) {
                        inventory.setHotbarSlot(i, draggedItemStack);
                        draggedItemStack = null; // Item is placed, no longer dragged
                        placed = true;
                    } else if (targetStack.getBlockTypeId() == draggedItemStack.getBlockTypeId() &&
                               targetStack.getCount() < targetStack.getMaxStackSize()) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) {
                            draggedItemStack = null; // Fully stacked, clear dragged item
                            placed = true;
                        }
                    } else { // Swap with hotbar slot
                        if (targetStack.getBlockTypeId() != draggedItemStack.getBlockTypeId()) {
                            ItemStack itemFromTargetSlot = targetStack.copy(); // Make a copy before targetStack is overwritten
                            inventory.setHotbarSlot(i, draggedItemStack); // Place current dragged item into the target hotbar slot

                            // Place the item originally from the target hotbar slot into the original slot of the dragged item
                            if(isDraggingFromHotbar) {
                                inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                            } else {
                                inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                            }
                            draggedItemStack = null; // Successfully swapped, clear dragged item
                            placed = true;
                        }
                    }
                    break; // Found a slot
                }
            }
        }

        if (placed && (draggedItemStack == null || draggedItemStack.isEmpty())) {
            draggedItemStack = null;
            draggedItemOriginalSlotIndex = -1;
        } else if (!placed && draggedItemStack != null && !draggedItemStack.isEmpty()) {
            // If not placed and item still exists, return to original slot
            tryReturnToOriginalSlot();
        } else if (placed && draggedItemStack != null && !draggedItemStack.isEmpty()){
            // This case means a swap happened, and draggedItemStack is now the item from the target slot.
            // We need to place *this* new draggedItemStack back into the *original* slot of the first item.
            tryReturnToOriginalSlot(); // This will try to place the *new* dragged item into the *old* original slot
        }


        // If an item was placed (and draggedItemStack is now empty/null) or successfully returned
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
             draggedItemStack = null;
             draggedItemOriginalSlotIndex = -1;
        }
        // If after all attempts, draggedItemStack still exists (e.g. partial stack, or failed return)
        // it will continue to be rendered with the mouse. A click outside could clear it or a proper
        // "drop item on ground" mechanic would be needed. For now, it returns if it can.
    }

    private void tryReturnToOriginalSlot() {
        if (draggedItemStack == null || draggedItemStack.isEmpty() || draggedItemOriginalSlotIndex == -1) {
            draggedItemStack = null; // Ensure it's cleared if it was emptied by stacking
            return;
        }

        ItemStack originalSlotItemStack;
        if (isDraggingFromHotbar) {
            originalSlotItemStack = inventory.getHotbarSlot(draggedItemOriginalSlotIndex);
            if (originalSlotItemStack.isEmpty()) {
                inventory.setHotbarSlot(draggedItemOriginalSlotIndex, draggedItemStack);
                draggedItemStack = null;
            } else if (originalSlotItemStack.getBlockTypeId() == draggedItemStack.getBlockTypeId() &&
                       originalSlotItemStack.getCount() < originalSlotItemStack.getMaxStackSize()) {
                // Try to stack back
                int canAdd = originalSlotItemStack.getMaxStackSize() - originalSlotItemStack.getCount();
                int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                originalSlotItemStack.incrementCount(toAdd);
                draggedItemStack.decrementCount(toAdd);
                if (draggedItemStack.isEmpty()) {
                    draggedItemStack = null;
                }
            } // Else, cannot return to original slot if it's now occupied by a different item and cannot stack.
              // Item remains "in hand". A more robust system might drop it or find another empty slot.
        } else { // Was from main inventory
            originalSlotItemStack = inventory.getMainInventorySlot(draggedItemOriginalSlotIndex);
            if (originalSlotItemStack.isEmpty()) {
                inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, draggedItemStack);
                draggedItemStack = null;
            } else if (originalSlotItemStack.getBlockTypeId() == draggedItemStack.getBlockTypeId() &&
                       originalSlotItemStack.getCount() < originalSlotItemStack.getMaxStackSize()) {
                int canAdd = originalSlotItemStack.getMaxStackSize() - originalSlotItemStack.getCount();
                int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                originalSlotItemStack.incrementCount(toAdd);
                draggedItemStack.decrementCount(toAdd);
                if (draggedItemStack.isEmpty()) {
                    draggedItemStack = null;
                }
            }
        }
         if (draggedItemStack != null && draggedItemStack.isEmpty()){ // If count became zero after trying to return
            draggedItemStack = null;
        }
    }

    private void checkHover(ItemStack itemStack, int slotX, int slotY, int screenWidth, int screenHeight) {
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
}