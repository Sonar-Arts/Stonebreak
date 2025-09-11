package com.stonebreak.ui.components.buttons;

import com.stonebreak.rendering.UI.UIRenderer;

/**
 * A specialized button component that displays a dropdown menu when clicked.
 * Supports keyboard navigation, mouse interaction, and customizable dropdown items.
 */
public class DropdownButton extends Button {
    
    // ===== DROPDOWN PROPERTIES =====
    private String[] dropdownItems;
    private int selectedItemIndex;
    private boolean isDropdownOpen;
    private float dropdownItemHeight;
    private Runnable onSelectionChangeAction;
    
    // ===== CONSTRUCTORS =====
    
    /**
     * Creates a new dropdown button with the specified properties.
     * 
     * @param text the button label text
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     * @param dropdownItems the array of dropdown menu items
     * @param dropdownItemHeight the height of each dropdown item
     */
    public DropdownButton(String text, float x, float y, float width, float height, 
                         String[] dropdownItems, float dropdownItemHeight) {
        super(text, x, y, width, height);
        this.dropdownItems = dropdownItems;
        this.selectedItemIndex = 0;
        this.isDropdownOpen = false;
        this.dropdownItemHeight = dropdownItemHeight;
        this.onSelectionChangeAction = null;
    }
    
    /**
     * Creates a new dropdown button with the specified properties and selection change action.
     * 
     * @param text the button label text
     * @param x the x position of the button
     * @param y the y position of the button
     * @param width the width of the button
     * @param height the height of the button
     * @param dropdownItems the array of dropdown menu items
     * @param dropdownItemHeight the height of each dropdown item
     * @param onSelectionChangeAction the action to execute when selection changes
     */
    public DropdownButton(String text, float x, float y, float width, float height, 
                         String[] dropdownItems, float dropdownItemHeight, 
                         Runnable onSelectionChangeAction) {
        this(text, x, y, width, height, dropdownItems, dropdownItemHeight);
        this.onSelectionChangeAction = onSelectionChangeAction;
    }
    
    // ===== DROPDOWN INTERACTION =====
    
    /**
     * Toggles the dropdown menu open/closed state.
     */
    public void toggleDropdown() {
        isDropdownOpen = !isDropdownOpen;
    }
    
    /**
     * Opens the dropdown menu.
     */
    public void openDropdown() {
        isDropdownOpen = true;
    }
    
    /**
     * Closes the dropdown menu.
     */
    public void closeDropdown() {
        isDropdownOpen = false;
    }
    
