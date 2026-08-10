package com.openmason.main.systems.menus.panes.projectBrowser.views;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetType;
import com.openmason.main.systems.scene.dnd.ScenePayloads;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;

/**
 * Makes a browser entry draggable into the Scene Viewer.
 *
 * <p>Shared by all three view modes so grid, list and compact behave identically —
 * duplicating the drag block three times is how they would quietly drift apart.
 */
public final class ProjectBrowserDragSource {

    private ProjectBrowserDragSource() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Call immediately after submitting the item's widget.
     *
     * @return true if a drag is under way from this item, in which case the caller must
     *         <b>not</b> also treat the press as a click. {@code isItemClicked} fires on
     *         mouse-<em>down</em>, so without this, merely starting to drag a model would
     *         also load it into the editor.
     */
    public static boolean emit(AssetEntry item) {
        if (item == null || item.type() != AssetType.OMO) {
            return false;
        }
        if (ImGui.beginDragDropSource(ImGuiDragDropFlags.SourceAllowNullID)) {
            // imgui-java carries the Java object itself, so the absolute path is enough.
            ImGui.setDragDropPayload(ScenePayloads.OMO_ASSET, item.path().toString(), ImGuiCond.Once);
            ImGui.text("Place: " + item.name());
            ImGui.endDragDropSource();
            return true;
        }
        return false;
    }
}
