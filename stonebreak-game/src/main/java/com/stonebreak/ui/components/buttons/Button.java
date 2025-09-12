package com.stonebreak.ui.components.buttons;

import com.stonebreak.rendering.UI.UIRenderer;

/**
 * Reusable button component for UI menus that encapsulates button behavior,
 * rendering, and interaction logic. Supports hover states, selection, and click handling.
 */
public class Button {
    
    // ===== BUTTON PROPERTIES =====
    private String text;
    private float x, y, width, height;
    private boolean isSelected;
    private boolean isHovered;
    private Runnable onClickAction;
    
    // ===== CONSTRUCTORS =====
    
    /**
     * Creates a new button with the specified properties.
     * 
     * @param text the button label text
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     */
    public Button(String text, float x, float y, float width, float height) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.isSelected = false;
        this.isHovered = false;
        this.onClickAction = null;
    }
    
    /**
     * Creates a new button with the specified properties and click action.
     * 
     * @param text the button label text
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     * @param onClickAction the action to execute when clicked
     */
    public Button(String text, float x, float y, float width, float height, Runnable onClickAction) {
        this(text, x, y, width, height);
        this.onClickAction = onClickAction;
    }
    
    // ===== INTERACTION METHODS =====
    
    /**
     * Checks if the specified mouse coordinates are within the button bounds.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the mouse is over the button
     */
    public boolean isMouseOver(float mouseX, float mouseY) {
        return mouseX >= x && mouseX <= x + width && 
               mouseY >= y && mouseY <= y + height;
    }
    
    /**
     * Handles mouse hover state updates.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the button is hovered
     */
    public boolean updateHover(float mouseX, float mouseY) {
        isHovered = isMouseOver(mouseX, mouseY);
        return isHovered;
    }
    
    /**
     * Handles mouse click events on the button.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the button was clicked
     */
    public boolean handleClick(float mouseX, float mouseY) {
        if (isMouseOver(mouseX, mouseY)) {
            onClick();
            return true;
        }
        return false;
    }
    
    /**
     * Executes the button's click action if one is set.
     */
    public void onClick() {
        if (onClickAction != null) {
            onClickAction.run();
        }
    }
    
    // ===== RENDERING =====
    
    /**
     * Renders the button using the specified UI renderer.
     * 
     * @param uiRenderer the UI renderer to use for drawing
     */
    public void render(UIRenderer uiRenderer) {
        uiRenderer.drawButton(text, x, y, width, height, isSelected);
    }
    
    // ===== GETTERS AND SETTERS =====
    
    /**
     * Gets the button text.
     * 
     * @return the button text
     */
    public String getText() {
        return text;
    }
    
    /**
     * Sets the button text.
     * 
     * @param text the new button text
     */
    public void setText(String text) {
        this.text = text;
    }
    
    /**
     * Gets the button x position.
     * 
     * @return the x position
     */
    public float getX() {
        return x;
    }
    
    /**
     * Sets the button x position.
     * 
     * @param x the new x position
     */
    public void setX(float x) {
        this.x = x;
    }
    
    /**
     * Gets the button y position.
     * 
     * @return the y position
     */
    public float getY() {
        return y;
    }
    
    /**
     * Sets the button y position.
     * 
     * @param y the new y position
     */
    public void setY(float y) {
        this.y = y;
    }
    
    /**
     * Gets the button width.
     * 
     * @return the button width
     */
    public float getWidth() {
        return width;
    }
    
    /**
     * Sets the button width.
     * 
     * @param width the new button width
     */
    public void setWidth(float width) {
        this.width = width;
    }
    
    /**
     * Gets the button height.
     * 
     * @return the button height
     */
    public float getHeight() {
        return height;
    }
    
    /**
     * Sets the button height.
     * 
     * @param height the new button height
     */
    public void setHeight(float height) {
        this.height = height;
    }
    
    /**
     * Checks if the button is currently selected.
     * 
     * @return true if selected
     */
    public boolean isSelected() {
        return isSelected;
    }
    
    /**
     * Sets the button selection state.
     * 
     * @param selected true to select the button
     */
    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }
    
    /**
     * Checks if the button is currently hovered.
     * 
     * @return true if hovered
     */
    public boolean isHovered() {
        return isHovered;
    }
    
    /**
     * Sets the button hover state.
     * 
     * @param hovered true to set as hovered
     */
    public void setHovered(boolean hovered) {
        this.isHovered = hovered;
    }
    
    /**
     * Gets the button's click action.
     * 
     * @return the click action runnable
     */
    public Runnable getOnClickAction() {
        return onClickAction;
    }
    
    /**
     * Sets the button's click action.
     * 
     * @param onClickAction the action to execute on click
     */
    public void setOnClickAction(Runnable onClickAction) {
        this.onClickAction = onClickAction;
    }
    
    /**
     * Sets the button position.
     * 
     * @param x the new x position
     * @param y the new y position
     */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    /**
     * Sets the button size.
     * 
     * @param width the new width
     * @param height the new height
     */
    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }
    
    /**
     * Sets all button bounds at once.
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