package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiBezier;
import com.openmason.engine.format.omui.UiEasing;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Easing of a key or a transition (#295): a named easing, or a custom {@code cubic-bezier} curve
 * edited as its four numbers with a drawn preview. Shared by the Timeline and the Style Sheets
 * panel so both author timing the same way.
 */
final class CurveField {

    /** What the author picked this frame; {@code ended} closes a merged drag. */
    record Result(boolean changed, UiEasing easing, UiBezier bezier, boolean ended) {
        static final Result NONE = new Result(false, null, null, false);
    }

    /** The CSS {@code ease} curve, a pleasant starting point for a custom one. */
    static final UiBezier DEFAULT = new UiBezier(0.25, 0.1, 0.25, 1);

    private CurveField() {
    }

    /**
     * @param easing current named easing ({@code null} = several differ)
     * @param bezier current custom curve, or null
     */
    static Result edit(String id, UiEasing easing, UiBezier bezier, float width) {
        Result out = Result.NONE;
        String preview = bezier != null ? "custom curve" : easing == null ? "--" : easing.wire();
        ImGui.setNextItemWidth(width);
        if (ImGui.beginCombo("##ease" + id, preview)) {
            for (UiEasing e : UiEasing.values()) {
                if (ImGui.selectable(e.wire(), bezier == null && e == easing)) {
                    out = new Result(true, e, null, true);
                }
            }
            if (ImGui.selectable("custom curve...", bezier != null)) {
                out = new Result(true, easing == null ? UiEasing.LINEAR : easing, bezier != null ? bezier : DEFAULT, true);
            }
            ImGui.endCombo();
        }
        if (bezier == null) {
            return out;
        }
        float[][] p = {{(float) bezier.x1()}, {(float) bezier.y1()}, {(float) bezier.x2()}, {(float) bezier.y2()}};
        String[] names = {"x1", "y1", "x2", "y2"};
        boolean changed = false;
        boolean ended = false;
        float cell = (width - 12) / 4f;
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                ImGui.sameLine(0, 4);
            }
            ImGui.setNextItemWidth(cell);
            boolean x = i % 2 == 0;
            changed |= ImGui.dragFloat("##" + names[i] + id, p[i], 0.01f, x ? 0f : (float) UiBezier.MIN_Y,
                x ? 1f : (float) UiBezier.MAX_Y, names[i] + " %.2f");
            ended |= ImGui.isItemDeactivated();
        }
        UiBezier next = new UiBezier(round(p[0][0]), round(p[1][0]), round(p[2][0]), round(p[3][0]));
        draw(next, Math.min(width, 120f));
        if (changed) {
            return new Result(true, easing == null ? UiEasing.LINEAR : easing, next, ended);
        }
        return ended ? new Result(false, null, null, true) : out;
    }

    private static double round(float v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** The curve over time (x) against progress (y), with its control handles. */
    private static void draw(UiBezier b, float size) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float pad = size * 0.2f;
        float h = size;
        float top = y0 + pad;
        float span = h - 2 * pad;
        dl.addRectFilled(x0, y0, x0 + size, y0 + h, ImGui.getColorU32(ImGuiCol.FrameBg), 3f);
        dl.addLine(x0, top + span, x0 + size, top + span, ImGui.getColorU32(ImGuiCol.Border), 1f);
        dl.addLine(x0, top, x0 + size, top, ImGui.getColorU32(ImGuiCol.Border), 1f);
        int curve = ImGui.getColorU32(ImGuiCol.PlotLines);
        float px = x0;
        float py = top + span;
        for (int i = 1; i <= 32; i++) {
            double u = i / 32.0;
            float nx = x0 + (float) u * size;
            float ny = top + span - (float) b.apply(u) * span;
            dl.addLine(px, py, nx, ny, curve, 2f);
            px = nx;
            py = ny;
        }
        int handle = ImGui.getColorU32(ImGuiCol.CheckMark);
        float h1x = x0 + (float) b.x1() * size;
        float h1y = top + span - (float) b.y1() * span;
        float h2x = x0 + (float) b.x2() * size;
        float h2y = top + span - (float) b.y2() * span;
        dl.addLine(x0, top + span, h1x, h1y, handle, 1f);
        dl.addLine(x0 + size, top, h2x, h2y, handle, 1f);
        dl.addCircleFilled(h1x, h1y, 3f, handle);
        dl.addCircleFilled(h2x, h2y, 3f, handle);
        ImGui.dummy(size, h);
    }
}