    /**
     * Handles mouse click events, including dropdown interaction.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if the button or dropdown was clicked
     */
    @Override
    public boolean handleClick(float mouseX, float mouseY) {
        // Check if clicking on the main button
        if (isMouseOver(mouseX, mouseY)) {
            toggleDropdown();
            onClick();
            return true;
        }
        
        // Check if clicking on dropdown items when open
        if (isDropdownOpen) {
            int clickedItem = getDropdownItemUnderMouse(mouseX, mouseY);
            if (clickedItem >= 0) {
                selectDropdownItem(clickedItem);
                return true;
            } else {
                // Click outside dropdown - close it
                closeDropdown();
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Updates hover state for both button and dropdown items.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return true if hovering over button or dropdown
     */
    @Override
    public boolean updateHover(float mouseX, float mouseY) {
        boolean buttonHovered = super.updateHover(mouseX, mouseY);
        
        if (isDropdownOpen) {
            int hoveredItem = getDropdownItemUnderMouse(mouseX, mouseY);
            if (hoveredItem >= 0) {
                selectedItemIndex = hoveredItem;
                return true;
            }
        }
        
        return buttonHovered;
    }
    
    /**
     * Determines which dropdown item the mouse is currently over.
     * 
     * @param mouseX the mouse x coordinate
     * @param mouseY the mouse y coordinate
     * @return dropdown item index, or -1 if none
     */
    public int getDropdownItemUnderMouse(float mouseX, float mouseY) {
        if (!isDropdownOpen || dropdownItems == null) {
            return -1;
        }
        
        float dropdownX = getX();
        float dropdownY = getY() + getHeight();
        
        for (int i = 0; i < dropdownItems.length; i++) {
            float itemY = dropdownY + i * dropdownItemHeight;
            if (mouseX >= dropdownX && mouseX <= dropdownX + getWidth() && 
                mouseY >= itemY && mouseY <= itemY + dropdownItemHeight) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Selects a dropdown item and closes the dropdown.
     * 
     * @param itemIndex the index of the item to select
     */
    public void selectDropdownItem(int itemIndex) {
        if (itemIndex >= 0 && itemIndex < dropdownItems.length) {
            selectedItemIndex = itemIndex;
            closeDropdown();
            
            if (onSelectionChangeAction != null) {
                onSelectionChangeAction.run();
            }
        }
    }
    
    /**
     * Adjusts the selected dropdown item by the specified direction.
     * 
     * @param direction -1 for previous item, 1 for next item
     */
    public void adjustSelection(int direction) {
        if (dropdownItems == null || dropdownItems.length == 0) return;
        
        selectedItemIndex = Math.max(0, Math.min(dropdownItems.length - 1, selectedItemIndex + direction));
        
        if (onSelectionChangeAction != null) {
            onSelectionChangeAction.run();
        }
    }
    
    // ===== RENDERING =====
    
    /**
     * Renders the dropdown button only (not the dropdown menu).
     * Use renderDropdown() separately to render the dropdown menu on top of other UI elements.
     * 
     * @param uiRenderer the UI renderer to use for drawing
     */
    @Override
    public void render(UIRenderer uiRenderer) {
        // Render only the main button
        uiRenderer.drawDropdownButton(getText(), getX(), getY(), getWidth(), getHeight(), 
                                    isSelected(), isDropdownOpen);
    }
    
    /**
     * Renders the dropdown menu if open. This should be called after all buttons are rendered
     * to ensure the dropdown appears on top of other UI elements.
     * 
     * @param uiRenderer the UI renderer to use for drawing
     */
    public void renderDropdown(UIRenderer uiRenderer) {
        // Render dropdown menu if open
        if (isDropdownOpen && dropdownItems != null) {
            // Position dropdown to be flush with bottom of button
            float dropdownX = getX();
            float dropdownY = getY() + getHeight();
            
            uiRenderer.drawDropdownMenu(dropdownItems, selectedItemIndex, 
                                      dropdownX, dropdownY, getWidth(), dropdownItemHeight);
        }
    }
    
    // ===== GETTERS AND SETTERS =====
    
    /**
     * Gets the dropdown items array.
     * 
     * @return the dropdown items
     */
    public String[] getDropdownItems() {
        return dropdownItems;
    }
    
    /**
     * Sets the dropdown items array.
     * 
     * @param dropdownItems the new dropdown items
     */
    public void setDropdownItems(String[] dropdownItems) {
        this.dropdownItems = dropdownItems;
        // Reset selected index if out of bounds
        if (selectedItemIndex >= dropdownItems.length) {
            selectedItemIndex = 0;
        }
    }
    
    /**
     * Gets the currently selected item index.
     * 
     * @return the selected item index
     */
    public int getSelectedItemIndex() {
        return selectedItemIndex;
    }
    
    /**
     * Sets the selected item index.
     * 
     * @param selectedItemIndex the new selected item index
     */
    public void setSelectedItemIndex(int selectedItemIndex) {
        if (selectedItemIndex >= 0 && dropdownItems != null && selectedItemIndex < dropdownItems.length) {
            this.selectedItemIndex = selectedItemIndex;
        }
    }
    
    /**
     * Gets the currently selected item text.
     * 
     * @return the selected item text, or null if no items
     */
    public String getSelectedItem() {
        if (dropdownItems != null && selectedItemIndex >= 0 && selectedItemIndex < dropdownItems.length) {
            return dropdownItems[selectedItemIndex];
        }
        return null;
    }
    
    /**
     * Checks if the dropdown menu is currently open.
     * 
     * @return true if dropdown is open
     */
    public boolean isDropdownOpen() {
        return isDropdownOpen;
    }
    
    /**
     * Sets the dropdown open state.
     * 
     * @param dropdownOpen true to open the dropdown
     */
    public void setDropdownOpen(boolean dropdownOpen) {
        this.isDropdownOpen = dropdownOpen;
    }
    
    /**
     * Gets the dropdown item height.
     * 
     * @return the dropdown item height
     */
    public float getDropdownItemHeight() {
        return dropdownItemHeight;
    }
    
    /**
     * Sets the dropdown item height.
     * 
     * @param dropdownItemHeight the new dropdown item height
     */
    public void setDropdownItemHeight(float dropdownItemHeight) {
        this.dropdownItemHeight = dropdownItemHeight;
    }
    
    /**
     * Gets the selection change action.
     * 
     * @return the selection change action
     */
    public Runnable getOnSelectionChangeAction() {
        return onSelectionChangeAction;
    }
    
    /**
     * Sets the selection change action.
     * 
     * @param onSelectionChangeAction the action to execute when selection changes
     */
    public void setOnSelectionChangeAction(Runnable onSelectionChangeAction) {
        this.onSelectionChangeAction = onSelectionChangeAction;
    }
}