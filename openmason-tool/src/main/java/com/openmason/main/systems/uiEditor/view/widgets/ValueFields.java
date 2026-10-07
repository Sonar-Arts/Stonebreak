package com.openmason.main.systems.uiEditor.view.widgets;

import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed value editors for the inspector. Each draws one field filling the rest of the row and
 * reports what happened: a live change (apply it now; drags apply every frame and merge into one
 * undo step), a reset, and whether the interaction ended (so the caller can close the undo
 * step). Unset values show the effective (cascaded) value dimmed, so the author always sees what
 * is on screen, and typing over it makes it authored.
 */
public final class ValueFields {

    /** Marker for "the selected elements disagree". */
    public static final UiValue MIXED = new UiValue.Str("<<mixed>>");

    private static final Pattern PERCENT = Pattern.compile("(-?\\d+(\\.\\d+)?)%");
    private static final Map<String, ImString> TEXT = new HashMap<>();
    private static final Map<String, String> TEXT_SOURCE = new HashMap<>();

    /** Field outcome. {@code value == null} with {@code changed} means reset (remove the declaration). */
    public record Result(boolean changed, UiValue value, boolean ended) {
        public static final Result NONE = new Result(false, null, false);

        static Result ended(boolean ended) {
            return ended ? new Result(false, null, true) : NONE;
        }
    }

    private ValueFields() {
    }

    // ── lengths ─────────────────────────────────────────────────────────────

