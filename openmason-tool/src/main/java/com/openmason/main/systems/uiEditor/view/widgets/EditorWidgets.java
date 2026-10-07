package com.openmason.main.systems.uiEditor.view.widgets;

import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

/**
 * Small custom controls the UI editor shares: glyph icon buttons and toggles, segmented
 * controls, search fields, chips and badges. Everything derives its colors from the live ImGui
 * style (accent = {@code HeaderActive}), so every theme, light or dark, reads correctly.
 */
public final class EditorWidgets {

    /** Paints a glyph into an {@code s x s} square. */
    @FunctionalInterface
    public interface Painter {
        void paint(ImDrawList dl, float x, float y, float s, int col);
    }

    private EditorWidgets() {
    }

    // ── colors ──────────────────────────────────────────────────────────────

    public static int accent(float alpha) {
        return ThemeColors.u32(ImGuiCol.HeaderActive, alpha);
    }

    public static int text(float alpha) {
        return ThemeColors.u32(ImGuiCol.Text, alpha);
    }

    public static int dim(float alpha) {
        return ThemeColors.u32(ImGuiCol.TextDisabled, alpha);
    }

    public static int frame(float alpha) {
        return ThemeColors.u32(ImGuiCol.FrameBg, alpha);
    }

    public static int border(float alpha) {
        return ThemeColors.u32(ImGuiCol.Border, alpha);
    }

    public static int windowBg(float alpha) {
        return ThemeColors.u32(ImGuiCol.WindowBg, alpha);
    }

    // ── buttons ─────────────────────────────────────────────────────────────

