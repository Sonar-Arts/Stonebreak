package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tool tab body for the SBO editor.
 *
 * <p>Edits {@link SBOFormat.ToolData} (SBO 1.9+): whether this item is a
 * mining tool, its tool class and tier, the hardness multiplier it applies
 * (lower = faster), the block materials it is effective on, and the optional
 * durability / attack-damage stats (stored, not yet read by the game).
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toToolData()} on save and provides initial state via
 * {@link #setFromToolData}. Known materials come from {@link SBOMiningIndex}.
 */
public class SBOToolSection {

    private static final String[] TOOL_CLASS_PRESETS = {"pickaxe", "axe", "shovel", "hoe"};

    private final ImBoolean enabled = new ImBoolean(false);
    private final ImString toolClass = new ImString(64);
    private final ImInt tier = new ImInt(0);
    private final ImFloat speedMultiplier = new ImFloat(0.5f);
    private final List<String> materials = new ArrayList<>();
    private final ImString customMaterial = new ImString(64);
    private final ImBoolean hasDurability = new ImBoolean(false);
    private final ImInt durability = new ImInt(64);
    private final ImBoolean hasAttackDamage = new ImBoolean(false);
    private final ImFloat attackDamage = new ImFloat(1.0f);
    private final Runnable onDirty;

    public SBOToolSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromToolData(SBOFormat.ToolData data) {
        enabled.set(data != null);
        materials.clear();
        customMaterial.set("");
        if (data == null) {
            toolClass.set("");
            tier.set(0);
            speedMultiplier.set(0.5f);
            hasDurability.set(false);
            hasAttackDamage.set(false);
            return;
        }
        toolClass.set(data.toolClass());
        tier.set(data.tier());
        speedMultiplier.set(data.speedMultiplier());
        materials.addAll(data.materials());
        hasDurability.set(data.durability() != null);
        durability.set(data.durability() != null ? data.durability() : 64);
        hasAttackDamage.set(data.attackDamage() != null);
        attackDamage.set(data.attackDamage() != null ? data.attackDamage() : 1.0f);
    }

    /**
     * {@code null} when the item is not a mining tool (manifest field absent).
     * Call {@link #validate()} first — an invalid form throws here.
     */
    public SBOFormat.ToolData toToolData() {
        if (!enabled.get()) return null;
        return new SBOFormat.ToolData(
                toolClass.get(),
                Math.max(0, tier.get()),
                speedMultiplier.get(),
                materials,
                hasDurability.get() ? Math.max(1, durability.get()) : null,
                hasAttackDamage.get() ? Math.max(0f, attackDamage.get()) : null);
    }

    /** {@code null} when the form can be saved; otherwise the first blocking problem. */
    public String validate() {
        if (!enabled.get()) return null;
        if (toolClass.get().isBlank()) return "Tool: a tool class is required (e.g. pickaxe)";
        if (!(speedMultiplier.get() > 0f)) return "Tool: speed multiplier must be greater than 0";
        if (materials.isEmpty()) return "Tool: add at least one material it is effective on";
        return null;
    }

    public void render() {
        ImGui.textDisabled("A mining tool breaks blocks of the listed materials faster.");
        if (ImGui.checkbox("This item is a mining tool", enabled)) onDirty.run();
        if (!enabled.get()) return;

        ThemedWidgets.sectionLabel("Kind");
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint("##tool_class", "e.g. pickaxe", toolClass)) onDirty.run();
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("Tool Class", "Presets")) {
            for (String preset : TOOL_CLASS_PRESETS) {
                if (ImGui.selectable(preset, preset.equals(toolClass.get().trim()))) {
                    toolClass.set(preset);
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();

        ThemedWidgets.sectionLabel("Strength");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Tier", tier)) {
            if (tier.get() < 0) tier.set(0);
            onDirty.run();
        }
        ImGui.sameLine();
        ImGui.textDisabled("(" + SBOFormat.ToolData.tierName(tier.get()) + ")");
        if (ImGui.inputFloat("Speed Multiplier", speedMultiplier, 0.05f, 0.1f, "%.2f")) {
            if (speedMultiplier.get() < 0.01f) speedMultiplier.set(0.01f);
            onDirty.run();
        }
        ImGui.popItemWidth();
        float m = speedMultiplier.get();
        if (m >= 1.0f) {
            ThemedWidgets.statusText(Tone.WARNING, "  A multiplier of 1 or more gives no speed-up.");
        } else {
            ImGui.textDisabled(String.format(Locale.ROOT,
                    "Block hardness x %.2f: %.1fx faster than by hand (floored at %.1f s).",
                    m, 1f / m, SBOFormat.ToolData.MIN_EFFECTIVE_HARDNESS));
        }

        ThemedWidgets.sectionLabel("Effective On");
        renderMaterials();

        ThemedWidgets.sectionLabel("Optional Stats");
        ImGui.textDisabled("Stored in the SBO for future systems; the game does not read them yet.");
        if (ImGui.checkbox("Durability##tool_dur", hasDurability)) onDirty.run();
        if (hasDurability.get()) {
            ImGui.sameLine(160);
            ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
            if (ImGui.inputInt("uses##tool_dur_val", durability)) {
                if (durability.get() < 1) durability.set(1);
                onDirty.run();
            }
            ImGui.popItemWidth();
        }
        if (ImGui.checkbox("Attack Damage##tool_atk", hasAttackDamage)) onDirty.run();
        if (hasAttackDamage.get()) {
            ImGui.sameLine(160);
            ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
            if (ImGui.inputFloat("damage##tool_atk_val", attackDamage, 0.5f, 1f, "%.1f")) {
                if (attackDamage.get() < 0f) attackDamage.set(0f);
                onDirty.run();
            }
            ImGui.popItemWidth();
        }
    }

    private void renderMaterials() {
        List<String> known = SBOMiningIndex.shared().materials();
        if (materials.isEmpty()) {
            ImGui.textDisabled("No materials yet; this tool speeds nothing up.");
        }
        for (int i = 0; i < materials.size(); i++) {
            String mat = materials.get(i);
            if (ImGui.smallButton("x##tool_mat_rm" + i)) {
                materials.remove(i);
                onDirty.run();
                break;
            }
            ImGui.sameLine();
            ImGui.text(mat);
            if (!known.contains(mat)) {
                ImGui.sameLine();
                ThemedWidgets.statusText(Tone.WARNING, "(no block uses this material)");
            }
        }

        ImGui.dummy(0, 4);
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.beginCombo("##tool_mat_add", "Add material...")) {
            for (String mat : known) {
                if (materials.contains(mat)) continue;
                if (ImGui.selectable(mat)) {
                    materials.add(mat);
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        ImGui.inputTextWithHint("##tool_mat_custom", "new material", customMaterial);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button("Add##tool_mat_custom_add")) {
            String mat = SBOFormat.normalizeMaterial(customMaterial.get());
            if (mat != null && !materials.contains(mat)) {
                materials.add(mat);
                onDirty.run();
            }
            customMaterial.set("");
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Rescan##tool_mat_rescan")) {
            SBOMiningIndex.refresh();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Re-read every SBO on disk for block materials and tools");
        }
    }
}