    /**
     * Number + unit (px, %, auto). {@code effective} fills the field when unset.
     *
     * @param allowAuto offer {@code auto}
     */
    public static Result length(String id, UiValue authored, UiValue effective, boolean allowAuto, float width) {
        UiValue shown = authored != null && authored != MIXED ? authored : effective;
        String unit = unitOf(shown);
        boolean isVar = shown instanceof UiValue.Str s && s.value().startsWith("var(");
        float unitW = Math.max(ImGui.getFrameHeight() * 1.6f, ImGui.calcTextSize("auto").x + ImGui.getStyle().getFramePaddingX() * 2 + 2);
        float fieldW = Math.max(30, width - unitW - 2);
        boolean dim = authored == null || authored == MIXED;
        Result out = Result.NONE;
        if (isVar) {
            return text(id, authored == null ? null : ((UiValue.Str) shown).value(), "", width, v -> parseLength(v));
        }
        if ("auto".equals(unit)) {
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
            ImGui.button("auto##" + id, fieldW, 0);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Sized by layout. Pick px or % to set a length.");
            }
        } else {
            float[] buf = {number(shown)};
            if (dim) {
                ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
            }
            ImGui.setNextItemWidth(fieldW);
            String fmt = authored == MIXED ? "--" : buf[0] == Math.rint(buf[0]) ? "%.0f" : "%.2f";
            boolean changed = ImGui.dragFloat("##" + id, buf, "%".equals(unit) ? 0.25f : 1f, -100000, 100000, fmt);
            boolean ended = ImGui.isItemDeactivated();
            if (dim) {
                ImGui.popStyleColor();
            }
            if (changed) {
                float v = Math.round(buf[0] * 100) / 100f;
                out = new Result(true, "%".equals(unit) ? UiValue.of(trimNum(v) + "%") : UiValue.of(v), ended);
            } else if (ended) {
                out = Result.ended(true);
            }
        }
        ImGui.sameLine(0, 2);
        String next = unitButton(id, unit, allowAuto, unitW);
        if (next != null && !next.equals(unit)) {
            float n = number(shown);
            UiValue v = switch (next) {
                case "auto" -> UiValue.of("auto");
                case "%" -> UiValue.of(trimNum(n == 0 ? 100 : n) + "%");
                default -> UiValue.of(Math.round(n));
            };
            return new Result(true, v, true);
        }
        return out;
    }

    private static String unitButton(String id, String unit, boolean allowAuto, float w) {
        String picked = null;
        if (ImGui.button(unit + "##unit" + id, w, 0)) {
            ImGui.openPopup("##units" + id);
        }
        if (ImGui.beginPopup("##units" + id)) {
            for (String u : allowAuto ? List.of("px", "%", "auto") : List.of("px", "%")) {
                if (ImGui.selectable(u, u.equals(unit))) {
                    picked = u;
                }
            }
            ImGui.endPopup();
        }
        return picked;
    }

    private static String unitOf(UiValue v) {
        if (v instanceof UiValue.Str s) {
            if ("auto".equals(s.value())) {
                return "auto";
            }
            if (PERCENT.matcher(s.value()).matches()) {
                return "%";
            }
        }
        return "px";
    }

    /** The number inside a length ({@code 12}, {@code "50%"}); 0 otherwise. */
    public static float number(UiValue v) {
        if (v instanceof UiValue.Num n) {
            return (float) n.value();
        }
        if (v instanceof UiValue.Str s) {
            var m = PERCENT.matcher(s.value());
            if (m.matches()) {
                return Float.parseFloat(m.group(1));
            }
        }
        return 0;
    }

    private static UiValue parseLength(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return UiValue.of(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return UiValue.of(t);
        }
    }

    private static String trimNum(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    // ── numbers ─────────────────────────────────────────────────────────────

    public static Result number(String id, UiValue authored, UiValue effective, float speed, float min, float max,
                                boolean integer, float width) {
        UiValue shown = authored != null && authored != MIXED ? authored : effective;
        float[] buf = {shown instanceof UiValue.Num n ? (float) n.value() : 0};
        boolean dim = authored == null || authored == MIXED;
        if (dim) {
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
        }
        ImGui.setNextItemWidth(width);
        String fmt = authored == MIXED ? "--" : integer || buf[0] == Math.rint(buf[0]) ? "%.0f" : "%.2f";
        boolean changed = ImGui.dragFloat("##" + id, buf, speed, min, max, fmt);
        boolean ended = ImGui.isItemDeactivated();
        if (dim) {
            ImGui.popStyleColor();
        }
        if (changed) {
            double v = integer ? Math.round(buf[0]) : Math.round(buf[0] * 1000) / 1000.0;
            return new Result(true, UiValue.of(v), ended);
        }
        return Result.ended(ended);
    }

    /** 0..1 slider (opacity). */
    public static Result unit(String id, UiValue authored, UiValue effective, float width) {
        UiValue shown = authored != null && authored != MIXED ? authored : effective;
        float[] buf = {shown instanceof UiValue.Num n ? (float) n.value() : 1f};
        boolean dim = authored == null || authored == MIXED;
        if (dim) {
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
        }
        ImGui.setNextItemWidth(width);
        boolean changed = ImGui.sliderFloat("##" + id, buf, 0f, 1f, authored == MIXED ? "--" : "%.2f");
        boolean ended = ImGui.isItemDeactivated();
        if (dim) {
            ImGui.popStyleColor();
        }
        return changed ? new Result(true, UiValue.of(Math.round(buf[0] * 100) / 100.0), ended) : Result.ended(ended);
    }

    public static Result bool(String id, UiValue authored, UiValue effective) {
        UiValue shown = authored != null && authored != MIXED ? authored : effective;
        ImBoolean b = new ImBoolean(shown instanceof UiValue.Bool v && v.value());
        if (ImGui.checkbox("##" + id, b)) {
            return new Result(true, UiValue.of(b.get()), true);
        }
        return Result.NONE;
    }

    // ── colors ──────────────────────────────────────────────────────────────

    /**
     * Swatch + hex text; the swatch opens a picker with alpha. {@code tokens} are the
     * {@code --variables} in scope, offered as {@code var(--name)} references.
     */
    public static Result color(String id, UiValue authored, UiValue effective, Collection<String> tokens, float width) {
        UiValue shown = authored != null && authored != MIXED ? authored : effective;
        String hex = shown instanceof UiValue.Str s ? s.value() : "";
        float[] rgba = parseHex(hex);
        float sw = ImGui.getFrameHeight();
        Result out = Result.NONE;
        int flags = ImGuiColorEditFlags.AlphaPreviewHalf | ImGuiColorEditFlags.NoTooltip;
        if (ImGui.colorButton("##sw" + id, rgba, flags, sw * 1.4f, sw)) {
            ImGui.openPopup("##pick" + id);
        }
        if (ImGui.beginPopup("##pick" + id)) {
            float[] edit = rgba.clone();
            if (ImGui.colorPicker4("##picker" + id, edit, ImGuiColorEditFlags.AlphaBar | ImGuiColorEditFlags.AlphaPreviewHalf
                | ImGuiColorEditFlags.DisplayHex)) {
                out = new Result(true, UiValue.of(toHex(edit)), false);
            }
            if (ImGui.isItemDeactivated()) {
                out = new Result(out.changed(), out.value(), true);
            }
            ImGui.endPopup();
        }
        ImGui.sameLine(0, 4);
        float tokW = tokens.isEmpty() ? 0 : sw + 2;
        Result text = text(id, authored == null ? null : authored == MIXED ? "" : hex, hex, width - sw * 1.4f - 4 - tokW,
            v -> v.isBlank() ? null : UiValue.of(v.trim().startsWith("#") || v.startsWith("var(") ? v.trim() : "#" + v.trim()));
        if (text.changed() || text.ended()) {
            out = text;
        }
        if (!tokens.isEmpty()) {
            ImGui.sameLine(0, 2);
            if (ImGui.button("$##tok" + id, sw, 0)) {
                ImGui.openPopup("##tokens" + id);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Use a design token (var)");
            }
            if (ImGui.beginPopup("##tokens" + id)) {
                for (String t : tokens) {
                    if (ImGui.selectable(t)) {
                        out = new Result(true, UiValue.of("var(" + t + ")"), true);
                    }
                }
                ImGui.endPopup();
            }
        }
        return out;
    }

    /** RGBA floats of {@code #RRGGBB[AA]}; transparent black when it does not parse. */
    public static float[] parseHex(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            return new float[]{0, 0, 0, 0};
        }
        float r = Integer.parseInt(hex.substring(1, 3), 16) / 255f;
        float g = Integer.parseInt(hex.substring(3, 5), 16) / 255f;
        float b = Integer.parseInt(hex.substring(5, 7), 16) / 255f;
        float a = hex.length() == 9 ? Integer.parseInt(hex.substring(7, 9), 16) / 255f : 1f;
        return new float[]{r, g, b, a};
    }

    public static String toHex(float[] c) {
        int r = Math.round(c[0] * 255);
        int g = Math.round(c[1] * 255);
        int b = Math.round(c[2] * 255);
        int a = Math.round(c[3] * 255);
        return a == 255 ? String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
            : String.format(Locale.ROOT, "#%02X%02X%02X%02X", r, g, b, a);
    }

    // ── choices ─────────────────────────────────────────────────────────────

    /** A keyword combo; the first entry "(unset)" resets. */
    public static Result keyword(String id, UiValue authored, UiValue effective, Collection<String> options, float width) {
        String current = authored instanceof UiValue.Str s && authored != MIXED ? s.value() : null;
        String preview = authored == MIXED ? "--" : current != null ? current
            : effective instanceof UiValue.Str e ? e.value() : "";
        boolean dim = current == null;
        if (dim) {
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.getStyle().getColor(ImGuiCol.TextDisabled));
        }
        ImGui.setNextItemWidth(width);
        boolean open = ImGui.beginCombo("##" + id, preview, ImGuiComboFlags.HeightLarge);
        if (dim) {
            ImGui.popStyleColor();
        }
        Result out = Result.NONE;
        if (open) {
            if (ImGui.selectable("(unset)", current == null)) {
                out = new Result(true, null, true);
            }
            for (String o : options) {
                if (ImGui.selectable(o, o.equals(current))) {
                    out = new Result(true, UiValue.of(o), true);
                }
            }
            ImGui.endCombo();
        }
        return out;
    }

    /** Asset reference: dependency ids of the right kinds, {@code none}, or free text. */
    public static Result asset(String id, UiValue authored, UiValue effective, Collection<String> ids, float width) {
        String current = authored instanceof UiValue.Str s && authored != MIXED ? s.value() : null;
        String shown = current != null ? current : effective instanceof UiValue.Str e ? e.value() : "";
        float bw = ImGui.getFrameHeight();
        Result out = text(id, current, shown, width - bw - 2, v -> v.isBlank() ? null : UiValue.of(v.trim()));
        ImGui.sameLine(0, 2);
        if (ImGui.button("...##a" + id, bw, 0)) {
            ImGui.openPopup("##assets" + id);
        }
        if (ImGui.beginPopup("##assets" + id)) {
            if (ids.isEmpty()) {
                ImGui.textDisabled("No dependencies of this kind; add one in UI Assets");
            }
            if (ImGui.selectable("none")) {
                out = new Result(true, UiValue.of("none"), true);
            }
            for (String a : ids) {
                if (ImGui.selectable(a, a.equals(current))) {
                    out = new Result(true, UiValue.of(a), true);
                }
            }
            ImGui.endPopup();
        }
        return out;
    }

    // ── text ────────────────────────────────────────────────────────────────

    /**
     * A text field that commits on Enter or focus loss (one undo step per edit, never per
     * keystroke). {@code authored == null} shows {@code placeholder} as a hint.
     */
    public static Result text(String id, String authored, String placeholder, float width,
                              java.util.function.Function<String, UiValue> parse) {
        ImString buf = TEXT.computeIfAbsent(id, k -> new ImString(256));
        String source = authored == null ? "" : authored;
        boolean active = id.equals(activeText);
        if (!active && !source.equals(TEXT_SOURCE.get(id))) {
            buf.set(source);
            TEXT_SOURCE.put(id, source);
        }
        ImGui.setNextItemWidth(width);
        ImGui.inputTextWithHint("##" + id, placeholder == null ? "" : placeholder, buf, ImGuiInputTextFlags.AutoSelectAll);
        if (ImGui.isItemActivated()) {
            activeText = id;
        }
        if (ImGui.isItemDeactivated()) {
            if (id.equals(activeText)) {
                activeText = null;
            }
            if (ImGui.isItemDeactivatedAfterEdit()) {
                String v = buf.get();
                TEXT_SOURCE.put(id, v);
                return new Result(true, parse.apply(v), true);
            }
            return Result.ended(true);
        }
        return Result.NONE;
    }

    private static String activeText;

    /** JSON editing for list/object props: commits when the text parses. */
    public static Result json(String id, UiValue authored, float width) {
        String src = authored == null || authored == MIXED ? "" : new String(CanonicalJson.write(authored),
            StandardCharsets.UTF_8);
        return text(id, authored == null ? null : src, "JSON", width, v -> {
            if (v.isBlank()) {
                return null;
            }
            UiDiagnostics d = new UiDiagnostics();
            UiValue parsed = CanonicalJson.parse(v.getBytes(StandardCharsets.UTF_8), "inspector", d);
            return parsed == null ? authored : parsed;
        });
    }

    /** A plain value as display text ({@code 12}, {@code "50%"} -> {@code 50%}). */
    public static String display(UiValue v) {
        return switch (v) {
            case null -> "";
            case UiValue.Num n -> n.isIntegral() ? String.valueOf((long) n.value()) : String.valueOf(n.value());
            case UiValue.Str s -> s.value();
            case UiValue.Bool b -> String.valueOf(b.value());
            case UiValue.Null x -> "null";
            default -> new String(CanonicalJson.write(v), StandardCharsets.UTF_8);
        };
    }

    /** Parses display text back into the most specific value (number, bool, string). */
    public static UiValue parseLoose(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        if ("true".equals(t) || "false".equals(t)) {
            return UiValue.of(Boolean.parseBoolean(t));
        }
        try {
            return UiValue.of(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return UiValue.of(t);
        }
    }
}
