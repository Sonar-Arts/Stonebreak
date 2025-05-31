package com.stonebreak;

import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.nanovg.NVGColor;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_BOTTOM;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_RIGHT;
import static org.lwjgl.nanovg.NanoVG.nvgBeginPath;
import static org.lwjgl.nanovg.NanoVG.nvgFill;
import static org.lwjgl.nanovg.NanoVG.nvgFillColor;
import static org.lwjgl.nanovg.NanoVG.nvgFontFace;
import static org.lwjgl.nanovg.NanoVG.nvgFontSize;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgRoundedRect;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign;
import static org.lwjgl.nanovg.NanoVG.nvgTextBounds;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.system.MemoryStack.stackPush;



/**
 * A 2D UI for displaying the player's inventory.
 */
public class InventoryScreen {

    private final Inventory inventory;
    private boolean visible;
    // Font field removed since it's unused
    private final Renderer renderer;
    private final UIRenderer uiRenderer;
    private final InputHandler inputHandler; // Added for mouse input

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
    
    /**
     * Creates a new inventory screen.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, UIRenderer uiRenderer, InputHandler inputHandler) {
        this.inventory = inventory;
        this.visible = false;
        // font parameter is received but not used
        this.renderer = renderer;
        this.uiRenderer = uiRenderer;
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

        // Main Inventory Area (now includes hotbar visually as part of the panel)
        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;

        int inventoryPanelWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        // Height for main inventory slots + one row for hotbar + title
        int inventoryPanelHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + TITLE_HEIGHT;

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Draw panel background using UIRenderer
        drawInventoryPanel(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight);

        // Draw title using NanoVG
        drawInventoryTitle(panelStartX + inventoryPanelWidth / 2, panelStartY + 20, "Inventory");

        int contentStartY = panelStartY + TITLE_HEIGHT;

        // Draw Main Inventory Slots
        ItemStack[] mainSlots = inventory.getMainInventorySlots(); // Gets copies
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);
            drawInventorySlot(mainSlots[i], slotX, slotY, false, -1);
            checkHover(mainSlots[i], slotX, slotY);
        }
        
        // Draw Hotbar Slots (as part of the main inventory panel, visually)
        // Positioned below the main inventory slots within the same panel
        ItemStack[] hotbarSlots = inventory.getHotbarSlots(); // Gets copies
        int hotbarRowYOffset = contentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // Extra padding for separation

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar is a single row
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarRowYOffset;
             // Pass true for isHotbarSlot and the actual hotbar index
            drawInventorySlot(hotbarSlots[i], slotX, slotY, true, i);
            checkHover(hotbarSlots[i], slotX, slotY);
        }


        // Draw dragged item on top of everything else
        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            Vector2f mousePos = inputHandler.getMousePosition();
            // Center the item on the mouse cursor
            int itemRenderX = (int) (mousePos.x - (SLOT_SIZE -4) / 2.0f);
            int itemRenderY = (int) (mousePos.y - (SLOT_SIZE -4) / 2.0f);

            BlockType type = BlockType.getById(draggedItemStack.getBlockTypeId());
            if (type != null && type.getAtlasX() != -1 && type.getAtlasY() != -1) {
                drawDraggedItem(type, itemRenderX, itemRenderY, draggedItemStack.getCount());
            }
        }

        // Draw Tooltip
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && draggedItemStack == null) { // Only show tooltip if not dragging
            BlockType type = BlockType.getById(hoveredItemStack.getBlockTypeId());
            if (type != null && type != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                drawItemTooltip(type.getName(), mousePos.x + 15, mousePos.y + 15, screenWidth, screenHeight);
            }
        }
    }    // Helper method to draw inventory panel using UIRenderer
    private void drawInventoryPanel(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            // Removed unused bevelSize variable
            
            // Main panel background
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(50, 50, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Panel border
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
    
    private void drawInventoryTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 24);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, title);
        }
    }
    
    // Helper method to draw a single slot using UIRenderer
    private void drawInventorySlot(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex) {
        try {
            try (MemoryStack stack = stackPush()) {
                // Add validation
                if (uiRenderer == null) {
                    System.err.println("ERROR: UIRenderer is null in drawInventorySlot");
                    return;
                }
            long vg = uiRenderer.getVG();
            // Highlight selected hotbar slot
            if (isHotbarSlot && inventory.getSelectedHotbarSlotIndex() == hotbarIndex) {
                nvgBeginPath(vg);
                nvgRect(vg, slotX - 2, slotY - 2, SLOT_SIZE + 4, SLOT_SIZE + 4);
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            // Slot border
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, SLOT_SIZE, SLOT_SIZE);
            nvgFillColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Slot background
            nvgBeginPath(vg);
            nvgRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2);
            nvgFillColor(vg, nvgRGBA(70, 70, 70, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            if (itemStack != null && !itemStack.isEmpty()) {
                BlockType type = BlockType.getById(itemStack.getBlockTypeId());
                int count = itemStack.getCount();
                
                if (type != null && type.getAtlasX() != -1 && type.getAtlasY() != -1) {
                    try {
                        // End NanoVG frame temporarily to draw 3D item
                        uiRenderer.endFrame();
                        
                        // Draw 3D item using existing renderer
                        renderer.draw3DItemInSlot(type, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4);
                        
                        // Restart NanoVG frame
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                    } catch (Exception e) {
                        System.err.println("Error rendering 3D item in slot: " + e.getMessage());
                        // Try to recover by ensuring frame is restarted
                        try {
                            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                        } catch (Exception e2) {
                            System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                        }
                    }
                    
                    if (count > 1) {
                        String countText = String.valueOf(count);
                        nvgFontSize(vg, 12);
                        nvgFontFace(vg, "sans");
                        nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                        
                        // Text shadow
                        nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, countText);
                        
                        // Main text
                        nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + SLOT_SIZE - 3, slotY + SLOT_SIZE - 3, countText);
                    }
                }
            }
            }
        } catch (Exception e) {
            System.err.println("ERROR in drawInventorySlot: " + e.getMessage());
            e.printStackTrace();
            
            // Try to recover UI state
            try {
                if (uiRenderer != null) {
                    uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                }
            } catch (Exception e2) {
                System.err.println("Failed to recover UI frame: " + e2.getMessage());
            }
        }
    }
    
    private void drawDraggedItem(BlockType type, int x, int y, int count) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            // End NanoVG frame temporarily to draw 3D item
            uiRenderer.endFrame();
            
            // Draw 3D item using existing renderer
            renderer.draw3DItemInSlot(type, x, y, SLOT_SIZE - 4, SLOT_SIZE - 4);
            
            // Restart NanoVG frame
            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
            
            if (count > 1) {
                String countText = String.valueOf(count);
                nvgFontSize(vg, 12);
                nvgFontFace(vg, "sans");
                nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                
                // Text shadow
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                nvgText(vg, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, countText);
                
                // Main text
                nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                nvgText(vg, x + SLOT_SIZE - 3, y + SLOT_SIZE - 3, countText);
            }
        }
    }
    
    private void drawItemTooltip(String itemName, float x, float y, int screenWidth, int screenHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float padding = 12.0f;
            float cornerRadius = 6.0f;
            
            // Measure text with better font
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "minecraft");
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, itemName, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];
            
            float tooltipWidth = textWidth + 2 * padding;
            float tooltipHeight = textHeight + 2 * padding;
            
            // Adjust position to stay within screen bounds with margin
            float margin = 10.0f;
            if (x + tooltipWidth > screenWidth - margin) {
                x = screenWidth - tooltipWidth - margin;
            }
            if (y + tooltipHeight > screenHeight - margin) {
                y = screenHeight - tooltipHeight - margin;
            }
            if (x < margin) x = margin;
            if (y < margin) y = margin;
            
            // Drop shadow for depth
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 3, y + 3, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 100, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Tooltip background with gradient
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(40, 40, 50, 240, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Inner highlight for 3D effect
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 1, y + 1, tooltipWidth - 2, tooltipHeight - 2, cornerRadius - 1);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(80, 80, 100, 120, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Outer border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, tooltipWidth, tooltipHeight, cornerRadius);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 180, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Text shadow for better readability
            nvgFontSize(vg, 16);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
            nvgText(vg, x + tooltipWidth / 2 + 1, y + tooltipHeight / 2 + 1, itemName);
            
            // Main tooltip text
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, x + tooltipWidth / 2, y + tooltipHeight / 2, itemName);
        }
    }
    
    private void drawHotbarBackground(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(70, 70, 70, 180, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }
    
    private void drawHotbarTooltip(String itemName, float centerX, float y, int screenWidth, float alpha) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float padding = 10.0f;
            float cornerRadius = 5.0f;
            
            // Measure text with better font
            nvgFontSize(vg, 15);
            nvgFontFace(vg, "minecraft");
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, itemName, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1];
            
            float tooltipWidth = textWidth + 2 * padding;
            float tooltipHeight = textHeight + 2 * padding;
            
            float tooltipX = centerX - tooltipWidth / 2.0f;
            float tooltipY = y - tooltipHeight - 10.0f; // Add more spacing from hotbar
            
            // Adjust position to stay within screen bounds
            float margin = 5.0f;
            if (tooltipX < margin) tooltipX = margin;
            if (tooltipX + tooltipWidth > screenWidth - margin) tooltipX = screenWidth - tooltipWidth - margin;
            
            // Calculate alpha-adjusted colors
            int shadowAlpha = (int)(80 * alpha);
            int backgroundAlpha = (int)(240 * alpha);
            int borderAlpha = (int)(180 * alpha);
            int textAlpha = (int)(255 * alpha);
            
            // Drop shadow for depth
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX + 2, tooltipY + 2, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, shadowAlpha, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Tooltip background with improved color
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(40, 40, 50, backgroundAlpha, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Inner highlight for 3D effect
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX + 1, tooltipY + 1, tooltipWidth - 2, tooltipHeight - 2, cornerRadius - 1);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(80, 80, 100, (int)(120 * alpha), NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Outer border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, cornerRadius);
            nvgStrokeWidth(vg, 1.5f);
            nvgStrokeColor(vg, nvgRGBA(255, 255, 255, borderAlpha, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Tooltip text (only draw if substantially visible)
            if (alpha > 0.1f) {
                // Text shadow
                nvgFontSize(vg, 15);
                nvgFontFace(vg, "minecraft");
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, (int)(150 * alpha), NVGColor.malloc(stack)));
                nvgText(vg, tooltipX + tooltipWidth / 2 + 1, tooltipY + tooltipHeight / 2 + 1, itemName);
                
                // Main text
                nvgFillColor(vg, nvgRGBA(255, 255, 255, textAlpha, NVGColor.malloc(stack)));
                nvgText(vg, tooltipX + tooltipWidth / 2, tooltipY + tooltipHeight / 2, itemName);
            }
        }
    }

    // Helper method to create NVGColor
    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }


    /**
     * Renders the separate hotbar at the bottom of the screen when inventory is closed.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render separate hotbar if full inventory is open

        int hotbarWidth = Inventory.HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarStartX = (screenWidth - hotbarWidth) / 2;
        int hotbarStartY = screenHeight - SLOT_SIZE - HOTBAR_Y_OFFSET;

        drawHotbarBackground(hotbarStartX, hotbarStartY - SLOT_PADDING, hotbarWidth, SLOT_SIZE + SLOT_PADDING * 2);

        ItemStack[] hotbarItems = inventory.getHotbarSlots(); // Get copies
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + SLOT_PADDING + i * (SLOT_SIZE + SLOT_PADDING);
            
            // Removed debug logging to reduce console spam
            
            drawInventorySlot(hotbarItems[i], slotX, hotbarStartY, true, i);
        }

        // Render Hotbar Selection Tooltip
        if (hotbarSelectedItemName != null && hotbarSelectedItemTooltipAlpha > 0.0f && !visible) {
            // Position above the *selected* hotbar slot
            int selectedSlotIndex = inventory.getSelectedHotbarSlotIndex();
            float selectedSlotX = hotbarStartX + SLOT_PADDING + selectedSlotIndex * (SLOT_SIZE + SLOT_PADDING);
            float selectedSlotCenterX = selectedSlotX + SLOT_SIZE / 2.0f;
            
            drawHotbarTooltip(hotbarSelectedItemName, selectedSlotCenterX, hotbarStartY - 10, screenWidth, hotbarSelectedItemTooltipAlpha);
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
    }    private void checkHover(ItemStack itemStack, int slotX, int slotY) {
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