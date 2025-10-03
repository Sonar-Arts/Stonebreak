package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.ui.recipeScreen.state.SearchState;
import com.stonebreak.ui.recipeScreen.state.UIState;
import org.lwjgl.glfw.GLFW;

public class SearchInputHandler {
    private final SearchState searchState;
    private final UIState uiState;

    public SearchInputHandler(SearchState searchState, UIState uiState) {
        this.searchState = searchState;
        this.uiState = uiState;
    }

    public void handleCharacterInput(char character) {
        if (!searchState.isSearchActive()) {
            return;
        }

        searchState.addCharacter(character);
        uiState.resetScrollOffset();
    }

    public void handleKeyInput(int key, int action) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return;
        }

        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> handleBackspace();
            case GLFW.GLFW_KEY_ENTER -> handleEnter();
        }
    }

    private void handleBackspace() {
        if (searchState.isSearchActive()) {
            searchState.removeLastCharacter();
            uiState.resetScrollOffset();
        }
    }

    private void handleEnter() {
        if (searchState.isSearchActive()) {
            searchState.setSearchActive(false);
            searchState.setTyping(false);
        }
    }

    public void activateSearch() {
        searchState.setSearchActive(true);
    }

    public void deactivateSearch() {
        searchState.setSearchActive(false);
    }
}