package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Smelting tab body for the SBO editor.
 *
 * <p>Edits {@link SBOFormat.SmeltingRecipeData} — smelting recipes that produce
 * this SBO's own item (each entry references its input by objectId). Rendered
 * as icon-tile rows: input tile → output tile ×count, sharing the ingredient
 * picker, icon cache and recent-ingredients MRU with the Recipes tab.
 *
 * <p>Fuel data lives in the Properties tab — see
 * {@code SBOEditorWindow.renderGamePropertiesTab()}.
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toSmeltingRecipeData()} on save and provides initial state via
 * {@link #setFromSmeltingData}.
 */
public class SBOSmeltingSection {

    private static final float TILE = 44f;

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
    /** objectId of the SBO being edited — the smelt's implicit output. May be blank. */
    private final Supplier<String> selfObjectId;

    public SBOSmeltingSection(Runnable onDirty) {
        this(onDirty, null);
    }

    public SBOSmeltingSection(Runnable onDirty, Supplier<String> selfObjectId) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.selfObjectId = selfObjectId != null ? selfObjectId : () -> "";
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
            // Blank inputs are silently dropped — the format record rejects them.
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
            picker.render();
            return;
        }

        ImGui.dummy(0, 6);

        int removeIndex = -1;
        for (int i = 0; i < recipes.size(); i++) {
            EditableSmeltingEntry r = recipes.get(i);
            ImGui.pushID("smelt_" + i);

            imgui.ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameRounding, 6.0f);

            // Input tile: icon button opening the shared picker.
            boolean hasInput = r.inputObjectId != null && !r.inputObjectId.isBlank();
            int inIcon = hasInput ? SBOIngredientIcons.glIcon(r.inputObjectId) : 0;
            boolean pickClicked;
            if (inIcon > 0) {
                pickClicked = ImGui.imageButton("##smelt_in", inIcon, TILE - 8, TILE - 8);
            } else {
                pickClicked = ImGui.button(hasInput ? shortLabel(r.inputObjectId) : "+",
                        TILE, TILE);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(hasInput
                        ? r.inputObjectId + "\nClick to change - right-click to clear"
                        : "Click to pick the smelting input");
            }
            if (pickClicked) {
                final int captured = i;
                picker.open(picked -> {
                    recipes.get(captured).inputObjectId = picked;
                    if (picked != null && !picked.isEmpty()) IngredientMru.shared().touch(picked);
                    onDirty.run();
                });
            }
            if (ImGui.isItemClicked(1) && hasInput) {
                r.inputObjectId = "";
                onDirty.run();
            }

            ImGui.sameLine();
            ImGui.text(" -> ");
            ImGui.sameLine();

            // Output tile: this SBO's own icon (read-only).
            String self = selfObjectId.get();
            int outIcon = (self == null || self.isEmpty()) ? 0 : SBOIngredientIcons.glIcon(self);
            if (outIcon > 0) {
                ImGui.image(outIcon, TILE - 8, TILE - 8);
                if (ImGui.isItemHovered() && self != null) ImGui.setTooltip(self);
                ImGui.sameLine();
            }
            ImGui.text("x");
            ImGui.sameLine();
            ImInt count = new ImInt(r.outputCount);
            ImGui.pushItemWidth(84);
            if (ImGui.inputInt("##smelt_oc", count)) {
                r.outputCount = Math.max(1, count.get());
                onDirty.run();
            }
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.smallButton("Delete")) {
                removeIndex = i;
            }

            imgui.ImGui.popStyleVar();

            ImGui.dummy(0, 4);
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

    /** Render an objectId compactly (drop namespace, truncate). */
    private static String shortLabel(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        if (local.length() > 7) local = local.substring(0, 6) + "..";
        return local;
    }
}
