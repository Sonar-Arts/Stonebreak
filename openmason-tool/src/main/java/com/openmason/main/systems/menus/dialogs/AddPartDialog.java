package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.rendering.model.gmr.parts.PartShapeFactory;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for adding a new model part.
 * Allows the user to choose a primitive shape and name for the new part.
 *
 * <p>Follows the same modal pattern as {@link ExportFormatDialog}:
 * callback-based result, centered positioning, radio button selection.
 */
public class AddPartDialog {

    private static final Logger logger = LoggerFactory.getLogger(AddPartDialog.class);

    private static final String POPUP_ID = "Add Model Part";
    private static final float DIALOG_WIDTH = 380;
    private static final float DIALOG_HEIGHT = 260;

    private boolean isOpen = false;
    private final ImInt selectedShape = new ImInt(0);
    private final ImString partName = new ImString(64);
    private AddPartCallback callback;

    // Available shapes
    private final PartShapeFactory.Shape[] shapes = PartShapeFactory.Shape.values();

    /**
     * Show the add part dialog.
     *
     * @param callback Callback to receive the selected shape and name
     */
    public void show(AddPartCallback callback) {
        this.isOpen = true;
        this.callback = callback;
        this.selectedShape.set(0);
        this.partName.set("");
        logger.debug("Add part dialog opened");
    }

    /**
     * Render the dialog. Call every frame from the main render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // Center the modal
        ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - DIALOG_WIDTH / 2,
                ImGui.getMainViewport().getCenterY() - DIALOG_HEIGHT / 2
        );

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {

            // Part name input
            ImGui.text("Part Name:");
            ImGui.setNextItemWidth(-1);
            ImGui.inputText("##part_name", partName);

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Shape selection
            ImGui.text("Shape:");
            ImGui.spacing();

            for (int i = 0; i < shapes.length; i++) {
                if (ImGui.radioButton(shapes[i].getDisplayName(), selectedShape.get() == i)) {
                    selectedShape.set(i);
                }
                ImGui.indent();
                ImGui.textDisabled(shapes[i].getDescription());
                ImGui.unindent();

                if (i < shapes.length - 1) {
                    ImGui.spacing();
                }
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Buttons
            if (ImGui.button("Add", 120, 0)) {
                PartShapeFactory.Shape shape = shapes[selectedShape.get()];
                String name = partName.get().trim();
                if (name.isEmpty()) {
                    name = shape.getDisplayName(); // Default to shape name
                }

                logger.info("Adding part '{}' with shape {}", name, shape);

                if (callback != null) {
                    callback.onPartAdded(shape, name);
                }

                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.sameLine();

            if (ImGui.button("Cancel", 120, 0)) {
                logger.debug("Add part dialog cancelled");
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            // ESC to close
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open popup on first frame
        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    /**
     * Check if dialog is currently open.
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Callback interface for part creation.
     */
    public interface AddPartCallback {
        /**
         * Called when the user confirms adding a new part.
         *
         * @param shape The selected primitive shape
         * @param name  The user-provided part name
         */
        void onPartAdded(PartShapeFactory.Shape shape, String name);
    }
}
