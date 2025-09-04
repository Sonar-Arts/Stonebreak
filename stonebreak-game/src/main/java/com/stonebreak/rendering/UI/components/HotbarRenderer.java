package com.stonebreak.rendering.UI.components;

import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Item;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer component for the hotbar UI element.
 * Handles rendering of hotbar background, slots, items, and tooltips.
 */
public class HotbarRenderer {
    
    private long vg;
    private boolean initialized = false;
    
    // Hotbar visual constants - use the same as InventoryScreen for consistency
    private static final int INVENTORY_SLOT_SIZE = 40;
    private static final int INVENTORY_SLOT_PADDING = 5;
    
    public HotbarRenderer(long vg) {
        this.vg = vg;
    }
    
    /**
     * Initializes the hotbar renderer with OpenGL resources.
     */
    public void initialize() {
        if (initialized) return;
        
        // HotbarRenderer uses NanoVG for rendering, no OpenGL VAO/VBO needed
        initialized = true;
    }
    
    /**
     * Renders the complete hotbar (background, slots, items, tooltips).
     */
    public void renderHotbar(HotbarScreen hotbarScreen, int screenWidth, int screenHeight, 
                           TextureAtlas textureAtlas, UIRenderer uiRenderer,
                           ShaderProgram shaderProgram) {
        if (!initialized) {
            initialize();
        }
        
        // Safety checks
        if (hotbarScreen == null || uiRenderer == null) {
            return;
        }
        
        // Get layout from HotbarScreen for consistency
        HotbarScreen.HotbarLayout layout = hotbarScreen.calculateLayout(screenWidth, screenHeight);
        ItemStack[] hotbarItems = hotbarScreen.getHotbarSlots();
        
        // Render hotbar background
        renderHotbarBackground(layout.startX, layout.startY - HotbarScreen.SLOT_PADDING, 
                             layout.width, layout.height);
        
        // Render hotbar slots and items
        for (int i = 0; i < hotbarItems.length; i++) {
            HotbarScreen.SlotPosition slotPos = hotbarScreen.calculateSlotPosition(i, layout);
            renderHotbarSlot(hotbarItems[i], slotPos.x, slotPos.y, 
                           hotbarScreen.getSelectedSlotIndex() == i,
                           textureAtlas, uiRenderer, shaderProgram);
        }
    }
    
    /**
     * Renders only the hotbar tooltip (for layered rendering).
     */
    public void renderHotbarTooltip(HotbarScreen hotbarScreen, int screenWidth, int screenHeight) {
        if (hotbarScreen == null || !hotbarScreen.shouldShowTooltip()) {
            return;
        }
        
        HotbarScreen.HotbarLayout layout = hotbarScreen.calculateLayout(screenWidth, screenHeight);
        
        // Calculate tooltip position above selected slot
        int selectedSlotIndex = hotbarScreen.getSelectedSlotIndex();
        HotbarScreen.SlotPosition slotPos = hotbarScreen.calculateSlotPosition(selectedSlotIndex, layout);
        float selectedSlotCenterX = slotPos.x + HotbarScreen.SLOT_SIZE / 2.0f;
        
        renderTooltip(hotbarScreen.getTooltipText(), selectedSlotCenterX, 
                     layout.startY - 10, screenWidth, hotbarScreen.getTooltipAlpha());
    }
    
