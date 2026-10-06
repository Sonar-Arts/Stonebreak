package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImDrawList;
import imgui.ImGui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The inspector's layout controls: position mode with an anchor/offset diagram, size, the
 * flexbox container controls (direction, wrap, justify, align, gap) as glyph segments, the
 * element's own flex item settings, and a box-model widget for margin and padding.
 */
final class LayoutSection {

    private static final String[] DIRECTIONS = {"row", "column", "row-reverse", "column-reverse"};
    private static final String[] JUSTIFY = {"flex-start", "center", "flex-end", "space-between", "space-around",
        "space-evenly"};
    private static final String[] ALIGN = {"flex-start", "center", "flex-end", "stretch", "baseline"};

    private LayoutSection() {
    }

    static void draw(UiEditorContext ctx, DetailRows rows) {
        InspectorTarget t = rows.target();
        positionRows(ctx, rows, t);
        EditorWidgets.caption("Size");
        rows.length("Width", "width", true);
        rows.length("Height", "height", true);
        if (rows.visible("Min / Max", "min-width")) {
            rows.length("Min Width", "min-width", true);
            rows.length("Max Width", "max-width", true);
            rows.length("Min Height", "min-height", true);
            rows.length("Max Height", "max-height", true);
        }
        rows.number("Aspect Ratio", "aspect-ratio", 0.01f, 0, 100, false);

        boolean container = t.internal ? t.element != null && !t.element.children().isEmpty()
            : t.type() != null && NodeCommands.acceptsChildren(t.type());
        if (container) {
            EditorWidgets.caption("Flex Container");
            boolean row = keyword(t, "flex-direction", "column").startsWith("row");
            rows.segmented("Direction", "flex-direction", DIRECTIONS, painters(DIRECTIONS, (dl, k, x, y, s, c) ->
                Glyphs.direction(dl, k, x, y, s, c)), new String[]{"Row (left to right)", "Column (top to bottom)",
                "Row reversed", "Column reversed"});
            rows.segmented("Justify", "justify-content", JUSTIFY, painters(JUSTIFY, (dl, k, x, y, s, c) ->
                Glyphs.justify(dl, k, row, x, y, s, c)), new String[]{"Start", "Center", "End", "Space between",
                "Space around", "Space evenly"});
            rows.segmented("Align Items", "align-items", ALIGN, painters(ALIGN, (dl, k, x, y, s, c) ->
                Glyphs.align(dl, k, row, x, y, s, c)), new String[]{"Start", "Center", "End", "Stretch", "Baseline"});
            rows.keyword("Wrap", "flex-wrap");
            if (!"nowrap".equals(keyword(t, "flex-wrap", "nowrap"))) {
                rows.keyword("Align Content", "align-content");
            }
            rows.length("Row Gap", "row-gap", false);
            rows.length("Column Gap", "column-gap", false);
        }
        if (t.element != null && t.element.parent() != null) {
            EditorWidgets.caption("Flex Item");
            boolean parentRow = t.element.parent().computedStyle().keyword("flex-direction", "column").startsWith("row");
            String[] self = {"auto", "flex-start", "center", "flex-end", "stretch"};
            rows.segmented("Align Self", "align-self", self, painters(self, (dl, k, x, y, s, c) -> {
                if ("auto".equals(k)) {
                    dl.addText(x + s * 0.05f, y + s * 0.05f, c, "A");
                } else {
                    Glyphs.align(dl, k, parentRow, x, y, s, c);
                }
            }), new String[]{"Auto (from the container)", "Start", "Center", "End", "Stretch"});
            rows.number("Grow", "flex-grow", 0.05f, 0, 1000, false);
            rows.number("Shrink", "flex-shrink", 0.05f, 0, 1000, false);
            rows.length("Basis", "flex-basis", true);
        }
        EditorWidgets.caption("Spacing");
        boxModel(ctx, rows, t);
        rows.length("Translate X", "translate-x", false);
        rows.length("Translate Y", "translate-y", false);
    }

    private static String keyword(InspectorTarget t, String prop, String fallback) {
        UiValue a = t.style(prop);
        UiValue v = a != null && a != ValueFields.MIXED ? a : t.effective(prop);
        return v instanceof UiValue.Str s ? s.value() : fallback;
    }

    // ── position ────────────────────────────────────────────────────────────

