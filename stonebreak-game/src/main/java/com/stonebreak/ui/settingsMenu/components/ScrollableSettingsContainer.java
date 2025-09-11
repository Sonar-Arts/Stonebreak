package com.stonebreak.ui.settingsMenu.components;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.ScrollManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Scrollable container for settings items with viewport clipping and scrollbar.
 * Handles rendering of settings within a bounded viewport with smooth scrolling.
 */
public class ScrollableSettingsContainer {
    
    private final StateManager stateManager;
    private final ScrollManager scrollManager;
    
    // Container bounds
    private float containerX;
    private float containerY;
    private float containerWidth;
    private float containerHeight;
    
    // Content properties
    private float contentHeight;
    private float itemSpacing;
    
    public ScrollableSettingsContainer(StateManager stateManager, ScrollManager scrollManager) {
        this.stateManager = stateManager;
        this.scrollManager = scrollManager;
        this.itemSpacing = SettingsConfig.SCROLL_ITEM_SPACING;
    }
    
    /**
     * Updates container bounds and content dimensions.
     */
    public void updateBounds(float centerX, float centerY, float panelHeight) {
        // Calculate container dimensions
        this.containerWidth = SettingsConfig.SETTINGS_PANEL_WIDTH;
        this.containerHeight = panelHeight - SettingsConfig.SCROLL_TOP_MARGIN - SettingsConfig.SCROLL_BOTTOM_MARGIN;
        this.containerX = centerX + SettingsConfig.SETTINGS_PANEL_X_OFFSET - containerWidth / 2;
        this.containerY = centerY - panelHeight / 2 + SettingsConfig.SCROLL_TOP_MARGIN;
        
        // Calculate total content height
        CategoryState selectedCategory = stateManager.getSelectedCategory();
        int settingsCount = selectedCategory.getSettings().length;
        this.contentHeight = Math.max(0, (settingsCount - 1) * itemSpacing + SettingsConfig.BUTTON_HEIGHT);
        
        // Update scroll manager bounds
        scrollManager.updateBounds(containerHeight, contentHeight);
    }
    
    /**
     * Renders the scrollable container with settings items and scrollbar.
     */
    public void render(UIRenderer uiRenderer) {
        long vg = uiRenderer.getVG();
        
        // Set up viewport clipping
        nvgSave(vg);
        nvgScissor(vg, containerX, containerY, containerWidth, containerHeight);
        
        // Render container background (subtle)
        renderContainerBackground(vg);
        
        // Render settings items with scroll offset
        renderScrollableContent(uiRenderer);
        
        // Restore clipping
        nvgRestore(vg);
        
        // Render scrollbar (outside clipping area)
        if (scrollManager.isScrollNeeded()) {
            renderScrollbar(vg);
        }
    }
    
