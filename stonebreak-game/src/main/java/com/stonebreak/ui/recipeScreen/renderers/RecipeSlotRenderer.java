package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of recipe-specific slots with enhanced visual styling.
 * Extends the functionality of InventorySlotRenderer while maintaining compatibility.
 */
public class RecipeSlotRenderer {

    private RecipeSlotRenderer() {
        // Utility class - prevent instantiation
    }

    /**
     * Draws a recipe slot with standard size for recipe grid display.
     *
     * @param itemStack The item stack to render
     * @param slotX X position of the slot
     * @param slotY Y position of the slot
     * @param slotSize Size of the slot
     * @param uiRenderer UI renderer instance
     * @param renderer Main renderer instance
     * @param inputHandler Input handler for hover detection
     * @return The ItemStack if mouse is hovering over it, null otherwise
     */
    public static ItemStack drawRecipeSlot(ItemStack itemStack, int slotX, int slotY, int slotSize, UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= slotX && mousePos.x <= slotX + slotSize &&
                               mousePos.y >= slotY && mousePos.y <= slotY + slotSize;

            ItemStack hoveredItem = null;
            if (isHovering && itemStack != null && !itemStack.isEmpty()) {
                hoveredItem = itemStack;
            }

            // Slot background
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, slotSize, slotSize);
            nvgFillColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Slot inner background
            nvgBeginPath(vg);
            nvgRect(vg, slotX + 1, slotY + 1, slotSize - 2, slotSize - 2);
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
                        if (item instanceof BlockType blockType) {
                            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), blockType, slotX + 2, slotY + 2, slotSize - 4, slotSize - 4, renderer.getTextureAtlas());
                        } else {
                            // For ItemTypes, render a 2D sprite using UIRenderer
                            uiRenderer.renderItemIcon(slotX + 2, slotY + 2, slotSize - 4, slotSize - 4, item, renderer.getTextureAtlas());
                        }

                        // Restart NanoVG frame
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);

                        if (count > 1) {
                            String countText = String.valueOf(count);
                            nvgFontSize(vg, 10);
                            nvgFontFace(vg, "sans");
                            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

                            // Text shadow
                            nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                            nvgText(vg, slotX + slotSize - 2, slotY + slotSize - 2, countText);

                            // Main text
                            nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                            nvgText(vg, slotX + slotSize - 3, slotY + slotSize - 3, countText);
                        }
                    } catch (Exception e) {
                        System.err.println("Error rendering 3D item in recipe slot: " + e.getMessage());
                        try {
                            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                        } catch (Exception e2) {
                            System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                        }
                    }
                }
            }

            return hoveredItem;
        }
    }

    /**
     * Draws a detailed recipe slot for popup display with enhanced styling.
     *
     * @param itemStack The item stack to render
     * @param slotX X position of the slot
     * @param slotY Y position of the slot
     * @param slotSize Size of the slot
     * @param uiRenderer UI renderer instance
     * @param renderer Main renderer instance
     * @param inputHandler Input handler for hover detection
     * @return The ItemStack if mouse is hovering over it, null otherwise
     */
    public static ItemStack drawDetailedRecipeSlot(ItemStack itemStack, int slotX, int slotY, int slotSize, UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler) {
        if (itemStack == null || itemStack.isEmpty()) {
            return null; // Slot background already drawn
        }

        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= slotX && mousePos.x <= slotX + slotSize &&
                               mousePos.y >= slotY && mousePos.y <= slotY + slotSize;

            ItemStack hoveredItem = null;
            if (isHovering) {
                hoveredItem = itemStack;
                // Draw hover highlight
                nvgBeginPath(vg);
                nvgRect(vg, slotX + 2, slotY + 2, slotSize - 4, slotSize - 4);
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 30, NVGColor.malloc(stack)));
                nvgFill(vg);
            }

            Item item = itemStack.getItem();
            int count = itemStack.getCount();

            if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
                try {
                    // End NanoVG frame temporarily to draw 3D item
                    uiRenderer.endFrame();

                    // Draw 3D item using UIRenderer's BlockIconRenderer with more padding for better look
                    if (item instanceof BlockType blockType) {
                        uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), blockType, slotX + 6, slotY + 6, slotSize - 12, slotSize - 12, renderer.getTextureAtlas());
                    } else {
                        // For ItemTypes, render a 2D sprite using UIRenderer
                        uiRenderer.renderItemIcon(slotX + 6, slotY + 6, slotSize - 12, slotSize - 12, item, renderer.getTextureAtlas());
                    }

                    // Restart NanoVG frame
                    uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);

                    if (count > 1) {
                        String countText = String.valueOf(count);
                        nvgFontSize(vg, 16); // Slightly larger for better visibility
                        nvgFontFace(vg, "sans");
                        nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

                        // Bold text shadow
                        nvgFillColor(vg, nvgRGBA(0, 0, 0, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + slotSize - 4, slotY + slotSize - 4, countText);

                        // Main text (bright yellow/gold)
                        nvgFillColor(vg, nvgRGBA(255, 255, 85, 255, NVGColor.malloc(stack)));
                        nvgText(vg, slotX + slotSize - 5, slotY + slotSize - 5, countText);
                    }
                } catch (Exception e) {
                    System.err.println("Error rendering 3D item in detailed recipe slot: " + e.getMessage());
                    try {
                        uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);
                    } catch (Exception e2) {
                        System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                    }
                }
            }

            return hoveredItem;
        }
    }

    /**
     * Utility method for creating NanoVG RGBA colors.
     *
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @param a Alpha component (0-255)
     * @param color NVGColor instance to populate
     * @return The populated NVGColor instance
     */
    private static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        color.r(r / 255.0f);
        color.g(g / 255.0f);
        color.b(b / 255.0f);
        color.a(a / 255.0f);
        return color;
    }
}