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

    // Scaled instance dimensions (read these instead of the static constants at render/hit-test time)
    public final float itemHeight;
    public final float listWidth;
    public final float listHeight;
    public final float panelWidth;
    public final float panelHeight;
    public final float panelPadding;
    public final float actionButtonWidth;
    public final float actionButtonHeight;
    public final float dialogWidth;
    public final float dialogHeight;
    public final float dialogInputWidth;
    public final float dialogInputHeight;
    public final float dialogButtonWidth;
    public final float dialogButtonHeight;
    public final float confirmDialogWidth;
    public final float confirmDialogHeight;

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
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();

        // Compute scaled dimensions from the base constants
        this.itemHeight        = ITEM_HEIGHT         * s;
        this.listWidth         = LIST_WIDTH          * s;
        this.listHeight        = this.itemHeight     * ITEMS_PER_PAGE;
        this.panelPadding      = PANEL_PADDING       * s;
        this.panelWidth        = this.listWidth      + this.panelPadding * 2f;
        this.panelHeight       = this.listHeight     + this.panelPadding * 2f + 90f * s;
        this.actionButtonWidth = ACTION_BUTTON_WIDTH * s;
        this.actionButtonHeight= ACTION_BUTTON_HEIGHT* s;
        this.dialogWidth       = DIALOG_WIDTH        * s;
        this.dialogHeight      = DIALOG_HEIGHT       * s;
        this.dialogInputWidth  = DIALOG_INPUT_WIDTH  * s;
        this.dialogInputHeight = DIALOG_INPUT_HEIGHT * s;
        this.dialogButtonWidth = DIALOG_BUTTON_WIDTH * s;
        this.dialogButtonHeight= DIALOG_BUTTON_HEIGHT* s;
        this.confirmDialogWidth = CONFIRM_DIALOG_WIDTH * s;
        this.confirmDialogHeight= CONFIRM_DIALOG_HEIGHT* s;

        this.windowWidth = width;
        this.windowHeight = height;
        this.centerX = width / 2f;

        this.titleY = Math.max(72f * s, height * 0.11f);
        this.subtitleY = titleY + 38f * s;

        this.panelX = centerX - panelWidth / 2f;
        this.panelY = subtitleY + 28f * s;

        this.listX = panelX + panelPadding;
        this.listY = panelY + panelPadding;
        this.scrollbarX = listX + listWidth + 4f;

        float actionButtonGap = ACTION_BUTTON_GAP * s;
        float actionRowY = panelY + panelHeight - actionButtonHeight - 22f * s;
        float actionRowWidth = actionButtonWidth * ACTION_BUTTON_COUNT + actionButtonGap * (ACTION_BUTTON_COUNT - 1);
        float actionRowX = centerX - actionRowWidth / 2f;
        float stride = actionButtonWidth + actionButtonGap;
        this.backButtonY = actionRowY;
        this.createButtonY = actionRowY;
        this.deleteButtonY = actionRowY;
        this.playButtonY = actionRowY;
        this.backButtonX   = actionRowX;
        this.createButtonX = actionRowX + stride;
        this.deleteButtonX = actionRowX + stride * 2f;
        this.playButtonX   = actionRowX + stride * 3f;

        this.dialogX = centerX - dialogWidth / 2f;
        this.dialogY = (height - dialogHeight) / 2f;
        this.nameFieldX = dialogX + (dialogWidth - dialogInputWidth) / 2f;
        this.nameFieldY = dialogY + 76f * s;
        this.seedFieldX = nameFieldX;
        this.seedFieldY = nameFieldY + dialogInputHeight + 32f * s;
        this.dialogButtonY = dialogY + dialogHeight - dialogButtonHeight - 22f * s;
        this.dialogCreateX = centerX - dialogButtonWidth - 10f * s;
        this.dialogCancelX = centerX + 10f * s;

        this.confirmDialogX = centerX - confirmDialogWidth / 2f;
        this.confirmDialogY = (height - confirmDialogHeight) / 2f;
        this.confirmButtonY = confirmDialogY + confirmDialogHeight - dialogButtonHeight - 22f * s;
        this.confirmConfirmX = centerX - dialogButtonWidth - 10f * s;
        this.confirmCancelX = centerX + 10f * s;
    }

    public static WorldSelectLayout compute(int width, int height) {
        return new WorldSelectLayout(width, height);
    }

    public boolean hitItem(double mouseX, double mouseY, int rowsVisible, int scrollOffset, int totalItems, int[] outIndex) {
        if (mouseX < listX || mouseX > listX + listWidth) return false;
        if (mouseY < listY || mouseY > listY + listHeight) return false;
        int row = (int) ((mouseY - listY) / itemHeight);
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
