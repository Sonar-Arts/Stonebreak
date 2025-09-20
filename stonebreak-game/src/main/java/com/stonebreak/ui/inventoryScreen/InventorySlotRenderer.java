package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles rendering of individual inventory slots with items and count text.
 * Separated from InventoryScreen for better modularity and maintainability.
 */
public class InventorySlotRenderer {

    private static final int SLOT_SIZE = 40;

    /**
     * Draws a single inventory slot with its item and count text.
     *
     * @param itemStack The item stack to render in the slot
     * @param slotX X position of the slot
     * @param slotY Y position of the slot
     * @param isHotbarSlot Whether this is a hotbar slot (unused in current implementation)
     * @param hotbarIndex Index of hotbar slot (unused in current implementation)
     * @param uiRenderer The UI renderer instance
     * @param renderer The main renderer instance
     */
    public static void drawInventorySlot(ItemStack itemStack, int slotX, int slotY, boolean isHotbarSlot, int hotbarIndex, UIRenderer uiRenderer, Renderer renderer) {
        try {
            try (MemoryStack stack = stackPush()) {
                // Add validation
                if (uiRenderer == null) {
                    System.err.println("ERROR: UIRenderer is null in drawInventorySlot");
                    return;
                }
                long vg = uiRenderer.getVG();
                // Hotbar selection highlight removed - no highlight in inventory screen

                // Slot border
                nvgBeginPath(vg);
                nvgRect(vg, slotX, slotY, SLOT_SIZE, SLOT_SIZE);
                nvgFillColor(vg, nvgRGBA((byte) 100, (byte) 100, (byte) 100, (byte) 255, NVGColor.malloc(stack)));
                nvgFill(vg);

                // Slot background
                nvgBeginPath(vg);
                nvgRect(vg, slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2);
                nvgFillColor(vg, nvgRGBA((byte) 70, (byte) 70, (byte) 70, (byte) 255, NVGColor.malloc(stack)));
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
                            nvgFillColor(vg, nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 200, NVGColor.malloc(stack)));
                            nvgText(vg, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, countText);

                            // Main text
                            nvgFillColor(vg, nvgRGBA((byte) 255, (byte) 220, (byte) 0, (byte) 255, NVGColor.malloc(stack)));
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
}