package com.stonebreak.ui.worldSelect.managers;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the UI state for the WorldSelectScreen including selected world,
 * dialog states, scroll position, and user input.
 */
public class WorldStateManager {

    // ===== WORLD SELECTION STATE =====
    private List<String> worldList = new ArrayList<>();
    private int selectedIndex = 0;
    private int hoveredIndex = -1;
    private int scrollOffset = 0;

    // ===== DIALOG STATE =====
    private boolean showCreateDialog = false;
    private String newWorldName = "";
    private String newWorldSeed = "";

    // ===== SCROLL STATE =====
    private static final int ITEMS_PER_PAGE = 8;
    private static final int SCROLL_SPEED = 1;

    public WorldStateManager() {
        // Initialize with empty state
    }

    // ===== WORLD LIST MANAGEMENT =====

    /**
     * Updates the world list and resets selection if needed.
     */
    public void setWorldList(List<String> worlds) {
        this.worldList = new ArrayList<>(worlds);

        // Ensure selected index is valid
        if (selectedIndex >= worldList.size()) {
            selectedIndex = Math.max(0, worldList.size() - 1);
        }

        // Reset hover state
        hoveredIndex = -1;

        // Adjust scroll to keep selection visible
        adjustScrollToSelection();
    }

    public List<String> getWorldList() {
        return new ArrayList<>(worldList);
    }

    public boolean hasWorlds() {
        return !worldList.isEmpty();
    }

    // ===== SELECTION MANAGEMENT =====

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public String getSelectedWorld() {
        if (worldList.isEmpty() || selectedIndex < 0 || selectedIndex >= worldList.size()) {
            return null;
        }
        return worldList.get(selectedIndex);
    }

    public void setSelectedIndex(int index) {
        if (worldList.isEmpty()) {
            selectedIndex = 0;
            return;
        }

        selectedIndex = Math.max(0, Math.min(index, worldList.size() - 1));
        adjustScrollToSelection();
    }

    public void moveSelectionUp() {
        if (!worldList.isEmpty()) {
            setSelectedIndex(selectedIndex - 1);
        }
    }

    public void moveSelectionDown() {
        if (!worldList.isEmpty()) {
            setSelectedIndex(selectedIndex + 1);
        }
    }

    // ===== HOVER MANAGEMENT =====

    public int getHoveredIndex() {
        return hoveredIndex;
    }

    public void setHoveredIndex(int index) {
        if (worldList.isEmpty()) {
            hoveredIndex = -1;
            return;
        }

        if (index >= 0 && index < worldList.size()) {
            hoveredIndex = index;
        } else {
            hoveredIndex = -1;
        }
    }

    public void clearHover() {
        hoveredIndex = -1;
    }

    // ===== SCROLL MANAGEMENT =====

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int offset) {
        int maxScroll = Math.max(0, worldList.size() - ITEMS_PER_PAGE);
        scrollOffset = Math.max(0, Math.min(offset, maxScroll));
    }

    public void scrollUp() {
        setScrollOffset(scrollOffset - SCROLL_SPEED);
    }

    public void scrollDown() {
        setScrollOffset(scrollOffset + SCROLL_SPEED);
    }

    public void scroll(double delta) {
        if (delta > 0) {
            scrollUp();
        } else if (delta < 0) {
            scrollDown();
        }
    }

    /**
     * Adjusts scroll position to ensure selected item is visible.
     */
    private void adjustScrollToSelection() {
        if (worldList.isEmpty()) return;

        // If selection is above visible area, scroll up
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        }
        // If selection is below visible area, scroll down
        else if (selectedIndex >= scrollOffset + ITEMS_PER_PAGE) {
            scrollOffset = selectedIndex - ITEMS_PER_PAGE + 1;
        }

        // Ensure scroll stays within bounds
        setScrollOffset(scrollOffset);
    }

    public boolean isIndexVisible(int index) {
        return index >= scrollOffset && index < scrollOffset + ITEMS_PER_PAGE;
    }

    public int getVisibleStartIndex() {
        return scrollOffset;
    }

    public int getVisibleEndIndex() {
        return Math.min(scrollOffset + ITEMS_PER_PAGE, worldList.size());
    }

    // ===== DIALOG MANAGEMENT =====

    public boolean isShowCreateDialog() {
        return showCreateDialog;
    }

    public void openCreateDialog() {
        showCreateDialog = true;
        newWorldName = "";
        newWorldSeed = "";
    }

    public void closeCreateDialog() {
        showCreateDialog = false;
        newWorldName = "";
        newWorldSeed = "";
    }

    // ===== TEXT INPUT MANAGEMENT =====

    public String getNewWorldName() {
        return newWorldName;
    }

    public void setNewWorldName(String name) {
        this.newWorldName = name != null ? name : "";
    }

    public String getNewWorldSeed() {
        return newWorldSeed;
    }

    public void setNewWorldSeed(String seed) {
        this.newWorldSeed = seed != null ? seed : "";
    }

    public void appendToWorldName(char character) {
        if (Character.isLetterOrDigit(character) || character == ' ' || character == '-' || character == '_') {
            if (newWorldName.length() < 32) { // Reasonable limit
                newWorldName += character;
            }
        }
    }

    public void removeLastCharacterFromWorldName() {
        if (!newWorldName.isEmpty()) {
            newWorldName = newWorldName.substring(0, newWorldName.length() - 1);
        }
    }

    public void appendToWorldSeed(char character) {
        if (Character.isLetterOrDigit(character) || character == '-') {
            if (newWorldSeed.length() < 20) { // Reasonable limit
                newWorldSeed += character;
            }
        }
    }

    public void removeLastCharacterFromWorldSeed() {
        if (!newWorldSeed.isEmpty()) {
            newWorldSeed = newWorldSeed.substring(0, newWorldSeed.length() - 1);
        }
    }

    public boolean isValidWorldName() {
        return !newWorldName.trim().isEmpty() && !worldList.contains(newWorldName.trim());
    }

    // ===== UTILITY METHODS =====

    public void reset() {
        selectedIndex = 0;
        hoveredIndex = -1;
        scrollOffset = 0;
        closeCreateDialog();
    }
}