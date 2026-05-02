package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.input.InputHandler;
import com.stonebreak.ui.recipeScreen.renderers.RecipeRenderCoordinator;
import com.stonebreak.ui.recipeScreen.state.PopupState;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * Handles the recipe-detail variant cycler and ESC-to-deselect.
 *
 * Class name is preserved for backwards compatibility with the rest of the
 * recipe screen module; the on-screen artifact is no longer a modal popup
 * but a permanent right-side detail pane in the three-pane layout.
 */
public class PopupInputHandler {
    private final PopupState popupState;
    private final InputHandler inputHandler;
    private RecipeRenderCoordinator coordinator;

    public PopupInputHandler(PopupState popupState, InputHandler inputHandler) {
        this.popupState = popupState;
        this.inputHandler = inputHandler;
    }

    public void setCoordinator(RecipeRenderCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Returns true if the click landed on a prev/next variant button and the
     * caller should not run further click handling for this frame.
     */
    public boolean handleVariationClick(Vector2f mousePos) {
        if (coordinator == null) return false;
        if (!popupState.isShowingRecipePopup() || popupState.isPopupJustOpened()) return false;
        if (!popupState.hasMultipleVariations()) return false;

        if (coordinator.prevVariantClicked(mousePos.x, mousePos.y)) {
            popupState.navigatePrevious();
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true;
        }
        if (coordinator.nextVariantClicked(mousePos.x, mousePos.y)) {
            popupState.navigateNext();
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true;
        }
        return false;
    }

    public void handleEscapeKey() {
        if (popupState.isShowingRecipePopup()) {
            popupState.closePopup();
        }
    }
}
