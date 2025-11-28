package com.openmason.ui.dialogs;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Export format selection dialog for texture creator.
 * Allows user to choose between PNG (flattened export) and OMT (project export).
 */
public class ExportFormatDialog {

    private static final Logger logger = LoggerFactory.getLogger(ExportFormatDialog.class);

    /**
     * Export format options.
     */
    public enum ExportFormat {
        PNG("PNG Image", "Export flattened image (all visible layers combined)"),
        OMT("OMT Project", "Export project with all layers preserved");

        private final String displayName;
        private final String description;

        ExportFormat(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    private boolean isOpen = false;
    private final ImInt selectedFormat = new ImInt(0); // 0 = PNG, 1 = OMT
    private ExportFormatCallback callback;

    /**
     * Show the export format dialog.
     *
     * @param callback callback to receive selected format
     */
    public void show(ExportFormatCallback callback) {
        this.isOpen = true;
        this.callback = callback;
        this.selectedFormat.set(0); // Default to PNG
        logger.debug("Export format dialog opened");
    }

    /**
     * Render the export format dialog.
     * Call this every frame from your main render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // Center the modal
        ImGui.setNextWindowSize(400, 200);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - 200,
                ImGui.getMainViewport().getCenterY() - 100
        );

        // Open modal popup
        if (ImGui.beginPopupModal("Export Format", ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.text("Choose export format:");
            ImGui.spacing();

            // PNG option
            if (ImGui.radioButton(ExportFormat.PNG.getDisplayName(), selectedFormat.get() == 0)) {
                selectedFormat.set(0);
            }
            ImGui.indent();
            ImGui.textWrapped(ExportFormat.PNG.getDescription());
            ImGui.unindent();

            ImGui.spacing();

            // OMT option
            if (ImGui.radioButton(ExportFormat.OMT.getDisplayName(), selectedFormat.get() == 1)) {
                selectedFormat.set(1);
            }
            ImGui.indent();
            ImGui.textWrapped(ExportFormat.OMT.getDescription());
            ImGui.unindent();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Buttons
            if (ImGui.button("OK", 120, 0)) {
                ExportFormat format = selectedFormat.get() == 0 ? ExportFormat.PNG : ExportFormat.OMT;
                logger.info("Export format selected: {}", format);

                if (callback != null) {
                    callback.onFormatSelected(format);
                }

                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.sameLine();

            if (ImGui.button("Cancel", 120, 0)) {
                logger.debug("Export format dialog cancelled");
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open the popup (only needs to be called once)
        if (isOpen && !ImGui.isPopupOpen("Export Format")) {
            ImGui.openPopup("Export Format");
        }
    }

    /**
     * Check if dialog is currently open.
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Callback interface for format selection.
     */
    public interface ExportFormatCallback {
        void onFormatSelected(ExportFormat format);
    }
}
