package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.input.InputHandler;
import com.stonebreak.ui.recipeScreen.core.PositionCalculator;
import com.stonebreak.ui.recipeScreen.core.RecipeBookConstants;
import com.stonebreak.ui.recipeScreen.state.PopupState;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

public class PopupInputHandler {
    private final PopupState popupState;
    private final InputHandler inputHandler;

    public PopupInputHandler(PopupState popupState, InputHandler inputHandler) {
        this.popupState = popupState;
        this.inputHandler = inputHandler;
    }

    public boolean handlePopupClick(Vector2f mousePos) {
        if (!popupState.isShowingRecipePopup() || popupState.isPopupJustOpened()) {
            return false;
        }

        PositionCalculator.PopupDimensions popup = PositionCalculator.calculatePopupDimensions();

        if (isClickOutsidePopup(mousePos, popup)) {
            closePopupAndConsumeClick();
            return true;
        }

        if (isClickOnCloseButton(mousePos, popup)) {
            closePopupAndConsumeClick();
            return true;
        }

        if (popupState.hasMultipleVariations()) {
            return handlePaginationButtons(mousePos, popup);
        }

        return true;
    }

    public void handleEscapeKey() {
        if (popupState.isShowingRecipePopup()) {
            popupState.closePopup();
        }
    }

    private boolean isClickOutsidePopup(Vector2f mousePos, PositionCalculator.PopupDimensions popup) {
        return !PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                popup.x, popup.y, popup.width, popup.height);
    }

    private boolean isClickOnCloseButton(Vector2f mousePos, PositionCalculator.PopupDimensions popup) {
        int closeButtonX = popup.x + popup.width - RecipeBookConstants.CLOSE_BUTTON_SIZE - 16;
        int closeButtonY = popup.y + 16;

        return PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                closeButtonX, closeButtonY,
                RecipeBookConstants.CLOSE_BUTTON_SIZE, RecipeBookConstants.CLOSE_BUTTON_SIZE);
    }

    private boolean handlePaginationButtons(Vector2f mousePos, PositionCalculator.PopupDimensions popup) {
        int buttonY = popup.y + 60;
        int prevButtonX = popup.x + 20;
        int nextButtonX = popup.x + popup.width - RecipeBookConstants.PAGINATION_BUTTON_SIZE - 20;

        if (isClickOnPreviousButton(mousePos, prevButtonX, buttonY)) {
            popupState.navigatePrevious();
            consumeMouseClick();
            return true;
        }

        if (isClickOnNextButton(mousePos, nextButtonX, buttonY)) {
            popupState.navigateNext();
            consumeMouseClick();
            return true;
        }

        return true;
    }

    private boolean isClickOnPreviousButton(Vector2f mousePos, int buttonX, int buttonY) {
        return PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                buttonX, buttonY,
                RecipeBookConstants.PAGINATION_BUTTON_SIZE, RecipeBookConstants.PAGINATION_BUTTON_SIZE);
    }

    private boolean isClickOnNextButton(Vector2f mousePos, int buttonX, int buttonY) {
        return PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                buttonX, buttonY,
                RecipeBookConstants.PAGINATION_BUTTON_SIZE, RecipeBookConstants.PAGINATION_BUTTON_SIZE);
    }

    private void closePopupAndConsumeClick() {
        popupState.closePopup();
        consumeMouseClick();
    }

    private void consumeMouseClick() {
        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}