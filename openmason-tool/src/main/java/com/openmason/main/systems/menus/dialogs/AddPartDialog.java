package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.menus.dialogs.icons.PartShapeIconManager;
import imgui.ImGui;
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

    private static final String POPUP_ID = "Add Model Part##addPart";
    private static final float DIALOG_WIDTH = 420;
    private static final float ICON_SIZE = 24f;

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

        if (ModalDialogs.begin(POPUP_ID, DIALOG_WIDTH)) {

            // Part name input
            ImGui.text("Part Name:");
            ImGui.setNextItemWidth(-1);
            ImGui.inputText("##part_name", partName);

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Shape selection (scrollable child so dialog height is fixed)
            ImGui.text("Shape:");
            ImGui.spacing();

            PartShapeIconManager iconManager = PartShapeIconManager.getInstance();
            ImGui.beginChild("##shape_list", 0, 280, true);
            for (int i = 0; i < shapes.length; i++) {
                int textureId = iconManager.getIconTexture(shapes[i]);
                if (textureId != -1) {
                    ImGui.image(textureId, ICON_SIZE, ICON_SIZE);
                    ImGui.sameLine();
                    // Vertically align the radio button with the icon center
                    float y = ImGui.getCursorPosY();
                    ImGui.setCursorPosY(y + (ICON_SIZE - ImGui.getTextLineHeight()) * 0.5f);
                }
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
            ImGui.endChild();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.primary("Add", true, true)) {
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
            if (ModalDialogs.cancel()) {
                logger.debug("Add part dialog cancelled");
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ModalDialogs.end();
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
