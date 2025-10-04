package com.stonebreak.rendering.UI.components;

import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import com.stonebreak.ui.hotbar.renderers.*;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Item;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.player.Player;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Specialized renderer component for the hotbar UI element.
 * Handles rendering of hotbar background, slots, items, and tooltips using modular components.
 */
public class HotbarRenderer {

    private long vg;
    private boolean initialized = false;

    // Modular renderer components
    private HotbarBackgroundRenderer backgroundRenderer;
    private HotbarSlotRenderer slotRenderer;
    private HotbarTooltipRenderer tooltipRenderer;
    private HotbarItemCountRenderer itemCountRenderer;
    
    public HotbarRenderer(long vg) {
        this.vg = vg;
        // Initialize modular components
        this.backgroundRenderer = new HotbarBackgroundRenderer(vg);
        this.slotRenderer = new HotbarSlotRenderer(vg);
        this.tooltipRenderer = new HotbarTooltipRenderer(vg);
        this.itemCountRenderer = new HotbarItemCountRenderer(vg);
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
     * Renders the complete hotbar (background, slots, items, tooltips) using modular components.
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

        // Get layout using the new layout calculator
        HotbarLayoutCalculator.HotbarLayout layout = hotbarScreen.calculateLayout(screenWidth, screenHeight);
        ItemStack[] hotbarItems = hotbarScreen.getHotbarSlots();

        // Render hotbar background using modular component
        backgroundRenderer.renderBackground(layout);

        // Render hotbar slots and items using modular components
        for (int i = 0; i < hotbarItems.length; i++) {
            HotbarLayoutCalculator.SlotPosition slotPos = hotbarScreen.calculateSlotPosition(i, layout);
            boolean isSelected = hotbarScreen.getSelectedSlotIndex() == i;

            // Render slot background and effects
            slotRenderer.renderSlot(slotPos, isSelected, false);

            // Render item if present
            if (hotbarItems[i] != null && !hotbarItems[i].isEmpty()) {
                renderItemInSlot(hotbarItems[i], slotPos, textureAtlas, uiRenderer, shaderProgram);

                // Render item count if > 1
                if (hotbarItems[i].getCount() > 1) {
                    itemCountRenderer.renderItemCount(hotbarItems[i].getCount(), slotPos);
                }
            }
        }

        // Render health hearts above the hotbar
        renderHealthHearts(screenWidth, screenHeight, layout);
    }

    /**
     * Renders health hearts above the hotbar.
     */
    private void renderHealthHearts(int screenWidth, int screenHeight, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = com.stonebreak.core.Game.getInstance().getPlayer();
        if (player == null) return;

        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int hearts = (int) Math.ceil(maxHealth / 2.0f);
        float filledHearts = health / 2.0f;

        int heartSize = 16;
        int heartSpacing = 8;
        int totalWidth = hearts * (heartSize + heartSpacing) - heartSpacing;
        int startX = (screenWidth - totalWidth) / 2;
        int startY = layout.backgroundY - 30;

        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < hearts; i++) {
                float x = startX + i * (heartSize + heartSpacing);
                float y = startY;
                float heartFill = Math.max(0, Math.min(1, filledHearts - i));

                nvgBeginPath(vg);
                nvgRect(vg, x, y, heartSize, heartSize);
                nvgFillColor(vg, nvgRGBA((byte)60, (byte)60, (byte)60, (byte)200, NVGColor.malloc(stack)));
                nvgFill(vg);

                if (heartFill > 0) {
                    nvgBeginPath(vg);
                    nvgRect(vg, x, y + (heartSize * (1 - heartFill)), heartSize, heartSize * heartFill);
                    nvgFillColor(vg, nvgRGBA((byte)255, (byte)0, (byte)0, (byte)255, NVGColor.malloc(stack)));
                    nvgFill(vg);
                }

                nvgBeginPath(vg);
                nvgRect(vg, x, y, heartSize, heartSize);
                nvgStrokeColor(vg, nvgRGBA((byte)0, (byte)0, (byte)0, (byte)255, NVGColor.malloc(stack)));
                nvgStrokeWidth(vg, 2.0f);
                nvgStroke(vg);
            }
        }
    }
    
    /**
     * Renders only the hotbar tooltip (for layered rendering) using modular component.
     */
    public void renderHotbarTooltip(HotbarScreen hotbarScreen, int screenWidth, int screenHeight) {
        if (hotbarScreen == null || !hotbarScreen.shouldShowTooltip()) {
            return;
        }

        HotbarLayoutCalculator.HotbarLayout layout = hotbarScreen.calculateLayout(screenWidth, screenHeight);

        // Calculate tooltip position using the modular tooltip renderer
        int selectedSlotIndex = hotbarScreen.getSelectedSlotIndex();
        String tooltipText = hotbarScreen.getTooltipText();
        float alpha = hotbarScreen.getTooltipAlpha();

        HotbarLayoutCalculator.TooltipPosition tooltipPos = tooltipRenderer.calculateTooltipDimensions(
            tooltipText, selectedSlotIndex, layout, screenWidth);

        tooltipRenderer.renderTooltip(tooltipText, tooltipPos, alpha);
    }
    
    /**
     * Renders an item within a hotbar slot using the new layout system.
     */
    private void renderItemInSlot(ItemStack itemStack, HotbarLayoutCalculator.SlotPosition position,
                                TextureAtlas textureAtlas, UIRenderer uiRenderer,
                                ShaderProgram shaderProgram) {
        // Safety checks
        if (itemStack == null || uiRenderer == null || textureAtlas == null || shaderProgram == null) {
            return;
        }

        // Get the item and check if it's renderable
        Item item = itemStack.getItem();
        if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
            try {
                // End NanoVG frame temporarily to draw 3D item
                uiRenderer.endFrame();

                // Calculate icon size and padding using layout calculator
                int iconPadding = HotbarLayoutCalculator.calculateIconPadding();
                int iconSize = HotbarLayoutCalculator.calculateIconSize();

                if (item instanceof BlockType blockType) {
                    // Use UIRenderer's 3D item rendering with calculated dimensions
                    uiRenderer.draw3DItemInSlot(shaderProgram, blockType,
                                              position.x + iconPadding, position.y + iconPadding,
                                              iconSize, iconSize, textureAtlas);
                } else {
                    // For ItemTypes, render a 2D sprite using UIRenderer
                    uiRenderer.renderItemIcon(position.x + iconPadding, position.y + iconPadding,
                                            iconSize, iconSize, item, textureAtlas);
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
     * Cleanup resources used by the hotbar renderer and its modular components.
     */
    public void cleanup() {
        // The modular components don't require explicit cleanup as they don't manage OpenGL resources
        // They only use NanoVG which is managed by the parent renderer
        initialized = false;
    }
}