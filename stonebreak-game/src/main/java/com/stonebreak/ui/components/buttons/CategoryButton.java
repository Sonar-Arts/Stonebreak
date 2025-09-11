package com.stonebreak.ui.components.buttons;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;

/**
 * Specialized button component for category navigation in the settings menu.
 * Displays category names and handles category selection for the left panel.
 */
public class CategoryButton {
    
    // ===== BUTTON PROPERTIES =====
    private final CategoryState category;
    private float x, y, width, height;
    private boolean isSelected;
    private boolean isHovered;
    private Runnable onClickAction;
    
    // ===== CONSTRUCTORS =====
    
    /**
     * Creates a new category button with the specified properties.
     * 
     * @param category the category this button represents
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     */
    public CategoryButton(CategoryState category, float x, float y, float width, float height) {
        this.category = category;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.isSelected = false;
        this.isHovered = false;
        this.onClickAction = null;
    }
    
    /**
     * Creates a new category button with the specified properties and click action.
     * 
     * @param category the category this button represents
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     * @param onClickAction the action to execute when clicked
     */
    public CategoryButton(CategoryState category, float x, float y, float width, float height, Runnable onClickAction) {
        this(category, x, y, width, height);
        this.onClickAction = onClickAction;
    }
    
    // ===== RENDERING =====
    
    /**
     * Renders the category button using the provided UI renderer.
     * Displays the category name with appropriate selection and hover states.
     * 
     * @param uiRenderer the renderer to use for drawing
     */
    public void render(UIRenderer uiRenderer) {
        // Render the button background with appropriate state styling
        // UIRenderer.drawButton expects: (String text, float x, float y, float w, float h, boolean highlighted)
        boolean highlighted = isSelected || isHovered;
        uiRenderer.drawButton(category.getDisplayName(), x, y, width, height, highlighted);
    }
    
    // ===== INTERACTION =====
    
    /**
     * Handles click events for this button.
     * Executes the click action if one is set.
     */
    public void onClick() {
        if (onClickAction != null) {
            onClickAction.run();
        }
    }
    
    /**
     * Checks if a point is within this button's bounds.
     * 
     * @param pointX the x coordinate to check
     * @param pointY the y coordinate to check
     * @return true if the point is within the button bounds
     */
    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }
    
    // ===== GETTERS AND SETTERS =====
    
    public CategoryState getCategory() {
        return category;
    }
    
    public String getText() {
        return category.getDisplayName();
    }
    
    public float getX() {
        return x;
    }
    
    public float getY() {
        return y;
    }
    
    public float getWidth() {
        return width;
    }
    
    public float getHeight() {
        return height;
    }
    
    public boolean isSelected() {
        return isSelected;
    }
    
    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }
    
    public boolean isHovered() {
        return isHovered;
    }
    
    public void setHovered(boolean hovered) {
        this.isHovered = hovered;
    }
    
    public Runnable getOnClickAction() {
        return onClickAction;
    }
    
    public void setOnClickAction(Runnable onClickAction) {
        this.onClickAction = onClickAction;
    }
    
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }
}