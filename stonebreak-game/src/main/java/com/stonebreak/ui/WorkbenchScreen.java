package com.stonebreak.ui;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.util.ArrayList;
import java.util.List;

import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;

/**
 * A 2D UI for displaying the workbench, including a 3x3 crafting grid.
 */
public class WorkbenchScreen {

    private final Inventory playerInventory;
    private boolean visible;
    private final Renderer renderer;
    private final UIRenderer uiRenderer;
    private final InputHandler inputHandler;
    private final CraftingManager craftingManager;
    private final Game game; // To handle screen closing

    // Drag and drop state
    private ItemStack draggedItemStack;
    private int draggedItemOriginalSlotIndex; // -1 if not dragging. Special values for crafting grid.
    private enum DragSource { NONE, HOTBAR, MAIN_INVENTORY, CRAFTING_INPUT, CRAFTING_OUTPUT }
    private DragSource dragSource = DragSource.NONE;
    private ItemStack hoveredItemStack; // For tooltip

    // Crafting slots
    private static final int CRAFTING_GRID_SIZE = 3; // 3x3 grid
    private static final int CRAFTING_INPUT_SLOTS_COUNT = CRAFTING_GRID_SIZE * CRAFTING_GRID_SIZE;
    private final ItemStack[] craftingInputSlots;
    private ItemStack craftingOutputSlot;

    // Constants for slot indices for drag and drop mapping
    // Ensure these don't clash with player inventory indices (0-35 for hotbar + main)
    private static final int CRAFTING_INPUT_SLOT_START_INDEX = 100; // Start index for 3x3 grid slots
    private static final int CRAFTING_OUTPUT_SLOT_INDEX = 200;      // Index for output slot

    // Defensive fix: prevent rapid repeated shift-click transfers
    private long lastShiftClickTime = 0;
    private static final long SHIFT_CLICK_COOLDOWN_MS = 100; // 100ms cooldown between shift-clicks

    // UI constants
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int TITLE_HEIGHT = 30;

    // Recipe Book Button
    private static final String RECIPE_BUTTON_TEXT = "Recipes";
    private float recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight;
 
    /**
     * Creates a new workbench screen.
     */
    public WorkbenchScreen(Game game, Inventory playerInventory, Renderer renderer, UIRenderer uiRenderer, InputHandler inputHandler, CraftingManager craftingManager) {
        this.game = game;
        this.playerInventory = playerInventory;
        this.visible = false;
        this.renderer = renderer;
        this.uiRenderer = uiRenderer;
        this.inputHandler = inputHandler;
        this.craftingManager = craftingManager;

        this.draggedItemStack = null;
        this.draggedItemOriginalSlotIndex = -1;
        this.dragSource = DragSource.NONE;
        this.hoveredItemStack = null;

        this.craftingInputSlots = new ItemStack[CRAFTING_INPUT_SLOTS_COUNT];
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            this.craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
        this.craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
    }

    public void open() {
        this.visible = true;
        // Clear crafting grid when opening, or ensure it's persistent? For now, let's keep it persistent.
        updateCraftingOutput(); // Ensure output is correct if grid had items
        
        // Update mouse capture state when workbench opens
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public void close() {
        this.visible = false;
        // Potentially drop items from crafting grid if desired, or leave them
        // For now, leave items in grid when closing.
        
        // Update mouse capture state when workbench closes
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisibility() {
        if (visible) {
            close();
        } else {
            open();
        }
    }
    
    public void update(float deltaTime) {
        // Future: Any animations or timed events for the workbench screen
    }

    // Modified to match RecipeBookScreen and Main.java's render call structure
    public void render() {
        if (!visible || uiRenderer == null) {
            return;
        }

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f); // Manage frame at the screen level

        hoveredItemStack = null; // Reset hovered item

        // Panel dimensions (adjust to fit 3x3 grid, output, and player inventory)
        int playerInvWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        
        // Crafting area: 3x3 grid + arrow + output slot
        // Width: 3 * (SLOT_SIZE + PADDING) for grid, + SLOT_SIZE for arrow, + SLOT_SIZE for output + paddings
        int craftingGridVisualWidth = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int craftingSectionWidth = craftingGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        
        int panelWidth = Math.max(playerInvWidth, craftingSectionWidth); // Panel width accommodates largest section
        
        // Height: Title + Crafting Grid Area + Player Inv Title + Player Inv (Main + Hotbar) + Paddings
        int craftingGridActualHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int playerInvHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // +1 for hotbar

        int panelHeight = TITLE_HEIGHT + craftingGridActualHeight + TITLE_HEIGHT + playerInvHeight + SLOT_PADDING * 3; // Titles + Sections + Gaps

        int panelStartX = (screenWidth - panelWidth) / 2;
        int panelStartY = (screenHeight - panelHeight) / 2;

        // Draw panel background
        drawPanel(panelStartX, panelStartY, panelWidth, panelHeight);

        // Draw "Workbench" title
        float craftingTitleY = panelStartY + TITLE_HEIGHT / 2f + SLOT_PADDING;
        drawTitle(panelStartX + panelWidth / 2, craftingTitleY, "Workbench");

        // Crafting elements layout
        int craftingAreaBaseY = panelStartY + TITLE_HEIGHT + SLOT_PADDING;
        int craftingInputGridX = panelStartX + (panelWidth - craftingSectionWidth) / 2 + SLOT_PADDING;
        int craftingInputGridY = craftingAreaBaseY;

        // Draw 3x3 Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY + r * (SLOT_SIZE + SLOT_PADDING);
            drawSlotUI(craftingInputSlots[i], slotX, slotY, false, -1);
            checkHover(craftingInputSlots[i], slotX, slotY);
        }

        // Draw Arrow
        int arrowX = craftingInputGridX + craftingGridVisualWidth + SLOT_PADDING;
        // Vertically center arrow with the 3x3 grid
        int arrowY = craftingInputGridY + (CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING - SLOT_SIZE) / 2; // Approximate center
        drawCraftingArrow(arrowX, arrowY, SLOT_SIZE, SLOT_SIZE);

        // Draw Crafting Output Slot
        int outputSlotX = arrowX + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY = arrowY; // Align with arrow vertically
        drawSlotUI(craftingOutputSlot, outputSlotX, outputSlotY, false, -1);
        checkHover(craftingOutputSlot, outputSlotX, outputSlotY);

        // Define and draw Recipe Button position (near crafting output)
        recipeButtonWidth = uiRenderer.getTextWidth(RECIPE_BUTTON_TEXT, 18f, "sans") + 2 * SLOT_PADDING;
        recipeButtonHeight = SLOT_SIZE;
        recipeButtonX = outputSlotX + SLOT_SIZE + SLOT_PADDING * 3; // Position to the right of the output slot
        recipeButtonY = outputSlotY; // Align vertically

        drawRecipeButton(recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight, RECIPE_BUTTON_TEXT);
 
 
        // Player Inventory Area
        int playerInvTitleY = craftingInputGridY + craftingGridActualHeight + SLOT_PADDING + (int)(TITLE_HEIGHT / 2f);
        drawTitle(panelStartX + panelWidth / 2, playerInvTitleY, "Inventory");
        
        int playerInvSlotsStartY = playerInvTitleY + (int)(TITLE_HEIGHT / 2f) + SLOT_PADDING;
        int playerInvSlotsStartX = panelStartX + (panelWidth - playerInvWidth) / 2 + SLOT_PADDING; // Center player inv section if panel is wider

        // Draw Main Player Inventory Slots
        ItemStack[] mainSlots = playerInventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = playerInvSlotsStartX + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = playerInvSlotsStartY + row * (SLOT_SIZE + SLOT_PADDING);
            drawSlotUI(mainSlots[i], slotX, slotY, false, -1);
            checkHover(mainSlots[i], slotX, slotY);
        }
        
