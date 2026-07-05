package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarButton;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * New / Open / Save / Save As row plus a right-aligned save status. Routes
 * file operations through {@link FileDialogService} so the native picker is
 * used consistently with the rest of the tool.
 *
 * <p>Buttons are Mortar-painted when a Skija context exists (Save turns
 * PRIMARY when there are unsaved changes); the ImGui widgets remain as the
 * fallback path.
 */
public final class FileBarPanel implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FileBarPanel.class);

    private static final float BUTTON_HEIGHT = 26f;
    private static final float BUTTON_GAP = 6f;
    private static final float ROW_HEIGHT = BUTTON_HEIGHT + 2f;

    private final AnimationEditorController controller;
    private final MortarRegion region = new MortarRegion();
    private FileDialogService fileDialogService;

    public FileBarPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void setFileDialogService(FileDialogService service) {
        this.fileDialogService = service;
    }

    public void render() {
        if (region.isAvailable()) {
            renderMortar();
        } else {
            renderImGuiFallback();
        }
        renderStatus();
    }

    private void renderMortar() {
        boolean hasPath = controller.state().filePath() != null;
        // A never-saved clip must still be saveable — Save falls back to Save As.
        boolean canSave = !hasPath || controller.state().dirty();

        float[] widths = {52f, 68f, 56f, 84f};
        String[] ids = {"new", "open", "save", "saveAs"};
        String[] labels = {"New", "Open...", "Save", "Save As..."};

        float totalWidth = BUTTON_GAP * (widths.length - 1);
        for (float w : widths) totalWidth += w;

        region.begin(totalWidth, ROW_HEIGHT);
        float x = 0f;
        for (int i = 0; i < ids.length; i++) {
            MortarButton.Variant variant = (i == 2 && canSave)
                    ? MortarButton.Variant.PRIMARY
                    : MortarButton.Variant.SECONDARY;
            region.add(ids[i], x, 1f, widths[i], BUTTON_HEIGHT,
                    new MortarButton(labels[i], variant));
            x += widths[i] + BUTTON_GAP;
        }
        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        if (input.isClicked("new")) controller.newClip();
        if (input.isClicked("open")) promptOpen();
        if (input.isClicked("save") && canSave) requestSave();
        if (input.isClicked("saveAs")) promptSaveAs();
    }

    private void renderImGuiFallback() {
        if (ImGui.button("New")) {
            controller.newClip();
        }
        AnimUI.tooltip("Discard current clip and start a new untitled animation.");

        ImGui.sameLine();
        if (ImGui.button("Open...")) {
            promptOpen();
        }
        AnimUI.tooltip("Load an .omanim animation from disk.");

        ImGui.sameLine();
        boolean hasPath = controller.state().filePath() != null;
        boolean canSave = !hasPath || controller.state().dirty();
        AnimUI.beginDisabled(!canSave);
        if (ImGui.button("Save")) {
            requestSave();
        }
        AnimUI.endDisabled(!canSave);
        AnimUI.tooltip(hasPath
                ? "Save changes to the current file (Ctrl+S)."
                : "Save the clip — prompts for a file path (Ctrl+S).");

        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            promptSaveAs();
        }
        AnimUI.tooltip("Save the current clip to a new .omanim file.");
    }

    /**
     * Save to the current file, falling back to the Save As dialog when the
     * clip has never been saved. Single save entry point shared by the Save
     * button (both render paths) and the Ctrl+S shortcut.
     */
    public void requestSave() {
        if (controller.state().filePath() != null) {
            controller.save();
        } else {
            promptSaveAs();
        }
    }

    public void promptOpen() {
        if (fileDialogService == null) {
            logger.warn("File dialog service not available — cannot open animation file");
            return;
        }
        fileDialogService.showOpenOMADialog(controller::load);
    }

    public void promptSaveAs() {
        if (fileDialogService == null) {
            logger.warn("File dialog service not available — cannot save animation file");
            return;
        }
        fileDialogService.showSaveOMADialog(controller::saveAs);
    }

    private void renderStatus() {
        String status = saveStatusLabel();
        String path = controller.state().filePath();
        String fullStatus = path != null ? status + "  " + path : status;

        ImGui.sameLine();
        float available = ImGui.getContentRegionAvailX();
        float textWidth = ImGui.calcTextSize(fullStatus).x;
        if (available > textWidth + 8f) {
            ImGui.dummy(available - textWidth - 8f, 0);
            ImGui.sameLine();
        }
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled(fullStatus);
    }

    private String saveStatusLabel() {
        boolean hasPath = controller.state().filePath() != null;
        boolean dirty = controller.state().dirty();
        if (!hasPath) return "[unsaved]";
        return dirty ? "[modified]" : "[saved]";
    }

    @Override
    public void close() {
        region.close();
    }
}
