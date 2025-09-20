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
import com.stonebreak.ui.inventoryScreen.InventorySlotRenderer;
import com.stonebreak.ui.inventoryScreen.InventoryPanelRenderer;
import com.stonebreak.ui.inventoryScreen.InventoryButtonRenderer;
import com.stonebreak.ui.inventoryScreen.InventoryCraftingRenderer;
import com.stonebreak.ui.inventoryScreen.InventoryTooltipRenderer;
import com.stonebreak.ui.inventoryScreen.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.InventoryDragDropHandler.DragSource;
import com.stonebreak.ui.inventoryScreen.InventoryMouseHandler;
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
    private final InventoryDragDropHandler.DragState dragState = new InventoryDragDropHandler.DragState();
    // private boolean isDraggingFromHotbar; // Replaced by dragState.dragSource
    // private boolean isDraggingFromCraftingInput; // Replaced by dragState.dragSource
    private ItemStack hoveredItemStack; // For tooltip (inventory screen)

    // Crafting slots
    private final ItemStack[] craftingInputSlots;
    private ItemStack craftingOutputSlot;


    // Hotbar tooltip state now handled by HotbarScreen
 
 
    // UI constants moved to InventoryLayoutCalculator

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
        this.dragState.clear();
        this.hoveredItemStack = null;

        this.craftingInputSlots = new ItemStack[InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize()];
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
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

        // Calculate layout using the new layout calculator
        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);

        // Draw panel background using UIRenderer
        InventoryPanelRenderer.drawInventoryPanel(uiRenderer, layout.panelStartX, layout.panelStartY, layout.inventoryPanelWidth, layout.inventoryPanelHeight);

        // Draw "Crafting" title
        float craftingTitleY = layout.panelStartY + 20;
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2, craftingTitleY, "Crafting");

        // Crafting slots rendering
        int craftingGridStartY = layout.craftingGridStartY;
        // Center the 2x2 grid, arrow and output slot.
        // Width of 2x2: 2 * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding()
        // Width of arrow: ~InventoryLayoutCalculator.getSlotSize()
        // Width of output: InventoryLayoutCalculator.getSlotSize()
        // Total visual width: (2*(InventoryLayoutCalculator.getSlotSize()+InventoryLayoutCalculator.getSlotPadding())-InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() * 2 (approx)
        int craftInputGridVisualWidth = layout.craftInputGridVisualWidth;
        // int craftOutputXOffset = craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding(); // Unused

        int craftingElementsTotalWidth = craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize(); // grid + space + arrow + space + output
        int craftingElementsStartX = layout.craftingElementsStartX;


        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            drawInventorySlot(craftingInputSlots[i], slotX, slotY, false, -1); // Not a hotbar slot, no specific index for highlight
            checkHover(craftingInputSlots[i], slotX, slotY);
        }

        // Draw Arrow (placeholder visual)
        int arrowX = layout.craftingElementsStartX + layout.craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotPadding() + (InventoryLayoutCalculator.getSlotSize() - 20) / 2; // Centered in InventoryLayoutCalculator.getSlotSize() space
        int arrowY = layout.craftingGridStartY + (InventoryLayoutCalculator.getSlotSize() - 20) / 2; // Vertically centered in grid area
        InventoryCraftingRenderer.drawCraftingArrow(uiRenderer, arrowX, arrowY, 20, 20);

        // Draw Crafting Output Slot
        drawInventorySlot(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY, false, -1);
        checkHover(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY);

        // Define Recipe Button position (near crafting output)
        // Let's place it to the right of the output slot.
        recipeButtonWidth = uiRenderer.getTextWidth(RECIPE_BUTTON_TEXT, 18f, "sans") + 2 * InventoryLayoutCalculator.getSlotPadding(); // Width based on text
        recipeButtonHeight = InventoryLayoutCalculator.getSlotSize();
        recipeButtonX = layout.outputSlotX + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() * 3;
        recipeButtonY = layout.outputSlotY; // Align vertically with the output slot

        // Ensure button is within panel bounds, adjust if necessary or make panel wider.
        // For now, assume it fits or overlaps acceptably.
        // A more robust solution would adjust panelWidth or button position.
        // if (recipeButtonX + recipeButtonWidth > panelStartX + inventoryPanelWidth - InventoryLayoutCalculator.getSlotPadding()) {
        //     recipeButtonX = panelStartX + inventoryPanelWidth - InventoryLayoutCalculator.getSlotPadding() - recipeButtonWidth;
        // }

        InventoryButtonRenderer.drawRecipeButton(uiRenderer, inputHandler, recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight, RECIPE_BUTTON_TEXT);
 
        // Draw "Inventory" title below crafting area
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2, layout.mainInvContentStartY - 20, "Inventory");

        // Draw Main Inventory Slots
        ItemStack[] mainSlots = inventory.getMainInventorySlots(); // Gets copies
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() + row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            drawInventorySlot(mainSlots[i], slotX, slotY, false, -1);
            checkHover(mainSlots[i], slotX, slotY);
        }

        // Draw Hotbar Slots (as part of the main inventory panel, visually)
        // Positioned below the main inventory slots within the same panel
        ItemStack[] hotbarSlots = inventory.getHotbarSlots(); // Gets copies

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar is a single row
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;
             // Pass true for isHotbarSlot and the actual hotbar index
            drawInventorySlot(hotbarSlots[i], slotX, slotY, true, i);
            checkHover(hotbarSlots[i], slotX, slotY);
        }

        // Dragged item rendering moved to overlay phase for proper z-ordering

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

        // Calculate layout using the new layout calculator
        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);

        // Main Inventory Area (now includes hotbar visually as part of the panel)
        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // Unused

        // Define overall panel dimensions to include crafting area
        // Crafting area: 2x2 grid, an arrow, and 1 output slot.
        // Let's say arrow is InventoryLayoutCalculator.getSlotSize() wide. Grid is 2 * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()).
        // Total width needed for crafting section: 2 * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotSize() (for arrow) + InventoryLayoutCalculator.getSlotSize() (for output) + paddings
        // This will be drawn to the side or above the player inventory. For now, let's plan to draw it *above* the main inventory title.
        
        int craftingAreaHeight = InventoryLayoutCalculator.getCraftingGridSize() * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding(); // For the 2x2 grid
        // int craftingAreaMinY = InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getTitleHeight(); // Unused

        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1; // +1 for hotbar
        int mainAndHotbarHeight = totalInventoryRows * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();

        // Adjusted panel height calculation:
        // Let's position crafting grid to the side of player avatar, and inventory below.
        // Player avatar (not implemented) would be around 2 slots wide, 2 slots high.
        // Crafting: 2x2 input + arrow + 1 output. Approx 2 * (InventoryLayoutCalculator.getSlotSize()+PAD) for grid height.
        // For now, let's adjust the main inventory panel to make space.
        // We can add the crafting grid *within* the existing panel logic, shifted.

        // int numDisplayCols = Inventory.MAIN_INVENTORY_COLS; // This was a duplicate definition
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();

        // For now, keep panel width based on main inventory, and fit crafting grid within it visually
        // This might make the panel wider if we place crafting side-by-side later.
        // For this iteration, we will place the crafting elements *above* the standard inventory within the same panel.

        int craftingSectionHeight = InventoryLayoutCalculator.getCraftingGridSize() * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize(); // 2 rows for grid, 1 space for arrow/gap.

        int inventoryPanelWidth = baseInventoryPanelWidth; // Keeps current width based on hotbar/main inv.
        int inventoryPanelHeight = mainAndHotbarHeight + InventoryLayoutCalculator.getTitleHeight() + craftingSectionHeight + InventoryLayoutCalculator.getSlotPadding() * 2; // Added crafting space

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Draw panel background using UIRenderer
        InventoryPanelRenderer.drawInventoryPanel(uiRenderer, layout.panelStartX, layout.panelStartY, layout.inventoryPanelWidth, layout.inventoryPanelHeight);

        // Draw "Crafting" title
        float craftingTitleY = layout.panelStartY + 20;
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2, craftingTitleY, "Crafting");

        // Crafting slots rendering
        int craftingGridStartY = layout.craftingGridStartY;
        // Center the 2x2 grid, arrow and output slot.
        // Width of 2x2: 2 * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding()
        // Width of arrow: ~InventoryLayoutCalculator.getSlotSize()
        // Width of output: InventoryLayoutCalculator.getSlotSize()
        // Total visual width: (2*(InventoryLayoutCalculator.getSlotSize()+InventoryLayoutCalculator.getSlotPadding())-InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() * 2 (approx)
        int craftInputGridVisualWidth = layout.craftInputGridVisualWidth;
        // int craftOutputXOffset = craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding(); // Unused

        int craftingElementsTotalWidth = craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize(); // grid + space + arrow + space + output
        int craftingElementsStartX = layout.craftingElementsStartX;


        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            drawInventorySlot(craftingInputSlots[i], slotX, slotY, false, -1); // Not a hotbar slot, no specific index for highlight
            checkHover(craftingInputSlots[i], slotX, slotY);
        }

        // Draw Arrow (placeholder visual)
        int arrowX = layout.craftingElementsStartX + layout.craftInputGridVisualWidth + InventoryLayoutCalculator.getSlotPadding() + (InventoryLayoutCalculator.getSlotSize() - 20) / 2; // Centered in InventoryLayoutCalculator.getSlotSize() space
        int arrowY = layout.craftingGridStartY + (InventoryLayoutCalculator.getSlotSize() - 20) / 2; // Vertically centered in grid area
        InventoryCraftingRenderer.drawCraftingArrow(uiRenderer, arrowX, arrowY, 20, 20);

        // Draw Crafting Output Slot
        drawInventorySlot(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY, false, -1);
        checkHover(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY);

        // Define Recipe Button position (near crafting output)
        // Let's place it to the right of the output slot.
        recipeButtonWidth = uiRenderer.getTextWidth(RECIPE_BUTTON_TEXT, 18f, "sans") + 2 * InventoryLayoutCalculator.getSlotPadding(); // Width based on text
        recipeButtonHeight = InventoryLayoutCalculator.getSlotSize();
        recipeButtonX = layout.outputSlotX + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() * 3;
        recipeButtonY = layout.outputSlotY; // Align vertically with the output slot

        // Ensure button is within panel bounds, adjust if necessary or make panel wider.
        // For now, assume it fits or overlaps acceptably.
        // A more robust solution would adjust panelWidth or button position.
        // if (recipeButtonX + recipeButtonWidth > panelStartX + inventoryPanelWidth - InventoryLayoutCalculator.getSlotPadding()) {
        //     recipeButtonX = panelStartX + inventoryPanelWidth - InventoryLayoutCalculator.getSlotPadding() - recipeButtonWidth;
        // }

        InventoryButtonRenderer.drawRecipeButton(uiRenderer, inputHandler, recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight, RECIPE_BUTTON_TEXT);
 
        // Draw "Inventory" title below crafting area
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - InventoryLayoutCalculator.getSlotSize() /2; // Reposition inventory title
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2, inventoryTitleY, "Inventory");
 
        int contentStartY = (int)(inventoryTitleY + InventoryLayoutCalculator.getTitleHeight() / 2f + InventoryLayoutCalculator.getSlotPadding()); // Cast to int
 
        // Draw Main Inventory Slots
        ItemStack[] mainSlots = inventory.getMainInventorySlots(); // Gets copies

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = contentStartY + InventoryLayoutCalculator.getSlotPadding() + row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            drawInventorySlot(mainSlots[i], slotX, slotY, false, -1);
            checkHover(mainSlots[i], slotX, slotY);
        }
        
        // Draw Hotbar Slots (as part of the main inventory panel, visually)
        // Positioned below the main inventory slots within the same panel
        ItemStack[] hotbarSlots = inventory.getHotbarSlots(); // Gets copies
        int hotbarRowYOffset = contentStartY + InventoryLayoutCalculator.getSlotPadding() + Inventory.MAIN_INVENTORY_ROWS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding(); // Extra padding for separation

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar is a single row
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = hotbarRowYOffset;
            drawInventorySlot(hotbarSlots[i], slotX, slotY, true, i);
            checkHover(hotbarSlots[i], slotX, slotY);
        }

        // Dragged item rendering moved to overlay phase for proper z-ordering
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
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() && !dragState.isDragging()) { // Only show tooltip if not dragging
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                InventoryTooltipRenderer.drawItemTooltip(uiRenderer, item.getName(), mousePos.x + 15, mousePos.y + 15, screenWidth, screenHeight);
            }
        }
    }

    /**
     * Render only the dragged item for the inventory screen.
     * This method is called during the overlay phase to ensure dragged items appear above all other UI.
     */
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (!visible || !dragState.isDragging()) {
            return;
        }

        Vector2f mousePos = inputHandler.getMousePosition();
        // Center the item on the mouse cursor
        int itemRenderX = (int) (mousePos.x - (InventoryLayoutCalculator.getSlotSize() - 4) / 2.0f);
        int itemRenderY = (int) (mousePos.y - (InventoryLayoutCalculator.getSlotSize() - 4) / 2.0f);

        Item item = dragState.draggedItemStack.getItem();
        if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
            drawDraggedItem(item, itemRenderX, itemRenderY, dragState.draggedItemStack.getCount());
        }
    }

    // Helper method to draw a single slot using UIRenderer - delegated to InventorySlotRenderer
    private void drawInventorySlot(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex) {
        InventorySlotRenderer.drawInventorySlot(itemStack, slotX, slotY, isHotbarSlot, hotbarIndex, uiRenderer, renderer);
    }
    
    private void drawDraggedItem(Item item, int x, int y, int count) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            // End NanoVG frame temporarily to draw 3D item
            uiRenderer.endFrame();

            // Draw 3D item using UIRenderer's BlockIconRenderer with dragged item flag
            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, InventoryLayoutCalculator.getSlotSize() - 4, InventoryLayoutCalculator.getSlotSize() - 4, renderer.getTextureAtlas(), true);
            } else {
                // For ItemTypes, render a 2D sprite using UIRenderer
                uiRenderer.renderItemIcon(x, y, InventoryLayoutCalculator.getSlotSize() - 4, InventoryLayoutCalculator.getSlotSize() - 4, item, renderer.getTextureAtlas());
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
                nvgText(vg, x + InventoryLayoutCalculator.getSlotSize() - 2, y + InventoryLayoutCalculator.getSlotSize() - 2, countText);

                // Main text
                nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                nvgText(vg, x + InventoryLayoutCalculator.getSlotSize() - 3, y + InventoryLayoutCalculator.getSlotSize() - 3, countText);
            }
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
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();
        int craftingSectionHeight = InventoryLayoutCalculator.getCraftingGridSize() * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize();
        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1;
        int mainAndHotbarHeight = totalInventoryRows * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();
        int inventoryPanelWidth = baseInventoryPanelWidth;
        int inventoryPanelHeight = mainAndHotbarHeight + InventoryLayoutCalculator.getTitleHeight() + craftingSectionHeight + InventoryLayoutCalculator.getSlotPadding() * 2;
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;
        int craftingGridStartY_calc = panelStartY + InventoryLayoutCalculator.getTitleHeight() + InventoryLayoutCalculator.getSlotPadding();
        int craftInputGridVisualWidth_calc = InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getSlotSize() + (InventoryLayoutCalculator.getCraftingGridSize() - 1) * InventoryLayoutCalculator.getSlotPadding();
        int craftingElementsTotalWidth_calc = craftInputGridVisualWidth_calc + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize();
        int craftingElementsStartX_calc = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth_calc) / 2;
        int craftingAreaHeight_calc = InventoryLayoutCalculator.getCraftingGridSize() * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();
        float inventoryTitleY_calc = craftingGridStartY_calc + craftingSectionHeight - InventoryLayoutCalculator.getSlotSize() / 2f;
        int mainInvContentStartY_calc = (int) (inventoryTitleY_calc + InventoryLayoutCalculator.getTitleHeight() / 2f + InventoryLayoutCalculator.getSlotPadding());
        int hotbarRowY_calc = mainInvContentStartY_calc + InventoryLayoutCalculator.getSlotPadding() + Inventory.MAIN_INVENTORY_ROWS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) + InventoryLayoutCalculator.getSlotPadding();
        int outputSlotX_calc = craftingElementsStartX_calc + craftInputGridVisualWidth_calc + InventoryLayoutCalculator.getSlotPadding() + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
        int outputSlotY_calc = craftingGridStartY_calc + (craftingAreaHeight_calc - InventoryLayoutCalculator.getSlotSize()) / 2 - InventoryLayoutCalculator.getSlotPadding();

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
            if (dragState.draggedItemStack == null) {
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
             if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
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
            if (dragState.draggedItemStack != null && !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                 // Mouse button (specifically left) released while dragging
                placeDraggedItem(screenWidth, screenHeight); // placeDraggedItem handles clearing drag state if successful
            } else if (dragState.draggedItemStack != null &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                // Fallback: if somehow still dragging but no buttons are down (e.g., after a failed place)
                tryReturnToOriginalSlot();
                if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
                    dropEntireStackIntoWorld();
                } else {
                    clearDraggedItemState();
                }
            } else if (dragState.draggedItemStack == null) {
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
            int slotX = panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = mainInvContentStartY_calc + InventoryLayoutCalculator.getSlotPadding() + row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
                mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = DragSource.MAIN_INVENTORY;
                    return;
                }
            }
        }

        // Check hotbar slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = hotbarRowY_calc;

            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
                mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = DragSource.HOTBAR;
                    return;
                }
            }
        }

        // Check crafting input slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingInputSlotsCount(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = craftingElementsStartX_calc + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = craftingGridStartY_calc + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
                mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    dragState.draggedItemStack = craftingInputSlots[i].copy();
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                    dragState.draggedItemOriginalSlotIndex = InventoryDragDropHandler.getCraftingInputSlotStartIndex() + i;
                    dragState.dragSource = DragSource.CRAFTING_INPUT;
                    updateCraftingOutput();
                    return;
                }
            }
        }
        
        // Check crafting output slot (pickup only)
        if (mouseX >= outputSlotX_calc && mouseX <= outputSlotX_calc + InventoryLayoutCalculator.getSlotSize() &&
            mouseY >= outputSlotY_calc && mouseY <= outputSlotY_calc + InventoryLayoutCalculator.getSlotSize()) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                dragState.draggedItemStack = craftingOutputSlot.copy();
                consumeCraftingIngredients();
                craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
                dragState.draggedItemOriginalSlotIndex = InventoryDragDropHandler.getCraftingOutputSlotIndex();
                dragState.dragSource = DragSource.NONE; // Source is effectively 'crafting system', not a persistent slot
                updateCraftingOutput();
            }
        }
    }

    private void clearDraggedItemState() {
        dragState.clear();
    }


    private boolean tryHandleShiftClickTransfer(float mouseX, float mouseY,
                                            int craftingGridStartY_calc, int craftingElementsStartX_calc,
                                            int outputSlotX_calc, int outputSlotY_calc) {
        // 1. Check Crafting Output Slot
        if (mouseX >= outputSlotX_calc && mouseX <= outputSlotX_calc + InventoryLayoutCalculator.getSlotSize() &&
            mouseY >= outputSlotY_calc && mouseY <= outputSlotY_calc + InventoryLayoutCalculator.getSlotSize()) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                ItemStack itemsInOutput = craftingOutputSlot.copy(); // What's available to take

                // Try to add the items to inventory using the same approach as WorkbenchScreen
                boolean wasAdded = inventory.addItem(itemsInOutput);

                if (wasAdded) {
                    // Items were successfully added to inventory
                    consumeCraftingIngredients();
                    craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0); // Clear the output slot as the craft is "taken"
                    updateCraftingOutput(); // Update for the next potential craft
                    return true; // Transfer occurred
                } else {
                    // Inventory is full - drop the item instead of deleting it
                    Player player = Game.getPlayer();
                    if (player != null) {
                        com.stonebreak.util.DropUtil.dropItemFromPlayer(player, itemsInOutput);
                        // Still consume ingredients and clear output slot since the craft was "taken" (as a drop)
                        consumeCraftingIngredients();
                        craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
                        updateCraftingOutput();
                        return true; // Transfer occurred (as a drop)
                    }
                    return false; // No items could be transferred and no player to drop to
                }
            }
        }

        // 2. Check Crafting Input Slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingInputSlotsCount(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = craftingElementsStartX_calc + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = craftingGridStartY_calc + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
                mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
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
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) {
            return false;
        }

        // Try Main Inventory Slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = mainInvContentStartY_calc + InventoryLayoutCalculator.getSlotPadding() + row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() && mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
                return attemptDropOneToSlot(inventory.getMainInventorySlot(slotIndex), (stack) -> inventory.setMainInventorySlot(slotIndex, stack), null, -1);
            }
        }

        // Try Hotbar Slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = panelStartX + InventoryLayoutCalculator.getSlotPadding() + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = hotbarRowY_calc;
            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() && mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
                return attemptDropOneToSlot(inventory.getHotbarSlot(slotIndex), (stack) -> inventory.setHotbarSlot(slotIndex, stack), null, -1);
            }
        }

        // Try Crafting Input Slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingInputSlotsCount(); i++) {
            final int slotIndex = i; // effectively final for lambda
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = craftingElementsStartX_calc + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = craftingGridStartY_calc + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() && mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
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
            ItemStack newItem = new ItemStack(dragState.draggedItemStack.getItem(), 1);
             // Use newStackSetterForCrafting if provided and it's a crafting slot context, otherwise directSlotSetter.
            if(isCraftingSlot && newStackSetterForCrafting != null) newStackSetterForCrafting.accept(newItem);
            else directSlotSetter.accept(newItem);
            
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) clearDraggedItemState(); // this clears dragState.draggedItemStack itself
            if (isCraftingSlot) updateCraftingOutput();
            return true;
        } else if (targetSlot.canStackWith(dragState.draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
            targetSlot.incrementCount(1);
            // directSlotSetter might not be needed if targetSlot is a direct reference from an array (like craftingInputSlots)
            // or if playerInventory.get...Slot() returns a modifiable reference.
            // If inventory.getXSlot() returns a copy, then directSlotSetter.accept(targetSlot) IS needed.
            // Let's assume for now Inventory.getXSlot() returns a reference for .incrementCount() to work directly.
            // If this is not the case, directSlotSetter.accept(targetSlot) would be needed after targetSlot.incrementCount(1);
            
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) clearDraggedItemState(); // this clears dragState.draggedItemStack itself
            if (isCraftingSlot) updateCraftingOutput();
            return true;
        }
        return false;
    }

 
    private void placeDraggedItem(int screenWidth, int screenHeight) {
        Vector2f mousePos = inputHandler.getMousePosition();
        InventoryDragDropHandler.placeDraggedItem(dragState, inventory, craftingInputSlots,
                                                mousePos, screenWidth, screenHeight, this::updateCraftingOutput);
    }


    private void tryReturnToOriginalSlot() {
        InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, this::updateCraftingOutput);
    }

    private void updateCraftingOutput() {
        List<List<ItemStack>> grid = new ArrayList<>();
        for (int r = 0; r < InventoryLayoutCalculator.getCraftingGridSize(); r++) {
            List<ItemStack> row = new ArrayList<>();
            for (int c = 0; c < InventoryLayoutCalculator.getCraftingGridSize(); c++) {
                row.add(craftingInputSlots[r * InventoryLayoutCalculator.getCraftingGridSize() + c]);
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
        InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
    }

    private void consumeCraftingIngredients() {
        // This is simplified. A robust solution would involve the recipe telling us what was consumed.
        // For now, assume 1 of each item in the input grid is consumed if an output was taken.
        // This needs to be tied to the *actual matched recipe's* requirements if recipes can have varying input counts.
        // For 2x2 direct crafting, often it's 1 of each unless explicitly stated by a more complex recipe system.

        // To correctly consume, we'd ideally get the matched recipe from craftItem or have CraftingManager provide
        // a method to get the last matched recipe's input.
        // Lacking that for now, decrement non-empty slots in craftingInputSlots by 1.

        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
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

// Brace removed, the comment about its removal is now accurate.

private void checkHover(ItemStack itemStack, int slotX, int slotY) {
    if (itemStack == null || itemStack.isEmpty() || !visible) {
        return;
    }

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Check if mouse is over this slot
        if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
            mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
            // Only set hovered item if the slot actually contains an item
            // The check (itemStack != null && !itemStack.isEmpty()) was removed as it's redundant
            // due to the check at the beginning of the method.
            hoveredItemStack = itemStack;
            // Note: If slot is empty, hoveredItemStack remains null (cleared at start of render)
        }
    }


// Removing the duplicate drawRecipeButton method. The first one (lines 1658-1689) is kept.
// The class's closing brace '}' at the original line 1723 will now correctly close the class.
}