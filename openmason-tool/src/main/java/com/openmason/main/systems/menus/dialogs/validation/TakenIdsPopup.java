package com.openmason.main.systems.menus.dialogs.validation;

import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.List;

/**
 * Informational picker showing every numeric ID currently registered in the
 * chosen domain. Opened from a "Show Taken IDs" button next to the Numeric ID
 * input — purely read-only; closes via the X / Close button.
 *
 * <p>Caches the registry snapshot at open time so the table doesn't re-query
 * on every frame.
 */
public final class TakenIdsPopup {

    private static final String POPUP_ID = "Taken Numeric IDs##nid_taken_popup";

    private boolean wantOpen = false;
    private NumericIdValidator.Domain domain = NumericIdValidator.Domain.NONE;
    private List<NumericIdValidator.TakenId> cached = List.of();
    private int suggestedNextFree = -1;

    public void open(NumericIdValidator.Domain domain) {
        this.domain = domain;
        this.cached = NumericIdValidator.listTakenIds(domain);
        this.suggestedNextFree = NumericIdValidator.suggestNextFreeId(domain);
        this.wantOpen = true;
    }

    public void render() {
        if (wantOpen) {
            ImGui.openPopup(POPUP_ID);
            wantOpen = false;
        }

        if (ModalDialogs.begin(POPUP_ID, 460)) {
            String domainLabel = switch (domain) {
                case BLOCK -> "Blocks";
                case ITEM -> "Items";
                case NONE -> "(none)";
            };
            ImGui.text("Domain: " + domainLabel);
            ImGui.text("Registered: " + cached.size());
            if (suggestedNextFree >= 0) {
                ImGui.sameLine();
                ImGui.textDisabled("   |   Next free: " + suggestedNextFree);
            }
            ImGui.separator();

            int flags = ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.Borders
                    | ImGuiTableFlags.ScrollY
                    | ImGuiTableFlags.SizingStretchProp;
            float tableHeight = 380.0f;
            if (ImGui.beginTable("##taken_table", 3, flags, 0, tableHeight)) {
                ImGui.tableSetupColumn("ID");
                ImGui.tableSetupColumn("Object ID");
                ImGui.tableSetupColumn("Name");
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (NumericIdValidator.TakenId t : cached) {
                    ImGui.tableNextRow();
                    ImGui.tableNextColumn();
                    ImGui.text(String.valueOf(t.numericId()));
                    ImGui.tableNextColumn();
                    ImGui.text(t.objectId());
                    ImGui.tableNextColumn();
                    ImGui.text(t.displayName() != null ? t.displayName() : "");
                }
                ImGui.endTable();
            }

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.closeButton()) {
                ImGui.closeCurrentPopup();
            }
            ModalDialogs.end();
        }
    }
}
