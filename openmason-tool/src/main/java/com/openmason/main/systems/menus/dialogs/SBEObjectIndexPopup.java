package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTableFlags;

import java.util.List;

/**
 * Informational picker showing every SBE {@code objectId} currently registered
 * in the bundled Stonebreak resources. Opened from a "Registered IDs" button
 * next to the Object ID input in the SBE exporter and editor — purely
 * read-only; closes via the Close button.
 *
 * <p>The SBE counterpart to {@code TakenIdsPopup}. Refreshes the index at open
 * time so freshly-exported SBEs appear.
 */
public final class SBEObjectIndexPopup {

    private static final String POPUP_ID = "Registered SBE Entities##sbe_index_popup";

    private boolean wantOpen = false;
    private List<SBEObjectIndex.Entry> cached = List.of();

    /** Refresh the index and request the popup to open next frame. */
    public void open() {
        SBEObjectIndex.refresh();
        this.cached = SBEObjectIndex.listAll();
        this.wantOpen = true;
    }

    public void render() {
        if (wantOpen) {
            ImGui.openPopup(POPUP_ID);
            wantOpen = false;
        }

        ImVec2 center = ImGui.getMainViewport().getCenter();
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(460, 480, ImGuiCond.Appearing);

        if (ImGui.beginPopupModal(POPUP_ID, null, 0)) {
            ImGui.text("Registered SBE entities: " + cached.size());
            ImGui.textDisabled("Object IDs already in use — pick a unique one for new entities.");
            ImGui.separator();

            int flags = ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.Borders
                    | ImGuiTableFlags.ScrollY
                    | ImGuiTableFlags.SizingStretchProp;
            float tableHeight = ImGui.getContentRegionAvailY() - 36.0f;
            if (ImGui.beginTable("##sbe_index_table", 3, flags, 0, tableHeight)) {
                ImGui.tableSetupColumn("Object ID");
                ImGui.tableSetupColumn("Name");
                ImGui.tableSetupColumn("Type");
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                if (cached.isEmpty()) {
                    ImGui.tableNextRow();
                    ImGui.tableNextColumn();
                    ImGui.textDisabled("(none found)");
                }
                for (SBEObjectIndex.Entry entry : cached) {
                    ImGui.tableNextRow();
                    ImGui.tableNextColumn();
                    ImGui.text(entry.objectId() != null ? entry.objectId() : "");
                    ImGui.tableNextColumn();
                    ImGui.text(entry.objectName() != null ? entry.objectName() : "");
                    ImGui.tableNextColumn();
                    ImGui.text(entry.entityType() != null ? entry.entityType() : "");
                }
                ImGui.endTable();
            }

            ImGui.dummy(0, 4);
            if (ImGui.button("Close", 110, 26)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }
}
