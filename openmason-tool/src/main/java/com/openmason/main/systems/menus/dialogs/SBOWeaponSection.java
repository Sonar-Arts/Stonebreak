package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImString;

/**
 * Weapon tab body for the SBO editor.
 *
 * <p>Edits {@link SBOFormat.WeaponData} (SBO 1.10+): whether this item is a
 * melee weapon, its weapon group and damage type, base damage per hit, and
 * the swing time between hits for a trained wielder.
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toWeaponData()} on save and provides initial state via
 * {@link #setFromWeaponData}.
 */
public class SBOWeaponSection {

    private static final String[] GROUP_PRESETS = {"unarmed", "one_handed", "two_handed", "ranged"};
    private static final String[] DAMAGE_TYPE_PRESETS = {"slashing", "piercing", "blunt", "fire", "arcane"};

    private final ImBoolean enabled = new ImBoolean(false);
    private final ImString group = new ImString(64);
    private final ImString damageType = new ImString(64);
    private final ImFloat damage = new ImFloat(1.0f);
    private final ImFloat swingSeconds = new ImFloat(0.5f);
    private final Runnable onDirty;

    public SBOWeaponSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromWeaponData(SBOFormat.WeaponData data) {
        enabled.set(data != null);
        group.set(data != null ? data.group() : "");
        damageType.set(data != null ? data.damageType() : "");
        damage.set(data != null ? data.damage() : 1.0f);
        swingSeconds.set(data != null ? data.swingSeconds() : 0.5f);
    }

    /**
     * {@code null} when the item is not a weapon (manifest field absent).
     * Call {@link #validate()} first — an invalid form throws here.
     */
    public SBOFormat.WeaponData toWeaponData() {
        if (!enabled.get()) return null;
        return new SBOFormat.WeaponData(group.get(), damageType.get(), damage.get(), swingSeconds.get());
    }

    /** {@code null} when the form can be saved; otherwise the first blocking problem. */
    public String validate() {
        if (!enabled.get()) return null;
        if (group.get().isBlank()) return "Weapon: a weapon group is required (e.g. one_handed)";
        if (damageType.get().isBlank()) return "Weapon: a damage type is required (e.g. slashing)";
        if (!(damage.get() >= 0f)) return "Weapon: damage must be 0 or more";
        if (!(swingSeconds.get() > 0f)) return "Weapon: swing time must be greater than 0";
        return null;
    }

    public void render() {
        ImGui.textDisabled("A weapon's group picks the wielder's proficiency; its damage type meets resistances.");
        if (ImGui.checkbox("This item is a weapon", enabled)) onDirty.run();
        if (!enabled.get()) return;

        ThemedWidgets.sectionLabel("Kind");
        presetField("Group", "##weapon_group", "e.g. one_handed", group, GROUP_PRESETS);
        presetField("Damage Type", "##weapon_damage_type", "e.g. slashing", damageType, DAMAGE_TYPE_PRESETS);

        ThemedWidgets.sectionLabel("Strength");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputFloat("Damage", damage, 0.5f, 1f, "%.1f")) {
            if (damage.get() < 0f) damage.set(0f);
            onDirty.run();
        }
        if (ImGui.inputFloat("Swing Seconds", swingSeconds, 0.05f, 0.1f, "%.2f")) {
            if (swingSeconds.get() < 0.05f) swingSeconds.set(0.05f);
            onDirty.run();
        }
        ImGui.popItemWidth();
        ImGui.textDisabled("Seconds between hits for a Trained wielder; other ranks scale it.");
    }

    private void presetField(String label, String id, String hint, ImString value, String[] presets) {
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint(id, hint, value)) onDirty.run();
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo(label, "Presets")) {
            for (String preset : presets) {
                if (ImGui.selectable(preset, preset.equals(value.get().trim()))) {
                    value.set(preset);
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
    }
}