    private static void positionRows(UiEditorContext ctx, DetailRows rows, InspectorTarget t) {
        if (!rows.visible("Position", "position")) {
            return;
        }
        UiValue a = t.style("position");
        boolean absolute = "absolute".equals(keyword(t, "position", "relative"));
        float w = rows.begin("Position", "position\nRelative: placed by the parent's flex layout.\n"
            + "Absolute: placed by left/top/right/bottom inside the parent.", a != null, t.bound("style:position"));
        int clicked = EditorWidgets.segmentedText("pos", absolute ? 1 : 0, new String[]{"Relative", "Absolute"},
            ImGui.getFrameHeight(), null);
        if (clicked >= 0) {
            boolean toAbs = clicked == 1;
            if (toAbs != absolute) {
                Map<String, UiValue> m = new LinkedHashMap<>();
                m.put("position", UiValue.of(toAbs ? "absolute" : "relative"));
                if (toAbs && t.element != null && !t.internal) {
                    // keep the element where it is: pin its current offset
                    float[] o = ctx.actions.offsets(t.keys.getFirst());
                    m.put("left", UiValue.of(Math.round(o[0])));
                    m.put("top", UiValue.of(Math.round(o[1])));
                }
                ctx.actions.run(t.setStyle(m, toAbs ? "Make absolute" : "Make relative"));
                ctx.actions.endInteraction();
            }
        }
        ImGui.sameLine(0, 0);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, w - ImGui.getItemRectSizeX()));
        if (rows.end(a != null, "position")) {
            ctx.actions.run(t.setStyle("position", null));
        }
        if (absolute) {
            anchorDiagram(rows, t);
            rows.length("Left", "left", true);
            rows.length("Top", "top", true);
            rows.length("Right", "right", true);
            rows.length("Bottom", "bottom", true);
            rows.number("Layer", "-sb-layer", 0.1f, -1000, 1000, true);
        }
    }

    /** Which sides are pinned: a parent box with the element inside and lit edge links. */
    private static void anchorDiagram(DetailRows rows, InspectorTarget t) {
        float s = 64;
        float x0 = ImGui.getCursorScreenPosX() + DetailRows.labelWidth();
        float y0 = ImGui.getCursorScreenPosY() + 2;
        ImDrawList dl = ImGui.getWindowDrawList();
        int frame = EditorWidgets.border(1f);
        int on = Glyphs.rgba(0.30f, 0.78f, 0.86f, 1f);
        int off = EditorWidgets.dim(0.45f);
        dl.addRect(x0, y0, x0 + s * 1.6f, y0 + s, frame, 3f, 0, 1f);
        float ex0 = x0 + s * 0.5f;
        float ey0 = y0 + s * 0.3f;
        float ex1 = x0 + s * 1.1f;
        float ey1 = y0 + s * 0.7f;
        dl.addRectFilled(ex0, ey0, ex1, ey1, EditorWidgets.accent(0.35f), 2f);
        dl.addRect(ex0, ey0, ex1, ey1, EditorWidgets.accent(1f), 2f, 0, 1f);
        float cy = (ey0 + ey1) / 2f;
        float cx = (ex0 + ex1) / 2f;
        pin(dl, x0, cy, ex0, cy, set(t, "left") ? on : off);
        pin(dl, ex1, cy, x0 + s * 1.6f, cy, set(t, "right") ? on : off);
        pin(dl, cx, y0, cx, ey0, set(t, "top") ? on : off);
        pin(dl, cx, ey1, cx, y0 + s, set(t, "bottom") ? on : off);
        ImGui.dummy(0, s + 6);
    }

    private static boolean set(InspectorTarget t, String side) {
        UiValue v = t.effective(side);
        return v != null && !(v instanceof UiValue.Str s && "auto".equals(s.value()));
    }

    private static void pin(ImDrawList dl, float x0, float y0, float x1, float y1, int col) {
        Glyphs.dashed(dl, x0, y0, x1, y1, col, 1.5f, 3f, 2f);
        dl.addCircleFilled(x0, y0, 2.5f, col);
    }

    // ── box model ───────────────────────────────────────────────────────────

    /** Nested margin / padding boxes with a drag field on every side, Chrome devtools-style. */
    private static void boxModel(UiEditorContext ctx, DetailRows rows, InspectorTarget t) {
        if (!rows.visible("Margin Padding", "margin")) {
            return;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        float w = ImGui.getContentRegionAvailX();
        float fh = ImGui.getFrameHeight();
        float h = fh * 6.4f;
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        int marginCol = Glyphs.rgba(0.96f, 0.62f, 0.26f, 0.16f);
        int paddingCol = Glyphs.rgba(0.40f, 0.80f, 0.45f, 0.18f);
        float mInset = fh * 1.1f;
        float pInset = mInset + fh * 1.2f;
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, marginCol, 4f);
        dl.addRect(x0, y0, x0 + w, y0 + h, Glyphs.withAlpha(marginCol, 1f), 4f, 0, 1f);
        dl.addRectFilled(x0 + mInset, y0 + mInset, x0 + w - mInset, y0 + h - mInset, paddingCol, 3f);
        dl.addRect(x0 + mInset, y0 + mInset, x0 + w - mInset, y0 + h - mInset, Glyphs.withAlpha(paddingCol, 1f), 3f, 0, 1f);
        dl.addRectFilled(x0 + pInset, y0 + pInset, x0 + w - pInset, y0 + h - pInset, EditorWidgets.frame(1f), 2f);
        dl.addText(x0 + 4, y0 + 2, EditorWidgets.dim(1f), "margin");
        dl.addText(x0 + mInset + 4, y0 + mInset + 2, EditorWidgets.dim(1f), "padding");
        String size = t.element == null ? "" : Math.round(t.element.rect().width() / Math.max(0.01f, scale(ctx))) + " x "
            + Math.round(t.element.rect().height() / Math.max(0.01f, scale(ctx)));
        float sw = ImGui.calcTextSize(size).x;
        dl.addText(x0 + (w - sw) / 2f, y0 + h / 2f - ImGui.getTextLineHeight() / 2f, EditorWidgets.text(0.8f), size);
        float fw = Math.min(44, w / 7f);
        side(ctx, t, "margin-top", x0 + w / 2f - fw / 2f, y0 + (mInset - fh) / 2f, fw);
        side(ctx, t, "margin-bottom", x0 + w / 2f - fw / 2f, y0 + h - mInset + (mInset - fh) / 2f, fw);
        side(ctx, t, "margin-left", x0 + (mInset - fw) / 2f + 1, y0 + h / 2f - fh / 2f, fw);
        side(ctx, t, "margin-right", x0 + w - mInset + (mInset - fw) / 2f - 1, y0 + h / 2f - fh / 2f, fw);
        float pin = pInset - mInset;
        side(ctx, t, "padding-top", x0 + w / 2f - fw / 2f, y0 + mInset + (pin - fh) / 2f, fw);
        side(ctx, t, "padding-bottom", x0 + w / 2f - fw / 2f, y0 + h - pInset + (pin - fh) / 2f, fw);
        side(ctx, t, "padding-left", x0 + mInset + (pin - fw) / 2f, y0 + h / 2f - fh / 2f, fw);
        side(ctx, t, "padding-right", x0 + w - pInset + (pin - fw) / 2f, y0 + h / 2f - fh / 2f, fw);
        ImGui.setCursorScreenPos(x0, y0 + h + 4);
        ImGui.dummy(w, 0);
    }

    private static float scale(UiEditorContext ctx) {
        DocumentViewState v = ctx.view();
        return v == null ? 1f : v.scale();
    }

    private static void side(UiEditorContext ctx, InspectorTarget t, String prop, float x, float y, float w) {
        ImGui.setCursorScreenPos(x, y);
        UiValue a = t.style(prop);
        UiValue eff = t.effective(prop) == null ? UiValue.of(0) : t.effective(prop);
        ValueFields.Result r = ValueFields.number("bm_" + prop, a, eff, 0.5f, -10000, 10000, true, w);
        if (r.changed()) {
            ctx.actions.run(t.setStyle(prop, r.value()));
        }
        if (r.ended()) {
            ctx.actions.endInteraction();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(prop + (a != null ? "  (authored; right-click to reset)" : "  from " + t.origin(prop)));
        }
        if (a != null && ImGui.isItemClicked(1)) {
            ctx.actions.run(t.setStyle(prop, null));
        }
        if (a != null) {
            ImGui.getWindowDrawList().addLine(x + 3, y + ImGui.getFrameHeight() - 2, x + w - 3,
                y + ImGui.getFrameHeight() - 2, t.internal ? Glyphs.typeColor("Instance", 1f) : EditorWidgets.accent(1f), 2f);
        }
    }

    // ── glyph painters ──────────────────────────────────────────────────────

    @FunctionalInterface
    private interface KeyPainter {
        void paint(ImDrawList dl, String keyword, float x, float y, float s, int col);
    }

    private static EditorWidgets.Painter[] painters(String[] keys, KeyPainter p) {
        EditorWidgets.Painter[] out = new EditorWidgets.Painter[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            out[i] = (dl, x, y, s, c) -> p.paint(dl, k, x, y, s, c);
        }
        return out;
    }
}
