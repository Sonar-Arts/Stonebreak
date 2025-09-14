package com.stonebreak.ui.worldSelect.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.worldSelect.config.WorldSelectConfig;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.handlers.WorldInputHandler;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the create world dialog for the WorldSelectScreen.
 * Handles dialog background, input fields, buttons, and cursor blinking.
 */
public class CreateDialogRenderer {

    private final UIRenderer uiRenderer;
    private final WorldStateManager stateManager;
    private final WorldInputHandler inputHandler;

    // Cursor blinking animation
    private long lastCursorBlink = System.currentTimeMillis();
    private boolean showCursor = true;

    public CreateDialogRenderer(UIRenderer uiRenderer, WorldStateManager stateManager, WorldInputHandler inputHandler) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.inputHandler = inputHandler;
    }

    /**
     * Renders the complete create world dialog.
     */
    public void renderCreateDialog(int windowWidth, int windowHeight) {
        if (!stateManager.isShowCreateDialog()) {
            return;
        }

        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        // Draw overlay
        renderOverlay(windowWidth, windowHeight);

        // Draw dialog
        renderDialogBackground(centerX, centerY);
        renderDialogContent(centerX, centerY);
    }

    /**
     * Renders the dark overlay behind the dialog.
     */
    private void renderOverlay(int windowWidth, int windowHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, windowWidth, windowHeight);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.OVERLAY_COLOR_R,
                WorldSelectConfig.OVERLAY_COLOR_G,
                WorldSelectConfig.OVERLAY_COLOR_B,
                WorldSelectConfig.OVERLAY_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgFill(vg);
        }
    }

    /**
     * Renders the dialog background and border.
     */
    private void renderDialogBackground(float centerX, float centerY) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float dialogX = centerX - WorldSelectConfig.DIALOG_WIDTH / 2.0f;
            float dialogY = centerY - WorldSelectConfig.DIALOG_HEIGHT / 2.0f;

            // Draw dialog background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, dialogX, dialogY, WorldSelectConfig.DIALOG_WIDTH, WorldSelectConfig.DIALOG_HEIGHT,
                          WorldSelectConfig.DIALOG_CORNER_RADIUS);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.DIALOG_BG_COLOR_R,
                WorldSelectConfig.DIALOG_BG_COLOR_G,
                WorldSelectConfig.DIALOG_BG_COLOR_B,
                WorldSelectConfig.DIALOG_BG_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgFill(vg);

            // Draw dialog border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, dialogX, dialogY, WorldSelectConfig.DIALOG_WIDTH, WorldSelectConfig.DIALOG_HEIGHT,
                          WorldSelectConfig.DIALOG_CORNER_RADIUS);
            nvgStrokeColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.DIALOG_BORDER_COLOR_R,
                WorldSelectConfig.DIALOG_BORDER_COLOR_G,
                WorldSelectConfig.DIALOG_BORDER_COLOR_B,
                WorldSelectConfig.DIALOG_BORDER_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgStrokeWidth(vg, WorldSelectConfig.BORDER_WIDTH);
            nvgStroke(vg);
        }
    }

    /**
     * Renders the dialog content (title, input fields, buttons).
     */
    private void renderDialogContent(float centerX, float centerY) {
        float dialogY = centerY - WorldSelectConfig.DIALOG_HEIGHT / 2.0f;

        // Render dialog title
        renderDialogTitle(centerX, dialogY + 30);

        // Render input fields
        renderNameInputField(centerX, dialogY + 80);
        renderSeedInputField(centerX, dialogY + 140);

        // Render buttons
        renderDialogButtons(centerX, dialogY + WorldSelectConfig.DIALOG_HEIGHT - 70);

        // Render validation message if needed
        renderValidationMessage(centerX, dialogY + 200);
    }

    /**
     * Renders the dialog title.
     */
    private void renderDialogTitle(float centerX, float y) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            nvgFontSize(vg, WorldSelectConfig.DIALOG_TITLE_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_COLOR_R,
                WorldSelectConfig.TEXT_COLOR_G,
                WorldSelectConfig.TEXT_COLOR_B,
                WorldSelectConfig.TEXT_COLOR_A,
                NVGColor.malloc(stack)
            ));

            nvgText(vg, centerX, y, "Create New World");
        }
    }

    /**
     * Renders the world name input field.
     */
    private void renderNameInputField(float centerX, float y) {
        boolean isActive = inputHandler.isNameInputMode();
        String text = stateManager.getNewWorldName();

        renderInputField(centerX, y, "World Name:", text, isActive);
    }

    /**
     * Renders the world seed input field.
     */
    private void renderSeedInputField(float centerX, float y) {
        boolean isActive = !inputHandler.isNameInputMode();
        String text = stateManager.getNewWorldSeed();

        renderInputField(centerX, y, "Seed (optional):", text, isActive);
    }

    /**
     * Renders a generic input field.
     */
    private void renderInputField(float centerX, float y, String label, String text, boolean isActive) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float fieldX = centerX - WorldSelectConfig.INPUT_FIELD_WIDTH / 2.0f;

            // Render label
            nvgFontSize(vg, WorldSelectConfig.LABEL_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_COLOR_R,
                WorldSelectConfig.TEXT_COLOR_G,
                WorldSelectConfig.TEXT_COLOR_B,
                WorldSelectConfig.TEXT_COLOR_A,
                NVGColor.malloc(stack)
            ));

            nvgText(vg, fieldX, y - 8, label);

            // Render input field background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, fieldX, y + 5, WorldSelectConfig.INPUT_FIELD_WIDTH, WorldSelectConfig.INPUT_FIELD_HEIGHT,
                          WorldSelectConfig.INPUT_CORNER_RADIUS);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.INPUT_BG_COLOR_R,
                WorldSelectConfig.INPUT_BG_COLOR_G,
                WorldSelectConfig.INPUT_BG_COLOR_B,
                WorldSelectConfig.INPUT_BG_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgFill(vg);

            // Render input field border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, fieldX, y + 5, WorldSelectConfig.INPUT_FIELD_WIDTH, WorldSelectConfig.INPUT_FIELD_HEIGHT,
                          WorldSelectConfig.INPUT_CORNER_RADIUS);

            if (isActive) {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(
                    WorldSelectConfig.INPUT_FOCUSED_BORDER_COLOR_R,
                    WorldSelectConfig.INPUT_FOCUSED_BORDER_COLOR_G,
                    WorldSelectConfig.INPUT_FOCUSED_BORDER_COLOR_B,
                    WorldSelectConfig.INPUT_FOCUSED_BORDER_COLOR_A,
                    NVGColor.malloc(stack)
                ));
            } else {
                nvgStrokeColor(vg, uiRenderer.nvgRGBA(
                    WorldSelectConfig.INPUT_BORDER_COLOR_R,
                    WorldSelectConfig.INPUT_BORDER_COLOR_G,
                    WorldSelectConfig.INPUT_BORDER_COLOR_B,
                    WorldSelectConfig.INPUT_BORDER_COLOR_A,
                    NVGColor.malloc(stack)
                ));
            }
            nvgStrokeWidth(vg, WorldSelectConfig.BORDER_WIDTH);
            nvgStroke(vg);

            // Render text
            if (!text.isEmpty()) {
                nvgFontSize(vg, WorldSelectConfig.INPUT_FONT_SIZE);
                nvgFontFace(vg, "minecraft");
                nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
                nvgFillColor(vg, uiRenderer.nvgRGBA(
                    WorldSelectConfig.TEXT_COLOR_R,
                    WorldSelectConfig.TEXT_COLOR_G,
                    WorldSelectConfig.TEXT_COLOR_B,
                    WorldSelectConfig.TEXT_COLOR_A,
                    NVGColor.malloc(stack)
                ));

                float textX = fieldX + WorldSelectConfig.INPUT_FIELD_PADDING;
                float textY = y + 5 + WorldSelectConfig.INPUT_FIELD_HEIGHT / 2.0f;
                nvgText(vg, textX, textY, text);
            }

            // Render cursor if field is active
            if (isActive) {
                renderCursor(fieldX, y + 5, text);
            }
        }
    }

    /**
     * Renders the blinking cursor in the active input field.
     */
    private void renderCursor(float fieldX, float fieldY, String text) {
        // Update cursor blinking
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCursorBlink > (1000 / WorldSelectConfig.CURSOR_BLINK_SPEED) / 2) {
            showCursor = !showCursor;
            lastCursorBlink = currentTime;
        }

        if (!showCursor) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Calculate cursor position
            float textWidth = 0;
            if (!text.isEmpty()) {
                nvgFontSize(vg, WorldSelectConfig.INPUT_FONT_SIZE);
                nvgFontFace(vg, "minecraft");
                java.nio.FloatBuffer bounds = stack.mallocFloat(4);
                textWidth = nvgTextBounds(vg, 0, 0, text, bounds);
            }

            float cursorX = fieldX + WorldSelectConfig.INPUT_FIELD_PADDING + textWidth;
            float cursorY = fieldY + 5;
            float cursorHeight = WorldSelectConfig.INPUT_FIELD_HEIGHT - 10;

            // Draw cursor
            nvgBeginPath(vg);
            nvgRect(vg, cursorX, cursorY, WorldSelectConfig.CURSOR_WIDTH, cursorHeight);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_COLOR_R,
                WorldSelectConfig.TEXT_COLOR_G,
                WorldSelectConfig.TEXT_COLOR_B,
                WorldSelectConfig.TEXT_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgFill(vg);
        }
    }

    /**
     * Renders the dialog buttons (Create, Cancel).
     */
    private void renderDialogButtons(float centerX, float y) {
        float buttonY = y;

        // Create button
        float createButtonX = centerX - WorldSelectConfig.BUTTON_WIDTH - 10;
        boolean canCreate = stateManager.isValidWorldName();

        if (canCreate) {
            uiRenderer.drawButton("Create", createButtonX, buttonY,
                                          WorldSelectConfig.BUTTON_WIDTH, WorldSelectConfig.BUTTON_HEIGHT, false);
        } else {
            // Draw disabled button
            renderDisabledButton("Create", createButtonX, buttonY,
                               WorldSelectConfig.BUTTON_WIDTH, WorldSelectConfig.BUTTON_HEIGHT);
        }

        // Cancel button
        float cancelButtonX = centerX + 10;
        uiRenderer.drawButton("Cancel", cancelButtonX, buttonY,
                                     WorldSelectConfig.BUTTON_WIDTH, WorldSelectConfig.BUTTON_HEIGHT, false);
    }

    /**
     * Renders a disabled button.
     */
    private void renderDisabledButton(String text, float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Draw button background (dimmed)
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, WorldSelectConfig.BUTTON_CORNER_RADIUS);
            nvgFillColor(vg, uiRenderer.nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Draw button border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, height, WorldSelectConfig.BUTTON_CORNER_RADIUS);
            nvgStrokeColor(vg, uiRenderer.nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 2);
            nvgStroke(vg);

            // Draw button text (disabled color)
            nvgFontSize(vg, WorldSelectConfig.BUTTON_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_DISABLED_COLOR_R,
                WorldSelectConfig.TEXT_DISABLED_COLOR_G,
                WorldSelectConfig.TEXT_DISABLED_COLOR_B,
                WorldSelectConfig.TEXT_DISABLED_COLOR_A,
                NVGColor.malloc(stack)
            ));

            nvgText(vg, x + width / 2.0f, y + height / 2.0f, text);
        }
    }

    /**
     * Renders validation messages if needed.
     */
    private void renderValidationMessage(float centerX, float y) {
        String worldName = stateManager.getNewWorldName().trim();
        if (worldName.isEmpty()) {
            return; // No message for empty name
        }

        if (stateManager.isValidWorldName()) {
            return; // No message if valid
        }

        // Show error message
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            nvgFontSize(vg, WorldSelectConfig.LABEL_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(255, 100, 100, 255, NVGColor.malloc(stack)));

            String message = "A world with that name already exists";
            nvgText(vg, centerX, y, message);
        }
    }
}