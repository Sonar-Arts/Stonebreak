package com.stonebreak.ui.recipeScreen.state;

public class SearchState {
    private String searchText = "";
    private boolean searchActive = false;
    private boolean isTyping = false;
    private long lastTypingTime = 0L;

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText != null ? searchText : "";
    }

    public boolean isSearchActive() {
        return searchActive;
    }

    public void setSearchActive(boolean searchActive) {
        this.searchActive = searchActive;
    }

    public boolean isTyping() {
        return isTyping;
    }

    public void setTyping(boolean typing) {
        this.isTyping = typing;
        if (typing) {
            this.lastTypingTime = System.currentTimeMillis();
        }
    }

    public long getLastTypingTime() {
        return lastTypingTime;
    }

    public void updateTypingState() {
        if (isTyping && System.currentTimeMillis() - lastTypingTime > 500L) {
            isTyping = false;
        }
    }

    public void addCharacter(char character) {
        this.searchText += character;
        setTyping(true);
    }

    public void removeLastCharacter() {
        if (!searchText.isEmpty()) {
            this.searchText = searchText.substring(0, searchText.length() - 1);
            setTyping(true);
        }
    }

    public void clearSearch() {
        this.searchText = "";
        this.searchActive = false;
        this.isTyping = false;
    }

    public boolean hasSearchText() {
        return !searchText.isEmpty();
    }
}