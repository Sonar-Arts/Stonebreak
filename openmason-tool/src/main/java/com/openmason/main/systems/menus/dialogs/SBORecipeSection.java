package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Recipes tab body for the SBO editor.
 *
 * <p>Edits the {@link SBOFormat.RecipeData} of an SBO: a list of shaped
 * recipes whose output is the SBO's own item. Each recipe is an N×M grid of
 * objectId slots plus an output count.
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toRecipeData()} on save and provides initial state via
 * {@link #setFromRecipeData}.
 */
public class SBORecipeSection {

    /** Mutable working copy of one recipe. */
    private static final class EditableRecipe {
        int width = 3;
        int height = 3;
        int outputCount = 1;
        /** Always sized {@code 3 * 3} — slots beyond width/height are ignored on save. */
        final String[] slots = new String[9];

        EditableRecipe() {
            Arrays.fill(slots, "");
        }

        EditableRecipe(SBOFormat.ShapedRecipe r) {
            this.width = r.width();
            this.height = r.height();
            this.outputCount = Math.max(1, r.outputCount());
            Arrays.fill(slots, "");
            // Source pattern is row-major width*height; copy into the
            // top-left of our 3x3 working buffer.
            List<String> p = r.pattern();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    slots[row * 3 + col] = p.get(row * width + col);
                }
            }
        }

        SBOFormat.ShapedRecipe toShaped() {
            List<String> flat = new ArrayList<>(width * height);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    String slot = slots[row * 3 + col];
                    flat.add(slot == null ? "" : slot);
                }
            }
            return new SBOFormat.ShapedRecipe(width, height, flat, outputCount);
        }
    }

    private final List<EditableRecipe> recipes = new ArrayList<>();
    private final ImInt selected = new ImInt(0);
    private final SBOObjectPickerPopup picker = new SBOObjectPickerPopup();
    private final Runnable onDirty;

    /**
     * @param onDirty callback fired whenever the user mutates recipe state.
     *                Owning editor uses this to flip its dirty flag.
     */
    public SBORecipeSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromRecipeData(SBOFormat.RecipeData data) {
        recipes.clear();
        if (data != null) {
            for (SBOFormat.ShapedRecipe r : data.shaped()) {
                recipes.add(new EditableRecipe(r));
            }
        }
        selected.set(0);
    }

    /** Returns null when there are no recipes (so the manifest field stays absent). */
    public SBOFormat.RecipeData toRecipeData() {
        if (recipes.isEmpty()) return null;
        List<SBOFormat.ShapedRecipe> shaped = new ArrayList<>(recipes.size());
        for (EditableRecipe r : recipes) shaped.add(r.toShaped());
        return new SBOFormat.RecipeData(shaped);
    }

    public void render() {
        if (ImGui.button("+ Add Recipe")) {
            recipes.add(new EditableRecipe());
            selected.set(recipes.size() - 1);
            onDirty.run();
        }

        if (recipes.isEmpty()) {
            ImGui.sameLine();
            ImGui.textDisabled("No recipes - click + Add Recipe to create one.");
            picker.render();
            return;
        }

        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        ImGui.text("Recipe:");
        ImGui.sameLine();

        String[] labels = new String[recipes.size()];
        for (int i = 0; i < recipes.size(); i++) labels[i] = "Recipe " + (i + 1);
        ImGui.pushItemWidth(160);
        ImGui.combo("##recipe_picker", selected, labels);
        ImGui.popItemWidth();

        ImGui.sameLine();
        if (ImGui.button("Delete Recipe")) {
            recipes.remove(selected.get());
            selected.set(Math.max(0, selected.get() - 1));
            onDirty.run();
            picker.render();
            return;
        }

        ImGui.separator();
        renderRecipeBody(recipes.get(selected.get()));

        // Picker is a singleton popup — must render every frame regardless of
        // whether a cell triggered open() this frame.
        picker.render();
    }

    private void renderRecipeBody(EditableRecipe r) {
        ImInt w = new ImInt(r.width);
        ImInt h = new ImInt(r.height);
        ImInt count = new ImInt(r.outputCount);

        ImGui.pushItemWidth(80);
        if (ImGui.inputInt("Width", w)) {
            r.width = clamp(w.get(), 1, 3);
            onDirty.run();
        }
        ImGui.sameLine();
        if (ImGui.inputInt("Height", h)) {
            r.height = clamp(h.get(), 1, 3);
            onDirty.run();
        }
        ImGui.popItemWidth();

        ImGui.pushItemWidth(60);
        if (ImGui.inputInt("Output count", count)) {
            r.outputCount = Math.max(1, count.get());
            onDirty.run();
        }
        ImGui.popItemWidth();

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);
        ImGui.text("Pattern");
        ImGui.sameLine();
        ImGui.textDisabled("(left-click to pick, right-click to clear)");
        ImGui.dummy(0, 6);

        final float cellSize = 64.0f;
        final float cellSpacing = 6.0f;
        imgui.ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemSpacing, cellSpacing, cellSpacing);
        imgui.ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameRounding, 4.0f);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                boolean active = row < r.height && col < r.width;
                int idx = row * 3 + col;
                String slot = r.slots[idx];

                ImGui.pushID("cell_" + idx);
                if (!active) {
                    ImGui.beginDisabled();
                    ImGui.button("##inactive", cellSize, cellSize);
                    ImGui.endDisabled();
                } else {
                    boolean filled = slot != null && !slot.isEmpty();
                    String label = filled ? shortLabel(slot) : "+";
                    if (ImGui.button(label, cellSize, cellSize)) {
                        final int captured = idx;
                        picker.open(picked -> {
                            r.slots[captured] = picked;
                            onDirty.run();
                        });
                    }
                    if (ImGui.isItemHovered() && filled) {
                        ImGui.setTooltip(slot);
                    }
                    if (ImGui.isItemClicked(1)) {
                        r.slots[idx] = "";
                        onDirty.run();
                    }
                }
                ImGui.popID();
                if (col < 2) ImGui.sameLine();
            }
        }
        imgui.ImGui.popStyleVar(2);
    }

    /** Render a slot's objectId compactly (drop namespace, truncate). */
    private static String shortLabel(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        if (local.length() > 9) local = local.substring(0, 8) + "..";
        return local;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Test/util hook — allows clearing all recipes (e.g. on editor close). */
    public void clear(Consumer<Void> ack) {
        recipes.clear();
        selected.set(0);
        if (ack != null) ack.accept(null);
    }
}
