package com.stonebreak.ui.components.sliders;

import com.stonebreak.rendering.UI.UIRenderer;
import java.util.function.Consumer;

/**
 * Reusable slider component for UI menus that encapsulates slider behavior,
 * rendering, and interaction logic. Supports dragging, hover states, and value callbacks.
 */
public class Slider {
    
    // ===== SLIDER PROPERTIES =====
    private String label;
    private float x, y, width, height;
    private float value;
    private float minValue;
    private float maxValue;
    private boolean isSelected;
    private boolean isHovered;
    private boolean isDragging;
    
    // ===== INTERACTION PROPERTIES =====
    private float sliderAreaHeight;
    private Consumer<Float> onValueChangeAction;
    
    // ===== CONSTRUCTORS =====
    
    /**
     * Creates a new slider with the specified properties.
     * 
     * @param label the slider label text
     * @param x the x position of the slider
     * @param y the y position of the slider
     * @param width the width of the slider
     * @param height the height of the slider track
     * @param minValue the minimum slider value
     * @param maxValue the maximum slider value
     * @param initialValue the initial slider value
     */
    public Slider(String label, float x, float y, float width, float height, 
                  float minValue, float maxValue, float initialValue) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.value = Math.max(minValue, Math.min(maxValue, initialValue));
        this.isSelected = false;
        this.isHovered = false;
        this.isDragging = false;
        this.sliderAreaHeight = height * 3; // Expanded interaction area like in SettingsMenu
        this.onValueChangeAction = null;
    }
    
    /**
     * Creates a new slider with the specified properties and value change action.
     * 
     * @param label the slider label text
     * @param x the x position of the slider
     * @param y the y position of the slider
     * @param width the width of the slider
     * @param height the height of the slider track
     * @param minValue the minimum slider value
     * @param maxValue the maximum slider value
     * @param initialValue the initial slider value
     * @param onValueChangeAction the action to execute when value changes
     */
    public Slider(String label, float x, float y, float width, float height, 
                  float minValue, float maxValue, float initialValue, 
                  Consumer<Float> onValueChangeAction) {
        this(label, x, y, width, height, minValue, maxValue, initialValue);
        this.onValueChangeAction = onValueChangeAction;
    }
    
    // ===== INTERACTION METHODS =====
    
    /**
     * Checks if the specified mouse coordinates are within the slider interaction area.
     * Uses an expanded hit area similar to SettingsMenu's volume area detection.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the mouse is over the slider
     */
    public boolean isMouseOver(float mouseX, float mouseY) {
        float sliderX = x - width / 2;
        float sliderY = y - sliderAreaHeight / 2;
        
        return mouseX >= sliderX && mouseX <= sliderX + width && 
               mouseY >= sliderY && mouseY <= sliderY + sliderAreaHeight;
    }
    
    /**
     * Handles mouse hover state updates.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the slider is hovered
     */
    public boolean updateHover(float mouseX, float mouseY) {
        isHovered = isMouseOver(mouseX, mouseY);
        return isHovered;
    }
    
    /**
     * Handles mouse click events on the slider.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the slider was clicked
     */
    public boolean handleClick(float mouseX, float mouseY) {
        if (isMouseOver(mouseX, mouseY)) {
            startDragging(mouseX);
            return true;
        }
        return false;
    }
    
    /**
     * Starts dragging the slider and sets initial value based on click position.
     * 
     * @param mouseX the mouse x coordinate where dragging started
     */
    public void startDragging(float mouseX) {
        isDragging = true;
        updateValueFromMousePosition(mouseX);
    }
    
    /**
     * Stops dragging the slider.
     */
    public void stopDragging() {
        isDragging = false;
    }
    
    /**
     * Handles mouse dragging when the slider is being dragged.
     * 
     * @param mouseX the mouse x coordinate
     */
    public void handleDragging(float mouseX) {
        if (isDragging) {
            updateValueFromMousePosition(mouseX);
        }
    }
    
    /**
     * Updates slider value based on mouse position along the slider track.
     * 
     * @param mouseX the mouse x coordinate
     */
    private void updateValueFromMousePosition(float mouseX) {
        float sliderX = x - width / 2;
        float relativeX = mouseX - sliderX;
        float normalizedValue = Math.max(0.0f, Math.min(1.0f, relativeX / width));
        float newValue = minValue + (normalizedValue * (maxValue - minValue));
        setValue(newValue);
    }
    
    /**
     * Adjusts the slider value by a step amount.
     * 
     * @param step the amount to adjust the value by
     */
    public void adjustValue(float step) {
        setValue(value + step);
    }
    
    /**
     * Sets the slider value and triggers the change callback if provided.
     * 
     * @param newValue the new slider value
     */
    public void setValue(float newValue) {
        float oldValue = this.value;
        this.value = Math.max(minValue, Math.min(maxValue, newValue));
        
        if (oldValue != this.value && onValueChangeAction != null) {
            onValueChangeAction.accept(this.value);
        }
    }
    
    /**
     * Gets the normalized value (0.0 to 1.0) for rendering purposes.
     * 
     * @return normalized value between 0.0 and 1.0
     */
    public float getNormalizedValue() {
        if (maxValue == minValue) return 0.0f;
        return (value - minValue) / (maxValue - minValue);
    }
    
    // ===== RENDERING =====
    
    /**
     * Renders the slider using the specified UI renderer.
     * 
     * @param uiRenderer the UI renderer to use for drawing
     */
    public void render(UIRenderer uiRenderer) {
        uiRenderer.drawVolumeSlider(label, x, y, width, height, getNormalizedValue(), isSelected);
    }
    
    // ===== GETTERS AND SETTERS =====
    
    /**
     * Gets the slider label.
     * 
     * @return the slider label
     */
    public String getLabel() {
        return label;
    }
    
    /**
     * Sets the slider label.
     * 
     * @param label the new slider label
     */
    public void setLabel(String label) {
        this.label = label;
    }
    
    /**
     * Gets the slider x position.
     * 
     * @return the x position
     */
    public float getX() {
        return x;
    }
    
    /**
     * Sets the slider x position.
     * 
     * @param x the new x position
     */
    public void setX(float x) {
        this.x = x;
    }
    
    /**
     * Gets the slider y position.
     * 
     * @return the y position
     */
    public float getY() {
        return y;
    }
    
    /**
     * Sets the slider y position.
     * 
     * @param y the new y position
     */
    public void setY(float y) {
        this.y = y;
    }
    
    /**
     * Gets the slider width.
     * 
     * @return the slider width
     */
    public float getWidth() {
        return width;
    }
    
    /**
     * Sets the slider width.
     * 
     * @param width the new slider width
     */
    public void setWidth(float width) {
        this.width = width;
    }
    
    /**
     * Gets the slider height.
     * 
     * @return the slider height
     */
    public float getHeight() {
        return height;
    }
    
    /**
     * Sets the slider height.
     * 
     * @param height the new slider height
     */
    public void setHeight(float height) {
        this.height = height;
    }
    
    /**
     * Gets the current slider value.
     * 
     * @return the current value
     */
    public float getValue() {
        return value;
    }
    
    /**
     * Gets the minimum slider value.
     * 
     * @return the minimum value
     */
    public float getMinValue() {
        return minValue;
    }
    
    /**
     * Sets the minimum slider value.
     * 
     * @param minValue the new minimum value
     */
    public void setMinValue(float minValue) {
        this.minValue = minValue;
        if (value < minValue) {
            setValue(minValue);
        }
    }
    
    /**
     * Gets the maximum slider value.
     * 
     * @return the maximum value
     */
    public float getMaxValue() {
        return maxValue;
    }
    
    /**
     * Sets the maximum slider value.
     * 
     * @param maxValue the new maximum value
     */
    public void setMaxValue(float maxValue) {
        this.maxValue = maxValue;
        if (value > maxValue) {
            setValue(maxValue);
        }
    }
    
    /**
     * Checks if the slider is currently selected.
     * 
     * @return true if selected
     */
    public boolean isSelected() {
        return isSelected;
    }
    
    /**
     * Sets the slider selection state.
     * 
     * @param selected true to select the slider
     */
    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }
    
    /**
     * Checks if the slider is currently hovered.
     * 
     * @return true if hovered
     */
    public boolean isHovered() {
        return isHovered;
    }
    
    /**
     * Sets the slider hover state.
     * 
     * @param hovered true to set as hovered
     */
    public void setHovered(boolean hovered) {
        this.isHovered = hovered;
    }
    
    /**
     * Checks if the slider is currently being dragged.
     * 
     * @return true if dragging
     */
    public boolean isDragging() {
        return isDragging;
    }
    
    /**
     * Sets the slider dragging state.
     * 
     * @param dragging true to set as dragging
     */
    public void setDragging(boolean dragging) {
        this.isDragging = dragging;
    }
    
    /**
     * Gets the slider's value change action.
     * 
     * @return the value change action consumer
     */
    public Consumer<Float> getOnValueChangeAction() {
        return onValueChangeAction;
    }
    
    /**
     * Sets the slider's value change action.
     * 
     * @param onValueChangeAction the action to execute on value change
     */
    public void setOnValueChangeAction(Consumer<Float> onValueChangeAction) {
        this.onValueChangeAction = onValueChangeAction;
    }
    
    /**
     * Sets the slider position.
     * 
     * @param x the new x position
     * @param y the new y position
     */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    /**
     * Sets the slider size.
     * 
     * @param width the new width
     * @param height the new height
     */
    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }
    
    /**
     * Sets the value range for the slider.
     * 
     * @param minValue the new minimum value
     * @param maxValue the new maximum value
     */
    public void setRange(float minValue, float maxValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        setValue(value); // Clamp current value to new range
    }
    
    /**
     * Sets all slider bounds at once.
     * 
     * @param x the new x position
     * @param y the new y position
     * @param width the new width
     * @param height the new height
     */
    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}