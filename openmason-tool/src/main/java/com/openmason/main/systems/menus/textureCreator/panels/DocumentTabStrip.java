package com.openmason.main.systems.menus.textureCreator.panels;

import com.openmason.main.systems.layout.TabRightClickGuard;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import com.openmason.main.systems.menus.textureCreator.dialogs.CloseTextureDocumentsDialog;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.type.ImBoolean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open textures as tabs above the canvas (like the UI Editor's document tabs): click to
 * switch, drag to reorder, the close button / middle-click / context menu to close (unsaved
 * textures ask first, see {@link CloseTextureDocumentsDialog}). A face document's tab reads
 * "Face N"; the dirty dot marks unsaved standalone textures.
 */
public final class DocumentTabStrip {

    private final TextureCreatorController controller;
    private final CloseTextureDocumentsDialog closeDialog;
    private final TabRightClickGuard rightClick = new TabRightClickGuard();
    private final Map<TextureDocument, ImBoolean> tabOpen = new HashMap<>();

    /** The document whose tab ImGui showed selected last frame. */
    private TextureDocument shownTab;

    public DocumentTabStrip(TextureCreatorController controller, CloseTextureDocumentsDialog closeDialog) {
        this.controller = controller;
        this.closeDialog = closeDialog;
    }

    /** Draws the tab strip at the cursor; nothing when no texture is open. */
    public void render() {
        List<TextureDocument> docs = controller.getDocuments();
        tabOpen.keySet().retainAll(docs);
        if (docs.isEmpty()) {
            shownTab = null;
            return;
        }
        TextureDocument active = controller.getActiveDocument();
        // A switch made elsewhere (menu, agent, opening a face) selects its tab once.
        TextureDocument forceTab = active != shownTab ? active : null;

        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 10, 4);
        rightClick.begin(); // right-clicking a tab opens its menu without switching documents
        if (ImGui.beginTabBar("##textureDocTabs", ImGuiTabBarFlags.Reorderable
                | ImGuiTabBarFlags.FittingPolicyScroll | ImGuiTabBarFlags.AutoSelectNewTabs)) {
            List<TextureDocument> toClose = new ArrayList<>();
            TextureDocument clicked = null;
            for (TextureDocument d : docs) {
                ImBoolean keep = tabOpen.computeIfAbsent(d, k -> new ImBoolean(true));
                keep.set(true);
                int flags = (d.needsSavePrompt() ? ImGuiTabItemFlags.UnsavedDocument : 0)
                        | (d == forceTab ? ImGuiTabItemFlags.SetSelected : 0);
                boolean selected = ImGui.beginTabItem(d.title() + "###texdoc" + d.id(), keep, flags);
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(tooltip(d));
                }
                if (ImGui.isItemHovered() && ImGui.isMouseReleased(imgui.flag.ImGuiMouseButton.Middle)) {
                    toClose.add(d);
                }
                String ctx = "##texdocctx" + d.id();
                rightClick.openContextMenu(ctx);
                if (ImGui.beginPopupContextItem(ctx)) {
                    if (ImGui.menuItem("Close")) {
                        toClose.add(d);
                    }
                    if (ImGui.menuItem("Close Others", "", false, docs.size() > 1)) {
                        docs.stream().filter(o -> o != d).forEach(toClose::add);
                    }
                    if (ImGui.menuItem("Close All")) {
                        toClose.addAll(docs);
                    }
                    if (d.getFilePath() != null && ImGui.menuItem("Copy Path")) {
                        ImGui.setClipboardText(d.getFilePath());
                    }
                    ImGui.endPopup();
                }
                if (selected) {
                    clicked = d;
                    ImGui.endTabItem();
                }
                if (!keep.get()) {
                    toClose.add(d);
                }
            }
            ImGui.endTabBar();
            rightClick.end();
            if (!toClose.isEmpty()) {
                closeDialog.request(toClose.stream().distinct().toList(), null);
            } else if (forceTab == null && clicked != null && clicked != controller.getActiveDocument()) {
                // A forced frame only shows the new selection; afterwards, what the user picks wins.
                controller.activate(clicked);
            }
        }
        rightClick.end(); // no-op when already ended inside the bar
        ImGui.popStyleVar();
        shownTab = controller.getActiveDocument();
    }

    private static String tooltip(TextureDocument d) {
        var layers = d.getLayerManager();
        String size = layers.getCanvasWidth() + "x" + layers.getCanvasHeight();
        if (d.isFace()) {
            return d.title() + " of the model (" + size + ")\nEdits preview live on the model;"
                    + "\nclosing saves the model.";
        }
        String file = d.getFilePath() != null ? d.getFilePath() : "Not saved yet";
        return file + "\n" + size + (d.hasUnsavedChanges() ? "  -  unsaved changes" : "");
    }
}