        // Draw Player Hotbar Slots
        ItemStack[] hotbarSlots = playerInventory.getHotbarSlots();
        int hotbarRowY = playerInvSlotsStartY + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = playerInvSlotsStartX + i * (SLOT_SIZE + SLOT_PADDING);
            drawSlotUI(hotbarSlots[i], slotX, hotbarRowY, true, i); // isHotbarSlot = true, pass hotbarIndex
            checkHover(hotbarSlots[i], slotX, hotbarRowY);
        }

        // Draw dragged item
        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            Vector2f mousePos = inputHandler.getMousePosition();
            int itemRenderX = (int) (mousePos.x - (SLOT_SIZE - 4) / 2.0f);
            int itemRenderY = (int) (mousePos.y - (SLOT_SIZE - 4) / 2.0f);
            Item item = draggedItemStack.getItem();
            if (item != null) {
                drawDraggedItem3D(item, itemRenderX, itemRenderY, draggedItemStack.getCount());
            }
        }

        // Draw Tooltip
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && draggedItemStack == null) {
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                drawItemTooltipUI(item.getName(), mousePos.x + 15, mousePos.y + 15, screenWidth, screenHeight);
            }
        }
        uiRenderer.endFrame(); // End frame at the end of screen rendering
    }

    private void drawPanel(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, 5);
            nvgFillColor(vg, nvgRGBA(50, 50, 50, 220, NVGColor.malloc(stack)));
            nvgFill(vg);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }

    private void drawTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgFontSize(vg, 20); // Adjusted size
            nvgFontFace(vg, "minecraft"); // Assuming "minecraft" font is loaded
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY, title);
        }
    }

    private void drawSlotUI(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Hotbar selection highlight removed - no highlight in workbench screen

            // Slot border
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, SLOT_SIZE, SLOT_SIZE);
            nvgFillColor(vg, nvgRGBA(30, 30, 30, 255, NVGColor.malloc(stack))); // Darker border
            nvgFill(vg);
            
            // Slot background (inner part)
            nvgBeginPath(vg);
            nvgRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2);
            nvgFillColor(vg, nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack))); // Mid-gray
            nvgFill(vg);

            if (itemStack != null && !itemStack.isEmpty()) {
                Item item = itemStack.getItem();
                int count = itemStack.getCount();

                if (item != null && item != BlockType.AIR) {
                    // Temporarily end frame for 3D rendering within NanoVG pass.
                    // This specific call to uiRenderer.endFrame() and .beginFrame() inside a slot
                    // assumes that draw3DItemInSlot does OpenGL operations that need NanoVG to be paused.
                    // If draw3DItemInSlot itself manages NanoVG state, this might be simpler.
                    // For now, assume InventoryScreen.java approach is needed.
                    // The main begin/endFrame for the whole screen is now in this class's render().

                    uiRenderer.endFrame(); // End NanoVG frame for 3D rendering
                    // Draw 3D item using UIRenderer's BlockIconRenderer
                    if (item instanceof BlockType blockType) {
                        uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), blockType, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, renderer.getTextureAtlas());
                    } else {
                        // For ItemTypes, render a 2D sprite using UIRenderer
                        uiRenderer.renderItemIcon(slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, item, renderer.getTextureAtlas());
                    }
                    uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f); // Restart NanoVG frame

                    if (count > 1) {
                        String countText = String.valueOf(count);
                        nvgFontSize(vg, 14);
                        nvgFontFace(vg, "sans");
                        nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                        // Shadow
                        nvgFillColor(vg, nvgRGBA(0, 0, 0, 180, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + SLOT_SIZE - 3, slotY + SLOT_SIZE - 1, countText);
                        // Text
                        nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + SLOT_SIZE - 4, slotY + SLOT_SIZE - 2, countText);
                    }
                }
            }
        }
    }
    
    private void drawDraggedItem3D(Item item, int x, int y, int count) {
        try (MemoryStack stack = stackPush()){
            long vg = uiRenderer.getVG();
            uiRenderer.endFrame(); // End NanoVG frame for 3D rendering
            // Draw 3D item using UIRenderer's BlockIconRenderer
            if (item instanceof BlockType blockType) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), blockType, x + 2, y + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, renderer.getTextureAtlas());
            } else {
                // For ItemTypes, render a 2D sprite using UIRenderer
                uiRenderer.renderItemIcon(x + 2, y + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, item, renderer.getTextureAtlas());
            }
            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f); // Restart NanoVG frame

            if (count > 1) {
                String countText = String.valueOf(count);
                nvgFontSize(vg, 14);
                nvgFontFace(vg, "sans");
                nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
                 // Shadow
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 180, NVGColor.malloc(stack)));
                nvgText(vg, x + SLOT_SIZE - 2, y + SLOT_SIZE - 0, countText); // Adjusted y slightly
                // Text
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                nvgText(vg, x + SLOT_SIZE - 3, y + SLOT_SIZE - 1, countText);
            }
        }
    }

    private void drawItemTooltipUI(String itemName, float x, float y, int screenWidth, int screenHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float padding = 10.0f;
            float cornerRadius = 4.0f;

            nvgFontSize(vg, 15);
            nvgFontFace(vg, "minecraft");
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, itemName, bounds);
            float textWidth = bounds[2] - bounds[0];
            float textHeight = bounds[3] - bounds[1]; // Use actual text height

            float tooltipWidth = textWidth + 2 * padding;
            float tooltipHeight = Math.max(20, textHeight + 2 * padding); // Ensure min height

            float tooltipX = x;
            float tooltipY = y;

            // Adjust to keep within screen bounds
            if (tooltipX + tooltipWidth + 5 > screenWidth) tooltipX = screenWidth - tooltipWidth - 5;
            if (tooltipY + tooltipHeight + 5 > screenHeight) tooltipY = screenHeight - tooltipHeight - 5;
            if (tooltipX < 5) tooltipX = 5;
            if (tooltipY < 5) tooltipY = 5;
            
            // Background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, cornerRadius);
            nvgFillColor(vg, nvgRGBA(20, 0, 30, 230, NVGColor.malloc(stack))); // Dark purple, Minecraft style
            nvgFill(vg);

            // Border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, cornerRadius);
            nvgStrokeWidth(vg, 1.5f);
            nvgStrokeColor(vg, nvgRGBA(80, 0, 120, 255, NVGColor.malloc(stack))); // Slightly lighter purple border
            nvgStroke(vg);
            
            // Text
            nvgFontSize(vg, 15);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP); // Align to top-left for padding
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, tooltipX + padding, tooltipY + padding - (textHeight > 18 ? 2 : 0) , itemName); // Adjust y for font baseline
        }
    }

    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }

    private void drawCraftingArrow(float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgBeginPath(vg);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 220, NVGColor.malloc(stack)));
            // Basic arrow shape (adjust as needed)
            float stemHeight = height * 0.3f;
            float stemY = y + (height - stemHeight) / 2;
            nvgRect(vg, x, stemY, width * 0.7f, stemHeight); // Stem
            nvgFill(vg);

            nvgBeginPath(vg); // Triangle head
            nvgMoveTo(vg, x + width * 0.6f, y);
            nvgLineTo(vg, x + width, y + height / 2);
            nvgLineTo(vg, x + width * 0.6f, y + height);
            nvgClosePath(vg);
            nvgFill(vg);
        }
    }

    // New general input handler
    public void handleInput(InputHandler inputHandlerInstance) {
        if (!visible) return;


        // Keyboard input for closing the screen with Escape is now handled by InputHandler.handleEscapeKey()
        // No need for direct Escape key check here anymore.
        
        // Mouse input handling (delegates to existing method)
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        handleMouseInputInternal(screenWidth, screenHeight, inputHandlerInstance);
    }


    // Renamed from handleMouseInput to handleMouseInputInternal to avoid signature clash if public visibility was intended before
    private void handleMouseInputInternal(int screenWidth, int screenHeight, InputHandler currentInputHandler) {
        if (!visible) return;


        Vector2f mousePos = currentInputHandler.getMousePosition();
        boolean leftShiftDown = currentInputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT);
        boolean rightShiftDown = currentInputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean shiftDown = leftShiftDown || rightShiftDown;

        // Debug: Check if any hotbar keys are pressed
        boolean anyHotbarKeyPressed = false;
        for (int i = 0; i < 9; i++) {
            if (currentInputHandler.isKeyDown(GLFW.GLFW_KEY_1 + i)) {
                anyHotbarKeyPressed = true;
                break;
            }
        }

        // --- Calculate slot coordinates consistently ---
        int playerInvWidth_calc_local = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingGridVisualWidth_calc_local = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int craftingSectionWidth_calc_local = craftingGridVisualWidth_calc_local + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING; // grid + arrow + output + paddings
        int panelWidth_calc_local = Math.max(playerInvWidth_calc_local, craftingSectionWidth_calc_local);
        int craftingGridActualHeight_calc_local = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int playerInvHeight_calc_local = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int panelHeight_calc_local = TITLE_HEIGHT + craftingGridActualHeight_calc_local + TITLE_HEIGHT + playerInvHeight_calc_local + SLOT_PADDING * 3;
        int panelStartX_calc_local = (screenWidth - panelWidth_calc_local) / 2;
        int panelStartY_calc_local = (screenHeight - panelHeight_calc_local) / 2;

        int craftingAreaBaseY_calc_local = panelStartY_calc_local + TITLE_HEIGHT + SLOT_PADDING;
        int craftingInputGridX_calc_local = panelStartX_calc_local + (panelWidth_calc_local - craftingSectionWidth_calc_local) / 2 + SLOT_PADDING;
        int craftingInputGridY_calc_local = craftingAreaBaseY_calc_local;

        int arrowX_calc_local = craftingInputGridX_calc_local + craftingGridVisualWidth_calc_local + SLOT_PADDING;
        int arrowY_calc_local = craftingInputGridY_calc_local + (CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING - SLOT_SIZE) / 2;
        int outputSlotX_calc_local = arrowX_calc_local + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY_calc_local = arrowY_calc_local;

        // Player Inventory Area calculations from render()
        int playerInvTitleY_calc_local = craftingInputGridY_calc_local + craftingGridActualHeight_calc_local + SLOT_PADDING + (TITLE_HEIGHT / 2);
        int playerInvSlotsStartY_calc_local = playerInvTitleY_calc_local + (TITLE_HEIGHT / 2) + SLOT_PADDING;
        int playerInvSlotsStartX_calc_local = panelStartX_calc_local + (panelWidth_calc_local - playerInvWidth_calc_local) / 2 + SLOT_PADDING;
        int hotbarRowY_calc_local = playerInvSlotsStartY_calc_local + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;


        boolean leftMouseButtonPressed = currentInputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = currentInputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);


        if (leftMouseButtonPressed) {
            // Check Recipe Book Button first
            if (mousePos.x >= recipeButtonX && mousePos.x <= recipeButtonX + recipeButtonWidth &&
                mousePos.y >= recipeButtonY && mousePos.y <= recipeButtonY + recipeButtonHeight) {
                if (draggedItemStack == null) { // Only click button if not dragging
                    Game.getInstance().openRecipeBookScreen();
                    currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
            }
            
            if (shiftDown) {
                // Defensive fix: If hotbar key is pressed, don't treat as shift-click
                if (anyHotbarKeyPressed) {
                    // Don't consume the input - let it fall through to normal click logic
                } else {
                    // Defensive fix: prevent rapid repeated shift-clicks
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastShiftClickTime < SHIFT_CLICK_COOLDOWN_MS) {
                        currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                    lastShiftClickTime = currentTime;

                    boolean transferred = tryHandleShiftClickTransfer_Workbench(mousePos.x, mousePos.y,
                                                                              craftingInputGridX_calc_local, craftingInputGridY_calc_local,
                                                                              outputSlotX_calc_local, outputSlotY_calc_local,
                                                                              playerInvSlotsStartX_calc_local, playerInvSlotsStartY_calc_local, hotbarRowY_calc_local);
                    if (transferred) {
                        currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        // updateCraftingOutput(); // Called within tryHandleShiftClick if craft grid changes
                        return;
                    }
                    // If shift was down and a click happened on an interactive slot, but no transfer, consume it.
                     if (isMouseOverAnyInteractiveSlot(mousePos, craftingInputGridX_calc_local, craftingInputGridY_calc_local, outputSlotX_calc_local, outputSlotY_calc_local, playerInvSlotsStartX_calc_local, playerInvSlotsStartY_calc_local, hotbarRowY_calc_local)) {
                        currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        return;
                    }
                }
            }

            // Normal Left Click (pickup if not dragging, else it's a drop action handled on release)
            if (draggedItemStack == null) {
                pickupItem(mousePos, /*screenWidth, screenHeight,*/
                           /*panelStartX_calc_local, panelStartY_calc_local, panelWidth_calc_local, panelHeight_calc_local,*/
                           craftingInputGridX_calc_local, craftingInputGridY_calc_local, outputSlotX_calc_local, outputSlotY_calc_local,
                           playerInvSlotsStartX_calc_local, playerInvSlotsStartY_calc_local, hotbarRowY_calc_local);
                // Consume if a drag started
                if (draggedItemStack != null) {
                    currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                }
            }
            // If already dragging, left click release is handled below.

        } else if (rightMouseButtonPressed) {
            if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                boolean placedOne = tryHandleRightClickDropSingle_Workbench(mousePos.x, mousePos.y,
                                                                        /*screenWidth, screenHeight,*/
                                                                        /*panelStartX_calc_local, panelStartY_calc_local, panelWidth_calc_local, panelHeight_calc_local,*/
                                                                        craftingInputGridX_calc_local, craftingInputGridY_calc_local,
                                                                        /*outputSlotX_calc_local, outputSlotY_calc_local,*/
                                                                        playerInvSlotsStartX_calc_local, playerInvSlotsStartY_calc_local, hotbarRowY_calc_local);
                if (placedOne) {
                    currentInputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                }
            }
            // Optionally: Add logic for picking up half a stack with right-click if cursor is empty (not in InventoryScreen)
        } else { // Mouse button not pressed this frame, check for release
            if (draggedItemStack != null && !currentInputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                // Left mouse button released while dragging an item
                placeDraggedItem(mousePos, /*screenWidth, screenHeight,*/
                                 /*panelStartX_calc_local, panelStartY_calc_local, panelWidth_calc_local, panelHeight_calc_local,*/
                                 craftingInputGridX_calc_local, craftingInputGridY_calc_local, /*outputSlotX_calc_local, outputSlotY_calc_local,*/
                                 playerInvSlotsStartX_calc_local, playerInvSlotsStartY_calc_local, hotbarRowY_calc_local);
            } else if (draggedItemStack == null && !currentInputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) && !currentInputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT) ) {
                  // If no item is dragged and no mouse buttons are down, ensure the state is clear.
                 // This handles cases where a drag might have been aborted or an item fully consumed by stacking.
                 clearDraggedItemState();
            }
        }
    }
    
    private void pickupItem(Vector2f mousePos, /*int screenWidth, int screenHeight,*/
                        /*int panelStartX_param, int panelStartY_param, int panelWidth_param, int panelHeight_param,*/
                        int craftingInputGridX_param, int craftingInputGridY_param,
                        int outputSlotX_param, int outputSlotY_param,
                        int playerInvSlotsStartX_param, int playerInvSlotsStartY_param, int hotbarRowY_param) {
        // Check Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX_param + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY_param + r * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    draggedItemStack = craftingInputSlots[i].copy();
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                    draggedItemOriginalSlotIndex = CRAFTING_INPUT_SLOT_START_INDEX + i;
                    dragSource = DragSource.CRAFTING_INPUT;
                    updateCraftingOutput();
                    return;
                }
            }
        }

        // Check Crafting Output Slot
        if (isMouseOverSlot(mousePos, outputSlotX_param, outputSlotY_param)) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                draggedItemStack = craftingOutputSlot.copy();
                consumeCraftingIngredients(); // Consume ingredients AFTER taking the output
                craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Clear the output slot to prevent duplication
                draggedItemOriginalSlotIndex = CRAFTING_OUTPUT_SLOT_INDEX;
                dragSource = DragSource.CRAFTING_OUTPUT; // From Workbench specific enum
                updateCraftingOutput(); // This will regenerate output based on remaining ingredients
                return;
            }
        }
        
        // Check Main Player Inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = playerInvSlotsStartX_param + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = playerInvSlotsStartY_param + row * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, slotY)) {
                ItemStack currentStack = playerInventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    playerInventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    draggedItemOriginalSlotIndex = i; // Direct index
                    dragSource = DragSource.MAIN_INVENTORY;
                    return;
                }
            }
        }

        // Check Player Hotbar
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            // Hotbar typically uses same X calculation method as main inventory columns
            int slotX = playerInvSlotsStartX_param + i * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, hotbarRowY_param)) {
                ItemStack currentStack = playerInventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    playerInventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    draggedItemOriginalSlotIndex = i; // Direct index
                    dragSource = DragSource.HOTBAR;
                    return;
                }
            }
        }
    }
 
    private void placeDraggedItem(Vector2f mousePos, /*int screenWidth, int screenHeight,*/
                                /*int panelStartX_param, int panelStartY_param, int panelWidth_param, int panelHeight_param,*/
                                int craftingInputGridX_param, int craftingInputGridY_param,
                                /*int outputSlotX_param, int outputSlotY_param,*/ // Not a target for placing
                                int playerInvSlotsStartX_param, int playerInvSlotsStartY_param, int hotbarRowY_param) {
        if (draggedItemStack == null) return;
 
        boolean placedOrSwapped = false;

        // Try to place/swap in Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX_param + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY_param + r * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, slotY)) {
                ItemStack targetStack = craftingInputSlots[i]; // Direct reference
                if (targetStack.isEmpty()) {
                    craftingInputSlots[i] = draggedItemStack;
                    draggedItemStack = null; // Placed fully
                } else if (targetStack.canStackWith(draggedItemStack)) {
                    int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    targetStack.incrementCount(toAdd);
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) draggedItemStack = null;
                } else { // Swap - complete atomically
                    ItemStack itemFromCraftingSlot = targetStack.copy();
                    ItemStack originalDraggedItem = draggedItemStack.copy();

                    // Place dragged item in crafting slot
                    craftingInputSlots[i] = originalDraggedItem;

                    // Place crafting slot item back in original slot
                    switch (dragSource) {
                        case HOTBAR -> playerInventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromCraftingSlot);
                        case MAIN_INVENTORY -> playerInventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromCraftingSlot);
                        case CRAFTING_INPUT -> {
                            int originalCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                            if (originalCraftingIndex >= 0 && originalCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                craftingInputSlots[originalCraftingIndex] = itemFromCraftingSlot;
                            }
                        }
                    }

                    // Clear drag state since swap is complete
                    clearDraggedItemState();
                }
                updateCraftingOutput();
                placedOrSwapped = true;
                break;
            }
        }

        // Try to place/swap in Main Player Inventory
        if (!placedOrSwapped) {
            for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                int row = i / Inventory.MAIN_INVENTORY_COLS;
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = playerInvSlotsStartX_param + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = playerInvSlotsStartY_param + row * (SLOT_SIZE + SLOT_PADDING);
                if (isMouseOverSlot(mousePos, slotX, slotY)) {
                    ItemStack targetStack = playerInventory.getMainInventorySlot(i); // May be copy
                    if (targetStack.isEmpty()) {
                        playerInventory.setMainInventorySlot(i, draggedItemStack);
                        draggedItemStack = null;
                    } else if (targetStack.canStackWith(draggedItemStack)) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        playerInventory.setMainInventorySlot(i, targetStack); // Set back if it was a copy
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) draggedItemStack = null;
                    } else { // Swap - complete atomically
                        ItemStack itemFromMainInventory = targetStack.copy();
                        ItemStack originalDraggedItem = draggedItemStack.copy();

                        // Place dragged item in main inventory slot
                        playerInventory.setMainInventorySlot(i, originalDraggedItem);

                        // Place main inventory item back in original slot
                        switch (dragSource) {
                            case HOTBAR -> playerInventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromMainInventory);
                            case MAIN_INVENTORY -> playerInventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromMainInventory);
                            case CRAFTING_INPUT -> {
                                int originalCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                                if (originalCraftingIndex >= 0 && originalCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                    craftingInputSlots[originalCraftingIndex] = itemFromMainInventory;
                                }
                            }
                        }

                        // Clear drag state since swap is complete
                        clearDraggedItemState();
                    }
                    placedOrSwapped = true;
                    break;
                }
            }
        }

        // Try to place/swap in Player Hotbar
        if (!placedOrSwapped) {
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int slotX = playerInvSlotsStartX_param + i * (SLOT_SIZE + SLOT_PADDING);
                if (isMouseOverSlot(mousePos, slotX, hotbarRowY_param)) {
                    ItemStack targetStack = playerInventory.getHotbarSlot(i); // May be copy
                    if (targetStack.isEmpty()) {
                        playerInventory.setHotbarSlot(i, draggedItemStack);
                        draggedItemStack = null;
                    } else if (targetStack.canStackWith(draggedItemStack)) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        playerInventory.setHotbarSlot(i, targetStack); // Set back if it was a copy
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) draggedItemStack = null;
                    } else { // Swap - complete atomically
                        ItemStack itemFromHotbar = targetStack.copy();
                        ItemStack originalDraggedItem = draggedItemStack.copy();

                        // Place dragged item in hotbar slot
                        playerInventory.setHotbarSlot(i, originalDraggedItem);

                        // Place hotbar item back in original slot
                        switch (dragSource) {
                            case HOTBAR -> playerInventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromHotbar);
                            case MAIN_INVENTORY -> playerInventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromHotbar);
                            case CRAFTING_INPUT -> {
                                int originalCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                                if (originalCraftingIndex >= 0 && originalCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                    craftingInputSlots[originalCraftingIndex] = itemFromHotbar;
                                }
                            }
                        }

                        // Clear drag state since swap is complete
                        clearDraggedItemState();
                    }
                    placedOrSwapped = true;
                    break;
                }
            }
        }

        if (draggedItemStack == null || draggedItemStack.isEmpty()) { // Item successfully placed/stacked fully
             clearDraggedItemState();
        } else if (!placedOrSwapped) { // Not placed or swapped, and still dragging something
            // Check if mouse is outside workbench bounds - if so, drop into world
            if (isMouseOutsideWorkbenchBounds(mousePos.x, mousePos.y,
                    craftingInputGridX_param, craftingInputGridY_param,
                    playerInvSlotsStartX_param, playerInvSlotsStartY_param, hotbarRowY_param)) {
                dropEntireStackIntoWorld();
            } else {
                tryReturnToOriginalSlot(); // Attempt to return the current draggedItemStack
            }
        }
        // If a swap occurred, draggedItemStack holds the swapped item, dragSource/Index updated.
        // No further clear here.
    }
    // This method seems unused or redundant given pickupItem and placeDraggedItem detailed logic.
    // Commenting out for now, if functionality is missing, it can be reinstated or merged.
    /*
    private void handleSlotInteraction(ItemStack slotStack, int slotIndex, DragSource newDragSourceType,
                                       java.util.function.BiConsumer<Integer, ItemStack> setter) {
        if (draggedItemStack == null) { // Picking up from this slot
            if (slotStack != null && !slotStack.isEmpty()) {
                draggedItemStack = slotStack.copy();
                setter.accept(slotIndex, new ItemStack(BlockType.AIR.getId(), 0));
                draggedItemOriginalSlotIndex = (newDragSourceType == DragSource.CRAFTING_INPUT) ? CRAFTING_INPUT_SLOT_START_INDEX + slotIndex : slotIndex;
                dragSource = newDragSourceType;
                 if (newDragSourceType == DragSource.CRAFTING_INPUT) updateCraftingOutput();
            }
        } else { // Placing or swapping dragged item into this slot
            if (slotStack == null || slotStack.isEmpty()) { // Target slot is empty, place
                setter.accept(slotIndex, draggedItemStack);
                clearDraggedItemState(); // Item placed, clear drag state
                if (newDragSourceType == DragSource.CRAFTING_INPUT || dragSource == DragSource.CRAFTING_INPUT) updateCraftingOutput(); // if destination or source was crafting input
            } else if (slotStack.canStackWith(draggedItemStack)) { // Target can stack
                int canPlace = slotStack.getMaxStackSize() - slotStack.getCount();
                int toPlace = Math.min(canPlace, draggedItemStack.getCount());
                if (toPlace > 0) {
                    slotStack.incrementCount(toPlace);
                    // If slotStack is a copy (from playerInventory.get...Slot()), need setter.
                    // setter.accept(slotIndex, slotStack); // Apply incremented stack
                    draggedItemStack.decrementCount(toPlace);
                    if (draggedItemStack.isEmpty()) {
                        clearDraggedItemState();
                    }
                }
                 if (newDragSourceType == DragSource.CRAFTING_INPUT || dragSource == DragSource.CRAFTING_INPUT) updateCraftingOutput();
            } else { // Swap
                ItemStack temp = slotStack.copy();
                setter.accept(slotIndex, draggedItemStack);
                draggedItemStack = temp;
                // Original source of the *new* dragged item is now this slot's type
                draggedItemOriginalSlotIndex = (newDragSourceType == DragSource.CRAFTING_INPUT) ? CRAFTING_INPUT_SLOT_START_INDEX + slotIndex : slotIndex;
                dragSource = newDragSourceType;
                 if (newDragSourceType == DragSource.CRAFTING_INPUT || dragSource == DragSource.CRAFTING_INPUT) updateCraftingOutput();
            }
        }
    }
    */

    // This method also seems somewhat redundant if placeDraggedItem is sufficiently robust.
    // Keeping it commented, may be removed if not needed.
    /*
    private boolean tryPlaceOrSwap(ItemStack[] slotArray, int index,
                                 java.util.function.Function<Integer, ItemStack> getter,
                                 java.util.function.BiConsumer<Integer, ItemStack> setter) {
        ItemStack targetStack = getter.apply(index);
        if (targetStack.isEmpty()) {
            setter.accept(index, draggedItemStack);
            clearDraggedItemState(); // Full place, clear drag state.
            return true;
        } else if (targetStack.canStackWith(draggedItemStack)) {
            int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
            int toAdd = Math.min(canAdd, draggedItemStack.getCount());
            if (toAdd > 0) {
                targetStack.incrementCount(toAdd);
                // Assuming getter returns a direct reference if slotArray is from craftingInputSlots
                // or setter handles player inventory copy issues.
                // If not, setter.accept(index, targetStack); might be needed here too.
                draggedItemStack.decrementCount(toAdd);
                if (draggedItemStack.isEmpty()) {
                    clearDraggedItemState();
                }
                return true; // Even if partially stacked, consider it placed for this interaction cycle
            }
        } else { // Swap
            ItemStack temp = targetStack.copy();
            setter.accept(index, draggedItemStack); // Place current dragged
            draggedItemStack = temp; // Now dragging the item that was in the slot

            // Update dragSource and originalSlotIndex for the NEWLY dragged item (the one that was in the slot)
            // This logic assumes slotArray directly indicates the source type.
            if(slotArray == craftingInputSlots){ // Direct comparison of array reference.
                dragSource = DragSource.CRAFTING_INPUT;
                draggedItemOriginalSlotIndex = CRAFTING_INPUT_SLOT_START_INDEX + index;
            } else if (slotArray == playerInventory.getMainInventorySlots()){ // Relies on playerInventory providing direct array if this comparison works
                dragSource = DragSource.MAIN_INVENTORY;
                draggedItemOriginalSlotIndex = index;
            } else if (slotArray == playerInventory.getHotbarSlots()){
                dragSource = DragSource.HOTBAR;
                draggedItemOriginalSlotIndex = index;
            } else {
                // Fallback if array source isn't easily determined by reference.
            }
            return true;
        }
        return false;
    }
    */

    private boolean attemptDropOneToSlot_Workbench(ItemStack targetSlotItemStack,
                                               java.util.function.Consumer<ItemStack> slotStackSetter,
                                               boolean isCraftingInputSlot) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            return false;
        }

        if (targetSlotItemStack.isEmpty()) {
            ItemStack newItem = new ItemStack(draggedItemStack.getItem(), 1);
            slotStackSetter.accept(newItem);
            draggedItemStack.decrementCount(1);
            if (draggedItemStack.isEmpty()) clearDraggedItemState();
            if (isCraftingInputSlot) updateCraftingOutput();
            return true;
        } else if (targetSlotItemStack.canStackWith(draggedItemStack) && targetSlotItemStack.getCount() < targetSlotItemStack.getMaxStackSize()) {
            targetSlotItemStack.incrementCount(1);
            slotStackSetter.accept(targetSlotItemStack); // Ensure the change is saved, esp. if targetSlotItemStack was a copy
            draggedItemStack.decrementCount(1);
            if (draggedItemStack.isEmpty()) clearDraggedItemState();
            if (isCraftingInputSlot) updateCraftingOutput();
            return true;
        }
        return false;
    }
 
    private boolean tryHandleRightClickDropSingle_Workbench(float mouseX, float mouseY,
                                                          /*int screenWidth, int screenHeight,*/
                                                          /*int panelStartX_param, int panelStartY_param, int panelWidth_param, int panelHeight_param,*/
                                                          int craftingInputGridX_calc, int craftingInputGridY_calc,
                                                          /*int outputSlotX_calc, int outputSlotY_calc,*/ // Output slot not a target for this
                                                          int playerInvSlotsStartX_calc, int playerInvSlotsStartY_calc, int hotbarRowY_calc) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) return false;
        
        Vector2f currentMousePos = new Vector2f(mouseX, mouseY);

        // Try Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX_calc + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY_calc + r * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, slotY)) {
                final int slotIndex = i;
                return attemptDropOneToSlot_Workbench(craftingInputSlots[slotIndex], // Pass direct ref from array
                                                  (stack) -> craftingInputSlots[slotIndex] = stack, // Setter updates the array element
                                                  true);
            }
        }

        // Try Main Player Inventory Slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = playerInvSlotsStartX_calc + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = playerInvSlotsStartY_calc + row * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, slotY)) {
                final int slotIndex = i;
                 // playerInventory.getMainInventorySlot(i) returns a COPY.
                 // So, pass the copy to attemptDropOne... then use its setter to apply changes.
                 ItemStack currentSlotCopy = playerInventory.getMainInventorySlot(slotIndex);
                 boolean success = attemptDropOneToSlot_Workbench(currentSlotCopy,
                                                    (modifiedStack) -> playerInventory.setMainInventorySlot(slotIndex, modifiedStack),
                                                    false);
                 return success;
            }
        }

        // Try Player Hotbar Slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = playerInvSlotsStartX_calc + i * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, hotbarRowY_calc)) {
                final int slotIndex = i;
                 ItemStack currentSlotCopy = playerInventory.getHotbarSlot(slotIndex);
                 boolean success = attemptDropOneToSlot_Workbench(currentSlotCopy,
                                                    (modifiedStack) -> playerInventory.setHotbarSlot(slotIndex, modifiedStack),
                                                    false);
                 return success;
            }
        }
        return false;
    }

    private void clearDraggedItemState() {
        draggedItemStack = null;
        draggedItemOriginalSlotIndex = -1;
        dragSource = DragSource.NONE;
    }

    private boolean tryHandleShiftClickTransfer_Workbench(float mouseX, float mouseY,
                                                        int craftingInputGridX_calc, int craftingInputGridY_calc,
                                                        int outputSlotX_calc, int outputSlotY_calc,
                                                        int playerInvSlotsStartX_calc, int playerInvSlotsStartY_calc, int hotbarRowY_calc) {
        Vector2f currentMousePos = new Vector2f(mouseX, mouseY);

        // 1. FROM Crafting Output TO Player Inventory
        if (isMouseOverSlot(currentMousePos, outputSlotX_calc, outputSlotY_calc)) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                ItemStack toTransfer = craftingOutputSlot.copy();
                // int originalCountInOutput = toTransfer.getCount(); // Store how many we're trying to transfer

                if (playerInventory.addItem(toTransfer)) { // addItem should modify 'toTransfer' to reflect remaining items
                     // If all items from 'toTransfer' (originally craftingOutputSlot) were added to inventory:
                     if (toTransfer.isEmpty()) {
                        consumeCraftingIngredients(); // Consume what was used for the now-taken craft
                        craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Clear the actual output slot
                        updateCraftingOutput(); // Update based on new grid state
                        return true;
                    } else {
                         // This means partial transfer happened. Output slot was not fully emptied.
                         // This logic is tricky. Safest to assume craft is taken if ANY part is moved by shift-click.
                         // Consider output to be cleared if Inventory.addItem said 'true' (meaning some items were moved).
                        consumeCraftingIngredients();
                        craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Clear the output
                        updateCraftingOutput();
                        return true; // Some items were moved.
                    }
                }
            }
            return false; // Clicked output, but was empty or couldn't transfer
        }

        // 2. FROM Crafting Input Slots TO Player Inventory
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX_calc + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY_calc + r * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    ItemStack itemToMove = craftingInputSlots[i].copy(); // Copy the stack to transfer
                    
                    if (playerInventory.addItem(itemToMove)) { // addItem should modify itemToMove with remaining items
                        // If playerInventory.addItem returns true, it means items were transferred to inventory.
                        // To fix duplication and "materials stay" bug, the source crafting slot must be cleared,
                        // assuming shift-click from crafting grid implies consuming the entire source stack if transfer is successful.
                        craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0); // Clear the crafting slot
                        updateCraftingOutput();
                        return true;
                    }
                }
                return false; // Clicked input, but empty or couldn't transfer
            }
        }
        
        // 3. FROM Player Main Inventory TO Crafting Grid (if possible - simple first available empty or stackable)
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = playerInvSlotsStartX_calc + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = playerInvSlotsStartY_calc + row * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, slotY)) {
                ItemStack sourceStack = playerInventory.getMainInventorySlot(i);
                if (sourceStack != null && !sourceStack.isEmpty()) {
                    ItemStack remainingToPlace = sourceStack.copy(); // Work with a copy
                    tryTransferToCraftingGrid(remainingToPlace); // Modifies remainingToPlace
                    if (remainingToPlace.getCount() < sourceStack.getCount()) { // If any item was moved
                        playerInventory.setMainInventorySlot(i, remainingToPlace); // Update source slot with what's left
                        updateCraftingOutput();
                        return true;
                    }
                }
                return false; // Clicked main inv slot, but empty or couldn't transfer to craft grid
            }
        }
        
        // 4. FROM Player Hotbar TO Crafting Grid
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = playerInvSlotsStartX_calc + i * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(currentMousePos, slotX, hotbarRowY_calc)) {
                ItemStack sourceStack = playerInventory.getHotbarSlot(i);
                 if (sourceStack != null && !sourceStack.isEmpty()) {
                    ItemStack remainingToPlace = sourceStack.copy();
                    tryTransferToCraftingGrid(remainingToPlace);
                    if (remainingToPlace.getCount() < sourceStack.getCount()) {
                        playerInventory.setHotbarSlot(i, remainingToPlace);
                        updateCraftingOutput();
                        return true;
                    }
                }
                return false; // Clicked hotbar slot, but empty or couldn't transfer
            }
        }
        return false; // Click was not on a transferable slot, or no transfer occurred
    }
    
    // Helper method for shift-clicking items from player inventory TO crafting grid.
    // Modifies the passed itemStack representing items to be placed.
    private void tryTransferToCraftingGrid(ItemStack itemStackToPlace) {
        if (itemStackToPlace == null || itemStackToPlace.isEmpty()) return;

        // First, try to stack with existing items in the crafting grid
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            if (itemStackToPlace.isEmpty()) break; // All placed

            ItemStack craftSlot = craftingInputSlots[i];
            if (craftSlot.canStackWith(itemStackToPlace)) {
                int canAddToSlot = craftSlot.getMaxStackSize() - craftSlot.getCount();
                int amountToMove = Math.min(itemStackToPlace.getCount(), canAddToSlot);
                if (amountToMove > 0) {
                    craftSlot.incrementCount(amountToMove);
                    itemStackToPlace.decrementCount(amountToMove);
                }
            }
        }

        // Then, try to place in empty slots in the crafting grid
        if (!itemStackToPlace.isEmpty()) {
            for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                if (itemStackToPlace.isEmpty()) break; // All placed

                if (craftingInputSlots[i].isEmpty()) {
                    craftingInputSlots[i] = itemStackToPlace.copy(); // Place a copy of the entire remaining stack
                    itemStackToPlace.setCount(0); // Mark original as fully placed
                    // If only placing one from a stack is desired, this logic changes
                }
            }
        }
        // updateCraftingOutput(); // Caller should call this after all transfers complete
    }


    private boolean isMouseOverAnyInteractiveSlot(Vector2f mousePos, /*int screenWidth, int screenHeight,*/
                                             /*int panelStartX_param, int panelStartY_param, int panelWidth_param, int panelHeight_param,*/
                                             int craftingInputGridX_param, int craftingInputGridY_param,
                                             int outputSlotX_param, int outputSlotY_param,
                                             int playerInvSlotsStartX_param, int playerInvSlotsStartY_param, int hotbarRowY_param) {
        // Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingInputGridX_param + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingInputGridY_param + r * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, slotY)) return true;
        }
        // Crafting Output Slot
        if (isMouseOverSlot(mousePos, outputSlotX_param, outputSlotY_param)) return true;
        // Main Player Inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = playerInvSlotsStartX_param + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = playerInvSlotsStartY_param + row * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, slotY)) return true;
        }
        // Player Hotbar
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = playerInvSlotsStartX_param + i * (SLOT_SIZE + SLOT_PADDING);
            if (isMouseOverSlot(mousePos, slotX, hotbarRowY_param)) return true;
        }
        return false;
    }

    private void tryReturnToOriginalSlot() {
        if (draggedItemStack == null) return; // Nothing to return or was cleared
        if (draggedItemStack.isEmpty()){ // Ensure empty dragged stacks are fully cleared
            clearDraggedItemState();
            return;
        }

        if (draggedItemOriginalSlotIndex == -1 && dragSource != DragSource.CRAFTING_OUTPUT) {
            // No specific original slot (e.g., drag began from nowhere, or item was from a temporary source NOT output)
            // Try to add to player inventory
            if (playerInventory.addItem(draggedItemStack)) { // addItem should modify draggedItemStack if partially added
                 if (draggedItemStack.isEmpty()) {
                    clearDraggedItemState();
                 } // else, some remains dragged.
            } // If not added at all, item remains dragged.
            return;
        }


        // boolean returnedOrStackedSuccessfully = false; // This variable seems unused effectively
        switch (dragSource) {
            case HOTBAR -> {
                if (draggedItemOriginalSlotIndex >= 0 && draggedItemOriginalSlotIndex < Inventory.HOTBAR_SIZE) {
                   ItemStack targetStack = playerInventory.getHotbarSlot(draggedItemOriginalSlotIndex);
                   if (targetStack.isEmpty()) {
                       playerInventory.setHotbarSlot(draggedItemOriginalSlotIndex, draggedItemStack);
                       draggedItemStack = null;
                   } else if (targetStack.canStackWith(draggedItemStack)) {
                       int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                       int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                       targetStack.incrementCount(toAdd);
                       playerInventory.setHotbarSlot(draggedItemOriginalSlotIndex, targetStack); // Save if getHotbarSlot gives copy
                       draggedItemStack.decrementCount(toAdd);
                       if (draggedItemStack.isEmpty()) draggedItemStack = null;
                   }
                }
            }
            case MAIN_INVENTORY -> {
                if (draggedItemOriginalSlotIndex >= 0 && draggedItemOriginalSlotIndex < Inventory.MAIN_INVENTORY_SIZE) {
                    ItemStack targetStack = playerInventory.getMainInventorySlot(draggedItemOriginalSlotIndex);
                    if (targetStack.isEmpty()) {
                        playerInventory.setMainInventorySlot(draggedItemOriginalSlotIndex, draggedItemStack);
                        draggedItemStack = null;
                    } else if (targetStack.canStackWith(draggedItemStack)) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        playerInventory.setMainInventorySlot(draggedItemOriginalSlotIndex, targetStack); // Save if copy
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) draggedItemStack = null;
                    }
                }
            }
            case CRAFTING_INPUT -> {
                int craftSlotIdx = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                if (craftSlotIdx >= 0 && craftSlotIdx < CRAFTING_INPUT_SLOTS_COUNT) {
                   ItemStack targetStack = craftingInputSlots[craftSlotIdx]; // Direct reference
                   if (targetStack.isEmpty()) {
                       craftingInputSlots[craftSlotIdx] = draggedItemStack;
                       draggedItemStack = null;
                   } else if (targetStack.canStackWith(draggedItemStack)) {
                       int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                       int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                       targetStack.incrementCount(toAdd);
                       draggedItemStack.decrementCount(toAdd);
                       if (draggedItemStack.isEmpty()) draggedItemStack = null;
                   }
                   if(draggedItemStack == null) updateCraftingOutput(); // If item was placed/stacked into crafting grid
                }
            }
            case CRAFTING_OUTPUT -> {
                 // Crafting output items should not be automatically returned to inventory
                 // when dragged outside. Instead, try to drop them in the world.

                 Player player = Game.getPlayer();
                 if (player != null) {
                    // Use DropUtil to create proper item drop entities (not place blocks)
                    com.stonebreak.util.DropUtil.dropItemFromPlayer(player, draggedItemStack.copy());
                    clearDraggedItemState(); // Item dropped, clear drag state
                 } else {
                    // If no player reference, clear the item to prevent duplication
                    clearDraggedItemState();
                 }
            }
            case NONE -> { // Item from unknown source
                 // Try to add to player inventory first.
                 if (playerInventory.addItem(draggedItemStack)) { // addItem should update draggedItemStack if partial
                    if (draggedItemStack.isEmpty()) {
                        // Fully added, will be handled by clearDraggedItemState later if null/empty
                    }
                 }
                 // If after addItem, draggedItemStack is still present, it remains dragged.
            }
        }
 
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            clearDraggedItemState(); // If item is gone (placed, stacked, or fully added to inv)
        }
        // If not successfully returned/stacked into original slot AND not added to inventory (for NONE/OUTPUT source),
        // and draggedItemStack is still present, it remains with the cursor.
    }
    
    // This seems redundant, `tryReturnToOriginalSlot` above handles the logic more specifically based on `dragSource`.
    // If InventoryScreen.java's was used directly it might make sense, but here we integrate with WorkbenchScreen's sources.
    /*
    private boolean tryPlaceOrStackInSlot(ItemStack targetSlotStack, java.util.function.Consumer<ItemStack> slotSetter) {
        if (targetSlotStack.isEmpty()) {
            slotSetter.accept(draggedItemStack);
            clearDraggedItemState(); // Was: draggedItemStack = null;
            return true;
        } else if (targetSlotStack.canStackWith(draggedItemStack)) {
            int canAdd = targetSlotStack.getMaxStackSize() - targetSlotStack.getCount();
            int toAdd = Math.min(canAdd, draggedItemStack.getCount());
            if (toAdd > 0) {
                targetSlotStack.incrementCount(toAdd);
                // slotSetter.accept(targetSlotStack); // Only if targetSlotStack is a copy. CraftingInput is direct ref. PlayerInv is more complex.
                draggedItemStack.decrementCount(toAdd);
                if (draggedItemStack.isEmpty()) {
                    clearDraggedItemState(); // was: draggedItemStack = null;
                }
                return true;
            }
        }
        return false; // Could not place or stack
    }
    */


    private boolean isMouseOverSlot(Vector2f mousePos, int slotX, int slotY) {
        return mousePos.x >= slotX && mousePos.x <= slotX + SLOT_SIZE &&
               mousePos.y >= slotY && mousePos.y <= slotY + SLOT_SIZE;
    }

    private boolean isMouseOutsideWorkbenchBounds(float mouseX, float mouseY,
                                                  int craftingInputGridX_param, int craftingInputGridY_param,
                                                  int playerInvSlotsStartX_param, int playerInvSlotsStartY_param, int hotbarRowY_param) {
        // Calculate the overall workbench panel bounds (same logic as in render method)
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        int playerInvWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingGridVisualWidth = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int craftingSectionWidth = craftingGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        int panelWidth = Math.max(playerInvWidth, craftingSectionWidth);

        int craftingGridActualHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int playerInvHeight = (Inventory.MAIN_INVENTORY_ROWS + 1) * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int panelHeight = TITLE_HEIGHT + craftingGridActualHeight + TITLE_HEIGHT + playerInvHeight + SLOT_PADDING * 3;

        int panelStartX = (screenWidth - panelWidth) / 2;
        int panelStartY = (screenHeight - panelHeight) / 2;

        // Check if mouse is outside the panel bounds
        return mouseX < panelStartX || mouseX > panelStartX + panelWidth ||
               mouseY < panelStartY || mouseY > panelStartY + panelHeight;
    }

    private void dropEntireStackIntoWorld() {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            clearDraggedItemState();
            return;
        }

        // Get player reference to drop items from player position
        Player player = Game.getPlayer();
        if (player != null) {
            // Use DropUtil to properly create item drops in the world
            com.stonebreak.util.DropUtil.dropItemFromPlayer(player, draggedItemStack.copy());
        }

        // Clear the dragged item state after dropping
        clearDraggedItemState();
    }

    private void checkHover(ItemStack itemStack, int slotX, int slotY) {
        if (itemStack == null || itemStack.isEmpty() || !visible) {
            return;
        }
        Vector2f mousePos = inputHandler.getMousePosition();
        if (isMouseOverSlot(mousePos, slotX, slotY)) {
            hoveredItemStack = itemStack;
        }
    }

    public void updateCraftingOutput() {
        List<List<ItemStack>> grid = new ArrayList<>();
        for (int r = 0; r < CRAFTING_GRID_SIZE; r++) {
            List<ItemStack> row = new ArrayList<>();
            for (int c = 0; c < CRAFTING_GRID_SIZE; c++) {
                row.add(craftingInputSlots[r * CRAFTING_GRID_SIZE + c]);
            }
            grid.add(row);
        }
        
        ItemStack result = craftingManager.craftItem(grid);
        if (result != null && !result.isEmpty()) {
            craftingOutputSlot = result;
        } else {
            craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }

    public void consumeCraftingIngredients() {
        // Correct consumption should be based on the *matched recipe*.
        // For now, this simplified version consumes 1 of each *present* item in the input grid.
        // This matches typical simple Minecraft crafting.
        // CraftingManager.craftItem would ideally return the matched recipe to know what/how much to consume.
        List<List<ItemStack>> currentGridState = new ArrayList<>();
         for (int r = 0; r < CRAFTING_GRID_SIZE; r++) {
            List<ItemStack> rowList = new ArrayList<>();
            for (int c = 0; c < CRAFTING_GRID_SIZE; c++) {
                rowList.add(craftingInputSlots[r * CRAFTING_GRID_SIZE + c]);
            }
            currentGridState.add(rowList);
        }

        Recipe matchedRecipe = craftingManager.getMatchedRecipe(currentGridState);

        if (matchedRecipe != null) {
            // Determine the top-left corner (minBoundRow, minBoundCol) of the actual items
            // in the 3x3 craftingInputSlots that formed the successful recipe match.
            int minBoundRow = -1, /*maxBoundRow = -1,*/ minBoundCol = -1, maxBoundCol = -1;
 
            for (int r_scan = 0; r_scan < CRAFTING_GRID_SIZE; r_scan++) {
                List<ItemStack> rowList = currentGridState.get(r_scan);
                for (int c_scan = 0; c_scan < CRAFTING_GRID_SIZE; c_scan++) {
                    ItemStack item = rowList.get(c_scan);
                    if (item != null && !item.isEmpty()) {
                        if (minBoundRow == -1) minBoundRow = r_scan;
                        // maxBoundRow = r_scan;
                        if (minBoundCol == -1 || c_scan < minBoundCol) minBoundCol = c_scan;
                        if (c_scan > maxBoundCol) maxBoundCol = c_scan;
                    }
                }
            }

            if (minBoundRow == -1) {
                System.err.println("WorkbenchScreen.consumeCraftingIngredients: Matched recipe found but grid seems empty. No consumption.");
                return;
            }

            List<List<ItemStack>> recipeInputPattern = matchedRecipe.getInputPattern();
            int recipePatternHeight = matchedRecipe.getRecipeHeight();
            int recipePatternWidth = matchedRecipe.getRecipeWidth();

            for (int r_recipe = 0; r_recipe < recipePatternHeight; r_recipe++) {
                List<ItemStack> recipeRowItems = recipeInputPattern.get(r_recipe);
                for (int c_recipe = 0; c_recipe < recipePatternWidth; c_recipe++) {
                    ItemStack recipeIngredient = recipeRowItems.get(c_recipe);

                    if (recipeIngredient != null && !recipeIngredient.isEmpty()) {
                        int actualGridRow = minBoundRow + r_recipe;
                        int actualGridCol = minBoundCol + c_recipe;

                        if (actualGridRow < CRAFTING_GRID_SIZE && actualGridCol < CRAFTING_GRID_SIZE) {
                            int slotIndexToConsume = actualGridRow * CRAFTING_GRID_SIZE + actualGridCol;
                            ItemStack itemInWorkbenchSlot = craftingInputSlots[slotIndexToConsume];

                            if (itemInWorkbenchSlot != null && !itemInWorkbenchSlot.isEmpty() &&
                                itemInWorkbenchSlot.getBlockTypeId() == recipeIngredient.getBlockTypeId()) {

                                int countToConsume = recipeIngredient.getCount();
                                itemInWorkbenchSlot.decrementCount(countToConsume);

                                if (itemInWorkbenchSlot.isEmpty()) {
                                    craftingInputSlots[slotIndexToConsume] = new ItemStack(BlockType.AIR.getId(), 0);
                                }
                            } else {
                                System.err.println("WorkbenchScreen.consumeCraftingIngredients: Mismatch for recipe item " +
                                                   recipeIngredient.getItem().getName() + " (qty " + recipeIngredient.getCount() + ")" +
                                                   " at recipe(" + r_recipe + "," + c_recipe + ") / grid(" + actualGridRow + "," + actualGridCol + "). Found: " +
                                                   (itemInWorkbenchSlot != null && !itemInWorkbenchSlot.isEmpty() ? itemInWorkbenchSlot.getItem().getName() : "empty/null"));
                            }
                        } else {
                             System.err.println("WorkbenchScreen.consumeCraftingIngredients: Recipe item at (" + r_recipe + "," + c_recipe +
                                               ") maps out of 3x3 grid bounds. Offset: ("+minBoundRow+","+minBoundCol+"). Mapped Grid Pos: (" + actualGridRow + "," + actualGridCol + ").");
                        }
                    }
                }
            }
        }
        // updateCraftingOutput() will be called after this by the caller if needed
    }
    
    // Call this when Escape or Inventory key is pressed while Workbench is open
    public void handleCloseRequest() {
        if (isVisible()) {
            // Before closing, if items are being dragged, try to return them.
            if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                tryReturnToOriginalSlot();
                // If still dragging after trying to return (e.g., original slot gone or full, no other place found)
                // drop the item into the world or player inventory.
                if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                    if (!playerInventory.addItem(draggedItemStack)) {
                        Player player = Game.getPlayer();
                        if (player != null) {
                            // Use DropUtil to create proper item drop entities (not place blocks)
                            com.stonebreak.util.DropUtil.dropItemFromPlayer(player, draggedItemStack.copy());
                        } else {
                            System.out.println("Workbench: Player instance is null, cannot drop item.");
                        }
                    }
                    draggedItemStack = null; // Clear dragged item regardless of drop success after attempting inventory add/drop
                }
            }
            game.closeWorkbenchScreen(); // This will be fixed after Game.closeWorkbenchScreen() is added
        }
    }

    private void drawRecipeButton(float x, float y, float w, float h, String text) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            NVGColor color = NVGColor.malloc(stack);

            // Button background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, w, h, 4);
            boolean isHovering = inputHandler.getMousePosition().x >= x && inputHandler.getMousePosition().x <= x + w &&
                                 inputHandler.getMousePosition().y >= y && inputHandler.getMousePosition().y <= y + h;
            if (isHovering) {
                nvgFillColor(vg, nvgRGBA(100, 120, 140, 255, color)); // Hover color
            } else {
                nvgFillColor(vg, nvgRGBA(80, 100, 120, 255, color)); // Normal color
            }
            nvgFill(vg);

            // Button border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 0.5f, y + 0.5f, w - 1, h - 1, 3.5f);
            nvgStrokeColor(vg, nvgRGBA(150, 170, 190, 255, color));
            nvgStrokeWidth(vg, 1.0f);
            nvgStroke(vg);

            // Button text
            nvgFontSize(vg, 18);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, color));
            nvgText(vg, x + w / 2, y + h / 2, text);
        }
    }
}