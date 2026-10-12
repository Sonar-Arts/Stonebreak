package com.openmason.main.systems.uiEditor.view;

import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.toolbars.BaseToolbarRenderer;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The UI workspace's own toolbar (Unity UI Builder keeps its File menu inside the builder, not
 * in the editor's main menu): File and Window menus, quick Save/Export, the active document's
 * path, and the pop-out / dock-back control. Drawn above the UI dockspace in whichever window
 * holds the workspace; same flat style and height as the Scene toolbar so switching tabs does
 * not shift the panels.
 */
final class UiWorkspaceHeader extends BaseToolbarRenderer {

    private static final String FILE_POPUP = "##uiHeaderFile";
    private static final String WINDOW_POPUP = "##uiHeaderWindow";

    private final UiEditorWorkspace workspace;
    private BooleanSupplier detached = () -> false;
    private BooleanSupplier canDetach = () -> true;
    private Runnable toggleDetached;
    private float popupX;
    private float popupY;

    UiWorkspaceHeader(UiEditorWorkspace workspace) {
        this.workspace = workspace;
    }

    void setDockControls(BooleanSupplier detached, BooleanSupplier canDetach, Runnable toggleDetached) {
        this.detached = detached == null ? () -> false : detached;
        this.canDetach = canDetach == null ? () -> true : canDetach;
        this.toggleDetached = toggleDetached;
    }

    /** Pop Out is refused while UI is the main window's only tab (the other one is out). */
    private boolean dockToggleEnabled() {
        return detached.getAsBoolean() || canDetach.getAsBoolean();
    }

    private String dockTooltip() {
        if (detached.getAsBoolean()) {
            return "Put the UI workspace back into the main window as a tab";
        }
        return canDetach.getAsBoolean()
                ? "Open the UI workspace in its own window (you can also drag its tab off)"
                : "The main window keeps at least one tab: dock the other one back first";
    }

    void render() {
        UiEditorContext ctx = workspace.context();
        UiEditorDocument doc = ctx.doc();
        boolean unsaved = doc != null && (doc.isDirty() || ctx.hasPendingEdits(doc));

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 2.0f, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8.0f, 4.0f);
        pushFlatButtonStyle();

        menuButton("File", FILE_POPUP);
        ImGui.sameLine(0.0f, 2.0f);
        menuButton("Window", WINDOW_POPUP);

        renderThinSeparator();
        if (dimmedButton("Save", unsaved ? withShortcut("Save the active UI document", "ui.save")
                : doc == null ? "No UI document open" : "No unsaved changes", !unsaved)) {
            workspace.saveActive();
        }
        ImGui.sameLine(0.0f, 2.0f);
        boolean anyUnsaved = workspace.hasUnsavedChanges();
        if (dimmedButton("Save All", anyUnsaved ? "Save every open UI document with unsaved changes"
                : "No unsaved changes", !anyUnsaved)) {
            saveAll();
        }
        ImGui.sameLine(0.0f, 2.0f);
        if (dimmedButton("Export", doc == null ? "No UI document open"
                : "Export the active document as an SBUI for the game", doc == null)) {
            workspace.exportActive();
        }

        renderRightSide(ctx, doc, unsaved);

        popFlatButtonStyle();
        ImGui.popStyleVar(2);

