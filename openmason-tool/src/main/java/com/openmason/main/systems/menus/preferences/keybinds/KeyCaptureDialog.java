package com.openmason.main.systems.menus.preferences.keybinds;

import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import imgui.ImGui;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.flag.ImGuiCol;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for capturing keyboard input to assign new keybinds.
 * <p>
 * Displays a modal popup that captures the next key press combination
 * (key + modifiers) and allows the user to confirm or cancel the capture.
 * </p>
 *
 * @author Open Mason Team
 */
public class KeyCaptureDialog {

    private static final Logger logger = LoggerFactory.getLogger(KeyCaptureDialog.class);

    private static final String POPUP_ID = "Capture Keybind##keyCapture";

    private boolean isCapturing = false;
    private int capturedFrame = -1;
    private String capturingActionId = null;
    private String capturingActionName = null;
    private ShortcutKey capturedKey = null;
    private String statusMessage = "";
    private Runnable onConfirm = null;
    private Runnable onCancel = null;

    /**
     * Start capturing input for a specific action.
     *
     * @param actionId   the action ID being rebound
     * @param actionName the display name of the action
     * @param onConfirm  callback to execute when user confirms the captured key
     * @param onCancel   callback to execute when user cancels
     */
    public void startCapture(String actionId, String actionName, Runnable onConfirm, Runnable onCancel) {
        this.isCapturing = true;
        this.capturingActionId = actionId;
        this.capturingActionName = actionName;
        this.capturedKey = null;
        this.statusMessage = "Press any key combination... (Esc to cancel)";
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        logger.debug("Started key capture for action: {}", actionId);
    }

    /**
     * Render the key capture dialog.
     * Call this in the main render loop.
     */
    public void render() {
        if (!isCapturing) {
            return;
        }

        ModalDialogs.openIfNeeded(POPUP_ID);

        if (ModalDialogs.begin(POPUP_ID, 320)) {
            ImGui.text("Rebind: " + capturingActionName);
            ImGui.spacing();

            // Capture key input
            if (capturedKey == null) {
                ShortcutKey pressed = captureKeyPress();
                if (pressed != null) {
                    if (ShortcutKey.isValidKeybind(pressed)) {
                        capturedKey = pressed;
                        capturedFrame = ImGui.getFrameCount();
                        statusMessage = "Captured: " + pressed.getDisplayName();
                        logger.debug("Captured key: {} for action: {}", pressed.getDisplayName(), capturingActionId);
                    } else {
                        statusMessage = "Invalid key! Reserved for system use. Try another key...";
                        logger.warn("Invalid key captured (system reserved)");
                    }
                }
            }

            // Status message
            if (statusMessage.startsWith("Invalid")) {
                ThemedWidgets.statusTextWrapped(ThemeColors.Tone.ERROR, statusMessage);
            } else {
                ImGui.textWrapped(statusMessage);
            }

            // Display captured key as a keycap (FrameBg)
            if (capturedKey != null) {
                ImGui.spacing();
                ImGui.text("New Keybind: ");
                ImGui.sameLine();
                ThemeColors.push(ImGuiCol.Button, ImGuiCol.FrameBg);
                ThemeColors.push(ImGuiCol.ButtonHovered, ImGuiCol.FrameBg);
                ThemeColors.push(ImGuiCol.ButtonActive, ImGuiCol.FrameBg);
                ImGui.button(capturedKey.getDisplayName(), 120, ModalDialogs.BTN_H);
                ImGui.popStyleColor(3);
            }

            ModalDialogs.buttonsBegin();
            if (capturedKey != null) {
                // Enter is itself a capturable key, so it only confirms once a key is already captured
                // (and not on the frame that captured it).
                boolean enterConfirms = ImGui.getFrameCount() > capturedFrame
                        && ModalDialogs.enterPressed(false);
                if (ModalDialogs.primaryClickOnly("Assign") || enterConfirms) {
                    confirmCapture();
                }
            }
            if (ModalDialogs.cancel()) {
                cancelCapture();
            }

            ModalDialogs.end();
        }
    }

    /**
     * Capture a key press from ImGui input state.
     *
     * @return the captured ShortcutKey, or null if no key pressed
     */
    private ShortcutKey captureKeyPress() {
        // Escape is the dialog's Cancel (handled by ModalDialogs.cancel()).
        // Get current modifier state
        boolean ctrlPressed = ImGui.getIO().getKeyCtrl();
        boolean shiftPressed = ImGui.getIO().getKeyShift();
        boolean altPressed = ImGui.getIO().getKeyAlt();

        // Check all common keys for presses
        // A-Z keys
        for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
            if (KeyCodeTranslator.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        // 0-9 keys
        for (int key = GLFW.GLFW_KEY_0; key <= GLFW.GLFW_KEY_9; key++) {
            if (KeyCodeTranslator.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        // Function keys
        for (int key = GLFW.GLFW_KEY_F1; key <= GLFW.GLFW_KEY_F12; key++) {
            if (KeyCodeTranslator.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        // Special keys
        int[] specialKeys = {
                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_DELETE,
                GLFW.GLFW_KEY_BACKSPACE,
                GLFW.GLFW_KEY_TAB,
                GLFW.GLFW_KEY_SPACE,
                GLFW.GLFW_KEY_COMMA,
                GLFW.GLFW_KEY_PERIOD,
                GLFW.GLFW_KEY_SLASH,
                GLFW.GLFW_KEY_EQUAL,
                GLFW.GLFW_KEY_MINUS,
                GLFW.GLFW_KEY_KP_ADD,
                GLFW.GLFW_KEY_KP_SUBTRACT,
                GLFW.GLFW_KEY_KP_ENTER,
                GLFW.GLFW_KEY_KP_0
        };

        for (int key : specialKeys) {
            if (KeyCodeTranslator.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        return null;
    }

    /**
     * Confirm the captured keybind and close the dialog.
     */
    private void confirmCapture() {
        if (capturedKey != null && onConfirm != null) {
            isCapturing = false;
            ImGui.closeCurrentPopup();
            onConfirm.run();
            logger.debug("Key capture confirmed for action: {}", capturingActionId);
        }
    }

    /**
     * Cancel the capture and close the dialog.
     */
    private void cancelCapture() {
        isCapturing = false;
        capturedKey = null;
        ImGui.closeCurrentPopup();
        if (onCancel != null) {
            onCancel.run();
        }
        logger.debug("Key capture cancelled for action: {}", capturingActionId);
    }

    /**
     * Gets the captured key (only valid after startCapture and before confirm/cancel).
     *
     * @return the captured key, or null if none captured
     */
    public ShortcutKey getCapturedKey() {
        return capturedKey;
    }

    /**
     * Checks if currently capturing.
     *
     * @return true if capturing is active
     */
    public boolean isCapturing() {
        return isCapturing;
    }
}
