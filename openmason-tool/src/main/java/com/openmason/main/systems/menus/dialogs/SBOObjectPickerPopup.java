package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable modal popup that lets the user pick an SBO object by {@code objectId}
 * from the {@link SBOObjectIndex}. Uses ImGui's popup system; the caller opens
 * it via {@link #open(Consumer)} and then calls {@link #render()} every frame.
 */
public class SBOObjectPickerPopup {

    private static final String POPUP_ID = "##sbo_object_picker";

    private final ImString filter = new ImString(128);
    private Consumer<String> onPick;
    private boolean openRequested;

    /** Open the picker. {@code onPick} receives the chosen objectId, or "" if cleared. */
    public void open(Consumer<String> onPick) {
        this.onPick = onPick;
        this.openRequested = true;
        this.filter.set("");
    }

    public void render() {
        if (openRequested) {
            ImGui.openPopup(POPUP_ID);
            openRequested = false;
        }

        ImGui.setNextWindowSize(420, 460);
        int flags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
        if (ImGui.beginPopupModal(POPUP_ID, flags)) {
            ImGui.text("Pick ingredient");
            ImGui.separator();

            ImGui.pushItemWidth(-1);
            ImGui.inputTextWithHint("##picker_filter", "Filter by objectId or name...", filter);
            ImGui.popItemWidth();

            String filterText = filter.get().trim().toLowerCase();
            List<SBOObjectIndex.Entry> entries = SBOObjectIndex.listAll();

            if (ImGui.beginTable("##picker_table", 3,
                    ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersOuter
                            | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable,
                    0, 360)) {
                ImGui.tableSetupColumn("Object ID");
                ImGui.tableSetupColumn("Name");
                ImGui.tableSetupColumn("Type");
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (SBOObjectIndex.Entry e : entries) {
                    if (!filterText.isEmpty()
                            && !e.objectId().toLowerCase().contains(filterText)
                            && !e.displayName().toLowerCase().contains(filterText)) {
                        continue;
                    }
                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    if (ImGui.selectable(e.objectId(), false,
                            imgui.flag.ImGuiSelectableFlags.SpanAllColumns)) {
                        if (onPick != null) onPick.accept(e.objectId());
                        ImGui.closeCurrentPopup();
                    }
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(e.displayName());
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text(e.objectType());
                }
                ImGui.endTable();
            }

            ImGui.separator();
            if (ImGui.button("Clear slot")) {
                if (onPick != null) onPick.accept("");
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }
    }
}
