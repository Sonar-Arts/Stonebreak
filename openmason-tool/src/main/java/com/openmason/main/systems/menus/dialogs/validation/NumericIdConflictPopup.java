package com.openmason.main.systems.menus.dialogs.validation;

import imgui.ImGui;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
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

        if (ModalDialogs.begin(POPUP_ID, 480)) {
            if (conflict == null) {
                ImGui.closeCurrentPopup();
                ModalDialogs.end();
                return;
            }

            ImGui.text("Numeric ID " + conflict.numericId() + " is already taken by:");
            ImGui.bulletText(conflict.existingObjectId() + "  (" + conflict.existingDisplayName() + ")");
            ImGui.dummy(0, 6);

            ImGui.textWrapped(
                    "Saving with this ID will create a registry collision. The conflicting "
                  + "objects will fight over the same slot in chunk saves and item lookups, "
                  + "and the load order will determine which one wins. Existing worlds may "
                  + "load the wrong block where this ID was placed.");

            ImGui.dummy(0, 8);
            ImGui.checkbox("I understand - override anyway", acknowledged);
            ImGui.dummy(0, 8);

            boolean enabled = acknowledged.get();
            if (!enabled) {
                ThemedWidgets.inlineError("Tick the box above to enable Override.");
            }

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.danger("Override", enabled)) {
                ImGui.closeCurrentPopup();
                Runnable r = onOverride;
                onOverride = null;
                conflict = null;
                if (r != null) r.run();
            }
            if (ModalDialogs.cancel()) {
                ImGui.closeCurrentPopup();
                onOverride = null;
                conflict = null;
            }

            ModalDialogs.end();
        }
    }
}
