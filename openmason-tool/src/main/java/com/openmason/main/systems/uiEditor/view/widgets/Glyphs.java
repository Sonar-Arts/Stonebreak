package com.openmason.main.systems.uiEditor.view.widgets;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;

/**
 * Vector glyphs for the UI editor, drawn straight into a draw list so they stay crisp at any
 * density and take any theme color. Each draws into the {@code s x s} square at {@code (x, y)}.
 * Widget-type glyphs echo what the widget is (a "T" for text, a mountain for images, a diamond
 * for component instances), as in Unreal's palette.
 */
public final class Glyphs {

    private Glyphs() {
    }

    /** The glyph for a widget type ({@code Instance} for components; unknown types get a dashed box). */
    public static void widget(ImDrawList dl, String type, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 12f);
        switch (type) {
            case "Box" -> dl.addRect(x + s * 0.12f, y + s * 0.12f, x + s * 0.88f, y + s * 0.88f, col, s * 0.12f, 0, t * 1.2f);
            case "Label" -> {
                dl.addLine(x + s * 0.18f, y + s * 0.2f, x + s * 0.82f, y + s * 0.2f, col, t * 1.6f);
                dl.addLine(x + s * 0.5f, y + s * 0.2f, x + s * 0.5f, y + s * 0.84f, col, t * 1.6f);
            }
            case "Button" -> {
                dl.addRectFilled(x + s * 0.08f, y + s * 0.26f, x + s * 0.92f, y + s * 0.74f, withAlpha(col, 0.35f), s * 0.2f);
                dl.addRect(x + s * 0.08f, y + s * 0.26f, x + s * 0.92f, y + s * 0.74f, col, s * 0.2f, 0, t * 1.2f);
                dl.addLine(x + s * 0.32f, y + s * 0.5f, x + s * 0.68f, y + s * 0.5f, col, t * 1.4f);
            }
            case "Image" -> {
                dl.addRect(x + s * 0.1f, y + s * 0.16f, x + s * 0.9f, y + s * 0.84f, col, s * 0.08f, 0, t * 1.2f);
                dl.addTriangleFilled(x + s * 0.18f, y + s * 0.76f, x + s * 0.42f, y + s * 0.44f, x + s * 0.62f, y + s * 0.76f, col);
                dl.addTriangleFilled(x + s * 0.5f, y + s * 0.76f, x + s * 0.66f, y + s * 0.56f, x + s * 0.82f, y + s * 0.76f, col);
                dl.addCircleFilled(x + s * 0.7f, y + s * 0.34f, s * 0.07f, col);
            }
            case "ItemSlot" -> {
                dl.addRect(x + s * 0.1f, y + s * 0.1f, x + s * 0.9f, y + s * 0.9f, col, s * 0.06f, 0, t * 1.2f);
                dl.addRectFilled(x + s * 0.3f, y + s * 0.3f, x + s * 0.7f, y + s * 0.7f, col, s * 0.04f);
            }
            case "DrawProvider" -> {
                dl.addRect(x + s * 0.1f, y + s * 0.1f, x + s * 0.9f, y + s * 0.9f, col, s * 0.06f, 0, t * 1.2f);
                dl.addLine(x + s * 0.28f, y + s * 0.72f, x + s * 0.72f, y + s * 0.28f, col, t * 1.8f);
                dl.addCircleFilled(x + s * 0.28f, y + s * 0.72f, s * 0.06f, col);
            }
            case "ScrollView" -> {
                dl.addRect(x + s * 0.08f, y + s * 0.1f, x + s * 0.76f, y + s * 0.9f, col, s * 0.06f, 0, t * 1.2f);
                dl.addRectFilled(x + s * 0.82f, y + s * 0.18f, x + s * 0.92f, y + s * 0.5f, col, s * 0.05f);
            }
            case "TextField" -> {
                dl.addRect(x + s * 0.06f, y + s * 0.24f, x + s * 0.94f, y + s * 0.76f, col, s * 0.08f, 0, t * 1.2f);
                dl.addLine(x + s * 0.3f, y + s * 0.34f, x + s * 0.3f, y + s * 0.66f, col, t * 1.4f);
            }
            case "ListView" -> {
                for (int i = 0; i < 3; i++) {
                    float yy = y + s * (0.24f + i * 0.26f);
                    dl.addCircleFilled(x + s * 0.18f, yy, s * 0.07f, col);
                    dl.addLine(x + s * 0.34f, yy, x + s * 0.88f, yy, col, t * 1.4f);
                }
            }
            case "Canvas" -> {
                dl.addRect(x + s * 0.08f, y + s * 0.1f, x + s * 0.92f, y + s * 0.9f, col, s * 0.06f, 0, t * 1.1f);
                dl.addCircle(x + s * 0.36f, y + s * 0.42f, s * 0.14f, col, 12, t * 1.2f);
                dl.addTriangleFilled(x + s * 0.5f, y + s * 0.78f, x + s * 0.66f, y + s * 0.5f, x + s * 0.82f, y + s * 0.78f, col);
            }
            case "Instance" -> component(dl, x, y, s, col);
            default -> {
                dashedRect(dl, x + s * 0.12f, y + s * 0.12f, x + s * 0.88f, y + s * 0.88f, col, t);
                dl.addCircleFilled(x + s * 0.5f, y + s * 0.5f, s * 0.08f, col);
            }
        }
    }

    /** A filled diamond with a cut: Unreal's "user widget"/component mark. */
    public static void component(ImDrawList dl, float x, float y, float s, int col) {
        float cx = x + s / 2f;
        float cy = y + s / 2f;
        float r = s * 0.42f;
        dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, withAlpha(col, 0.45f));
        dl.addQuad(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, col, Math.max(1f, s / 10f));
        dl.addQuadFilled(cx, cy - r * 0.45f, cx + r * 0.45f, cy, cx, cy + r * 0.45f, cx - r * 0.45f, cy, col);
    }

    /** Category tint of a widget type: fixed mid-tones that read on light and dark themes. */
    public static int typeColor(String type, float alpha) {
        return switch (type) {
            case "Box", "ScrollView", "ListView" -> rgba(0.36f, 0.66f, 0.88f, alpha);
            case "Label", "TextField" -> rgba(0.52f, 0.78f, 0.46f, alpha);
            case "Button", "Image" -> rgba(0.86f, 0.74f, 0.36f, alpha);
            case "ItemSlot", "DrawProvider", "Canvas" -> rgba(0.92f, 0.56f, 0.30f, alpha);
            case "Instance" -> rgba(0.70f, 0.52f, 0.92f, alpha);
            default -> rgba(0.62f, 0.64f, 0.68f, alpha);
        };
    }

    public static void eye(ImDrawList dl, float x, float y, float s, int col, boolean open) {
        float cx = x + s / 2f;
        float cy = y + s / 2f;
        float t = Math.max(1f, s / 11f);
        dl.addBezierCubic(x + s * 0.06f, cy, x + s * 0.3f, y + s * 0.16f, x + s * 0.7f, y + s * 0.16f, x + s * 0.94f, cy, col, t, 12);
        dl.addBezierCubic(x + s * 0.06f, cy, x + s * 0.3f, y + s * 0.84f, x + s * 0.7f, y + s * 0.84f, x + s * 0.94f, cy, col, t, 12);
        if (open) {
            dl.addCircleFilled(cx, cy, s * 0.14f, col);
        } else {
            dl.addLine(x + s * 0.16f, y + s * 0.84f, x + s * 0.84f, y + s * 0.16f, col, t * 1.2f);
        }
    }

    public static void lock(ImDrawList dl, float x, float y, float s, int col, boolean locked) {
        float t = Math.max(1f, s / 11f);
        dl.addRectFilled(x + s * 0.2f, y + s * 0.46f, x + s * 0.8f, y + s * 0.9f, col, s * 0.08f);
        float right = locked ? x + s * 0.68f : x + s * 0.88f;
        dl.addBezierCubic(x + s * 0.32f, y + s * 0.46f, x + s * 0.32f, y + s * 0.06f, right, y + s * 0.06f, right,
            y + s * (locked ? 0.46f : 0.3f), col, t * 1.2f, 12);
    }

    public static void link(ImDrawList dl, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 10f);
        dl.addRect(x + s * 0.08f, y + s * 0.34f, x + s * 0.56f, y + s * 0.66f, col, s * 0.16f, 0, t);
        dl.addRect(x + s * 0.44f, y + s * 0.34f, x + s * 0.92f, y + s * 0.66f, col, s * 0.16f, 0, t);
    }

    public static void warning(ImDrawList dl, float x, float y, float s, int col) {
        dl.addTriangleFilled(x + s * 0.5f, y + s * 0.08f, x + s * 0.96f, y + s * 0.9f, x + s * 0.04f, y + s * 0.9f, col);
        int ink = rgba(0.08f, 0.08f, 0.08f, 1f);
        dl.addLine(x + s * 0.5f, y + s * 0.36f, x + s * 0.5f, y + s * 0.62f, ink, Math.max(1f, s / 9f));
        dl.addCircleFilled(x + s * 0.5f, y + s * 0.76f, s * 0.06f, ink);
    }

    public static void error(ImDrawList dl, float x, float y, float s, int col) {
        dl.addCircleFilled(x + s / 2f, y + s / 2f, s * 0.44f, col);
        int ink = rgba(1f, 1f, 1f, 1f);
        float t = Math.max(1f, s / 8f);
        dl.addLine(x + s * 0.34f, y + s * 0.34f, x + s * 0.66f, y + s * 0.66f, ink, t);
        dl.addLine(x + s * 0.66f, y + s * 0.34f, x + s * 0.34f, y + s * 0.66f, ink, t);
    }

    public static void info(ImDrawList dl, float x, float y, float s, int col) {
        dl.addCircle(x + s / 2f, y + s / 2f, s * 0.42f, col, 16, Math.max(1f, s / 10f));
        dl.addLine(x + s * 0.5f, y + s * 0.44f, x + s * 0.5f, y + s * 0.74f, col, Math.max(1f, s / 8f));
        dl.addCircleFilled(x + s * 0.5f, y + s * 0.3f, s * 0.06f, col);
    }

    public static void play(ImDrawList dl, float x, float y, float s, int col) {
        dl.addTriangleFilled(x + s * 0.24f, y + s * 0.14f, x + s * 0.86f, y + s * 0.5f, x + s * 0.24f, y + s * 0.86f, col);
    }

    public static void stop(ImDrawList dl, float x, float y, float s, int col) {
        dl.addRectFilled(x + s * 0.2f, y + s * 0.2f, x + s * 0.8f, y + s * 0.8f, col, s * 0.08f);
    }

    public static void pencil(ImDrawList dl, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 9f);
        dl.addLine(x + s * 0.2f, y + s * 0.8f, x + s * 0.78f, y + s * 0.22f, col, t * 1.6f);
        dl.addTriangleFilled(x + s * 0.12f, y + s * 0.88f, x + s * 0.18f, y + s * 0.66f, x + s * 0.34f, y + s * 0.82f, col);
    }

    public static void plus(ImDrawList dl, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 8f);
        dl.addLine(x + s * 0.5f, y + s * 0.18f, x + s * 0.5f, y + s * 0.82f, col, t);
        dl.addLine(x + s * 0.18f, y + s * 0.5f, x + s * 0.82f, y + s * 0.5f, col, t);
    }

    public static void cross(ImDrawList dl, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 9f);
        dl.addLine(x + s * 0.25f, y + s * 0.25f, x + s * 0.75f, y + s * 0.75f, col, t);
        dl.addLine(x + s * 0.75f, y + s * 0.25f, x + s * 0.25f, y + s * 0.75f, col, t);
    }

    /** Small curved arrow: "reset to default" (Unreal's yellow revert arrow). */
    public static void reset(ImDrawList dl, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 8f);
        dl.addBezierCubic(x + s * 0.78f, y + s * 0.64f, x + s * 0.78f, y + s * 0.2f, x + s * 0.3f, y + s * 0.16f, x + s * 0.24f,
            y + s * 0.5f, col, t, 10);
        dl.addTriangleFilled(x + s * 0.06f, y + s * 0.44f, x + s * 0.42f, y + s * 0.44f, x + s * 0.24f, y + s * 0.72f, col);
    }

    public static void chevron(ImDrawList dl, float x, float y, float s, int col, boolean open) {
        float t = Math.max(1f, s / 8f);
        if (open) {
            dl.addLine(x + s * 0.25f, y + s * 0.38f, x + s * 0.5f, y + s * 0.64f, col, t);
            dl.addLine(x + s * 0.5f, y + s * 0.64f, x + s * 0.75f, y + s * 0.38f, col, t);
        } else {
            dl.addLine(x + s * 0.38f, y + s * 0.25f, x + s * 0.64f, y + s * 0.5f, col, t);
            dl.addLine(x + s * 0.64f, y + s * 0.5f, x + s * 0.38f, y + s * 0.75f, col, t);
        }
    }

    /** Arrow pointing in a flex direction ({@code row}, {@code column}, {@code row-reverse}, ...). */
    public static void direction(ImDrawList dl, String dir, float x, float y, float s, int col) {
        float t = Math.max(1f, s / 8f);
        float cx = x + s / 2f;
        float cy = y + s / 2f;
        float a = s * 0.34f;
        switch (dir) {
            case "row" -> arrow(dl, cx - a, cy, cx + a, cy, col, t, s);
            case "row-reverse" -> arrow(dl, cx + a, cy, cx - a, cy, col, t, s);
            case "column-reverse" -> arrow(dl, cx, cy + a, cx, cy - a, col, t, s);
            default -> arrow(dl, cx, cy - a, cx, cy + a, col, t, s);
        }
    }

    /**
     * Distribution glyph for {@code justify-content}/{@code align-items}: bars laid along the
     * main axis ({@code horizontal}) placed by the keyword.
     */
    public static void justify(ImDrawList dl, String keyword, boolean horizontal, float x, float y, float s, int col) {
        float[] pos;
        float bar = s * 0.16f;
        switch (keyword) {
            case "center" -> pos = new float[]{0.32f, 0.52f};
            case "flex-end" -> pos = new float[]{0.56f, 0.76f};
            case "space-between" -> pos = new float[]{0.1f, 0.74f};
            case "space-around" -> pos = new float[]{0.2f, 0.64f};
            case "space-evenly" -> pos = new float[]{0.26f, 0.58f};
            default -> pos = new float[]{0.1f, 0.3f};
        }
        int dim = withAlpha(col, 0.35f);
        if (horizontal) {
            dl.addLine(x + s * 0.06f, y + s * 0.1f, x + s * 0.06f, y + s * 0.9f, dim, 1f);
            dl.addLine(x + s * 0.94f, y + s * 0.1f, x + s * 0.94f, y + s * 0.9f, dim, 1f);
            for (float p : pos) {
                dl.addRectFilled(x + s * p, y + s * 0.26f, x + s * p + bar, y + s * 0.74f, col, 1f);
            }
        } else {
            dl.addLine(x + s * 0.1f, y + s * 0.06f, x + s * 0.9f, y + s * 0.06f, dim, 1f);
            dl.addLine(x + s * 0.1f, y + s * 0.94f, x + s * 0.9f, y + s * 0.94f, dim, 1f);
            for (float p : pos) {
                dl.addRectFilled(x + s * 0.26f, y + s * p, x + s * 0.74f, y + s * p + bar, col, 1f);
            }
        }
    }

    /** Cross-axis placement glyph for {@code align-items}/{@code align-self}. */
    public static void align(ImDrawList dl, String keyword, boolean horizontalMain, float x, float y, float s, int col) {
        int dim = withAlpha(col, 0.35f);
        // cross axis is vertical when the main axis is horizontal
        float a0;
        float a1;
        switch (keyword) {
            case "center" -> { a0 = 0.3f; a1 = 0.7f; }
            case "flex-end" -> { a0 = 0.5f; a1 = 0.9f; }
            case "stretch" -> { a0 = 0.1f; a1 = 0.9f; }
            case "baseline" -> { a0 = 0.24f; a1 = 0.62f; }
            default -> { a0 = 0.1f; a1 = 0.5f; }
        }
        if (horizontalMain) {
            dl.addLine(x + s * 0.08f, y + s * 0.06f, x + s * 0.92f, y + s * 0.06f, dim, 1f);
            dl.addLine(x + s * 0.08f, y + s * 0.94f, x + s * 0.92f, y + s * 0.94f, dim, 1f);
            dl.addRectFilled(x + s * 0.2f, y + s * a0, x + s * 0.4f, y + s * a1, col, 1f);
            dl.addRectFilled(x + s * 0.56f, y + s * Math.min(a0 + 0.08f, a1 - 0.1f), x + s * 0.76f, y + s * a1, col, 1f);
            if ("baseline".equals(keyword)) {
                dl.addLine(x + s * 0.12f, y + s * 0.62f, x + s * 0.88f, y + s * 0.62f, col, 1f);
            }
        } else {
            dl.addLine(x + s * 0.06f, y + s * 0.08f, x + s * 0.06f, y + s * 0.92f, dim, 1f);
            dl.addLine(x + s * 0.94f, y + s * 0.08f, x + s * 0.94f, y + s * 0.92f, dim, 1f);
            dl.addRectFilled(x + s * a0, y + s * 0.2f, x + s * a1, y + s * 0.4f, col, 1f);
            dl.addRectFilled(x + s * a0, y + s * 0.56f, x + s * Math.max(a1 - 0.08f, a0 + 0.1f), y + s * 0.76f, col, 1f);
        }
    }

    /** Align/distribute toolbar glyphs. */
    public static void alignOp(ImDrawList dl, String op, float x, float y, float s, int col) {
        int dim = withAlpha(col, 0.55f);
        float t = Math.max(1f, s / 12f);
        switch (op) {
            case "LEFT" -> {
                dl.addLine(x + s * 0.12f, y + s * 0.08f, x + s * 0.12f, y + s * 0.92f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.2f, y + s * 0.2f, x + s * 0.86f, y + s * 0.42f, col, 1f);
                dl.addRectFilled(x + s * 0.2f, y + s * 0.58f, x + s * 0.6f, y + s * 0.8f, dim, 1f);
            }
            case "CENTER_X" -> {
                dl.addLine(x + s * 0.5f, y + s * 0.06f, x + s * 0.5f, y + s * 0.94f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.16f, y + s * 0.2f, x + s * 0.84f, y + s * 0.42f, col, 1f);
                dl.addRectFilled(x + s * 0.3f, y + s * 0.58f, x + s * 0.7f, y + s * 0.8f, dim, 1f);
            }
            case "RIGHT" -> {
                dl.addLine(x + s * 0.88f, y + s * 0.08f, x + s * 0.88f, y + s * 0.92f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.14f, y + s * 0.2f, x + s * 0.8f, y + s * 0.42f, col, 1f);
                dl.addRectFilled(x + s * 0.4f, y + s * 0.58f, x + s * 0.8f, y + s * 0.8f, dim, 1f);
            }
            case "TOP" -> {
                dl.addLine(x + s * 0.08f, y + s * 0.12f, x + s * 0.92f, y + s * 0.12f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.2f, y + s * 0.2f, x + s * 0.42f, y + s * 0.86f, col, 1f);
                dl.addRectFilled(x + s * 0.58f, y + s * 0.2f, x + s * 0.8f, y + s * 0.6f, dim, 1f);
            }
            case "CENTER_Y" -> {
                dl.addLine(x + s * 0.06f, y + s * 0.5f, x + s * 0.94f, y + s * 0.5f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.2f, y + s * 0.16f, x + s * 0.42f, y + s * 0.84f, col, 1f);
                dl.addRectFilled(x + s * 0.58f, y + s * 0.3f, x + s * 0.8f, y + s * 0.7f, dim, 1f);
            }
            case "BOTTOM" -> {
                dl.addLine(x + s * 0.08f, y + s * 0.88f, x + s * 0.92f, y + s * 0.88f, col, t * 1.4f);
                dl.addRectFilled(x + s * 0.2f, y + s * 0.14f, x + s * 0.42f, y + s * 0.8f, col, 1f);
                dl.addRectFilled(x + s * 0.58f, y + s * 0.4f, x + s * 0.8f, y + s * 0.8f, dim, 1f);
            }
            case "DISTRIBUTE_X" -> {
                for (int i = 0; i < 3; i++) {
                    float xx = x + s * (0.1f + i * 0.32f);
                    dl.addRectFilled(xx, y + s * 0.26f, xx + s * 0.16f, y + s * 0.74f, i == 1 ? col : dim, 1f);
                }
            }
            default -> {
                for (int i = 0; i < 3; i++) {
                    float yy = y + s * (0.1f + i * 0.32f);
                    dl.addRectFilled(x + s * 0.26f, yy, x + s * 0.74f, yy + s * 0.16f, i == 1 ? col : dim, 1f);
                }
            }
        }
    }

    public static void arrow(ImDrawList dl, float x0, float y0, float x1, float y1, int col, float t, float s) {
        dl.addLine(x0, y0, x1, y1, col, t);
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        dx /= len;
        dy /= len;
        float h = s * 0.22f;
        dl.addTriangleFilled(x1 + dx * 1.5f, y1 + dy * 1.5f, x1 - dx * h - dy * h * 0.7f, y1 - dy * h + dx * h * 0.7f,
            x1 - dx * h + dy * h * 0.7f, y1 - dy * h - dx * h * 0.7f, col);
    }

    /** Dashed outline (unknown widget types, slot hosts). */
    public static void dashedRect(ImDrawList dl, float x0, float y0, float x1, float y1, int col, float t) {
        dashed(dl, x0, y0, x1, y0, col, t, 4f, 3f);
        dashed(dl, x1, y0, x1, y1, col, t, 4f, 3f);
        dashed(dl, x1, y1, x0, y1, col, t, 4f, 3f);
        dashed(dl, x0, y1, x0, y0, col, t, 4f, 3f);
    }

    public static void dashed(ImDrawList dl, float x0, float y0, float x1, float y1, int col, float t, float dash, float gap) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        for (float d = 0; d < len; d += dash + gap) {
            float e = Math.min(len, d + dash);
            dl.addLine(x0 + ux * d, y0 + uy * d, x0 + ux * e, y0 + uy * e, col, t);
        }
    }

    /** Diagonal hatching inside a rect (flex gaps). */
    public static void hatch(ImDrawList dl, float x0, float y0, float x1, float y1, int col, float spacing) {
        if (x1 - x0 < 1 || y1 - y0 < 1) {
            return;
        }
        dl.pushClipRect(x0, y0, x1, y1, true);
        float h = y1 - y0;
        for (float x = x0 - h; x < x1; x += spacing) {
            dl.addLine(x, y1, x + h, y0, col, 1f);
        }
        dl.popClipRect();
    }

    public static int rgba(float r, float g, float b, float a) {
        return ImGui.colorConvertFloat4ToU32(r, g, b, a);
    }

    /** {@code col} (packed ABGR) with its alpha multiplied by {@code alpha}. */
    public static int withAlpha(int col, float alpha) {
        int a = (col >>> 24) & 0xFF;
        int na = Math.round(a * Math.max(0f, Math.min(1f, alpha)));
        return (col & 0x00FFFFFF) | (na << 24);
    }

    /** Rounded-corner flags helper for clarity at call sites. */
    public static int roundAll() {
        return ImDrawFlags.RoundCornersAll;
    }
}
