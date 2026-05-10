package com.openmason.main.systems.menus.dialogs.validation;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * Modal popup shown when an export/save attempt hits a {@code numericId}
 * collision against the registry. Forces the user to tick an acknowledgment
 * checkbox before the Override button enables — passes the original write
 * action through on confirm.
 *
 * <p>One instance per host window. Lifecycle:
 * <ol>
 *   <li>Host calls {@link #open(NumericIdValidator.Result.Conflict, Runnable)}
 *       when a Conflict result is returned by the validator.</li>
 *   <li>{@link #render()} is called every frame from the host's render method
 *       (inside its window's begin/end pair).</li>
 *   <li>If the user ticks the acknowledge checkbox and clicks Override, the
 *       supplied {@code onOverride} runs and the popup closes.</li>
 * </ol>
 */
public final class NumericIdConflictPopup {

    private static final String POPUP_ID = "Numeric ID Conflict##nid_conflict_popup";

    private boolean wantOpen = false;
    private NumericIdValidator.Result.Conflict conflict;
    private Runnable onOverride;
    private final ImBoolean acknowledged = new ImBoolean(false);

    /** Queue the popup to open on the next frame, with the supplied conflict. */
    public void open(NumericIdValidator.Result.Conflict conflict, Runnable onOverride) {
        this.conflict = conflict;
        this.onOverride = onOverride;
        this.acknowledged.set(false);
        this.wantOpen = true;
    }

    public void render() {
        if (wantOpen) {
            ImGui.openPopup(POPUP_ID);
            wantOpen = false;
        }

        ImVec2 center = ImGui.getMainViewport().getCenter();
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(480, 0, ImGuiCond.Appearing);

        if (ImGui.beginPopupModal(POPUP_ID, null, ImGuiWindowFlags.AlwaysAutoResize)) {
            if (conflict == null) {
                ImGui.closeCurrentPopup();
                ImGui.endPopup();
                return;
            }

            ImGui.textColored(1.0f, 0.55f, 0.45f, 1.0f, "Numeric ID collision");
            ImGui.separator();
            ImGui.dummy(0, 4);

            ImGui.text("Numeric ID " + conflict.numericId() + " is already taken by:");
            ImGui.bulletText(conflict.existingObjectId() + "  (" + conflict.existingDisplayName() + ")");
            ImGui.dummy(0, 6);

            ImGui.textWrapped(
                    "Saving with this ID will create a registry collision. The conflicting "
                  + "objects will fight over the same slot in chunk saves and item lookups, "
                  + "and the load order will determine which one wins. Existing worlds may "
                  + "load the wrong block where this ID was placed.");

            ImGui.dummy(0, 8);
            ImGui.checkbox("I understand — override anyway", acknowledged);
            ImGui.dummy(0, 8);

            boolean enabled = acknowledged.get();
            if (!enabled) ImGui.beginDisabled();
            if (ImGui.button("Override", 110, 26)) {
                ImGui.closeCurrentPopup();
                Runnable r = onOverride;
                onOverride = null;
                conflict = null;
                if (r != null) r.run();
            }
            if (!enabled) ImGui.endDisabled();

            ImGui.sameLine();
            if (ImGui.button("Cancel", 110, 26)) {
                ImGui.closeCurrentPopup();
                onOverride = null;
                conflict = null;
            }

            ImGui.endPopup();
        }
    }
}
