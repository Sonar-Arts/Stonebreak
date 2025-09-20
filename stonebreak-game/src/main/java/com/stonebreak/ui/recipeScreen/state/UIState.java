package com.stonebreak.ui.recipeScreen.state;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;

public class UIState {
    private boolean visible = false;
    private int scrollOffset = 0;
    private String selectedCategory = "All";
    private ItemStack hoveredItemStack = null;
    private Recipe hoveredRecipe = null;

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int scrollOffset) {
        this.scrollOffset = Math.max(0, scrollOffset);
    }

    public void adjustScrollOffset(int delta) {
        this.scrollOffset = Math.max(0, this.scrollOffset + delta);
    }

    public void limitScrollOffset(int maxOffset) {
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public void setSelectedCategory(String selectedCategory) {
        this.selectedCategory = selectedCategory != null ? selectedCategory : "All";
        resetScrollOffset();
    }

    public ItemStack getHoveredItemStack() {
        return hoveredItemStack;
    }

    public void setHoveredItemStack(ItemStack hoveredItemStack) {
        this.hoveredItemStack = hoveredItemStack;
    }

    public Recipe getHoveredRecipe() {
        return hoveredRecipe;
    }

    public void setHoveredRecipe(Recipe hoveredRecipe) {
        this.hoveredRecipe = hoveredRecipe;
    }

    public void resetScrollOffset() {
        this.scrollOffset = 0;
    }

    public void clearHoverStates() {
        this.hoveredItemStack = null;
        this.hoveredRecipe = null;
    }

    public void resetToDefaults() {
        this.selectedCategory = "All";
        this.scrollOffset = 0;
        clearHoverStates();
    }
}