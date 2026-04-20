package com.stonebreak.ui.worldSelect;

/**
 * Single source of truth for world-select screen geometry. Both the Skija
 * renderer and the mouse hit-tester read coordinates from this class so they
 * cannot drift out of sync.
 *
 * Coordinates are computed lazily for a given (windowWidth, windowHeight)
 * pair via {@link #compute(int, int)}.
 */
public final class WorldSelectLayout {

    public static final int ITEMS_PER_PAGE = 8;

    public static final float ITEM_HEIGHT = 56f;
    public static final float LIST_WIDTH = 720f;
    public static final float LIST_HEIGHT = ITEM_HEIGHT * ITEMS_PER_PAGE;

    public static final float PANEL_PADDING = 24f;
    public static final float PANEL_WIDTH = LIST_WIDTH + PANEL_PADDING * 2f;
    public static final float PANEL_HEIGHT = LIST_HEIGHT + PANEL_PADDING * 2f + 90f;

    public static final float ACTION_BUTTON_WIDTH = 170f;
    public static final float ACTION_BUTTON_HEIGHT = 44f;
    public static final float ACTION_BUTTON_GAP = 12f;
    public static final int ACTION_BUTTON_COUNT = 4;

    public static final float DIALOG_WIDTH = 460f;
    public static final float DIALOG_HEIGHT = 280f;
    public static final float DIALOG_INPUT_WIDTH = 400f;
    public static final float DIALOG_INPUT_HEIGHT = 36f;
    public static final float DIALOG_BUTTON_WIDTH = 170f;
    public static final float DIALOG_BUTTON_HEIGHT = 40f;

    public static final float CONFIRM_DIALOG_WIDTH = 460f;
    public static final float CONFIRM_DIALOG_HEIGHT = 200f;

    public final int windowWidth;
    public final int windowHeight;
    public final float centerX;
    public final float titleY;
    public final float subtitleY;
    public final float panelX;
    public final float panelY;
    public final float listX;
    public final float listY;
    public final float scrollbarX;

    public final float playButtonX;
    public final float playButtonY;
    public final float createButtonX;
    public final float createButtonY;
    public final float deleteButtonX;
    public final float deleteButtonY;
    public final float backButtonX;
    public final float backButtonY;

    public final float dialogX;
    public final float dialogY;
    public final float nameFieldX;
    public final float nameFieldY;
    public final float seedFieldX;
    public final float seedFieldY;
    public final float dialogCreateX;
    public final float dialogCancelX;
    public final float dialogButtonY;

    public final float confirmDialogX;
    public final float confirmDialogY;
    public final float confirmConfirmX;
    public final float confirmCancelX;
    public final float confirmButtonY;

    private WorldSelectLayout(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        this.centerX = width / 2f;

        this.titleY = Math.max(72f, height * 0.11f);
        this.subtitleY = titleY + 38f;

        this.panelX = centerX - PANEL_WIDTH / 2f;
        this.panelY = subtitleY + 28f;

        this.listX = panelX + PANEL_PADDING;
        this.listY = panelY + PANEL_PADDING;
        this.scrollbarX = listX + LIST_WIDTH + 4f;

        float actionRowY = panelY + PANEL_HEIGHT - ACTION_BUTTON_HEIGHT - 22f;
        float actionRowWidth = ACTION_BUTTON_WIDTH * ACTION_BUTTON_COUNT + ACTION_BUTTON_GAP * (ACTION_BUTTON_COUNT - 1);
        float actionRowX = centerX - actionRowWidth / 2f;
        float stride = ACTION_BUTTON_WIDTH + ACTION_BUTTON_GAP;
        this.backButtonY = actionRowY;
        this.createButtonY = actionRowY;
        this.deleteButtonY = actionRowY;
        this.playButtonY = actionRowY;
        this.backButtonX   = actionRowX;
        this.createButtonX = actionRowX + stride;
        this.deleteButtonX = actionRowX + stride * 2f;
        this.playButtonX   = actionRowX + stride * 3f;

        this.dialogX = centerX - DIALOG_WIDTH / 2f;
        this.dialogY = (height - DIALOG_HEIGHT) / 2f;
        this.nameFieldX = dialogX + (DIALOG_WIDTH - DIALOG_INPUT_WIDTH) / 2f;
        this.nameFieldY = dialogY + 76f;
        this.seedFieldX = nameFieldX;
        this.seedFieldY = nameFieldY + DIALOG_INPUT_HEIGHT + 32f;
        this.dialogButtonY = dialogY + DIALOG_HEIGHT - DIALOG_BUTTON_HEIGHT - 22f;
        this.dialogCreateX = centerX - DIALOG_BUTTON_WIDTH - 10f;
        this.dialogCancelX = centerX + 10f;

        this.confirmDialogX = centerX - CONFIRM_DIALOG_WIDTH / 2f;
        this.confirmDialogY = (height - CONFIRM_DIALOG_HEIGHT) / 2f;
        this.confirmButtonY = confirmDialogY + CONFIRM_DIALOG_HEIGHT - DIALOG_BUTTON_HEIGHT - 22f;
        this.confirmConfirmX = centerX - DIALOG_BUTTON_WIDTH - 10f;
        this.confirmCancelX = centerX + 10f;
    }

    public static WorldSelectLayout compute(int width, int height) {
        return new WorldSelectLayout(width, height);
    }

    public boolean hitItem(double mouseX, double mouseY, int rowsVisible, int scrollOffset, int totalItems, int[] outIndex) {
        if (mouseX < listX || mouseX > listX + LIST_WIDTH) return false;
        if (mouseY < listY || mouseY > listY + LIST_HEIGHT) return false;
        int row = (int) ((mouseY - listY) / ITEM_HEIGHT);
        if (row < 0 || row >= rowsVisible) return false;
        int index = row + scrollOffset;
        if (index < 0 || index >= totalItems) return false;
        outIndex[0] = index;
        return true;
    }

    public boolean hitRect(double mouseX, double mouseY, float x, float y, float w, float h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }
}
