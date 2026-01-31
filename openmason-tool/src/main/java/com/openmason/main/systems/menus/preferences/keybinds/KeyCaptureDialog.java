package com.openmason.main.systems.menus.preferences.keybinds;

import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
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

    private boolean isCapturing = false;
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
        this.statusMessage = "Press any key combination... (ESC to cancel)";
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

        // Open modal popup
        ImGui.openPopup("Capture Keybind");

        // Center the modal
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                0, // ImGuiCond.Always
                0.5f, 0.5f // pivot
        );

        // Begin modal popup
        if (ImGui.beginPopupModal("Capture Keybind", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoMove)) {
            // Title
            ImGui.text("Rebind: " + capturingActionName);
            ImGui.separator();
            ImGui.spacing();

            // Capture key input
            if (capturedKey == null) {
                ShortcutKey pressed = captureKeyPress();
                if (pressed != null) {
                    if (ShortcutKey.isValidKeybind(pressed)) {
                        capturedKey = pressed;
                        statusMessage = "Captured: " + pressed.getDisplayName();
                        logger.debug("Captured key: {} for action: {}", pressed.getDisplayName(), capturingActionId);
                    } else {
                        statusMessage = "Invalid key! Reserved for system use. Try another key...";
                        logger.warn("Invalid key captured (system reserved)");
                    }
                }
            }

            // Status message
            ImGui.textWrapped(statusMessage);
            ImGui.spacing();

            // Display captured key
            if (capturedKey != null) {
                ImGui.text("New Keybind: ");
                ImGui.sameLine();

                // Display as button-like style (same as keybind display)
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.6f, 0.2f, 1.0f); // Green tint
                ImGui.button(capturedKey.getDisplayName(), 120, 0);
                ImGui.popStyleColor();

                ImGui.spacing();
                ImGui.separator();
                ImGui.spacing();

                // Confirm and Cancel buttons
                if (ImGui.button("Confirm", 100, 0)) {
                    confirmCapture();
                }
                ImGui.sameLine();
                if (ImGui.button("Cancel", 100, 0)) {
                    cancelCapture();
                }
            } else {
                // Only cancel button when no key captured yet
                if (ImGui.button("Cancel", 100, 0)) {
                    cancelCapture();
                }
            }

            ImGui.endPopup();
        }
    }

    /**
     * Capture a key press from ImGui input state.
     *
     * @return the captured ShortcutKey, or null if no key pressed
     */
    private ShortcutKey captureKeyPress() {
        // Check for ESC to cancel
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            cancelCapture();
            return null;
        }

        // Get current modifier state
        boolean ctrlPressed = ImGui.getIO().getKeyCtrl();
        boolean shiftPressed = ImGui.getIO().getKeyShift();
        boolean altPressed = ImGui.getIO().getKeyAlt();

        // Check all common keys for presses
        // A-Z keys
        for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
            if (ImGui.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        // 0-9 keys
        for (int key = GLFW.GLFW_KEY_0; key <= GLFW.GLFW_KEY_9; key++) {
            if (ImGui.isKeyPressed(key)) {
                return new ShortcutKey(key, ctrlPressed, shiftPressed, altPressed);
            }
        }

        // Function keys
        for (int key = GLFW.GLFW_KEY_F1; key <= GLFW.GLFW_KEY_F12; key++) {
            if (ImGui.isKeyPressed(key)) {
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
            if (ImGui.isKeyPressed(key)) {
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
