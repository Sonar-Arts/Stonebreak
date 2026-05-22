package com.stonebreak.ui.worldSelect.handlers;

import com.stonebreak.ui.worldSelect.WorldSelectLayout;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Hit-tests mouse events against {@link WorldSelectLayout} so screen geometry
 * stays in sync with the Skija renderer.
 */
public class WorldMouseHandler {

    private final WorldStateManager stateManager;
    private final WorldActionHandler actionHandler;
    private final WorldInputHandler inputHandler;

    public WorldMouseHandler(WorldStateManager stateManager, WorldActionHandler actionHandler,
                             WorldInputHandler inputHandler) {
        this.stateManager = stateManager;
        this.actionHandler = actionHandler;
        this.inputHandler = inputHandler;
    }

    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        WorldSelectLayout layout = WorldSelectLayout.compute(windowWidth, windowHeight);
        if (stateManager.isShowDeleteDialog()) {
            stateManager.setHoveredIndex(-1);
            stateManager.setHoveredButton(hitConfirmButton(mouseX, mouseY, layout));
            return;
        }
        if (stateManager.isShowCreateDialog()) {
            stateManager.setHoveredIndex(-1);
            stateManager.setHoveredButton(hitDialogButton(mouseX, mouseY, layout));
            return;
        }
        int total = stateManager.getWorldList().size();
        int rowsVisible = stateManager.getVisibleEndIndex() - stateManager.getVisibleStartIndex();
        int[] hit = new int[1];
        if (layout.hitItem(mouseX, mouseY, rowsVisible, stateManager.getScrollOffset(), total, hit)) {
            stateManager.setHoveredIndex(hit[0]);
        } else {
            stateManager.setHoveredIndex(-1);
        }
        stateManager.setHoveredButton(hitActionButton(mouseX, mouseY, layout));
    }

    private String hitActionButton(double mouseX, double mouseY, WorldSelectLayout layout) {
        float w = layout.actionButtonWidth;
        float h = layout.actionButtonHeight;
        if (layout.hitRect(mouseX, mouseY, layout.playButtonX,   layout.playButtonY,   w, h)) return "play";
        if (layout.hitRect(mouseX, mouseY, layout.createButtonX, layout.createButtonY, w, h)) return "create";
        if (layout.hitRect(mouseX, mouseY, layout.deleteButtonX, layout.deleteButtonY, w, h)) return "delete";
        if (layout.hitRect(mouseX, mouseY, layout.backButtonX,   layout.backButtonY,   w, h)) return "back";
        return null;
    }

    private String hitDialogButton(double mouseX, double mouseY, WorldSelectLayout layout) {
        float w = layout.dialogButtonWidth;
        float h = layout.dialogButtonHeight;
        if (layout.hitRect(mouseX, mouseY, layout.dialogCreateX, layout.dialogButtonY, w, h)) return "dialog-create";
        if (layout.hitRect(mouseX, mouseY, layout.dialogCancelX, layout.dialogButtonY, w, h)) return "dialog-cancel";
        return null;
    }

    private String hitConfirmButton(double mouseX, double mouseY, WorldSelectLayout layout) {
        float w = layout.dialogButtonWidth;
        float h = layout.dialogButtonHeight;
        if (layout.hitRect(mouseX, mouseY, layout.confirmConfirmX, layout.confirmButtonY, w, h)) return "confirm-delete";
        if (layout.hitRect(mouseX, mouseY, layout.confirmCancelX,  layout.confirmButtonY, w, h)) return "confirm-cancel";
        return null;
    }

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        if (action != GLFW_PRESS || button != GLFW_MOUSE_BUTTON_LEFT) return;
        WorldSelectLayout layout = WorldSelectLayout.compute(windowWidth, windowHeight);

        if (stateManager.isShowDeleteDialog()) {
            handleDeleteDialogClick(mouseX, mouseY, layout);
        } else if (stateManager.isShowCreateDialog()) {
            handleDialogClick(mouseX, mouseY, layout);
        } else {
            handleListClick(mouseX, mouseY, layout);
        }
    }

    private void handleListClick(double mouseX, double mouseY, WorldSelectLayout layout) {
        int total = stateManager.getWorldList().size();
        int rowsVisible = stateManager.getVisibleEndIndex() - stateManager.getVisibleStartIndex();
        int[] hit = new int[1];
        if (layout.hitItem(mouseX, mouseY, rowsVisible, stateManager.getScrollOffset(), total, hit)) {
            stateManager.setSelectedIndex(hit[0]);
            return; // single click selects; double-click / Play button loads
        }

        if (layout.hitRect(mouseX, mouseY,
                layout.playButtonX, layout.playButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight)) {
            actionHandler.loadSelectedWorld();
            return;
        }
        if (layout.hitRect(mouseX, mouseY,
                layout.createButtonX, layout.createButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight)) {
            actionHandler.openCreateWorldDialog();
            return;
        }
        if (layout.hitRect(mouseX, mouseY,
                layout.deleteButtonX, layout.deleteButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight)) {
            if (stateManager.hasWorlds() && stateManager.getSelectedWorld() != null) {
                actionHandler.requestDeleteSelectedWorld();
            }
            return;
        }
        if (layout.hitRect(mouseX, mouseY,
                layout.backButtonX, layout.backButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight)) {
            actionHandler.returnToMainMenu();
        }
    }

    private void handleDeleteDialogClick(double mouseX, double mouseY, WorldSelectLayout layout) {
        if (!layout.hitRect(mouseX, mouseY, layout.confirmDialogX, layout.confirmDialogY,
                layout.confirmDialogWidth, layout.confirmDialogHeight)) {
            actionHandler.cancelDeleteWorld();
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.confirmConfirmX, layout.confirmButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight)) {
            actionHandler.confirmDeleteWorld();
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.confirmCancelX, layout.confirmButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight)) {
            actionHandler.cancelDeleteWorld();
        }
    }

    private void handleDialogClick(double mouseX, double mouseY, WorldSelectLayout layout) {
        if (!layout.hitRect(mouseX, mouseY, layout.dialogX, layout.dialogY,
                layout.dialogWidth, layout.dialogHeight)) {
            actionHandler.closeCreateWorldDialog();
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.dialogCreateX, layout.dialogButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight)) {
            actionHandler.createWorldFromDialog();
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.dialogCancelX, layout.dialogButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight)) {
            actionHandler.closeCreateWorldDialog();
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.nameFieldX, layout.nameFieldY,
                layout.dialogInputWidth, layout.dialogInputHeight)) {
            inputHandler.setNameInputMode(true);
            return;
        }
        if (layout.hitRect(mouseX, mouseY, layout.seedFieldX, layout.seedFieldY,
                layout.dialogInputWidth, layout.dialogInputHeight)) {
            inputHandler.setNameInputMode(false);
        }
    }

    public void handleMouseWheel(double yOffset) {
        if (!stateManager.isAnyDialogOpen()) {
            stateManager.scroll(-yOffset);
        }
    }
}