    /**
     * Renders a subtle background for the scrollable area.
     */
    private void renderContainerBackground(long vg) {
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, containerX + 5, containerY + 5, containerWidth - 10, containerHeight - 10);
            nvgFillColor(vg, nvgRGBA((byte)0, (byte)0, (byte)0, (byte)15, NVGColor.malloc(stack))); // Very subtle background
            nvgFill(vg);
        }
    }
    
    /**
     * Renders settings items within the scrollable viewport.
     */
    private void renderScrollableContent(UIRenderer uiRenderer) {
        CategoryState selectedCategory = stateManager.getSelectedCategory();
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        
        float scrollOffset = scrollManager.getScrollOffset();
        float startY = containerY - scrollOffset + SettingsConfig.SCROLL_CONTENT_PADDING;
        float settingsX = containerX + containerWidth / 2;
        
        // Update button texts before rendering
        updateSettingTexts();
        
        // Position and render each setting item
        for (int i = 0; i < settings.length; i++) {
            float currentY = startY + (i * itemSpacing);
            
            // Cull items outside viewport (with small buffer for smooth scrolling)
            float cullBuffer = 50;
            if (currentY + SettingsConfig.BUTTON_HEIGHT < containerY - cullBuffer || 
                currentY > containerY + containerHeight + cullBuffer) {
                continue;
            }
            
            // Position and render setting
            positionAndRenderSetting(settings[i], settingsX, currentY);
        }
    }
    
    /**
     * Updates button texts for display based on current settings.
     */
    private void updateSettingTexts() {
        // Update button texts to show current values
        com.stonebreak.config.Settings settings = com.stonebreak.config.Settings.getInstance();
        
        String resolutionText = "Resolution: " + settings.getCurrentResolutionString();
        stateManager.getResolutionButton().setText(resolutionText);
        
        String currentArmModelName = SettingsConfig.ARM_MODEL_NAMES[stateManager.getSelectedArmModelIndex()];
        String armModelText = "Arm Model: " + currentArmModelName;
        stateManager.getArmModelButton().setText(armModelText);
        
        String currentStyleName = SettingsConfig.CROSSHAIR_STYLE_NAMES[stateManager.getSelectedCrosshairStyleIndex()];
        String crosshairStyleText = "Crosshair: " + currentStyleName;
        stateManager.getCrosshairStyleButton().setText(crosshairStyleText);
    }
    
    /**
     * Positions and renders individual setting components.
     */
    private void positionAndRenderSetting(CategoryState.SettingType setting, float x, float y) {
        switch (setting) {
            case RESOLUTION:
                stateManager.getResolutionButton().setPosition(x - SettingsConfig.BUTTON_WIDTH/2, y);
                stateManager.getResolutionButton().render(stateManager.getUIRenderer());
                break;
            case VOLUME:
                stateManager.getVolumeSlider().setPosition(x, y);
                stateManager.getVolumeSlider().render(stateManager.getUIRenderer());
                break;
            case ARM_MODEL:
                stateManager.getArmModelButton().setPosition(x - SettingsConfig.BUTTON_WIDTH/2, y);
                stateManager.getArmModelButton().render(stateManager.getUIRenderer());
                break;
            case CROSSHAIR_STYLE:
                stateManager.getCrosshairStyleButton().setPosition(x - SettingsConfig.BUTTON_WIDTH/2, y);
                stateManager.getCrosshairStyleButton().render(stateManager.getUIRenderer());
                break;
            case CROSSHAIR_SIZE:
                stateManager.getCrosshairSizeSlider().setPosition(x, y);
                stateManager.getCrosshairSizeSlider().render(stateManager.getUIRenderer());
                break;
        }
    }
    
    /**
     * Renders the scrollbar when content exceeds viewport.
     */
    private void renderScrollbar(long vg) {
        try (MemoryStack stack = stackPush()) {
            float scrollbarX = containerX + containerWidth - SettingsConfig.SCROLLBAR_WIDTH - 5;
            float scrollbarY = containerY + 5;
            float scrollbarHeight = containerHeight - 10;
            
            // Scrollbar track
            nvgBeginPath(vg);
            nvgRect(vg, scrollbarX, scrollbarY, SettingsConfig.SCROLLBAR_WIDTH, scrollbarHeight);
            nvgFillColor(vg, nvgRGBA((byte)60, (byte)60, (byte)60, (byte)180, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Scrollbar thumb
            float thumbHeight = Math.max(20, scrollbarHeight * (containerHeight / contentHeight));
            float thumbY = scrollbarY + (scrollManager.getScrollOffset() / scrollManager.getMaxScrollOffset()) * 
                          (scrollbarHeight - thumbHeight);
            
            nvgBeginPath(vg);
            nvgRect(vg, scrollbarX + 2, thumbY, SettingsConfig.SCROLLBAR_WIDTH - 4, thumbHeight);
            nvgFillColor(vg, nvgRGBA((byte)140, (byte)140, (byte)140, (byte)220, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Scrollbar thumb border
            nvgBeginPath(vg);
            nvgRect(vg, scrollbarX + 2, thumbY, SettingsConfig.SCROLLBAR_WIDTH - 4, thumbHeight);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA((byte)180, (byte)180, (byte)180, (byte)255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
    
    /**
     * Handles mouse wheel scrolling within the container area.
     */
    public boolean handleMouseWheel(float mouseX, float mouseY, float scrollDelta) {
        // Check if mouse is within container bounds
        if (mouseX >= containerX && mouseX <= containerX + containerWidth &&
            mouseY >= containerY && mouseY <= containerY + containerHeight) {
            
            scrollManager.handleScrollWheel(scrollDelta);
            return true;
        }
        return false;
    }
    
    /**
     * Gets container bounds for external positioning calculations.
     */
    public float getContainerBottom() {
        return containerY + containerHeight;
    }
    
    public float getContainerCenterX() {
        return containerX + containerWidth / 2;
    }
}