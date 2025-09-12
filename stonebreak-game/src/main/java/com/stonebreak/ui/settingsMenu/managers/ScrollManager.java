package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.ui.settingsMenu.config.SettingsConfig;

/**
 * Manages scroll state and bounds for the scrollable settings container.
 * Handles smooth scrolling, bounds checking, and scroll offset calculations.
 */
public class ScrollManager {
    
    // Scroll state
    private float scrollOffset;
    private float viewportHeight;
    private float contentHeight;
    private float maxScrollOffset;
    
    // Smooth scrolling
    private float targetScrollOffset;
    private float scrollVelocity;
    
    public ScrollManager() {
        reset();
    }
    
    /**
     * Resets scroll state to default values.
     */
    public void reset() {
        this.scrollOffset = 0;
        this.targetScrollOffset = 0;
        this.scrollVelocity = 0;
        this.viewportHeight = 0;
        this.contentHeight = 0;
        this.maxScrollOffset = 0;
    }
    
    /**
     * Updates viewport and content bounds, recalculating scroll limits.
     */
    public void updateBounds(float viewportHeight, float contentHeight) {
        this.viewportHeight = viewportHeight;
        this.contentHeight = contentHeight;
        
        // Calculate maximum scroll offset
        this.maxScrollOffset = Math.max(0, contentHeight - viewportHeight + SettingsConfig.SCROLL_CONTENT_PADDING * 2);
        
        // Clamp current scroll to new bounds
        clampScroll();
    }
    
    /**
     * Handles mouse wheel scrolling with smooth animation.
     */
    public void handleScrollWheel(float scrollDelta) {
        // Convert scroll delta to offset change
        float scrollAmount = scrollDelta * SettingsConfig.SCROLL_WHEEL_SENSITIVITY;
        
        // Update target scroll position
        targetScrollOffset -= scrollAmount;
        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxScrollOffset));
        
        // Add velocity for smooth animation
        scrollVelocity = scrollAmount * SettingsConfig.SCROLL_VELOCITY_FACTOR;
    }
    
    /**
     * Updates smooth scrolling animation.
     * Call this every frame to animate scroll transitions.
     */
    public void update(float deltaTime) {
        if (Math.abs(targetScrollOffset - scrollOffset) > 0.5f) {
            // Smooth interpolation towards target
            float lerpFactor = Math.min(1.0f, deltaTime * SettingsConfig.SCROLL_LERP_SPEED);
            scrollOffset += (targetScrollOffset - scrollOffset) * lerpFactor;
            
            // Apply velocity for more responsive feel
            scrollOffset += scrollVelocity * deltaTime;
            
            // Decay velocity
            scrollVelocity *= SettingsConfig.SCROLL_VELOCITY_DECAY;
            
            // Clamp to bounds
            clampScroll();
        } else {
            // Snap to target when close enough
            scrollOffset = targetScrollOffset;
            scrollVelocity = 0;
        }
    }
    
    /**
     * Clamps scroll offset to valid bounds.
     */
    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScrollOffset));
        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxScrollOffset));
    }
    
    /**
     * Instantly scrolls to a specific offset.
     */
    public void scrollTo(float offset) {
        scrollOffset = Math.max(0, Math.min(offset, maxScrollOffset));
        targetScrollOffset = scrollOffset;
        scrollVelocity = 0;
    }
    
    /**
     * Scrolls to ensure a specific item is visible within the viewport.
     */
    public void scrollToItem(int itemIndex, float itemSpacing) {
        if (itemIndex < 0) return;
        
        float itemY = itemIndex * itemSpacing;
        float itemBottom = itemY + SettingsConfig.BUTTON_HEIGHT;
        
        // Check if item is above viewport
        if (itemY < scrollOffset) {
            scrollTo(itemY - SettingsConfig.SCROLL_CONTENT_PADDING);
        }
        // Check if item is below viewport
        else if (itemBottom > scrollOffset + viewportHeight - SettingsConfig.SCROLL_CONTENT_PADDING) {
            scrollTo(itemBottom - viewportHeight + SettingsConfig.SCROLL_CONTENT_PADDING * 2);
        }
    }
    
    /**
     * Checks if scrolling is needed (content exceeds viewport).
     */
    public boolean isScrollNeeded() {
        return contentHeight > viewportHeight - SettingsConfig.SCROLL_CONTENT_PADDING * 2;
    }
    
    /**
     * Gets the current scroll offset.
     */
    public float getScrollOffset() {
        return scrollOffset;
    }
    
    /**
     * Gets the maximum possible scroll offset.
     */
    public float getMaxScrollOffset() {
        return maxScrollOffset;
    }
    
    /**
     * Gets the viewport height.
     */
    public float getViewportHeight() {
        return viewportHeight;
    }
    
    /**
     * Gets the total content height.
     */
    public float getContentHeight() {
        return contentHeight;
    }
    
    /**
     * Gets the scroll progress as a percentage (0.0 to 1.0).
     */
    public float getScrollProgress() {
        if (maxScrollOffset <= 0) return 0;
        return scrollOffset / maxScrollOffset;
    }
    
    /**
     * Checks if currently at the top of the scroll area.
     */
    public boolean isAtTop() {
        return scrollOffset <= 0.5f;
    }
    
    /**
     * Checks if currently at the bottom of the scroll area.
     */
    public boolean isAtBottom() {
        return scrollOffset >= maxScrollOffset - 0.5f;
    }
}