    /**
     * A square glyph button. {@code on} draws it as an active toggle (accent wash + accent glyph).
     *
     * @return true when clicked (never when disabled)
     */
    public static boolean iconButton(String id, float size, Painter glyph, String tooltip, boolean on, boolean enabled) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size) && enabled;
        boolean hovered = ImGui.isItemHovered() && enabled;
        boolean held = ImGui.isItemActive() && enabled;
        ImDrawList dl = ImGui.getWindowDrawList();
        float r = Math.max(3f, size * 0.18f);
        if (on) {
            dl.addRectFilled(x, y, x + size, y + size, accent(hovered ? 0.42f : 0.30f), r);
            dl.addRect(x, y, x + size, y + size, accent(0.65f), r, 0, 1f);
        } else if (held) {
            dl.addRectFilled(x, y, x + size, y + size, frame(1f), r);
        } else if (hovered) {
            dl.addRectFilled(x, y, x + size, y + size, ThemeColors.u32(ImGuiCol.ButtonHovered, 0.55f), r);
        }
        float pad = size * 0.2f;
        int col = !enabled ? dim(0.5f) : on ? accentText() : hovered ? text(1f) : text(0.82f);
        glyph.paint(dl, x + pad, y + pad, size - pad * 2, col);
        if (tooltip != null && ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(tooltip);
        }
        return clicked;
    }

    /** Text color for content on an accent wash: the accent itself, lifted for contrast. */
    public static int accentText() {
        ImVec4 a = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        boolean light = ThemeColors.isLightTheme();
        float k = light ? 0.75f : 1.25f;
        return ImGui.colorConvertFloat4ToU32(Math.min(1f, a.x * k), Math.min(1f, a.y * k), Math.min(1f, a.z * k), 1f);
    }

    /**
     * A segmented control of glyph cells; returns the clicked index or -1. {@code selected} may
     * be -1 (mixed or unset).
     */
    public static int segmented(String id, int selected, float cell, Painter[] glyphs, String[] tooltips) {
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        float w = cell * glyphs.length;
        float r = Math.max(3f, cell * 0.2f);
        dl.addRectFilled(x0, y0, x0 + w, y0 + cell, frame(1f), r);
        int clicked = -1;
        for (int i = 0; i < glyphs.length; i++) {
            float x = x0 + i * cell;
            ImGui.setCursorScreenPos(x, y0);
            if (ImGui.invisibleButton(id + "_" + i, cell, cell)) {
                clicked = i;
            }
            boolean hovered = ImGui.isItemHovered();
            if (i == selected) {
                dl.addRectFilled(x + 1, y0 + 1, x + cell - 1, y0 + cell - 1, accent(0.34f), r - 1);
                dl.addRect(x + 1, y0 + 1, x + cell - 1, y0 + cell - 1, accent(0.7f), r - 1, 0, 1f);
            } else if (hovered) {
                dl.addRectFilled(x + 1, y0 + 1, x + cell - 1, y0 + cell - 1, ThemeColors.u32(ImGuiCol.ButtonHovered, 0.5f),
                    r - 1);
            }
            float pad = cell * 0.2f;
            int col = i == selected ? accentText() : hovered ? text(1f) : text(0.7f);
            glyphs[i].paint(dl, x + pad, y0 + pad, cell - pad * 2, col);
            if (hovered && tooltips != null && i < tooltips.length) {
                ImGui.setTooltip(tooltips[i]);
            }
        }
        ImGui.setCursorScreenPos(x0 + w, y0);
        ImGui.dummy(0, cell);
        return clicked;
    }

    /** A segmented control of text labels (Design | Preview). */
    public static int segmentedText(String id, int selected, String[] labels, float height, Painter[] lead) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float padX = 10f;
        float icon = lead == null ? 0 : height * 0.5f;
        float[] widths = new float[labels.length];
        float total = 0;
        for (int i = 0; i < labels.length; i++) {
            widths[i] = ImGui.calcTextSize(labels[i]).x + padX * 2 + (lead != null && lead[i] != null ? icon + 4 : 0);
            total += widths[i];
        }
        float r = height * 0.25f;
        dl.addRectFilled(x0, y0, x0 + total, y0 + height, frame(1f), r);
        int clicked = -1;
        float x = x0;
        for (int i = 0; i < labels.length; i++) {
            ImGui.setCursorScreenPos(x, y0);
            if (ImGui.invisibleButton(id + "_" + i, widths[i], height)) {
                clicked = i;
            }
            boolean hovered = ImGui.isItemHovered();
            if (i == selected) {
                dl.addRectFilled(x + 2, y0 + 2, x + widths[i] - 2, y0 + height - 2, accent(0.85f), r - 1);
            } else if (hovered) {
                dl.addRectFilled(x + 2, y0 + 2, x + widths[i] - 2, y0 + height - 2,
                    ThemeColors.u32(ImGuiCol.ButtonHovered, 0.6f), r - 1);
            }
            int col = i == selected ? onAccent() : hovered ? text(1f) : text(0.75f);
            float tx = x + padX;
            if (lead != null && lead[i] != null) {
                lead[i].paint(dl, tx, y0 + (height - icon) / 2f, icon, col);
                tx += icon + 4;
            }
            dl.addText(tx, y0 + (height - ImGui.getTextLineHeight()) / 2f, col, labels[i]);
            x += widths[i];
        }
        ImGui.setCursorScreenPos(x0 + total, y0);
        ImGui.dummy(0, height);
        return clicked;
    }

    /** Text color on an opaque accent fill (white or near-black by contrast). */
    public static int onAccent() {
        ImVec4 a = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        float lum = 0.2126f * a.x + 0.7152f * a.y + 0.0722f * a.z;
        return lum > 0.55f ? Glyphs.rgba(0.08f, 0.09f, 0.11f, 1f) : Glyphs.rgba(1f, 1f, 1f, 1f);
    }

    // ── fields ──────────────────────────────────────────────────────────────

    /** A full-width search field with a magnifier glyph; returns true when the text changed. */
    public static boolean searchField(String id, ImString value, String hint) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = ImGui.getFrameHeight();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, h * 0.95f, ImGui.getStyle().getFramePaddingY());
        ImGui.setNextItemWidth(-1);
        boolean changed = ImGui.inputTextWithHint(id, hint, value, ImGuiInputTextFlags.AutoSelectAll);
        ImGui.popStyleVar();
        float s = h * 0.5f;
        float cx = x + h * 0.48f;
        float cy = y + h / 2f;
        dl.addCircle(cx - s * 0.1f, cy - s * 0.1f, s * 0.32f, dim(1f), 12, 1.4f);
        dl.addLine(cx + s * 0.14f, cy + s * 0.14f, cx + s * 0.42f, cy + s * 0.42f, dim(1f), 1.6f);
        if (!value.get().isEmpty()) {
            float bx = x + ImGui.getItemRectSizeX() - h;
            ImGui.setCursorScreenPos(bx, y);
            if (iconButton(id + "_clear", h, Glyphs::cross, "Clear", false, true)) {
                value.set("");
                changed = true;
            }
        }
        return changed;
    }

    /** A pill chip; returns true when its remove "x" was clicked. */
    public static boolean chip(String id, String label, boolean removable, int tint) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getTextLineHeight() + 6;
        float tw = ImGui.calcTextSize(label).x;
        float w = tw + 14 + (removable ? h - 4 : 0);
        if (ImGui.getContentRegionAvailX() < w) {
            ImGui.newLine();
        }
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        dl.addRectFilled(x, y, x + w, y + h, Glyphs.withAlpha(tint, 0.22f), h / 2f);
        dl.addRect(x, y, x + w, y + h, Glyphs.withAlpha(tint, 0.55f), h / 2f, 0, 1f);
        dl.addText(x + 7, y + 3, text(0.95f), label);
        boolean removed = false;
        ImGui.invisibleButton(id, w, h);
        if (removable) {
            float bx = x + w - h + 2;
            boolean overX = ImGui.isMouseHoveringRect(bx, y, x + w, y + h);
            dl.addCircleFilled(bx + (h - 4) / 2f, y + h / 2f, (h - 8) / 2f, overX ? Glyphs.withAlpha(tint, 0.6f) : 0);
            Glyphs.cross(dl, bx + 2, y + 4, h - 8, text(overX ? 1f : 0.6f));
            removed = overX && ImGui.isItemClicked();
            if (overX) {
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            }
        }
        return removed;
    }

    /** A small rounded badge drawn inline at the cursor (no interaction). */
    public static void badge(String label, int tint) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getTextLineHeight();
        float tw = ImGui.calcTextSize(label).x;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + 1;
        dl.addRectFilled(x, y, x + tw + 10, y + h, Glyphs.withAlpha(tint, 0.22f), 4f);
        dl.addText(x + 5, y, Glyphs.withAlpha(tint, 1f), label);
        ImGui.dummy(tw + 10, h + 2);
    }

    /** Dim, upper-case section caption with a hairline, Unreal-style. */
    public static void caption(String label) {
        ImGui.dummy(0, 2);
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        dl.addText(x, y, dim(1f), label.toUpperCase(java.util.Locale.ROOT));
        float tw = ImGui.calcTextSize(label.toUpperCase(java.util.Locale.ROOT)).x;
        float ly = y + ImGui.getTextLineHeight() / 2f;
        dl.addLine(x + tw + 8, ly, x + w, ly, border(0.6f), 1f);
        ImGui.dummy(w, ImGui.getTextLineHeight() + 2);
    }

    /** A centred, dim empty-state message for panels with nothing to show. */
    public static void emptyState(String title, String body) {
        float w = ImGui.getContentRegionAvailX();
        ImGui.dummy(0, 18);
        float tw = ImGui.calcTextSize(title).x;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, (w - tw) / 2f));
        ImGui.textDisabled(title);
        if (body != null && !body.isEmpty()) {
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + w);
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
            float bw = Math.min(w, ImGui.calcTextSize(body).x);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, (w - bw) / 2f));
            ImGui.textWrapped(body);
            ImGui.popStyleColor();
            ImGui.popTextWrapPos();
        }
    }
}
