package com.openmason.main.systems.menus.textureCreator.dialogs;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for creating a new texture with user-defined canvas dimensions.
 */
public class NewTextureDialog {

    private static final Logger logger = LoggerFactory.getLogger(NewTextureDialog.class);

    // Dialog state
    private boolean isOpen = false;
    private TextureCreatorState.CanvasSize confirmedSelection = null;
    private boolean needsPositioning = false;

    // Input fields
    private final ImInt inputWidth = new ImInt(16);
    private final ImInt inputHeight = new ImInt(16);

    // Validation
    private static final int MIN_DIMENSION = 1;
    private static final int MAX_DIMENSION = 4096;

    // Dialog dimensions
    private static final float DIALOG_WIDTH = 340.0f;
    private static final float DIALOG_HEIGHT = 200.0f;

    public NewTextureDialog() {
        logger.debug("New texture dialog created");
    }

    /**
     * Show the dialog (opens modal popup).
     */
    public void show() {
        isOpen = true;
        confirmedSelection = null;
        needsPositioning = true;
        inputWidth.set(16);
        inputHeight.set(16);
        logger.debug("New texture dialog opened");
    }

    /**
     * Check if dialog is currently open.
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Get confirmed canvas size selection.
     * Returns the selected size if user clicked "Create", null otherwise.
     * Resets to null after being read.
     */
    public TextureCreatorState.CanvasSize getSelectedCanvasSize() {
        TextureCreatorState.CanvasSize result = confirmedSelection;
        confirmedSelection = null;
        return result;
    }

    /**
     * Render the dialog.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        if (needsPositioning) {
            ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
            ImGui.setNextWindowPos(
                    ImGui.getMainViewport().getSizeX() / 2.0f - DIALOG_WIDTH / 2.0f,
                    ImGui.getMainViewport().getSizeY() / 2.0f - DIALOG_HEIGHT / 2.0f
            );
            needsPositioning = false;
        }

        if (ImGui.beginPopupModal("New Texture")) {

            ImGui.spacing();
            ImGui.text("Canvas Dimensions");
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Width input
            float labelWidth = 60.0f;
            float fieldWidth = 120.0f;

            ImGui.text("Width");
            ImGui.sameLine(labelWidth);
            ImGui.pushItemWidth(fieldWidth);
            ImGui.inputInt("##tex_width", inputWidth, 1, 16, ImGuiInputTextFlags.None);
            ImGui.popItemWidth();

            ImGui.spacing();

            // Height input
            ImGui.text("Height");
            ImGui.sameLine(labelWidth);
            ImGui.pushItemWidth(fieldWidth);
            ImGui.inputInt("##tex_height", inputHeight, 1, 16, ImGuiInputTextFlags.None);
            ImGui.popItemWidth();

            // Clamp values
            if (inputWidth.get() < MIN_DIMENSION) inputWidth.set(MIN_DIMENSION);
            if (inputWidth.get() > MAX_DIMENSION) inputWidth.set(MAX_DIMENSION);
            if (inputHeight.get() < MIN_DIMENSION) inputHeight.set(MIN_DIMENSION);
            if (inputHeight.get() > MAX_DIMENSION) inputHeight.set(MAX_DIMENSION);

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Buttons
            float buttonWidth = 100.0f;
            float buttonHeight = 26.0f;
            float totalButtonWidth = buttonWidth * 2 + ImGui.getStyle().getItemSpacingX();
            float buttonStartX = (ImGui.getWindowWidth() - totalButtonWidth) / 2.0f;

            ImGui.setCursorPosX(buttonStartX);

            if (ImGui.button("Create", buttonWidth, buttonHeight)) {
                confirmedSelection = new TextureCreatorState.CanvasSize(inputWidth.get(), inputHeight.get());
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("Created new texture: {}x{}", inputWidth.get(), inputHeight.get());
            }

            ImGui.sameLine();

            if (ImGui.button("Cancel", buttonWidth, buttonHeight)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.debug("New texture dialog cancelled");
            }

            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        if (isOpen && !ImGui.isPopupOpen("New Texture")) {
            ImGui.openPopup("New Texture");
        }
    }

    /**
     * Close the dialog.
     */
    public void close() {
        isOpen = false;
        confirmedSelection = null;
    }
}
