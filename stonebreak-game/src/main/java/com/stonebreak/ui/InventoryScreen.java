package com.stonebreak.ui;

import java.util.ArrayList; // Added import
import java.util.List;    // Added import

import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector2f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Item;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
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
import static org.lwjgl.nanovg.NanoVG.nvgLineTo;
import static org.lwjgl.nanovg.NanoVG.nvgMoveTo;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgRoundedRect;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign; // Added import
import static org.lwjgl.nanovg.NanoVG.nvgTextBounds; // Added import
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
    private final CraftingManager craftingManager;
    private final HotbarScreen hotbarScreen;

    // Drag and drop state
    private ItemStack draggedItemStack;
    private int draggedItemOriginalSlotIndex; // -1 if not dragging, or index in combined (hotbar + main + crafting)
    private enum DragSource { NONE, HOTBAR, MAIN_INVENTORY, CRAFTING_INPUT }
    private DragSource dragSource = DragSource.NONE;
    // private boolean isDraggingFromHotbar; // Replaced by dragSource
    // private boolean isDraggingFromCraftingInput; // Replaced by dragSource
    private ItemStack hoveredItemStack; // For tooltip (inventory screen)

    // Crafting slots
    private static final int CRAFTING_GRID_SIZE = 2; // 2x2 grid
    private static final int CRAFTING_INPUT_SLOTS_COUNT = CRAFTING_GRID_SIZE * CRAFTING_GRID_SIZE;
    private final ItemStack[] craftingInputSlots;
    private ItemStack craftingOutputSlot;
    // Constants for slot indices for easier drag and drop mapping
    private static final int CRAFTING_INPUT_SLOT_START_INDEX = 1000; // Arbitrary large number to differentiate from inventory
    private static final int CRAFTING_OUTPUT_SLOT_INDEX = 2000; // Arbitrary large number


    // Hotbar tooltip state now handled by HotbarScreen
 
 
    // UI constants
    // HOTBAR_SLOTS is now defined in Inventory.java
    // Hotbar constants now in HotbarScreen
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    // NUM_COLS is now Inventory.MAIN_INVENTORY_COLS
    private static final int TITLE_HEIGHT = 30;

    // Recipe Book Button
    private static final String RECIPE_BUTTON_TEXT = "Recipes";
    private float recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight;
    
    /**
     * Creates a new inventory screen.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, UIRenderer uiRenderer, InputHandler inputHandler, CraftingManager craftingManager) {
        this.inventory = inventory;
        this.visible = false;
        // font parameter is received but not used
        this.renderer = renderer;
        this.uiRenderer = uiRenderer;
        this.inputHandler = inputHandler; // Initialize InputHandler
        this.craftingManager = craftingManager;
        this.hotbarScreen = new HotbarScreen(inventory);
        this.draggedItemStack = null;
        this.draggedItemOriginalSlotIndex = -1;
        this.dragSource = DragSource.NONE;
        this.hoveredItemStack = null;

        this.craftingInputSlots = new ItemStack[CRAFTING_INPUT_SLOTS_COUNT];
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            this.craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0); // Initialize with empty ItemStacks
        }
        this.craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Initialize with empty ItemStack
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
        hotbarScreen.update(deltaTime);
    }

    /**
     * Call this when a hotbar item is selected to show its name.
     */
    public void displayHotbarItemTooltip(BlockType blockType) {
        hotbarScreen.displayItemTooltip(blockType);
    }
    
    /**
     * Gets the hotbar screen instance.
     */
    public HotbarScreen getHotbarScreen() {
        return hotbarScreen;
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
        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // Unused

        // Define overall panel dimensions to include crafting area
        // Crafting area: 2x2 grid, an arrow, and 1 output slot.
        // Let's say arrow is SLOT_SIZE wide. Grid is 2 * (SLOT_SIZE + SLOT_PADDING).
        // Total width needed for crafting section: 2 * (SLOT_SIZE + SLOT_PADDING) + SLOT_SIZE (for arrow) + SLOT_SIZE (for output) + paddings
        // This will be drawn to the side or above the player inventory. For now, let's plan to draw it *above* the main inventory title.
        
        int craftingAreaHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // For the 2x2 grid
        // int craftingAreaMinY = SLOT_PADDING + TITLE_HEIGHT; // Unused

        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1; // +1 for hotbar
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // Adjusted panel height calculation:
        // Let's position crafting grid to the side of player avatar, and inventory below.
        // Player avatar (not implemented) would be around 2 slots wide, 2 slots high.
        // Crafting: 2x2 input + arrow + 1 output. Approx 2 * (SLOT_SIZE+PAD) for grid height.
        // For now, let's adjust the main inventory panel to make space.
        // We can add the crafting grid *within* the existing panel logic, shifted.

        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // This was a duplicate definition
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // For now, keep panel width based on main inventory, and fit crafting grid within it visually
        // This might make the panel wider if we place crafting side-by-side later.
        // For this iteration, we will place the crafting elements *above* the standard inventory within the same panel.

        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE; // 2 rows for grid, 1 space for arrow/gap.

        int inventoryPanelWidth = baseInventoryPanelWidth; // Keeps current width based on hotbar/main inv.
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2; // Added crafting space

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Draw panel background using UIRenderer
        drawInventoryPanel(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight);

        // Draw panel background using UIRenderer
        drawInventoryPanel(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight);

        // Draw "Crafting" title
        float craftingTitleY = panelStartY + 20;
        drawInventoryTitle(panelStartX + inventoryPanelWidth / 2, craftingTitleY, "Crafting");

        // Crafting slots rendering
        int craftingGridStartY = panelStartY + TITLE_HEIGHT + SLOT_PADDING;
        // Center the 2x2 grid, arrow and output slot.
        // Width of 2x2: 2 * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING
        // Width of arrow: ~SLOT_SIZE
        // Width of output: SLOT_SIZE
        // Total visual width: (2*(SLOT_SIZE+SLOT_PADDING)-SLOT_PADDING) + SLOT_SIZE + SLOT_SIZE + SLOT_PADDING * 2 (approx)
        int craftInputGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE -1) * SLOT_PADDING;
        // int craftOutputXOffset = craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING; // Unused

        int craftingElementsTotalWidth = craftInputGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE; // grid + space + arrow + space + output
        int craftingElementsStartX = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth) / 2;


        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingElementsStartX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridStartY + r * (SLOT_SIZE + SLOT_PADDING);
            drawInventorySlot(craftingInputSlots[i], slotX, slotY, false, -1); // Not a hotbar slot, no specific index for highlight
            checkHover(craftingInputSlots[i], slotX, slotY);
        }

        // Draw Arrow (placeholder visual)
        int arrowX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + (SLOT_SIZE - 20) / 2; // Centered in SLOT_SIZE space
        int arrowY = craftingGridStartY + (craftingAreaHeight - 20) / 2 - SLOT_PADDING; // Vertically centered in grid area
        drawCraftingArrow(arrowX, arrowY, 20, 20);

        // Draw Crafting Output Slot
        int outputSlotX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        // Vertically align output slot with the middle of the 2x2 input grid
        int outputSlotY = craftingGridStartY + (craftingAreaHeight - SLOT_SIZE) / 2 - SLOT_PADDING;
        drawInventorySlot(craftingOutputSlot, outputSlotX, outputSlotY, false, -1);
        checkHover(craftingOutputSlot, outputSlotX, outputSlotY);

        // Define Recipe Button position (near crafting output)
        // Let's place it to the right of the output slot.
        recipeButtonWidth = uiRenderer.getTextWidth(RECIPE_BUTTON_TEXT, 18f, "sans") + 2 * SLOT_PADDING; // Width based on text
        recipeButtonHeight = SLOT_SIZE;
        recipeButtonX = outputSlotX + SLOT_SIZE + SLOT_PADDING * 3;
        recipeButtonY = outputSlotY; // Align vertically with the output slot

        // Ensure button is within panel bounds, adjust if necessary or make panel wider.
        // For now, assume it fits or overlaps acceptably.
        // A more robust solution would adjust panelWidth or button position.
        // if (recipeButtonX + recipeButtonWidth > panelStartX + inventoryPanelWidth - SLOT_PADDING) {
        //     recipeButtonX = panelStartX + inventoryPanelWidth - SLOT_PADDING - recipeButtonWidth;
        // }

        drawRecipeButton(recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight, RECIPE_BUTTON_TEXT);
 
        // Draw "Inventory" title below crafting area
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - SLOT_SIZE /2; // Reposition inventory title
        drawInventoryTitle(panelStartX + inventoryPanelWidth / 2, inventoryTitleY, "Inventory");
 
        int contentStartY = (int)(inventoryTitleY + TITLE_HEIGHT / 2f + SLOT_PADDING); // Cast to int
 
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

            Item item = draggedItemStack.getItem();
            if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                drawDraggedItem(item, itemRenderX, itemRenderY, draggedItemStack.getCount());
            }
        }

        // Tooltip rendering moved to separate method
    }
    
    /**
     * Render the full inventory screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }

        // Reset hovered item at the start of each render pass
        hoveredItemStack = null;

        // Main Inventory Area (now includes hotbar visually as part of the panel)
        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // Unused

        // Define overall panel dimensions to include crafting area
        // Crafting area: 2x2 grid, an arrow, and 1 output slot.
        // Let's say arrow is SLOT_SIZE wide. Grid is 2 * (SLOT_SIZE + SLOT_PADDING).
        // Total width needed for crafting section: 2 * (SLOT_SIZE + SLOT_PADDING) + SLOT_SIZE (for arrow) + SLOT_SIZE (for output) + paddings
        // This will be drawn to the side or above the player inventory. For now, let's plan to draw it *above* the main inventory title.
        
        int craftingAreaHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // For the 2x2 grid
        // int craftingAreaMinY = SLOT_PADDING + TITLE_HEIGHT; // Unused

        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1; // +1 for hotbar
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // Adjusted panel height calculation:
        // Let's position crafting grid to the side of player avatar, and inventory below.
        // Player avatar (not implemented) would be around 2 slots wide, 2 slots high.
        // Crafting: 2x2 input + arrow + 1 output. Approx 2 * (SLOT_SIZE+PAD) for grid height.
        // For now, let's adjust the main inventory panel to make space.
        // We can add the crafting grid *within* the existing panel logic, shifted.

        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // This was a duplicate definition
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // For now, keep panel width based on main inventory, and fit crafting grid within it visually
        // This might make the panel wider if we place crafting side-by-side later.
        // For this iteration, we will place the crafting elements *above* the standard inventory within the same panel.

        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE; // 2 rows for grid, 1 space for arrow/gap.

        int inventoryPanelWidth = baseInventoryPanelWidth; // Keeps current width based on hotbar/main inv.
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2; // Added crafting space

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Draw panel background using UIRenderer
        drawInventoryPanel(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight);

        // Draw panel background using UIRenderer
        drawInventoryPanel(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight);

        // Draw "Crafting" title
        float craftingTitleY = panelStartY + 20;
        drawInventoryTitle(panelStartX + inventoryPanelWidth / 2, craftingTitleY, "Crafting");

        // Crafting slots rendering
        int craftingGridStartY = panelStartY + TITLE_HEIGHT + SLOT_PADDING;
        // Center the 2x2 grid, arrow and output slot.
        // Width of 2x2: 2 * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING
        // Width of arrow: ~SLOT_SIZE
        // Width of output: SLOT_SIZE
        // Total visual width: (2*(SLOT_SIZE+SLOT_PADDING)-SLOT_PADDING) + SLOT_SIZE + SLOT_SIZE + SLOT_PADDING * 2 (approx)
        int craftInputGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE -1) * SLOT_PADDING;
        // int craftOutputXOffset = craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING; // Unused

        int craftingElementsTotalWidth = craftInputGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE; // grid + space + arrow + space + output
        int craftingElementsStartX = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth) / 2;


        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingElementsStartX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridStartY + r * (SLOT_SIZE + SLOT_PADDING);
            drawInventorySlot(craftingInputSlots[i], slotX, slotY, false, -1); // Not a hotbar slot, no specific index for highlight
            checkHover(craftingInputSlots[i], slotX, slotY);
        }

        // Draw Arrow (placeholder visual)
        int arrowX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + (SLOT_SIZE - 20) / 2; // Centered in SLOT_SIZE space
        int arrowY = craftingGridStartY + (craftingAreaHeight - 20) / 2 - SLOT_PADDING; // Vertically centered in grid area
        drawCraftingArrow(arrowX, arrowY, 20, 20);

        // Draw Crafting Output Slot
        int outputSlotX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        // Vertically align output slot with the middle of the 2x2 input grid
        int outputSlotY = craftingGridStartY + (craftingAreaHeight - SLOT_SIZE) / 2 - SLOT_PADDING;
        drawInventorySlot(craftingOutputSlot, outputSlotX, outputSlotY, false, -1);
        checkHover(craftingOutputSlot, outputSlotX, outputSlotY);

        // Define Recipe Button position (near crafting output)
        // Let's place it to the right of the output slot.
        recipeButtonWidth = uiRenderer.getTextWidth(RECIPE_BUTTON_TEXT, 18f, "sans") + 2 * SLOT_PADDING; // Width based on text
        recipeButtonHeight = SLOT_SIZE;
        recipeButtonX = outputSlotX + SLOT_SIZE + SLOT_PADDING * 3;
        recipeButtonY = outputSlotY; // Align vertically with the output slot

        // Ensure button is within panel bounds, adjust if necessary or make panel wider.
        // For now, assume it fits or overlaps acceptably.
        // A more robust solution would adjust panelWidth or button position.
        // if (recipeButtonX + recipeButtonWidth > panelStartX + inventoryPanelWidth - SLOT_PADDING) {
        //     recipeButtonX = panelStartX + inventoryPanelWidth - SLOT_PADDING - recipeButtonWidth;
        // }

        drawRecipeButton(recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight, RECIPE_BUTTON_TEXT);
 
        // Draw "Inventory" title below crafting area
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - SLOT_SIZE /2; // Reposition inventory title
        drawInventoryTitle(panelStartX + inventoryPanelWidth / 2, inventoryTitleY, "Inventory");
 
        int contentStartY = (int)(inventoryTitleY + TITLE_HEIGHT / 2f + SLOT_PADDING); // Cast to int
 
        // Draw Main Inventory Slots
        ItemStack[] mainSlots = inventory.getMainInventorySlots(); // Gets copies

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
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

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar is a single row
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarRowYOffset;
            drawInventorySlot(hotbarSlots[i], slotX, slotY, true, i);
            checkHover(hotbarSlots[i], slotX, slotY);
        }

        // Draw dragged item on top of everything else
        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            Vector2f mousePos = inputHandler.getMousePosition();
            // Center the item on the mouse cursor
            int itemRenderX = (int) (mousePos.x - (SLOT_SIZE -4) / 2.0f);
            int itemRenderY = (int) (mousePos.y - (SLOT_SIZE -4) / 2.0f);

            Item item = draggedItemStack.getItem();
            if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                drawDraggedItem(item, itemRenderX, itemRenderY, draggedItemStack.getCount());
            }
        }
    }
    
    /**
     * Render only tooltips for the full inventory screen.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }
        
        // Draw Tooltip
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && draggedItemStack == null) { // Only show tooltip if not dragging
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                drawItemTooltip(item.getName(), mousePos.x + 15, mousePos.y + 15, screenWidth, screenHeight);
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
                Item item = itemStack.getItem();
                int count = itemStack.getCount();
                
                if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                    try {
                        // End NanoVG frame temporarily to draw 3D item
                        uiRenderer.endFrame();
                        
                        // Draw 3D item using UIRenderer's BlockIconRenderer
                        if (item instanceof BlockType bt) {
                            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, renderer.getTextureAtlas());
                        } else {
                            // For ItemTypes, render a 2D sprite using UIRenderer
                            uiRenderer.renderItemIcon(slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, item, renderer.getTextureAtlas());
                        }
                        
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
            System.err.println("ERROR in drawInventorySlot: " + e.getMessage() + ". Problem drawing item: " + (itemStack != null ? itemStack.getItem().getName() : "unknown"));
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));

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
    
    private void drawDraggedItem(Item item, int x, int y, int count) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            // End NanoVG frame temporarily to draw 3D item
            uiRenderer.endFrame();
            
            // Draw 3D item using UIRenderer's BlockIconRenderer
            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, SLOT_SIZE - 4, SLOT_SIZE - 4, renderer.getTextureAtlas());
            } else {
                // For ItemTypes, render a 2D sprite using UIRenderer
                uiRenderer.renderItemIcon(x, y, SLOT_SIZE - 4, SLOT_SIZE - 4, item, renderer.getTextureAtlas());
            }
            
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
    
    
    // drawHotbarTooltip method removed - now handled by HotbarRenderer

    // Helper method to create NVGColor
    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }


    /**
     * Renders the separate hotbar at the bottom of the screen when inventory is closed.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render separate hotbar if full inventory is open
        
        // Delegate to UIRenderer's hotbar rendering
        uiRenderer.renderHotbar(hotbarScreen, screenWidth, screenHeight, 
                              renderer.getTextureAtlas(), renderer.getShaderProgram());
        uiRenderer.renderHotbarTooltip(hotbarScreen, screenWidth, screenHeight);
    }
    
    /**
     * Renders the separate hotbar at the bottom of the screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render separate hotbar if full inventory is open
        
        // Delegate to UIRenderer's hotbar rendering (without tooltips)
        uiRenderer.renderHotbar(hotbarScreen, screenWidth, screenHeight, 
                              renderer.getTextureAtlas(), renderer.getShaderProgram());
    }
    
    /**
     * Renders only the hotbar tooltip.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (visible) return; // Don't render separate hotbar if full inventory is open
        
        // Delegate to UIRenderer's hotbar tooltip rendering
        uiRenderer.renderHotbarTooltip(hotbarScreen, screenWidth, screenHeight);
    }

    // Method to handle mouse clicks for drag and drop
    public void handleMouseInput(int screenWidth, int screenHeight) {
        if (!visible) return;

        // Consistent panel and slot coordinate calculations
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE;
        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1;
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int inventoryPanelWidth = baseInventoryPanelWidth;
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2;
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;
        int craftingGridStartY_calc = panelStartY + TITLE_HEIGHT + SLOT_PADDING;
        int craftInputGridVisualWidth_calc = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE - 1) * SLOT_PADDING;
        int craftingElementsTotalWidth_calc = craftInputGridVisualWidth_calc + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE;
        int craftingElementsStartX_calc = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth_calc) / 2;
        int craftingAreaHeight_calc = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        float inventoryTitleY_calc = craftingGridStartY_calc + craftingSectionHeight - SLOT_SIZE / 2f;
        int mainInvContentStartY_calc = (int) (inventoryTitleY_calc + TITLE_HEIGHT / 2f + SLOT_PADDING);
        int hotbarRowY_calc = mainInvContentStartY_calc + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int outputSlotX_calc = craftingElementsStartX_calc + craftInputGridVisualWidth_calc + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY_calc = craftingGridStartY_calc + (craftingAreaHeight_calc - SLOT_SIZE) / 2 - SLOT_PADDING;

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        boolean shiftDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        if (leftMouseButtonPressed) {
            if (shiftDown) {
                // Handle Shift + Left Click for quick transfer
                boolean transferred = tryHandleShiftClickTransfer(mouseX, mouseY,
                                                              craftingGridStartY_calc, craftingElementsStartX_calc,
                                                              outputSlotX_calc, outputSlotY_calc);
                if (transferred) {
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
                // If not transferred by shift-click, allow fall-through to normal click behavior *if desired*
                // For now, if shift was down and we were over a slot, assume action was attempted and consume.
                // More precise would be if tryHandleShiftClickTransfer itself indicates if it specifically targeted a slot.
                // For simplicity: if shift was down, any click in a slot area for transfer attempt consumes the click.
                 inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT); // Consume to prevent normal pickup
                 return; // Stop further processing for this click if shift was involved over interactive area.
            }

            // Normal Left Click (pickup/place, or button click)
            if (draggedItemStack == null) {
                 // Check Recipe Book Button FIRST (as it's not a 'slot' interaction)
                if (mouseX >= recipeButtonX && mouseX <= recipeButtonX + recipeButtonWidth &&
                    mouseY >= recipeButtonY && mouseY <= recipeButtonY + recipeButtonHeight) {
                    Game.getInstance().openRecipeBookScreen();
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
                // Attempt to PICK UP an item
                tryPickUpItem(mouseX, mouseY, panelStartX, craftingGridStartY_calc, craftingElementsStartX_calc, outputSlotX_calc, outputSlotY_calc, mainInvContentStartY_calc, hotbarRowY_calc);
            }
             // Consume left click if a drag started or recipe button clicked.
             // The consume call should be specific to the action.
             // If tryPickUpItem started a drag, it implies an action was taken.
             // If recipeButton was clicked, it consumed the click.
             // If nothing happened, no consumption yet.
             // This explicit consume will happen IF an action actually occurred (pickup or button).
             // inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT); // This was moved to be more specific
 
         } else if (rightMouseButtonPressed) {
             if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                 boolean placedOne = tryHandleRightClickDropSingle(mouseX, mouseY, panelStartX,
                                                               craftingGridStartY_calc, craftingElementsStartX_calc,
                                                               mainInvContentStartY_calc, hotbarRowY_calc);
                 if (placedOne) {
                     inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                 }
             }
             // Consume right click if an action was taken, otherwise not.
             // This consumption is handled within tryHandleRightClickDropSingle implicitly if it returns true.
         } else {
            // Neither left nor right button newly pressed this frame. Check for drag release.
            if (draggedItemStack != null && !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                 // Mouse button (specifically left) released while dragging
                placeDraggedItem(screenWidth, screenHeight); // placeDraggedItem handles clearing drag state if successful
            } else if (draggedItemStack != null &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                // Fallback: if somehow still dragging but no buttons are down (e.g., after a failed place)
                tryReturnToOriginalSlot();
                clearDraggedItemState();
            } else if (draggedItemStack == null) {
                // Ensure drag state is fully reset if nothing is being dragged and no buttons are down.
                clearDraggedItemState();
            }
        }
    }

    private void tryPickUpItem(float mouseX, float mouseY, int panelStartX,
                           int craftingGridStartY_calc, int craftingElementsStartX_calc,
                           int outputSlotX_calc, int outputSlotY_calc,
                           int mainInvContentStartY_calc, int hotbarRowY_calc) {
        // This method encapsulates the pick-up logic previously directly in handleMouseInput
        // Check main inventory slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = mainInvContentStartY_calc + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    draggedItemOriginalSlotIndex = i;
                    dragSource = DragSource.MAIN_INVENTORY;
                    return;
                }
            }
        }

        // Check hotbar slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarRowY_calc;

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    draggedItemOriginalSlotIndex = i;
                    dragSource = DragSource.HOTBAR;
                    return;
                }
            }
        }

        // Check crafting input slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingElementsStartX_calc + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridStartY_calc + r * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
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
        
        // Check crafting output slot (pickup only)
        if (mouseX >= outputSlotX_calc && mouseX <= outputSlotX_calc + SLOT_SIZE &&
            mouseY >= outputSlotY_calc && mouseY <= outputSlotY_calc + SLOT_SIZE) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                draggedItemStack = craftingOutputSlot.copy();
                consumeCraftingIngredients();
                craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
                draggedItemOriginalSlotIndex = CRAFTING_OUTPUT_SLOT_INDEX;
                dragSource = DragSource.NONE; // Source is effectively 'crafting system', not a persistent slot
                updateCraftingOutput();
            }
        }
    }

    private void clearDraggedItemState() {
        draggedItemStack = null;
        draggedItemOriginalSlotIndex = -1;
        dragSource = DragSource.NONE;
    }


    private boolean tryHandleShiftClickTransfer(float mouseX, float mouseY,
                                            int craftingGridStartY_calc, int craftingElementsStartX_calc,
                                            int outputSlotX_calc, int outputSlotY_calc) {
        // 1. Check Crafting Output Slot
        if (mouseX >= outputSlotX_calc && mouseX <= outputSlotX_calc + SLOT_SIZE &&
            mouseY >= outputSlotY_calc && mouseY <= outputSlotY_calc + SLOT_SIZE) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                ItemStack itemsInOutput = craftingOutputSlot.copy(); // What's available to take
                int typeId = itemsInOutput.getBlockTypeId();
                // int initialCountInOutput = itemsInOutput.getCount(); // Not directly needed with before/after inventory count

                int itemsOfTypeInInventoryBefore = inventory.getItemCount(typeId);
                
                inventory.addItem(itemsInOutput); // Attempt to add the full stack from output
                                                  // Note: inventory.addItem(ItemStack) doesn't modify 'itemsInOutput' itself.

                int itemsOfTypeInInventoryAfter = inventory.getItemCount(typeId);

                if (itemsOfTypeInInventoryAfter > itemsOfTypeInInventoryBefore) {
                    // At least one item was successfully transferred from the craft output
                    consumeCraftingIngredients();
                    craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Clear the output slot as the craft is "taken"
                    updateCraftingOutput(); // Update for the next potential craft
                    return true; // Transfer (partial or full) occurred
                }
                return false; // No items could be transferred (e.g., inventory completely full for this type)
            }
        }

        // 2. Check Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingElementsStartX_calc + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridStartY_calc + r * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    ItemStack itemInInputSlot = craftingInputSlots[i];
                    int typeId = itemInInputSlot.getBlockTypeId();
                    int countInInputSlot = itemInInputSlot.getCount();

                    int itemsOfTypeInInventoryBefore = inventory.getItemCount(typeId);
                    
                    inventory.addItem(typeId, countInInputSlot); // Use the (ID, count) version
                    
                    int itemsOfTypeInInventoryAfter = inventory.getItemCount(typeId);
                    int numAdded = itemsOfTypeInInventoryAfter - itemsOfTypeInInventoryBefore;

                    if (numAdded > 0) {
                        itemInInputSlot.decrementCount(numAdded);
                        if (itemInInputSlot.isEmpty()) {
                            craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0); // Clear if all taken
                        }
                        updateCraftingOutput();
                        return true; // Successfully transferred some/all
                    }
                }
                 return false; // Click was on a slot but no action (e.g. inventory full for this type) or already empty
            }
        }
        return false; // Click was not on a transferable slot
    }

    private boolean tryHandleRightClickDropSingle(float mouseX, float mouseY, int panelStartX,
                                                int craftingGridStartY_calc, int craftingElementsStartX_calc,
                                                int mainInvContentStartY_calc, int hotbarRowY_calc) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            return false;
        }

        // Try Main Inventory Slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = mainInvContentStartY_calc + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                return attemptDropOneToSlot(inventory.getMainInventorySlot(slotIndex), (stack) -> inventory.setMainInventorySlot(slotIndex, stack), null, -1);
            }
        }

        // Try Hotbar Slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = hotbarRowY_calc;
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                return attemptDropOneToSlot(inventory.getHotbarSlot(slotIndex), (stack) -> inventory.setHotbarSlot(slotIndex, stack), null, -1);
            }
        }

        // Try Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            final int slotIndex = i; // effectively final for lambda
            int r = i / CRAFTING_GRID_SIZE;
            int c = i % CRAFTING_GRID_SIZE;
            int slotX = craftingElementsStartX_calc + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridStartY_calc + r * (SLOT_SIZE + SLOT_PADDING);
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                return attemptDropOneToSlot(craftingInputSlots[slotIndex],
                                           (stack) -> craftingInputSlots[slotIndex] = stack,
                                           (stack) -> craftingInputSlots[slotIndex] = stack, // Same setter for new/existing stack
                                           slotIndex); // Pass slotIndex as craftingSlotIndexIfApplicable
            }
        }
        return false;
    }

    private boolean attemptDropOneToSlot(ItemStack targetSlot, java.util.function.Consumer<ItemStack> directSlotSetter,
                                     java.util.function.Consumer<ItemStack> newStackSetterForCrafting, int craftingSlotIndexIfApplicable) {
        // Parameter craftingSlotIndexIfApplicable should actually be the slot index itself if it's a crafting slot, or -1 otherwise.
        // The method was being called with 'slotIndex' directly which is good.
        boolean isCraftingSlot = craftingSlotIndexIfApplicable != -1;

        if (targetSlot.isEmpty()) {
            ItemStack newItem = new ItemStack(draggedItemStack.getItem(), 1);
             // Use newStackSetterForCrafting if provided and it's a crafting slot context, otherwise directSlotSetter.
            if(isCraftingSlot && newStackSetterForCrafting != null) newStackSetterForCrafting.accept(newItem);
            else directSlotSetter.accept(newItem);
            
            draggedItemStack.decrementCount(1);
            if (draggedItemStack.isEmpty()) clearDraggedItemState(); // this clears draggedItemStack itself
            if (isCraftingSlot) updateCraftingOutput();
            return true;
        } else if (targetSlot.canStackWith(draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
            targetSlot.incrementCount(1);
            // directSlotSetter might not be needed if targetSlot is a direct reference from an array (like craftingInputSlots)
            // or if playerInventory.get...Slot() returns a modifiable reference.
            // If inventory.getXSlot() returns a copy, then directSlotSetter.accept(targetSlot) IS needed.
            // Let's assume for now Inventory.getXSlot() returns a reference for .incrementCount() to work directly.
            // If this is not the case, directSlotSetter.accept(targetSlot) would be needed after targetSlot.incrementCount(1);
            
            draggedItemStack.decrementCount(1);
            if (draggedItemStack.isEmpty()) clearDraggedItemState(); // this clears draggedItemStack itself
            if (isCraftingSlot) updateCraftingOutput();
            return true;
        }
        return false;
    }

 
    private void placeDraggedItem(int screenWidth, int screenHeight) {
        if (draggedItemStack == null) return;

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Use already calculated consistent coordinates if available, or recalculate
        // (This block is duplicated from handleMouseInput for robustness if placeDraggedItem is called independently)
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE; // Grid rows + space/arrow.
        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1; // +1 for hotbar
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        
        int inventoryPanelWidth = baseInventoryPanelWidth; // Assuming panel width is based on main inventory area
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2; // Matches render() line 199

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting area calculations (consistent with render())
        int craftingGridStartY_calc = panelStartY + TITLE_HEIGHT + SLOT_PADDING; // As per render line 215
        int craftInputGridVisualWidth_calc = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE -1) * SLOT_PADDING; // As per render line 221
        int craftingElementsTotalWidth_calc = craftInputGridVisualWidth_calc + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE; // grid + space + arrow + space + output, as per render line 224
        int craftingElementsStartX_calc = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth_calc) / 2; // As per render line 225
        // int craftingAreaHeight_calc = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // As per render line 176 - Not directly needed in this specific block for placeDraggedItem logic further down, but ensures consistency with pickup and render

        // Main inventory area calculations (consistent with render())
        float inventoryTitleY_calc = craftingGridStartY_calc + craftingSectionHeight - SLOT_SIZE /2f; // Render line 266
        int mainInvContentStartY_calc = (int)(inventoryTitleY_calc + TITLE_HEIGHT / 2f + SLOT_PADDING); // As per render line 269

        // Hotbar Y calculation (consistent with render())
        int hotbarRowY_calc = mainInvContentStartY_calc + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING; // As per render line 284

        boolean placed = false;

        // Try to place in main inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = mainInvContentStartY_calc + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack targetStack = inventory.getMainInventorySlot(i); // Direct reference
                if (targetStack.isEmpty()) {
                    inventory.setMainInventorySlot(i, draggedItemStack);
                    draggedItemStack = null; // Item is placed, no longer dragged
                    placed = true;
                } else if (targetStack.canStackWith(draggedItemStack)) {
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
                         switch (dragSource) {
                           case HOTBAR -> inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                           case MAIN_INVENTORY -> inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                           case CRAFTING_INPUT -> {
                               // Allow swapping back to crafting input if it's the source
                               int trueCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                               if (trueCraftingIndex >= 0 && trueCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                   craftingInputSlots[trueCraftingIndex] = itemFromTargetSlot;
                               }
                           }
                           case NONE -> {
                               // This case should ideally not happen if dragSource was set correctly when picking up.
                               // If it does, we might drop the item or log an error.
                               // For now, itemFromTargetSlot is lost if source was NONE.
                           }
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
                int slotY = hotbarRowY_calc;

                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                    mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack targetStack = inventory.getHotbarSlot(i); // Direct reference
                     if (targetStack.isEmpty()) {
                        inventory.setHotbarSlot(i, draggedItemStack);
                        draggedItemStack = null; // Item is placed, no longer dragged
                        placed = true;
                    } else if (targetStack.canStackWith(draggedItemStack)) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) {
                            draggedItemStack = null; // Fully stacked, clear dragged item
                            placed = true;
                        }
                    } else { // Target slot is not empty and not stackable: Perform a full swap.
                        // This logic mirrors the main inventory swap.
                        if (targetStack.getBlockTypeId() != draggedItemStack.getBlockTypeId()) { // Ensure different items for a true swap intent
                            ItemStack itemFromTargetSlot = targetStack.copy();
                            inventory.setHotbarSlot(i, draggedItemStack); // Place current dragged item into the target hotbar slot

                            // Place the item originally from the target slot into the original slot of the dragged item
                            switch (dragSource) {
                                case HOTBAR -> inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                                case MAIN_INVENTORY -> inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                                case CRAFTING_INPUT -> {
                                    int trueCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                                    if (trueCraftingIndex >= 0 && trueCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                        craftingInputSlots[trueCraftingIndex] = itemFromTargetSlot;
                                        // updateCraftingOutput(); // This will be called if crafting grid changed
                                    }
                                }
                                case NONE -> {
                                    // If original source was NONE (e.g., from crafting output),
                                    // and we are swapping with a hotbar item,
                                    // the itemFromTargetSlot would ideally try to go to player inventory if possible,
                                    // or be dropped. For now, it might be lost if original slot was transient.
                                    // A safer bet: if dragSource is NONE, this kind of complex swap might not be typical.
                                    // If dragSource was crafting output, there isn't an "original slot" to put itemFromTargetSlot.
                                    // This logic primarily handles swaps between persistent slots.
                                    // If we are here and dragSource is NONE, and we are trying to swap with a hotbar slot,
                                    // this means the draggedItemStack (from output) is placed in hotbar slot 'i',
                                    // and itemFromTargetSlot (from hotbar 'i') has nowhere defined to go back to.
                                    // The most sensible action might be to just place and NOT swap,
                                    // or to make itemFromTargetSlot the new dragged item.
                                    // Let's stick to the established "place and become new dragged item" for this edge case.
                                    if (draggedItemOriginalSlotIndex == CRAFTING_OUTPUT_SLOT_INDEX) {
                                        // Revert to place-and-pickup-other for output-to-occupied-hotbar
                                        inventory.setHotbarSlot(i, draggedItemStack); // Place item from output
                                        // The item from the target slot becomes the new dragged item.
                                        // The 'itemFromTargetSlot' variable itself is not directly used beyond this point in this path,
                                        // but its content is now in 'draggedItemStack'.
                                        draggedItemStack = itemFromTargetSlot; // itemFromTargetSlot used here
                                        dragSource = DragSource.HOTBAR;           // New source is this hotbar slot
                                        draggedItemOriginalSlotIndex = i;         // New original index
                                        // placed = true; // Redundant, set at line 1134 in this execution path
                                        // Skip further modification of draggedItemStack to null in the outer block
                                        // as we are still dragging something.
                                    }
                                    // else, if dragSource was unexpectedly NONE but not output, default behavior for swap might be okay
                                    // (itemFromTargetSlot could be lost if no valid original slot).
                                }
                            }
                            // If not the special DragSource.NONE case above that maintains a dragged item:
                            if (!(dragSource == DragSource.NONE && draggedItemOriginalSlotIndex == CRAFTING_OUTPUT_SLOT_INDEX && draggedItemStack != null)) {
                                draggedItemStack = null; // Successfully swapped (or placed from output), clear dragged item
                            }
                            placed = true;
                        } else if (targetStack.getBlockTypeId() == draggedItemStack.getBlockTypeId()) {
                            // Items are the same type but cannot stack (e.g. different NBT, or already full source/target)
                            // This is a "failed stack, so swap positions" case. Treat as full swap.
                            ItemStack itemFromTargetSlot = targetStack.copy();
                            inventory.setHotbarSlot(i, draggedItemStack);

                            switch (dragSource) {
                                case HOTBAR -> inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                                case MAIN_INVENTORY -> inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetSlot);
                                case CRAFTING_INPUT -> {
                                    int trueCraftingIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                                    if (trueCraftingIndex >= 0 && trueCraftingIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                        craftingInputSlots[trueCraftingIndex] = itemFromTargetSlot;
                                    }
                                }
                                case NONE -> { // As above, itemFromTargetSlot could be lost or become new dragged
                                     inventory.setHotbarSlot(i, draggedItemStack);
                                     // The item from the target slot becomes the new dragged item.
                                     // The 'itemFromTargetSlot' variable itself is not directly used beyond this point in this path,
                                     // but its content is now in 'draggedItemStack'.
                                     draggedItemStack = itemFromTargetSlot; // itemFromTargetSlot used here
                                     dragSource = DragSource.HOTBAR;
                                     draggedItemOriginalSlotIndex = i;
                                     // placed = true; // Redundant, set at line 1164 in this execution path
                                }
                            }
                             if (!(dragSource == DragSource.NONE && draggedItemStack != null)) { // If not the special output-to-hotbar new drag
                                draggedItemStack = null;
                            }
                            placed = true;
                        }
                    }
                    break; // Processed this slot.
                }
            }
        }

        // Try to place in crafting input slots
        if (!placed) {
            // Use the _calc variables established at the start of placeDraggedItem
            for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                int r = i / CRAFTING_GRID_SIZE;
                int c = i % CRAFTING_GRID_SIZE;
                int slotX = craftingElementsStartX_calc + c * (SLOT_SIZE + SLOT_PADDING);
                int slotY = craftingGridStartY_calc + r * (SLOT_SIZE + SLOT_PADDING);

                if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
                    mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    ItemStack targetStack = craftingInputSlots[i];
                    if (targetStack.isEmpty()) {
                        craftingInputSlots[i] = draggedItemStack;
                        draggedItemStack = null;
                        placed = true;
                    } else if (targetStack.canStackWith(draggedItemStack)) {
                        int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        targetStack.incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) {
                            draggedItemStack = null;
                            placed = true;
                        }
                    } else { // Target slot is not empty and not stackable: Perform a swap.
                        ItemStack itemFromTargetCraftingSlot = targetStack.copy();
                        craftingInputSlots[i] = draggedItemStack; // Place current dragged item into the target crafting slot.

                        boolean swappedBackToOriginal = false;
                        switch (dragSource) {
                            case HOTBAR -> {
                                inventory.setHotbarSlot(draggedItemOriginalSlotIndex, itemFromTargetCraftingSlot);
                                swappedBackToOriginal = true;
                            }
                            case MAIN_INVENTORY -> {
                                inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, itemFromTargetCraftingSlot);
                                swappedBackToOriginal = true;
                            }
                            case CRAFTING_INPUT -> {
                                // Swapping between two crafting input slots
                                int originalCraftingSlotTrueIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                                if (originalCraftingSlotTrueIndex >= 0 && originalCraftingSlotTrueIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                                    craftingInputSlots[originalCraftingSlotTrueIndex] = itemFromTargetCraftingSlot;
                                    swappedBackToOriginal = true;
                                }
                            }
                            case NONE -> { // e.g., draggedItemStack was from CRAFTING_OUTPUT_SLOT_INDEX
                                // If dragging from output and dropping on an occupied crafting input slot,
                                // the item from the input slot becomes the new dragged item.
                                draggedItemStack = itemFromTargetCraftingSlot;
                                // The new drag source is this crafting input slot.
                                dragSource = DragSource.CRAFTING_INPUT;
                                draggedItemOriginalSlotIndex = CRAFTING_INPUT_SLOT_START_INDEX + i;
                                // `placed` is true as the original draggedItem (from output) was placed into craftingInputSlots[i].
                                // `swappedBackToOriginal` remains false as there was no "original slot" to swap back to for itemFromTargetCraftingSlot.
                            }
                        }

                        if (swappedBackToOriginal) {
                            draggedItemStack = null; // Full swap completed, clear dragged item.
                        }
                        // If not swappedBackToOriginal, it implies dragSource was NONE (item from output), and draggedItemStack
                        // now holds itemFromTargetCraftingSlot. dragSource and original index were already updated.

                        placed = true;
                    }
                    if(placed) updateCraftingOutput(); // Update crafting output if any change in crafting grid
                    break; // Processed this slot.
                }
            }
        }

        if (placed && (draggedItemStack == null || draggedItemStack.isEmpty())) {
            draggedItemStack = null;
            draggedItemOriginalSlotIndex = -1;
            dragSource = DragSource.NONE;
        } else if (!placed && draggedItemStack != null && !draggedItemStack.isEmpty()) {
            // Check if the mouse is outside the inventory bounds - if so, drop the entire stack into the world
            if (isMouseOutsideInventoryBounds(mouseX, mouseY, panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight)) {
                dropEntireStackIntoWorld();
            } else {
                tryReturnToOriginalSlot();
            }
        } else if (placed && draggedItemStack != null && !draggedItemStack.isEmpty()){
            // This implies a swap happened, and draggedItemStack is now the item from the target slot.
            // Try to place this *new* draggedItemStack back into the *original* slot of the *first* item.
            tryReturnToOriginalSlot(); // This will try to place the *new* dragged item
        }

        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
             draggedItemStack = null;
             draggedItemOriginalSlotIndex = -1;
             dragSource = DragSource.NONE;
        }
        // If still dragging (e.g. couldn't return to a full original slot after a failed place)
        // it remains with the mouse.
    }

    private void tryReturnToOriginalSlot() {
        if (draggedItemStack == null || draggedItemStack.isEmpty() || draggedItemOriginalSlotIndex == -1) {
            draggedItemStack = null; // Ensure it's cleared if it was emptied by stacking
            dragSource = DragSource.NONE; // Reset source if item is gone
            return;
        }        ItemStack originalSlotItemStack;
        switch (dragSource) {
            case HOTBAR -> {
                originalSlotItemStack = inventory.getHotbarSlot(draggedItemOriginalSlotIndex);
                if (originalSlotItemStack.isEmpty()) {
                    inventory.setHotbarSlot(draggedItemOriginalSlotIndex, draggedItemStack);
                    draggedItemStack = null;
                } else if (originalSlotItemStack.canStackWith(draggedItemStack)) {
                    // Try to stack back
                    int canAdd = originalSlotItemStack.getMaxStackSize() - originalSlotItemStack.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    originalSlotItemStack.incrementCount(toAdd);
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) {
                        draggedItemStack = null;
                    }
                }
            }
            case MAIN_INVENTORY -> { // Was from main inventory
                originalSlotItemStack = inventory.getMainInventorySlot(draggedItemOriginalSlotIndex);
                if (originalSlotItemStack.isEmpty()) {
                    inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, draggedItemStack);
                    draggedItemStack = null;
                } else if (originalSlotItemStack.canStackWith(draggedItemStack)) {
                    int canAdd = originalSlotItemStack.getMaxStackSize() - originalSlotItemStack.getCount();
                    int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                    originalSlotItemStack.incrementCount(toAdd);
                    draggedItemStack.decrementCount(toAdd);
                    if (draggedItemStack.isEmpty()) {
                        draggedItemStack = null;
                    }
                }
            }
            case CRAFTING_INPUT -> {
                // Returning to a crafting input slot
                int craftingSlotTrueIndex = draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                if (craftingSlotTrueIndex >=0 && craftingSlotTrueIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                    originalSlotItemStack = craftingInputSlots[craftingSlotTrueIndex];
                    if (originalSlotItemStack.isEmpty()) {
                        craftingInputSlots[craftingSlotTrueIndex] = draggedItemStack;
                        draggedItemStack = null;
                    } else if (originalSlotItemStack.canStackWith(draggedItemStack)) {
                        int canAdd = originalSlotItemStack.getMaxStackSize() - originalSlotItemStack.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        originalSlotItemStack.incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if (draggedItemStack.isEmpty()) {
                            draggedItemStack = null;
                        }
                    }
                    if (draggedItemStack == null) updateCraftingOutput(); // Item returned to input grid
                }
            }
            case NONE -> {
                 // If dragSource was NONE (e.g. picked from crafting output), it can't "return" to an original inventory slot.
                 // The item just stays dragged if not placed elsewhere. Or, drop it, or add to inventory if space.
                 // For now, it remains dragged.
            }
        }
        // If item could not be returned, it remains dragged.

         if (draggedItemStack != null && draggedItemStack.isEmpty()){
            draggedItemStack = null;
        }
        if (draggedItemStack == null) { // If successfully returned or emptied
            draggedItemOriginalSlotIndex = -1;
            dragSource = DragSource.NONE;
        }
    }

    private void updateCraftingOutput() {
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
            craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Ensure output is cleared
        }
    }
    
    private boolean isMouseOutsideInventoryBounds(float mouseX, float mouseY, int panelStartX, int panelStartY, int panelWidth, int panelHeight) {
        return mouseX < panelStartX || mouseX > panelStartX + panelWidth ||
               mouseY < panelStartY || mouseY > panelStartY + panelHeight;
    }
    
    private void dropEntireStackIntoWorld() {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            return;
        }
        
        Player player = Game.getPlayer();
        if (player == null) {
            clearDraggedItemState();
            return;
        }
        
        // Get player position and camera direction for throwing
        org.joml.Vector3f playerPos = player.getPosition();
        org.joml.Vector3f cameraForward = player.getCamera().getFront();
        
        // Spawn the drop at player position (eye level)
        float dropX = playerPos.x;
        float dropY = playerPos.y + 1.5f; // Eye level
        float dropZ = playerPos.z;
        
        // Calculate throwing velocity - forward direction with upward arc
        // Slightly stronger throw for drag-to-drop since it's a more deliberate action
        float throwSpeed = 10.0f; // Stronger throwing speed for full stacks
        org.joml.Vector3f throwVelocity = new org.joml.Vector3f(
            cameraForward.x * throwSpeed,
            Math.max(3.0f, cameraForward.y * throwSpeed + 4.0f), // Higher arc for full stacks
            cameraForward.z * throwSpeed
        );
        
        // Use the new velocity-based spawning method for the entire stack
        World world = Game.getWorld();
        if (world != null && world.getBlockDropManager() != null) {
            world.getBlockDropManager().spawnDropWithVelocity(dropX, dropY, dropZ, 
                draggedItemStack.getBlockTypeId(), draggedItemStack.getCount(), throwVelocity);
            
            // Clear the dragged item state
            clearDraggedItemState();
        }
    }

    private void consumeCraftingIngredients() {
        // This is simplified. A robust solution would involve the recipe telling us what was consumed.
        // For now, assume 1 of each item in the input grid is consumed if an output was taken.
        // This needs to be tied to the *actual matched recipe's* requirements if recipes can have varying input counts.
        // For 2x2 direct crafting, often it's 1 of each unless explicitly stated by a more complex recipe system.

        // To correctly consume, we'd ideally get the matched recipe from craftItem or have CraftingManager provide
        // a method to get the last matched recipe's input.
        // Lacking that for now, decrement non-empty slots in craftingInputSlots by 1.

        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                craftingInputSlots[i].decrementCount(1);
                if (craftingInputSlots[i].isEmpty()) { // If count drops to 0
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                }
            }
        }
        // After consuming, the crafting grid *automatically* re-evaluates via updateCraftingOutput()
        // called after taking item from output slot.
    }

    private void drawCraftingArrow(float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            nvgBeginPath(vg);
            nvgFillColor(vg, nvgRGBA(200, 200, 200, 220, NVGColor.malloc(stack)));
            // Simple arrow: ->
            nvgMoveTo(vg, x, y + height / 2);
            nvgLineTo(vg, x + width - (width / 3), y + height / 2);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(150,150,150,255,NVGColor.malloc(stack)));
            nvgStroke(vg);

            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width - (width / 3) - (height/4), y + height / 4);
            nvgLineTo(vg, x + width, y + height / 2);
            nvgLineTo(vg, x + width - (width / 3) - (height/4), y + height * 3 / 4);
            // nvgClosePath(vg); // No fill for the point
            nvgStroke(vg);
        }
    }
// Brace removed, the comment about its removal is now accurate.

private void checkHover(ItemStack itemStack, int slotX, int slotY) {
    if (itemStack == null || itemStack.isEmpty() || !visible) {
        return;
    }

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Check if mouse is over this slot
        if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE &&
            mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
            // Only set hovered item if the slot actually contains an item
            // The check (itemStack != null && !itemStack.isEmpty()) was removed as it's redundant
            // due to the check at the beginning of the method.
            hoveredItemStack = itemStack;
            // Note: If slot is empty, hoveredItemStack remains null (cleared at start of render)
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
            nvgFontSize(vg, 18); // Use a reasonable font size
            nvgFontFace(vg, "sans"); // Or "minecraft" if available and preferred
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, color));
            nvgText(vg, x + w / 2, y + h / 2, text);
        }
    }

// Removing the duplicate drawRecipeButton method. The first one (lines 1658-1689) is kept.
// The class's closing brace '}' at the original line 1723 will now correctly close the class.
}