        filePopup(doc, anyUnsaved);
        windowPopup();
        renderBottomBorder();
    }

    // ── toolbar pieces ──────────────────────────────────────────────────────

    private void menuButton(String label, String popup) {
        if (renderFlatButton(label, null)) {
            popupX = ImGui.getItemRectMinX();
            popupY = ImGui.getItemRectMaxY();
            ImGui.openPopup(popup);
        }
    }

    /** A flat button whose text dims when it has nothing to do; clicks are ignored then. */
    private boolean dimmedButton(String label, String tooltip, boolean dim) {
        if (dim) {
            ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
            ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, c.w);
        }
        boolean clicked = renderFlatButton(label, tooltip);
        if (dim) {
            ImGui.popStyleColor();
        }
        return clicked && !dim;
    }

    private void renderRightSide(UiEditorContext ctx, UiEditorDocument doc, boolean unsaved) {
        String dockLabel = detached.getAsBoolean() ? "Dock Back" : "Pop Out";
        float framePad = ImGui.getStyle().getFramePaddingX();
        float dockW = toggleDetached == null ? 0f : ImGui.calcTextSize(dockLabel).x + framePad * 2f;
        String path = null;
        if (doc != null) {
            String rel = doc.file() == null ? null : ctx.project.relative(doc.file());
            path = (rel != null ? rel : doc.file() != null ? doc.file().toString() : doc.title() + " (not saved yet)")
                    + (unsaved ? " *" : "");
        }
        float pathW = path == null ? 0f : ImGui.calcTextSize(path).x + 12f;
        ImGui.sameLine(0.0f, 0.0f);
        float avail = ImGui.getContentRegionAvailX();
        float rightPad = 6f;
        if (avail < dockW + rightPad) {
            return;
        }
        boolean showPath = path != null && avail > dockW + pathW + rightPad + 24f;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + avail - dockW - rightPad - (showPath ? pathW : 0f));
        if (showPath) {
            // Painted, not laid out as text: centred on the 28 px button row like the button labels.
            float x = ImGui.getCursorScreenPosX();
            float y = ImGui.getCursorScreenPosY() + (getToolbarHeight() - ImGui.getTextLineHeight()) / 2f;
            ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
            ImGui.getWindowDrawList().addText(x, y, ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, 0.85f), path);
            ImGui.dummy(pathW - 12f, getToolbarHeight());
            ImGui.sameLine(0.0f, 12f);
        }
        if (toggleDetached != null && dimmedButton(dockLabel, dockTooltip(), !dockToggleEnabled())) {
            toggleDetached.run();
        }
    }

    // ── menus ───────────────────────────────────────────────────────────────

    private void filePopup(UiEditorDocument doc, boolean anyUnsaved) {
        ImGui.setNextWindowPos(popupX, popupY, ImGuiCond.Appearing);
        if (!ImGui.beginPopup(FILE_POPUP)) {
            return;
        }
        if (ImGui.menuItem("New UI Screen...", shortcut("ui.new"))) {
            workspace.newDocument(UiDocumentTemplates.MENU_SCREEN);
        }
        if (ImGui.menuItem("New UI Component...")) {
            workspace.newDocument(UiDocumentTemplates.BUTTON_COMPONENT);
        }
        if (ImGui.menuItem("Open UI Document...", shortcut("ui.open"))) {
            workspace.openDocument();
        }
        if (ImGui.menuItem("Import SBUI into Project...")) {
            workspace.importSbuiFromMenu();
        }
        ImGui.separator();
        boolean has = doc != null;
        if (ImGui.menuItem("Save", shortcut("ui.save"), false, has)) {
            workspace.saveActive();
        }
        if (ImGui.menuItem("Save As...", "", false, has)) {
            workspace.saveActiveAs();
        }
        if (ImGui.menuItem("Save All", "", false, anyUnsaved)) {
            saveAll();
        }
        ImGui.separator();
        if (ImGui.menuItem("Export SBUI...", "", false, has)) {
            workspace.exportActive();
        }
        ImGui.separator();
        if (ImGui.menuItem("Close", shortcut("ui.close"), false, has)) {
            workspace.closeActive();
        }
        ImGui.endPopup();
    }

    private void windowPopup() {
        ImGui.setNextWindowPos(popupX, popupY, ImGuiCond.Appearing);
        if (!ImGui.beginPopup(WINDOW_POPUP)) {
            return;
        }
        if (toggleDetached != null) {
            if (ImGui.menuItem(detached.getAsBoolean() ? "Dock Back into Main Window" : "Open in New Window", "",
                    false, dockToggleEnabled())) {
                toggleDetached.run();
            }
            ImGui.separator();
        }
        if (ImGui.menuItem("Graphs...", "", false, workspace.context().doc() != null)) {
            workspace.openGraphs();
        }
        ImGui.separator();
        if (ImGui.menuItem("Reset Layout")) {
            workspace.resetLayout();
        }
        ImGui.endPopup();
    }

    private void saveAll() {
        List<String> failed = workspace.saveAllInPlace();
        UiEditorDocument doc = workspace.context().doc();
        if (doc != null && !failed.isEmpty()) {
            doc.setLastMessage("Not saved: " + String.join("; ", failed));
        }
    }

    private static String shortcut(String actionId) {
        return KeybindRegistry.getInstance().getShortcutDisplayName(actionId);
    }
}
