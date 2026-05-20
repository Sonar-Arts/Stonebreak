package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.List;

/**
 * Smelting tab body for the SBO editor.
 *
 * <p>Edits {@link SBOFormat.SmeltingRecipeData} — smelting recipes that produce
 * this SBO's own item (each entry references its input by objectId).
 *
 * <p>Fuel data lives in the Properties tab — see
 * {@code SBOEditorWindow.renderGamePropertiesTab()}.
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toSmeltingRecipeData()} on save and provides initial state via
 * {@link #setFromSmeltingData}.
 */
public class SBOSmeltingSection {

    /** Mutable working copy of one smelting recipe entry. */
    private static final class EditableSmeltingEntry {
        String inputObjectId = "";
        int outputCount = 1;

        EditableSmeltingEntry() {}

        EditableSmeltingEntry(SBOFormat.SmeltingRecipeEntry e) {
            this.inputObjectId = e.inputObjectId();
            this.outputCount = e.outputCount();
        }

        SBOFormat.SmeltingRecipeEntry toEntry() {
            return new SBOFormat.SmeltingRecipeEntry(
                    inputObjectId == null ? "" : inputObjectId,
                    Math.max(1, outputCount)
            );
        }
    }

    private final List<EditableSmeltingEntry> recipes = new ArrayList<>();
    private final SBOObjectPickerPopup picker = new SBOObjectPickerPopup();
    private final Runnable onDirty;

    public SBOSmeltingSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromSmeltingData(SBOFormat.SmeltingRecipeData smeltingData) {
        recipes.clear();
        if (smeltingData != null) {
            for (SBOFormat.SmeltingRecipeEntry e : smeltingData.recipes()) {
                recipes.add(new EditableSmeltingEntry(e));
            }
        }
    }

    /** Returns null when there are no recipes (so the manifest field stays absent). */
    public SBOFormat.SmeltingRecipeData toSmeltingRecipeData() {
        if (recipes.isEmpty()) return null;
        List<SBOFormat.SmeltingRecipeEntry> out = new ArrayList<>(recipes.size());
        for (EditableSmeltingEntry e : recipes) {
            if (e.inputObjectId == null || e.inputObjectId.isBlank()) continue;
            out.add(e.toEntry());
        }
        return out.isEmpty() ? null : new SBOFormat.SmeltingRecipeData(out);
    }

    public void render() {
        ImGui.text("Smelting Recipes");
        ImGui.sameLine();
        ImGui.textDisabled("(this SBO is the output; input is referenced by objectId)");

        if (ImGui.button("+ Add Smelting Recipe")) {
            recipes.add(new EditableSmeltingEntry());
            onDirty.run();
        }

        if (recipes.isEmpty()) {
            ImGui.textDisabled("No smelting recipes - click + Add Smelting Recipe to create one.");
            return;
        }

        ImGui.dummy(0, 4);

        int removeIndex = -1;
        for (int i = 0; i < recipes.size(); i++) {
            EditableSmeltingEntry r = recipes.get(i);
            ImGui.pushID("smelt_" + i);

            ImGui.text("Recipe " + (i + 1));
            ImGui.sameLine();
            if (ImGui.smallButton("Delete##smelt_del")) {
                removeIndex = i;
            }

            ImGui.text("Input:");
            ImGui.sameLine();
            String label = (r.inputObjectId == null || r.inputObjectId.isBlank())
                    ? "(none) - click to pick"
                    : r.inputObjectId;
            if (ImGui.button(label + "##smelt_input")) {
                final int captured = i;
                picker.open(picked -> {
                    recipes.get(captured).inputObjectId = picked;
                    onDirty.run();
                });
            }
            if (ImGui.isItemClicked(1)) {
                r.inputObjectId = "";
                onDirty.run();
            }

            ImInt count = new ImInt(r.outputCount);

            ImGui.pushItemWidth(80);
            if (ImGui.inputInt("Output count##smelt_oc", count)) {
                r.outputCount = Math.max(1, count.get());
                onDirty.run();
            }
            ImGui.popItemWidth();

            ImGui.dummy(0, 6);
            ImGui.separator();
            ImGui.dummy(0, 4);
            ImGui.popID();
        }

        if (removeIndex >= 0) {
            recipes.remove(removeIndex);
            onDirty.run();
        }

        // Picker is a singleton popup — must render every frame regardless of
        // whether a recipe row triggered open() this frame.
        picker.render();
    }
}
