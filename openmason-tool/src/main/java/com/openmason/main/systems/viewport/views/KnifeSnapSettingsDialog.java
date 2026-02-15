package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.viewport.input.KnifeSnapSettings;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Floating dialog for configuring knife tool grid snap settings.
 * Draggable, non-blocking — does not gray out or lock any other UI.
 */
public class KnifeSnapSettingsDialog {

    private static final Logger logger = LoggerFactory.getLogger(KnifeSnapSettingsDialog.class);

    private static final String WINDOW_ID = "Knife Snap Settings";

    private static final float[] INCREMENT_PRESETS = {1.0f, 0.5f, 0.25f, 0.125f, 0.0625f};
    private static final String[] INCREMENT_LABELS = {"1.0", "0.5", "0.25", "0.125", "0.0625"};

    private final KnifeSnapSettings settings;
    private final ImBoolean isOpen = new ImBoolean(false);

    // Temporary ImGui state for checkbox binding
    private final ImBoolean enabledCheckbox = new ImBoolean();

    public KnifeSnapSettingsDialog(KnifeSnapSettings settings) {
        this.settings = settings;
    }

    /**
     * Open the dialog.
     */
    public void open() {
        isOpen.set(true);
        enabledCheckbox.set(settings.isEnabled());
        logger.debug("Knife snap settings dialog opened");
    }

    /**
     * Render the dialog as a normal draggable window. Call every frame.
     */
    public void render() {
        if (!isOpen.get()) {
            return;
        }

        // Center on first appearance only; user can drag freely after
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                ImGuiCond.Appearing,
                0.5f, 0.5f
        );

        int flags = ImGuiWindowFlags.AlwaysAutoResize
                  | ImGuiWindowFlags.NoCollapse;

        if (ImGui.begin(WINDOW_ID, isOpen, flags)) {
            // Enable checkbox
            enabledCheckbox.set(settings.isEnabled());
            if (ImGui.checkbox("Enable Knife Snap", enabledCheckbox)) {
                settings.setEnabled(enabledCheckbox.get());
            }

            ImGui.spacing();

            // Increment combo
            ImGui.text("Increment:");
            ImGui.sameLine();
            ImGui.setNextItemWidth(120);
            renderIncrementCombo();
        }
        ImGui.end();
    }

    /**
     * Render the increment selection combo using beginCombo for clean item iteration.
     */
    private void renderIncrementCombo() {
        float currentIncrement = settings.getIncrement();
        String preview = formatIncrement(currentIncrement);

        if (ImGui.beginCombo("##knifeSnapIncrementCombo", preview)) {
            for (int i = 0; i < INCREMENT_PRESETS.length; i++) {
                boolean isSelected = Math.abs(currentIncrement - INCREMENT_PRESETS[i]) < 0.0001f;
                if (ImGui.selectable(INCREMENT_LABELS[i], isSelected)) {
                    settings.setIncrement(INCREMENT_PRESETS[i]);
                }
                if (isSelected) {
                    ImGui.setItemDefaultFocus();
                }
            }
            ImGui.endCombo();
        }
    }

    private String formatIncrement(float increment) {
        for (int i = 0; i < INCREMENT_PRESETS.length; i++) {
            if (Math.abs(increment - INCREMENT_PRESETS[i]) < 0.0001f) {
                return INCREMENT_LABELS[i];
            }
        }
        return String.valueOf(increment);
    }
}
