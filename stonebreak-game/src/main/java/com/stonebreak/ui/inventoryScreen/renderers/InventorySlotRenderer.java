package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Modern inventory slot renderer with refined appearance, hover effects, and visual feedback.
 * Features depth rendering, smooth transitions, and professional visual design.
 */
public class InventorySlotRenderer {

    private static final int SLOT_SIZE = 40;

    /**
     * Draws a single inventory slot with modern styling, depth effects, and visual feedback.
     * Features gradient backgrounds, hover effects, and refined typography.
     *
     * @param itemStack The item stack to render in the slot
     * @param slotX X position of the slot
     * @param slotY Y position of the slot
     * @param isHotbarSlot Whether this is a hotbar slot
     * @param hotbarIndex Index of hotbar slot for selection highlighting
     * @param uiRenderer The UI renderer instance
     * @param renderer The main renderer instance
     */
    public static void drawInventorySlot(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex, UIRenderer uiRenderer, Renderer renderer) {
        try {
            try (MemoryStack stack = stackPush()) {
                // Validation
                if (uiRenderer == null) {
                    System.err.println("ERROR: UIRenderer is null in drawInventorySlot");
                    return;
                }
                long vg = uiRenderer.getVG();

                // Draw the slot with modern styling
                drawModernSlot(vg, slotX, slotY, isHotbarSlot, hotbarIndex, stack);

                // Render item if present
                if (itemStack != null && !itemStack.isEmpty()) {
                    Item item = itemStack.getItem();
                    int count = itemStack.getCount();

                    if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                        renderItemInSlot(item, slotX, slotY, uiRenderer, renderer);

                        // Draw count text with modern styling
                        if (count > 1) {
                            drawItemCount(vg, slotX, slotY, count, stack);
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

    /**
     * Enhanced slot rendering with hover detection and visual feedback.
     */
    public static void drawInventorySlotWithHover(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex, UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler) {
        // Check for hover state
        boolean isHovered = false;
        if (inputHandler != null) {
            Vector2f mousePos = inputHandler.getMousePosition();
            isHovered = mousePos.x >= slotX && mousePos.x <= slotX + SLOT_SIZE &&
                       mousePos.y >= slotY && mousePos.y <= slotY + SLOT_SIZE;
        }

        try {
            try (MemoryStack stack = stackPush()) {
                if (uiRenderer == null) {
                    System.err.println("ERROR: UIRenderer is null in drawInventorySlotWithHover");
                    return;
                }
                long vg = uiRenderer.getVG();

                // Draw slot with hover state
                drawModernSlotWithHover(vg, slotX, slotY, isHotbarSlot, hotbarIndex, isHovered, stack);

                // Render item if present
                if (itemStack != null && !itemStack.isEmpty()) {
                    Item item = itemStack.getItem();
                    int count = itemStack.getCount();

                    if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                        renderItemInSlot(item, slotX, slotY, uiRenderer, renderer);

                        if (count > 1) {
                            drawItemCount(vg, slotX, slotY, count, stack);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR in drawInventorySlotWithHover: " + e.getMessage());
            recoverUIState(uiRenderer);
        }
    }

    /**
     * Draws the modern slot background with depth and visual hierarchy.
     */
    private static void drawModernSlot(long vg, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex, MemoryStack stack) {
        drawModernSlotWithHover(vg, slotX, slotY, isHotbarSlot, hotbarIndex, false, stack);
    }

    /**
     * Draws the modern slot with optional hover state and selection highlighting.
     */
    private static void drawModernSlotWithHover(long vg, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex, boolean isHovered, MemoryStack stack) {
        float cornerRadius = InventoryTheme.Measurements.CORNER_RADIUS_SMALL;

        // Inner shadow for depth
        drawSlotInnerShadow(vg, slotX, slotY, cornerRadius, stack);

        // Main slot background with gradient
        drawSlotBackground(vg, slotX, slotY, cornerRadius, stack);

        // Border with state-based coloring
        drawSlotBorder(vg, slotX, slotY, cornerRadius, isHovered, stack);

        // Selection highlight for hotbar slots
        if (isHotbarSlot && hotbarIndex >= 0) {
            drawHotbarSelection(vg, slotX, slotY, cornerRadius, hotbarIndex, stack);
        }

        // Hover highlight
        if (isHovered) {
            drawHoverHighlight(vg, slotX, slotY, cornerRadius, stack);
        }
    }

    /**
     * Draws inner shadow for depth effect.
     */
    private static void drawSlotInnerShadow(long vg, int slotX, int slotY, float cornerRadius, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, cornerRadius - 1);
        nvgFillColor(vg, InventoryTheme.Slot.SHADOW_INNER.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Draws the main slot background with subtle gradient.
     */
    private static void drawSlotBackground(long vg, int slotX, int slotY, float cornerRadius, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, cornerRadius - 2);

        // Create subtle gradient for depth
        NVGPaint backgroundPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, slotX, slotY, slotX, slotY + SLOT_SIZE,
                         InventoryTheme.Slot.BACKGROUND.brighten(0.1f).toNVG(stack),
                         InventoryTheme.Slot.BACKGROUND.darken(0.1f).toNVG(stack),
                         backgroundPaint);

        nvgFillPaint(vg, backgroundPaint);
        nvgFill(vg);
    }

    /**
     * Draws the slot border with state-dependent styling.
     */
    private static void drawSlotBorder(long vg, int slotX, int slotY, float cornerRadius, boolean isHovered, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, slotX, slotY, SLOT_SIZE, SLOT_SIZE, cornerRadius);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_NORMAL);

        if (isHovered) {
            nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_HOVER.toNVG(stack));
        } else {
            nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_NORMAL.toNVG(stack));
        }
        nvgStroke(vg);
    }

    /**
     * Draws hotbar selection highlighting.
     */
    private static void drawHotbarSelection(long vg, int slotX, int slotY, float cornerRadius, int hotbarIndex, MemoryStack stack) {
        // You can implement hotbar selection logic here if needed
        // For now, we'll use a subtle highlight for selected hotbar items
        nvgBeginPath(vg);
        nvgRoundedRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, cornerRadius - 1);
        nvgStrokeWidth(vg, InventoryTheme.Measurements.BORDER_WIDTH_THIN);
        nvgStrokeColor(vg, InventoryTheme.Slot.BORDER_SELECTED.withAlpha(100).toNVG(stack));
        nvgStroke(vg);
    }

    /**
     * Draws hover highlight effect.
     */
    private static void drawHoverHighlight(long vg, int slotX, int slotY, float cornerRadius, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, cornerRadius - 1);
        nvgFillColor(vg, InventoryTheme.Slot.HIGHLIGHT_INNER.toNVG(stack));
        nvgFill(vg);
    }

    /**
     * Renders the item icon within the slot using the existing rendering system.
     */
    private static void renderItemInSlot(Item item, int slotX, int slotY, UIRenderer uiRenderer, Renderer renderer) {
        try {
            // End NanoVG frame temporarily to draw 3D item
            uiRenderer.endFrame();

            // Draw item using existing rendering system
            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, slotX + 3, slotY + 3, SLOT_SIZE - 6, SLOT_SIZE - 6, renderer.getTextureAtlas());
            } else {
                uiRenderer.renderItemIcon(slotX + 3, slotY + 3, SLOT_SIZE - 6, SLOT_SIZE - 6, item, renderer.getTextureAtlas());
            }

            // Restart NanoVG frame
            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
        } catch (Exception e) {
            System.err.println("Error rendering item in slot: " + e.getMessage());
            recoverUIState(uiRenderer);
        }
    }

    /**
     * Draws modern item count text with enhanced styling.
     */
    private static void drawItemCount(long vg, int slotX, int slotY, int count, MemoryStack stack) {
        String countText = String.valueOf(count);
        RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
        nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

        // Enhanced text shadow for better visibility
        nvgFillColor(vg, InventoryTheme.Text.COUNT_SHADOW.toNVG(stack));
        nvgText(vg, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, countText);

        // Main count text with modern coloring
        nvgFillColor(vg, InventoryTheme.Text.COUNT.toNVG(stack));
        nvgText(vg, slotX + SLOT_SIZE - 3, slotY + SLOT_SIZE - 3, countText);

        // Subtle glow effect for count text
        nvgGlobalAlpha(vg, 0.4f);
        nvgFillColor(vg, InventoryTheme.Text.COUNT.toNVG(stack));
        nvgText(vg, slotX + SLOT_SIZE - 3, slotY + SLOT_SIZE - 3, countText);
        nvgGlobalAlpha(vg, 1.0f);
    }

    /**
     * Attempts to recover UI state after rendering errors.
     */
    private static void recoverUIState(UIRenderer uiRenderer) {
        try {
            if (uiRenderer != null) {
                uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
            }
        } catch (Exception e2) {
            System.err.println("Failed to recover UI frame: " + e2.getMessage());
        }
    }
}