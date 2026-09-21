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
    private String hoveredButton = null;
    private int scrollOffset = 0;

    // ===== DIALOG STATE =====
    private boolean showCreateDialog = false;
    private String newWorldName = "";
    private String newWorldSeed = "";
    private boolean showDeleteDialog = false;
    private String worldPendingDelete = null;

    // ===== HOVER CARD STATE =====
    /** How long the cursor must rest on a row before its info card appears. */
    public static final long CARD_OPEN_DELAY_MS = 350L;
    /** Grace period after the cursor leaves both the row and the card, so you can cross the seam. */
    public static final long CARD_CLOSE_DELAY_MS = 220L;

    private int cardIndex = -1;
    private int pendingCardIndex = -1;
    private long pendingSinceMs = 0L;
    private long leavingSinceMs = 0L;
    private String hoveredCardButton = null;

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
        closeCard();

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
        hoveredButton = null;
        closeCard();
    }

    public String getHoveredButton() { return hoveredButton; }

    public void setHoveredButton(String key) { this.hoveredButton = key; }

    // ===== HOVER CARD MANAGEMENT =====

    /**
     * Index of the world whose info card is open, or -1 when none is.
     */
    public int getCardIndex() {
        return cardIndex;
    }

    /** Name of the world whose info card is open, or null when none is. */
    public String getCardWorld() {
        if (cardIndex < 0 || cardIndex >= worldList.size()) {
            return null;
        }
        return worldList.get(cardIndex);
    }

    public boolean isCardOpen() {
        return getCardWorld() != null;
    }

    public String getHoveredCardButton() {
        return hoveredCardButton;
    }

    public void setHoveredCardButton(String key) {
        this.hoveredCardButton = key;
    }

    /**
     * Feeds the card state machine one cursor sample.
     *
     * <p>The card is sticky: it stays open while the cursor is over its anchor row
     * <em>or</em> over the card itself, which is what makes the buttons inside it
     * reachable. Leaving both starts the close grace period rather than closing at once.
     *
     * @param rowIndex  world row under the cursor, or -1 for none
     * @param overCard  true when the cursor is inside the open card's bounds
     * @param nowMs     current wall-clock time
     */
    public void updateCardHover(int rowIndex, boolean overCard, long nowMs) {
        if (overCard || (cardIndex >= 0 && rowIndex == cardIndex)) {
            pendingCardIndex = -1;
            leavingSinceMs = 0L;
            return;
        }

        if (rowIndex >= 0) {
            if (pendingCardIndex != rowIndex) {
                pendingCardIndex = rowIndex;
                pendingSinceMs = nowMs;
            }
            // Moved onto a different row: the old card is no longer what the cursor is asking about
            if (cardIndex >= 0) {
                cardIndex = -1;
                hoveredCardButton = null;
            }
            leavingSinceMs = 0L;
            return;
        }

        pendingCardIndex = -1;
        if (cardIndex >= 0 && leavingSinceMs == 0L) {
            leavingSinceMs = nowMs;
        }
    }

    /**
     * Advances the open/close delays. Called once a frame, since a card must still open
     * when the cursor rests without producing further move events.
     */
    public void tickCard(long nowMs) {
        if (pendingCardIndex >= 0 && nowMs - pendingSinceMs >= CARD_OPEN_DELAY_MS) {
            cardIndex = pendingCardIndex < worldList.size() ? pendingCardIndex : -1;
            pendingCardIndex = -1;
            leavingSinceMs = 0L;
        }
        if (cardIndex >= 0 && leavingSinceMs != 0L && nowMs - leavingSinceMs >= CARD_CLOSE_DELAY_MS) {
            closeCard();
        }
    }

    /** Closes the card immediately and forgets any pending open. */
    public void closeCard() {
        cardIndex = -1;
        pendingCardIndex = -1;
        pendingSinceMs = 0L;
        leavingSinceMs = 0L;
        hoveredCardButton = null;
    }

    // ===== SCROLL MANAGEMENT =====

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int offset) {
        int maxScroll = Math.max(0, worldList.size() - ITEMS_PER_PAGE);
        int clamped = Math.max(0, Math.min(offset, maxScroll));
        if (clamped != scrollOffset) {
            // The card is anchored to a row's on-screen position, so scrolling strands it
            closeCard();
        }
        scrollOffset = clamped;
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

        // Route every change through setScrollOffset so bounds clamping and the
        // card-invalidation it performs cannot be bypassed.
        int target = scrollOffset;
        // If selection is above visible area, scroll up
        if (selectedIndex < scrollOffset) {
            target = selectedIndex;
        }
        // If selection is below visible area, scroll down
        else if (selectedIndex >= scrollOffset + ITEMS_PER_PAGE) {
            target = selectedIndex - ITEMS_PER_PAGE + 1;
        }

        setScrollOffset(target);
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
        closeCard();
        showCreateDialog = true;
        newWorldName = "";
        newWorldSeed = "";
    }

    public void closeCreateDialog() {
        showCreateDialog = false;
        newWorldName = "";
        newWorldSeed = "";
    }

    public boolean isShowDeleteDialog() {
        return showDeleteDialog;
    }

    public String getWorldPendingDelete() {
        return worldPendingDelete;
    }

    public void openDeleteDialog(String worldName) {
        if (worldName == null || worldName.isEmpty()) return;
        closeCard();
        this.showDeleteDialog = true;
        this.worldPendingDelete = worldName;
    }

    public void closeDeleteDialog() {
        this.showDeleteDialog = false;
        this.worldPendingDelete = null;
    }

    public boolean isAnyDialogOpen() {
        return showCreateDialog || showDeleteDialog;
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
        if (Character.isSurrogate(character)) return;
        if (Character.isLetterOrDigit(character) || character == ' ' || character == '-' || character == '_') {
            if (newWorldName.length() < 32) { // Reasonable limit
                newWorldName += character;
            }
        }
    }

    /** Appends every character the field accepts, dropping the rest (paste). */
    public void appendToWorldName(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            appendToWorldName(text.charAt(i));
        }
    }

    public void removeLastCharacterFromWorldName() {
        if (!newWorldName.isEmpty()) {
            newWorldName = newWorldName.substring(0, newWorldName.length() - 1);
        }
    }

    public void appendToWorldSeed(char character) {
        if (Character.isSurrogate(character)) return;
        if (Character.isLetterOrDigit(character) || character == '-') {
            if (newWorldSeed.length() < 20) { // Reasonable limit
                newWorldSeed += character;
            }
        }
    }

    /** Appends every character the field accepts, dropping the rest (paste). */
    public void appendToWorldSeed(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            appendToWorldSeed(text.charAt(i));
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
        closeCard();
        closeCreateDialog();
        closeDeleteDialog();
    }
}