    /**
     * Renders the hotbar background using NanoVG.
     */
    private void renderHotbarBackground(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, 8);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 200, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, 8);
            nvgStrokeColor(vg, nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 2);
            nvgStroke(vg);
        }
    }
    
    /**
     * Renders a single hotbar slot with its item.
     */
    private void renderHotbarSlot(ItemStack itemStack, int slotX, int slotY, boolean isSelected,
                                TextureAtlas textureAtlas, UIRenderer uiRenderer,
                                ShaderProgram shaderProgram) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            
            // Render slot background
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, HotbarScreen.SLOT_SIZE, HotbarScreen.SLOT_SIZE);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 180, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Render selection highlight
            if (isSelected) {
                nvgBeginPath(vg);
                nvgRect(vg, slotX - 2, slotY - 2, HotbarScreen.SLOT_SIZE + 4, HotbarScreen.SLOT_SIZE + 4);
                nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                nvgStrokeWidth(vg, 2);
                nvgStroke(vg);
            }
            
            // Render slot border
            nvgBeginPath(vg);
            nvgRect(vg, slotX, slotY, HotbarScreen.SLOT_SIZE, HotbarScreen.SLOT_SIZE);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 1);
            nvgStroke(vg);
            
            // Render item if present
            if (itemStack != null && !itemStack.isEmpty()) {
                renderItemInSlot(itemStack, slotX, slotY, textureAtlas, uiRenderer, shaderProgram);
                
                // Render item count if > 1
                if (itemStack.getCount() > 1) {
                    renderItemCount(itemStack.getCount(), slotX, slotY);
                }
            }
        }
    }
    
    /**
     * Renders an item within a hotbar slot.
     */
    private void renderItemInSlot(ItemStack itemStack, int slotX, int slotY,
                                TextureAtlas textureAtlas, UIRenderer uiRenderer,
                                ShaderProgram shaderProgram) {
        // Safety checks
        if (itemStack == null || uiRenderer == null || textureAtlas == null || shaderProgram == null) {
            return;
        }
        
        // Get the item and check if it's a BlockType
        Item item = itemStack.getItem();
        if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
            try {
                // End NanoVG frame temporarily to draw 3D item (same pattern as InventoryScreen)
                uiRenderer.endFrame();
                
                if (item instanceof BlockType blockType) {
                    // Use UIRenderer's 3D item rendering (same as InventoryScreen)
                    uiRenderer.draw3DItemInSlot(shaderProgram, blockType,
                                              slotX + 2, slotY + 2, HotbarScreen.SLOT_SIZE - 4, HotbarScreen.SLOT_SIZE - 4,
                                              textureAtlas);
                } else {
                    // For ItemTypes, render a 2D sprite using UIRenderer
                    uiRenderer.renderItemIcon(slotX + 2, slotY + 2, HotbarScreen.SLOT_SIZE - 4, HotbarScreen.SLOT_SIZE - 4,
                                            item, textureAtlas);
                }
                
                // Restart NanoVG frame
                uiRenderer.beginFrame(com.stonebreak.core.Game.getWindowWidth(), 
                                    com.stonebreak.core.Game.getWindowHeight(), 1.0f);
                
            } catch (Exception e) {
                // Try to recover by ensuring frame is restarted
                try {
                    uiRenderer.beginFrame(com.stonebreak.core.Game.getWindowWidth(), 
                                        com.stonebreak.core.Game.getWindowHeight(), 1.0f);
                } catch (Exception e2) {
                    System.err.println("Failed to recover NanoVG frame: " + e2.getMessage());
                }
                System.err.println("Error rendering hotbar item: " + e.getMessage());
            }
        }
    }
    
    /**
     * Renders the item count number in the corner of a slot.
     */
    private void renderItemCount(int count, int slotX, int slotY) {
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            
            String countText = String.valueOf(count);
            float textX = slotX + HotbarScreen.SLOT_SIZE - 8;
            float textY = slotY + HotbarScreen.SLOT_SIZE - 4;
            
            nvgFontFace(vg, "sans");
            nvgFontSize(vg, 12);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            nvgText(vg, textX, textY, countText);
        }
    }
    
    /**
     * Renders a tooltip above the hotbar.
     */
    private void renderTooltip(String text, float centerX, float y, int screenWidth, float alpha) {
        if (text == null || alpha <= 0) return;
        
        try (MemoryStack stack = stackPush()) {
            NVGColor color = NVGColor.malloc(stack);
            
            // Calculate tooltip dimensions
            nvgFontFace(vg, "sans");
            nvgFontSize(vg, 14);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            float[] bounds = new float[4];
            nvgTextBounds(vg, 0, 0, text, bounds);
            float tooltipWidth = bounds[2] - bounds[0] + 16;
            float tooltipHeight = bounds[3] - bounds[1] + 12;
            
            // Position tooltip
            float tooltipX = centerX - tooltipWidth / 2.0f;
            float tooltipY = y - tooltipHeight - 10.0f;
            
            // Keep tooltip within screen bounds
            if (tooltipX < 10) tooltipX = 10;
            if (tooltipX + tooltipWidth > screenWidth - 10) {
                tooltipX = screenWidth - tooltipWidth - 10;
            }
            
            // Render tooltip background
            int bgAlpha = (int)(alpha * 220);
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, 4);
            nvgFillColor(vg, nvgRGBA(30, 30, 30, bgAlpha, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Render tooltip border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, tooltipX, tooltipY, tooltipWidth, tooltipHeight, 4);
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, (int)(alpha * 255), NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 1);
            nvgStroke(vg);
            
            // Render tooltip text
            nvgFillColor(vg, nvgRGBA(255, 255, 255, (int)(alpha * 255), NVGColor.malloc(stack)));
            nvgText(vg, tooltipX + tooltipWidth / 2, tooltipY + tooltipHeight / 2, text);
        }
    }
    
    
    /**
     * Helper method to create NVGColor with proper byte casting.
     */
    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        initialized = false;
    }
}