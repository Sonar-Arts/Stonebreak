package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import imgui.ImGui;
import imgui.type.ImInt;

/**
 * Small shared ImGui idioms for the SBO/SBE editor sub-sections, so every tab
 * formats sizes and asset slots the same way instead of each section carrying
 * its own copy. Status colors, destructive/primary buttons, inline errors and
 * section labels live in {@link com.openmason.main.systems.themes.utils.ThemedWidgets}.
 */
public final class EditorWidgets {

    // ---- Shared widths (one value per role across exporters and editors) ----

    /** Numeric ID / small integer inputs. */
    public static final float NUMERIC_ID_WIDTH = 140.0f;
    /** State / variant / sound name fields. */
    public static final float NAME_FIELD_WIDTH = 160.0f;
    /** Row "Remove" buttons in the ImGui fallback paths. */
    public static final float REMOVE_BUTTON_WIDTH = 70.0f;
    /** Loop-mode combo. */
    public static final float LOOP_COMBO_WIDTH = 120.0f;
    /** Ingredient / tool slot tiles (recipes, smelting, drops). */
    public static final float ITEM_TILE_SIZE = 40.0f;

    /** Button that opens the list of numeric / object IDs already in use. */
    public static final String TAKEN_IDS_LABEL = "Taken IDs...";

    /** Loop-mode labels; index-aligned with {@link SBOFormat.LoopMode#values()}. */
    public static final String[] LOOP_MODE_LABELS = { "Clip default", "Loop", "Play once" };

    private EditorWidgets() {
    }

    /** "No SBO loaded." style empty-state line shared by both editors. */
    public static String emptyEditorText(String kind) {
        return "No " + kind + " loaded. Use \"Open Different " + kind + "...\" above, "
                + "or Tools > " + kind + " Editor...";
    }

    /** Shared 3-way loop-mode combo (Clip default / Loop / Play once); true when changed. */
    public static boolean loopModeCombo(String id, ImInt mode) {
        ImGui.pushItemWidth(LOOP_COMBO_WIDTH);
        boolean changed = ImGui.combo(id, mode, LOOP_MODE_LABELS);
        ImGui.popItemWidth();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Clip default: use the loop flag stored in the clip.\n"
                    + "Loop: wrap forever (e.g. a spinning fan).\n"
                    + "Play once: run through a single time and hold the final pose\n"
                    + "(e.g. a door opening).");
        }
        return changed;
    }

    /** "512 B" / "12.3 KB" / "4.0 MB". */
    public static String humanBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    /**
     * One labelled asset slot ("Model:", "Clip:") with source name + size and
     * Set/Replace/Clear buttons. Widget IDs are scoped by {@code label} so two
     * slots in the same row never collide (ImGui would route every click to
     * the first slot otherwise).
     */
    public static void assetSlot(String label, String sourceLabel, byte[] bytes,
                          Runnable onPick, Runnable onClear) {
        assetSlot(label,
                bytes != null ? (sourceLabel != null ? sourceLabel : "(loaded)") : null,
                bytes != null ? humanBytes(bytes.length) : null,
                "(unset)", null, onPick, onClear);
    }

    /**
     * Same slot layout for callers whose asset is a path resolved at write
     * time rather than bytes (the SBE exporter's staged state/variant files).
     *
     * @param name      file name to show, or null when the slot is empty
     * @param sizeText  dim size suffix, or null
     * @param emptyHint text shown while empty (e.g. "(use base OMO)")
     * @param tooltip   hover tooltip on the file name, or null
     */
    public static void assetSlot(String label, String name, String sizeText, String emptyHint,
                                 String tooltip, Runnable onPick, Runnable onClear) {
        ImGui.pushID(label);
        ImGui.indent(20.0f);
        ImGui.textDisabled(label);
        ImGui.sameLine(80.0f);

        if (name != null) {
            ImGui.text(name);
            if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
            if (sizeText != null) {
                ImGui.sameLine();
                ImGui.textDisabled("(" + sizeText + ")");
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Replace...")) onPick.run();
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) onClear.run();
        } else {
            ImGui.textDisabled(emptyHint);
            ImGui.sameLine();
            if (ImGui.smallButton("Set...")) onPick.run();
        }
        ImGui.unindent(20.0f);
        ImGui.popID();
    }
}
