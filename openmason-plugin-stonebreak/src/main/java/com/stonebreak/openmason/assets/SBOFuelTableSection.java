package com.stonebreak.openmason.assets;

import com.openmason.main.systems.menus.dialogs.EditorWidgets;
import com.stonebreak.gameplay.SboGameplay;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuel Table tab body for the SBO editor: burn times for <em>other</em> objects or whole item
 * dictionaries ({@code #stonebreak:logs} → 300 ticks). The game's table lives on the furnace SBO.
 * An object's own fuel value (Game Properties tab) always wins over a table entry.
 *
 * <p>Pure UI — never touches disk; {@link #setFrom} / {@link #toFuelTableData} move the state.
 */
public class SBOFuelTableSection {

    private static final float TILE = EditorWidgets.ITEM_TILE_SIZE;
    private static final int DEFAULT_TICKS = 300;

    /** Mutable working copy of one entry. */
    static final class Row {
        String ingredient = "";
        final ImInt burnTicks = new ImInt(DEFAULT_TICKS);
    }

    private final List<Row> rows = new ArrayList<>();
    private final SBOObjectPickerPopup picker = new SBOObjectPickerPopup();
    private final Runnable onDirty;

    public SBOFuelTableSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFrom(SboGameplay.FuelTableData data) {
        rows.clear();
        if (data == null) return;
        for (SboGameplay.FuelTableEntry e : data.entries()) {
            Row r = new Row();
            r.ingredient = e.ingredient();
            r.burnTicks.set(e.burnTicks());
            rows.add(r);
        }
    }

    /** {@code null} when no entry has an ingredient (so the manifest field stays absent). */
    public SboGameplay.FuelTableData toFuelTableData() {
        List<SboGameplay.FuelTableEntry> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.ingredient == null || r.ingredient.isBlank()) continue;
            out.add(new SboGameplay.FuelTableEntry(r.ingredient, Math.max(1, r.burnTicks.get())));
        }
        return out.isEmpty() ? null : new SboGameplay.FuelTableData(out);
    }

    /** Test hook: adds an entry as the UI would. */
    void add(String ingredient, int burnTicks) {
        Row r = new Row();
        r.ingredient = ingredient;
        r.burnTicks.set(burnTicks);
        rows.add(r);
    }

    public void render() {
        ImGui.textDisabled("Burn times for other objects or whole dictionaries (e.g. #stonebreak:logs).");
        ImGui.textDisabled("An object's own fuel value (Game Properties) wins. 200 ticks = 1 smelt.");
        ImGui.dummy(0, 4);
        ImGui.separator();
        ImGui.dummy(0, 4);
        if (rows.isEmpty()) {
            ImGui.textDisabled("No fuel table entries.");
        }

        int remove = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            ImGui.pushID("fuel_" + i);
            boolean has = r.ingredient != null && !r.ingredient.isBlank();
            int icon = has ? SBOIngredientIcons.glIcon(r.ingredient) : 0;
            boolean pick = icon > 0
                    ? ImGui.imageButton("##fuel_in", icon, TILE - 8, TILE - 8)
                    : ImGui.button(has ? SBODictionaryIndex.strip(r.ingredient) : "+", TILE, TILE);
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(has ? SBODictionaryIndex.shared().describe(r.ingredient) + "\nClick to change"
                        : "Click to pick an object or dictionary");
            }
            if (pick) {
                final Row target = r;
                picker.open(picked -> {
                    target.ingredient = picked == null ? "" : picked;
                    onDirty.run();
                });
            }
            ImGui.sameLine();
            ImGui.beginGroup();
            ImGui.text(has ? r.ingredient : "(no ingredient)");
            ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
            if (ImGui.inputInt("Burn ticks", r.burnTicks)) {
                if (r.burnTicks.get() < 1) r.burnTicks.set(1);
                onDirty.run();
            }
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove")) remove = i;
            ImGui.endGroup();
            ImGui.popID();
        }
        if (remove >= 0) {
            rows.remove(remove);
            onDirty.run();
        }
        ImGui.dummy(0, 4);
        if (ImGui.button("Add fuel entry")) {
            rows.add(new Row());
            onDirty.run();
        }
        picker.render();
    }
}
