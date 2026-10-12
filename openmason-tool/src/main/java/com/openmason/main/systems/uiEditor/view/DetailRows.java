package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImDrawList;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Inspector rows, Unreal Details-style: a dim label column, the field, and a reset arrow on
 * anything authored. An authored value gets an accent bar at the row's left edge (purple when it
 * is an instance override); unset fields show the effective cascaded value dimmed, with its
 * origin in the label tooltip. A search filter hides rows whose label does not match.
 */
final class DetailRows {

    private static final float LABEL_MIN = 84f;
    private static final float LABEL_MAX = 150f;

    private final UiEditorContext ctx;
    private final String filter;
    private final InspectorTarget target;

    DetailRows(UiEditorContext ctx, InspectorTarget target, String filter) {
        this.ctx = ctx;
        this.target = target;
        this.filter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    InspectorTarget target() {
        return target;
    }

    boolean visible(String label, String prop) {
        return filter.isEmpty() || label.toLowerCase(Locale.ROOT).contains(filter)
            || prop != null && prop.toLowerCase(Locale.ROOT).contains(filter);
    }

    static float labelWidth() {
        return Math.max(LABEL_MIN, Math.min(LABEL_MAX, ImGui.getContentRegionAvailX() * 0.38f));
    }

    /**
     * Draws the label and positions the cursor at the field column.
     *
     * @return the field width (the reset arrow's slot excluded)
     */
    float begin(String label, String tooltip, boolean authored, boolean bound) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = ImGui.getFrameHeight();
        if (authored) {
            int bar = target.internal ? Glyphs.typeColor("Instance", 1f) : EditorWidgets.accent(1f);
            dl.addRectFilled(x - 6, y + 3, x - 4, y + h - 3, bar, 1f);
        }
        float startX = ImGui.getCursorPosX();
        ImGui.alignTextToFramePadding();
        if (authored) {
            ImGui.textUnformatted(label);
        } else {
            ImGui.textDisabled(label);
        }
        if (ImGui.isItemHovered() && tooltip != null) {
            ImGui.setTooltip(tooltip);
        }
        if (bound) {
            ImGui.sameLine(0, 4);
            float gx = ImGui.getCursorScreenPosX();
            Glyphs.link(dl, gx, y + h * 0.2f, h * 0.6f, Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
            ImGui.dummy(h * 0.6f, h);
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Bound: the binding's value wins at runtime; the authored value is the fallback.");
            }
        }
        ImGui.sameLine();
        ImGui.setCursorPosX(startX + labelWidth());
        return Math.max(40, ImGui.getContentRegionAvailX() - h - 2);
    }

    /** Draws the reset arrow when {@code authored}; true when clicked. */
    boolean end(boolean authored, String what) {
        float h = ImGui.getFrameHeight();
        ImGui.sameLine(0, 2);
        if (!authored) {
            ImGui.dummy(h, h);
            return false;
        }
        String tip = target.internal ? "Reset " + what + " to the component's value" : "Reset " + what + " to default";
        return EditorWidgets.iconButton("##reset_" + what, h, (dl, x, y, s, c) ->
            Glyphs.reset(dl, x, y, s, Glyphs.rgba(0.96f, 0.76f, 0.24f, 1f)), tip, false, true);
    }

    /** Applies a field result through {@code command} (null value = reset). */
    void apply(ValueFields.Result r, Function<UiValue, UiCommand> command) {
        if (r.changed()) {
            ctx.actions.run(command.apply(r.value()));
        }
        if (r.ended()) {
            ctx.actions.endInteraction();
        }
    }

    // ── style rows ──────────────────────────────────────────────────────────

    private boolean authored(UiValue v) {
        return v != null;
    }

    private String tip(String prop) {
        return prop + "\nEffective: " + ValueFields.display(target.effective(prop)) + "\nFrom: " + target.origin(prop);
    }

    void length(String label, String prop, boolean allowAuto) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.length("st_" + prop, a, target.effective(prop) == null ? UiValue.of("auto")
            : target.effective(prop), allowAuto, w), v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    void number(String label, String prop, float speed, float min, float max, boolean integer) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.number("st_" + prop, a, target.effective(prop), speed, min, max, integer, w),
            v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    void unit(String label, String prop) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        UiValue eff = target.effective(prop) == null ? UiValue.of(1) : target.effective(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.unit("st_" + prop, a, eff, w), v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    void color(String label, String prop) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.color("st_" + prop, a, target.effective(prop), tokens(), w), v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    void keyword(String label, String prop) {
        if (!visible(label, prop)) {
            return;
        }
        UiStyleProperties.Spec spec = UiStyleProperties.spec(prop);
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.keyword("st_" + prop, a, target.effective(prop), new TreeSet<>(spec.keywords()), w),
            v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    void asset(String label, String prop, Set<UiDependency.Kind> kinds) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        apply(ValueFields.asset("st_" + prop, a, target.effective(prop), assetIds(kinds), w), v -> target.setStyle(prop, v));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    /** An image-valued style property (#294): textures, sprites and skins, with Edit buttons. */
    void image(String label, String prop) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        ImGui.beginGroup();
        apply(ctx.images.field("st_" + prop, a, target.effective(prop), w), v -> target.setStyle(prop, v));
        ImGui.endGroup();
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    /**
     * A keyword as a segmented glyph control (direction, justify, align). {@code glyphs[i]}
     * draws {@code options[i]}.
     */
    void segmented(String label, String prop, String[] options, EditorWidgets.Painter[] glyphs, String[] tips) {
        if (!visible(label, prop)) {
            return;
        }
        UiValue a = target.style(prop);
        UiValue shown = a != null && a != ValueFields.MIXED ? a : target.effective(prop);
        int sel = -1;
        for (int i = 0; i < options.length; i++) {
            if (shown instanceof UiValue.Str s && s.value().equals(options[i])) {
                sel = i;
            }
        }
        float w = begin(label, tip(prop), authored(a), target.bound("style:" + prop));
        float cell = Math.min(ImGui.getFrameHeight() + 4, w / options.length);
        int clicked = EditorWidgets.segmented("seg_" + prop, a == null ? -1 : sel, cell, glyphs, tips);
        if (a == null && sel >= 0) {
            // unauthored: outline the effective choice faintly
            float x0 = ImGui.getItemRectMinX() - cell * options.length + sel * cell;
            ImGui.getWindowDrawList().addRect(x0 + 1, ImGui.getItemRectMinY() + 1, x0 + cell - 1,
                ImGui.getItemRectMinY() + cell - 1, EditorWidgets.dim(0.8f), 3f, 0, 1f);
        }
        if (clicked >= 0) {
            ctx.actions.run(target.setStyle(prop, UiValue.of(options[clicked])));
            ctx.actions.endInteraction();
        }
        ImGui.sameLine(0, 0);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, w - cell * options.length));
        if (end(authored(a), prop)) {
            ctx.actions.run(target.setStyle(prop, null));
        }
    }

    /** {@code --tokens} visible to the element: the document's own sheets' variables. */
    List<String> tokens() {
        Set<String> out = new TreeSet<>();
        for (UiStyleSheet s : target.doc.archive().styles().values()) {
            out.addAll(s.variables().keySet());
        }
        if (target.element != null) {
            out.addAll(target.element.computedStyle().customs().keySet());
        }
        return new ArrayList<>(out);
    }

    List<String> assetIds(Set<UiDependency.Kind> kinds) {
        List<String> out = new ArrayList<>();
        for (UiDependency d : target.doc.archive().dependencies().entries()) {
            if (kinds.contains(d.kind())) {
                out.add(d.id());
            }
        }
        return out;
    }
}
