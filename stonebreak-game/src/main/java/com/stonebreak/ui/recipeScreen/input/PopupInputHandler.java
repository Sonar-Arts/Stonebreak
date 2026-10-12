package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.ui.recipeScreen.state.PopupState;

/**
 * Handles ESC-to-deselect in the recipe detail pane.
 *
 * Class name is preserved for backwards compatibility with the rest of the
 * recipe screen module; the on-screen artifact is no longer a modal popup
 * but a permanent right-side detail pane in the three-pane layout.
 */
public class PopupInputHandler {
    private final PopupState popupState;

    public PopupInputHandler(PopupState popupState) {
        this.popupState = popupState;
    }

    public void handleEscapeKey() {
        if (popupState.isShowingRecipePopup()) {
            popupState.closePopup();
        }
    }
}
