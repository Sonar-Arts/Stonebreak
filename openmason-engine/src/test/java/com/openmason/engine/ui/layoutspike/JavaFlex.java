package com.openmason.engine.ui.layoutspike;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.layoutspike.FlexMeasure.AT_MOST;
import static com.openmason.engine.ui.layoutspike.FlexMeasure.EXACTLY;
import static com.openmason.engine.ui.layoutspike.FlexMeasure.UNDEFINED;
import static com.openmason.engine.ui.layoutspike.FlexTree.*;

/**
 * Pure-Java flexbox over {@link FlexTree} records — the #283 spike's
 * challenger to Yoga. It follows Yoga's algorithm structure and defaults
 * (calculateLayoutImpl: flex basis with at-most measurement, line breaking,
 * two-pass free-space distribution, stretch, justify/align, absolute insets,
 * pixel-grid rounding) so the fuzz comparison measures what matching Yoga's
 * semantics in Java costs. Not tuned, not production code.
 */
final class JavaFlex {

    private final FlexTree t;
    private final FlexMeasure measure;
    private final List<List<Integer>> children = new ArrayList<>();
    private final float[] x, y, w, h;
    private final float[] measureOut = new float[2];

    private JavaFlex(FlexTree tree, FlexMeasure measure) {
        this.t = tree;
        this.measure = measure;
        int n = tree.count();
        x = new float[n];
        y = new float[n];
        w = new float[n];
        h = new float[n];
        for (int i = 0; i < n; i++) {
            children.add(new ArrayList<>());
            if (i > 0) {
                children.get((int) tree.get(i, PARENT)).add(i);
            }
        }
    }

    /** x, y, w, h per record, root-relative — same contract as {@link YogaFlex#layout}. */
    static float[] layout(FlexTree tree, float width, float height, float pointScale, FlexMeasure measure) {
        JavaFlex f = new JavaFlex(tree, measure);
        float rootW = f.resolveSize(0, WIDTH, WIDTH_PCT, width);
        float rootH = f.resolveSize(0, HEIGHT, HEIGHT_PCT, height);
        f.layoutNode(0, defined(rootW) ? f.clamp(0, true, rootW) : width,
            defined(rootW) ? EXACTLY : (defined(width) ? EXACTLY : UNDEFINED),
            defined(rootH) ? f.clamp(0, false, rootH) : height,
            defined(rootH) ? EXACTLY : (defined(height) ? EXACTLY : UNDEFINED),
            width, height, true);
        float[] out = new float[tree.count() * 4];
        f.write(0, 0, 0, pointScale, out);
        return out;
    }

    // ─────────────────────────── style access ───────────────────────────

    private static boolean defined(float v) {
        return !Float.isNaN(v);
    }

    private float or(int n, int field, float fallback) {
        float v = t.get(n, field);
        return defined(v) ? v : fallback;
    }

    private int direction(int n) {
        return (int) or(n, DIRECTION, COLUMN);
    }

    private static boolean isRow(int dir) {
        return dir == ROW || dir == ROW_REVERSE;
    }

    private boolean hidden(int n) {
        return t.get(n, DISPLAY) == 1;
    }

    private boolean absolute(int n) {
        return t.get(n, POSITION_TYPE) == 1;
    }

    private float edge(int n, int field, int side) {
        return or(n, field + side, 0f);
    }

    private float padBorder(int n, boolean row) {
        return row
            ? edge(n, PADDING, LEFT) + edge(n, PADDING, RIGHT) + edge(n, BORDER, LEFT) + edge(n, BORDER, RIGHT)
            : edge(n, PADDING, TOP) + edge(n, PADDING, BOTTOM) + edge(n, BORDER, TOP) + edge(n, BORDER, BOTTOM);
    }

    private float lead(int n, boolean row) {
        return row ? edge(n, PADDING, LEFT) + edge(n, BORDER, LEFT) : edge(n, PADDING, TOP) + edge(n, BORDER, TOP);
    }

    private float margins(int n, boolean row) {
        return row ? edge(n, MARGIN, LEFT) + edge(n, MARGIN, RIGHT) : edge(n, MARGIN, TOP) + edge(n, MARGIN, BOTTOM);
    }

    private float marginLead(int n, boolean row) {
        return edge(n, MARGIN, row ? LEFT : TOP);
    }

    private float resolveSize(int n, int field, int pctField, float ownerSize) {
        float v = t.get(n, field);
        if (defined(v)) {
            return v;
        }
        float pct = t.get(n, pctField);
        return defined(pct) && defined(ownerSize) ? pct * ownerSize / 100f : Float.NaN;
    }

    private float clamp(int n, boolean row, float v) {
        float min = t.get(n, row ? MIN_W : MIN_H);
        float max = t.get(n, row ? MAX_W : MAX_H);
        if (defined(max) && v > max) {
            v = max;
        }
        if (defined(min) && v < min) {
            v = min;
        }
        return Math.max(v, padBorder(n, row));
    }

    private int alignOf(int parent, int child) {
        int self = (int) or(child, ALIGN_SELF, A_AUTO);
        return self != A_AUTO ? self : (int) or(parent, ALIGN_ITEMS, A_STRETCH);
    }

    // ───────────────────────────── algorithm ─────────────────────────────

    /**
     * Sizes node {@code n} under (avail, mode) per axis (border-box), and when
     * {@code perform} lays out its children relative to it.
     */
    private void layoutNode(int n, float availW, int wMode, float availH, int hMode,
                            float ownerW, float ownerH, boolean perform) {
        float pbRow = padBorder(n, true);
        float pbCol = padBorder(n, false);
        float aspect = t.get(n, ASPECT);
        if (defined(aspect)) {
            if (wMode == EXACTLY && hMode != EXACTLY) {
                availH = availW / aspect;
                hMode = EXACTLY;
            } else if (hMode == EXACTLY && wMode != EXACTLY) {
                availW = availH * aspect;
                wMode = EXACTLY;
            }
        }
        if (defined(t.get(n, MEASURE_ID)) && measure != null) {
            float innerW = defined(availW) ? Math.max(0, availW - pbRow) : Float.NaN;
            float innerH = defined(availH) ? Math.max(0, availH - pbCol) : Float.NaN;
            if (wMode == EXACTLY && hMode == EXACTLY) {
                w[n] = clamp(n, true, availW);
                h[n] = clamp(n, false, availH);
            } else {
                measureOut[0] = 0;
                measureOut[1] = 0;
                measure.measure((int) t.get(n, MEASURE_ID), innerW, wMode, innerH, hMode, measureOut);
                w[n] = clamp(n, true, wMode == EXACTLY ? availW : measureOut[0] + pbRow);
                h[n] = clamp(n, false, hMode == EXACTLY ? availH : measureOut[1] + pbCol);
            }
            return;
        }
        List<Integer> kids = children.get(n);
        if (kids.isEmpty()) {
            w[n] = clamp(n, true, wMode == EXACTLY ? availW : pbRow);
            h[n] = clamp(n, false, hMode == EXACTLY ? availH : pbCol);
            return;
        }

        int dir = direction(n);
        boolean row = isRow(dir);
        boolean reverse = dir == ROW_REVERSE || dir == COLUMN_REVERSE;
        boolean wrap = or(n, WRAP, NO_WRAP) != NO_WRAP;
        float innerW = defined(availW) ? Math.max(0, availW - pbRow) : Float.NaN;
        float innerH = defined(availH) ? Math.max(0, availH - pbCol) : Float.NaN;
        float availMain = row ? innerW : innerH;
        float availCross = row ? innerH : innerW;
        int mainMode = row ? wMode : hMode;
        int crossMode = row ? hMode : wMode;
        float gapMain = or(n, row ? GAP_COLUMN : GAP_ROW, 0f);
        float gapCross = or(n, row ? GAP_ROW : GAP_COLUMN, 0f);

        // 1. flex basis
        List<Integer> flow = new ArrayList<>();
        float[] basis = new float[t.count()];
        float[] main = new float[t.count()];
        for (int c : kids) {
            if (hidden(c)) {
                zero(c);
                continue;
            }
            if (absolute(c)) {
                continue;
            }
            flow.add(c);
            basis[c] = flexBasis(n, c, row, innerW, innerH, wMode, hMode);
            main[c] = clampMain(c, row, basis[c]);
        }

        // 2. lines
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        float lineMain = 0;
        for (int c : flow) {
            float outer = main[c] + margins(c, row);
            float add = line.isEmpty() ? outer : outer + gapMain;
            if (wrap && defined(availMain) && !line.isEmpty() && lineMain + add > availMain) {
                lines.add(line);
                line = new ArrayList<>();
                lineMain = 0;
                add = outer;
            }
            line.add(c);
            lineMain += add;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }

        // 3–4. resolve flexible lengths and item cross sizes per line
        float[] cross = new float[t.count()];
        float[] lineCross = new float[lines.size()];
        float maxLineMain = 0;
        for (int li = 0; li < lines.size(); li++) {
            List<Integer> items = lines.get(li);
            float consumed = 0;
            float totalGrow = 0;
            float totalShrink = 0;
            for (int c : items) {
                consumed += main[c] + margins(c, row);
                totalGrow += or(c, GROW, 0f);
                totalShrink += or(c, SHRINK, 0f) * basis[c];
            }
            consumed += gapMain * Math.max(0, items.size() - 1);
            float lineAvail = availMain;
            if (mainMode != EXACTLY) {
                float minInner = t.get(n, row ? MIN_W : MIN_H) - (row ? pbRow : pbCol);
                float maxInner = t.get(n, row ? MAX_W : MAX_H) - (row ? pbRow : pbCol);
                if (defined(minInner) && consumed < minInner) {
                    lineAvail = minInner;
                } else if (defined(maxInner) && consumed > maxInner) {
                    lineAvail = maxInner;
                } else if ((totalGrow == 0 && totalShrink == 0)
                    || (or(n, GROW, 0f) == 0 && or(n, SHRINK, 0f) == 0)) {
                    lineAvail = consumed;
                }
            }
            float free = defined(lineAvail) ? lineAvail - consumed : 0;
            distribute(items, row, basis, main, free, totalGrow, totalShrink);

            float used = 0;
            float maxCross = 0;
            for (int c : items) {
                used += main[c] + margins(c, row);
                cross[c] = itemCross(n, c, row, main[c], availCross, crossMode, wrap, innerW, innerH);
                maxCross = Math.max(maxCross, cross[c] + margins(c, !row));
            }
            used += gapMain * Math.max(0, items.size() - 1);
            maxLineMain = Math.max(maxLineMain, used);
            lineCross[li] = (!wrap && crossMode == EXACTLY && defined(availCross)) ? availCross : maxCross;
        }

        // 7. own size
        float contentMain = maxLineMain;
        float contentCross = 0;
        for (int li = 0; li < lines.size(); li++) {
            contentCross += lineCross[li] + (li > 0 ? gapCross : 0);
        }
        float ownMain = sizeFor(n, row, mainMode, row ? availW : availH, contentMain);
        float ownCross = sizeFor(n, !row, crossMode, row ? availH : availW, contentCross);
        w[n] = row ? ownMain : ownCross;
        h[n] = row ? ownCross : ownMain;
        if (!perform) {
            return;
        }
        float innerMain = (row ? w[n] - pbRow : h[n] - pbCol);
        float innerCross = (row ? h[n] - pbCol : w[n] - pbRow);
        if (lines.size() == 1 && !wrap) {
            lineCross[0] = innerCross;
        }

        // align-content
        float crossFree = innerCross - contentCross;
        float crossLead = 0;
        float crossBetween = 0;
        int alignContent = (int) or(n, ALIGN_CONTENT, A_START);
        if (wrap && lines.size() > 0) {
            switch (alignContent) {
                case A_CENTER -> crossLead = crossFree / 2;
                case A_END -> crossLead = crossFree;
                case A_STRETCH -> {
                    if (crossFree > 0) {
                        float extra = crossFree / lines.size();
                        for (int li = 0; li < lines.size(); li++) {
                            lineCross[li] += extra;
                        }
                    }
                }
                case 6 -> crossBetween = lines.size() > 1 ? Math.max(0, crossFree) / (lines.size() - 1) : 0;
                case 7 -> {
                    crossBetween = Math.max(0, crossFree) / lines.size();
                    crossLead = crossBetween / 2;
                }
                default -> { }
            }
        }

        // 5–6. positions
        float crossPos = lead(n, !row) + crossLead;
        for (int li = 0; li < lines.size(); li++) {
            List<Integer> items = lines.get(li);
            float used = 0;
            for (int c : items) {
                used += main[c] + margins(c, row);
            }
            used += gapMain * Math.max(0, items.size() - 1);
            float free = innerMain - used;
            if (mainMode == AT_MOST && free > 0 && !defined(t.get(n, row ? MIN_W : MIN_H))) {
                free = 0;
            }
            int justify = (int) or(n, JUSTIFY, J_START);
            int count = items.size();
            float leadMain = 0;
            float between = 0;
            switch (justify) {
                case J_CENTER -> leadMain = free / 2;
                case J_END -> leadMain = free;
                case J_BETWEEN -> between = count > 1 ? Math.max(0, free) / (count - 1) : 0;
                case J_AROUND -> {
                    between = Math.max(0, free) / count;
                    leadMain = between / 2;
                }
                case J_EVENLY -> {
                    between = Math.max(0, free) / (count + 1);
                    leadMain = between;
                }
                default -> { }
            }
            float pos = lead(n, row) + leadMain;
            for (int c : items) {
                pos += marginLead(c, row);
                int align = alignOf(n, c);
                float itemCross = cross[c];
                boolean crossAuto = !defined(resolveSize(c, row ? HEIGHT : WIDTH, row ? HEIGHT_PCT : WIDTH_PCT,
                    row ? innerH : innerW));
                if (align == A_STRETCH && crossAuto) {
                    itemCross = clamp(c, !row, lineCross[li] - margins(c, !row));
                }
                float outerCross = itemCross + margins(c, !row);
                float offset = switch (align) {
                    case A_CENTER -> (lineCross[li] - outerCross) / 2;
                    case A_END -> lineCross[li] - outerCross;
                    default -> 0;
                };
                float mainPos = reverse ? (row ? w[n] : h[n]) - pos - main[c] : pos;
                float cPos = crossPos + offset + marginLead(c, !row);
                float cw = row ? main[c] : itemCross;
                float ch = row ? itemCross : main[c];
                layoutNode(c, cw, EXACTLY, ch, EXACTLY, innerW, innerH, true);
                x[c] = (row ? mainPos : cPos) + relativeOffset(c, LEFT, RIGHT, innerW);
                y[c] = (row ? cPos : mainPos) + relativeOffset(c, TOP, BOTTOM, innerH);
                pos += main[c] + margins(c, row) - marginLead(c, row) + between + gapMain;
            }
            crossPos += lineCross[li] + gapCross + crossBetween;
        }

        // 8. absolute children
        for (int c : kids) {
            if (!hidden(c) && absolute(c)) {
                layoutAbsolute(n, c, row);
            }
        }
    }

    private void zero(int c) {
        x[c] = y[c] = w[c] = h[c] = 0;
        for (int g : children.get(c)) {
            zero(g);
        }
    }

    private float clampMain(int c, boolean row, float v) {
        return clamp(c, row, v);
    }

    private float flexBasis(int n, int c, boolean row, float innerW, float innerH, int wMode, int hMode) {
        float b = t.get(c, BASIS);
        if (defined(b)) {
            return Math.max(b, padBorder(c, row));
        }
        float cw = resolveSize(c, WIDTH, WIDTH_PCT, innerW);
        float ch = resolveSize(c, HEIGHT, HEIGHT_PCT, innerH);
        if (row && defined(cw)) {
            return Math.max(cw, padBorder(c, true));
        }
        if (!row && defined(ch)) {
            return Math.max(ch, padBorder(c, false));
        }
        int cwMode = defined(cw) ? EXACTLY : UNDEFINED;
        int chMode = defined(ch) ? EXACTLY : UNDEFINED;
        if (!defined(cw) && defined(innerW)) {
            cw = innerW;
            cwMode = AT_MOST;
        }
        if (!defined(ch) && defined(innerH)) {
            ch = innerH;
            chMode = AT_MOST;
        }
        boolean stretch = alignOf(n, c) == A_STRETCH;
        if (!row && wMode == EXACTLY && stretch && !defined(resolveSize(c, WIDTH, WIDTH_PCT, innerW))) {
            cw = innerW - margins(c, true);
            cwMode = EXACTLY;
        }
        if (row && hMode == EXACTLY && stretch && !defined(resolveSize(c, HEIGHT, HEIGHT_PCT, innerH))) {
            ch = innerH - margins(c, false);
            chMode = EXACTLY;
        }
        layoutNode(c, cw, cwMode, ch, chMode, innerW, innerH, false);
        return row ? w[c] : h[c];
    }

    /** Yoga's two-pass distribution: clamp violators first, then share what is left. */
    private void distribute(List<Integer> items, boolean row, float[] basis, float[] main, float free,
                            float totalGrow, float totalShrink) {
        if (free == 0 || (free > 0 && totalGrow == 0) || (free < 0 && totalShrink == 0)) {
            return;
        }
        float remaining = free;
        float grow = totalGrow;
        float shrink = totalShrink;
        boolean[] frozen = new boolean[t.count()];
        for (int c : items) {
            float target = target(c, row, basis, remaining, grow, shrink);
            float clamped = clamp(c, row, target);
            if (clamped != target) {
                frozen[c] = true;
                remaining -= clamped - basis[c];
                if (free > 0) {
                    grow -= or(c, GROW, 0f);
                } else {
                    shrink -= or(c, SHRINK, 0f) * basis[c];
                }
            }
        }
        for (int c : items) {
            float target = target(c, row, basis, remaining, grow, shrink);
            main[c] = clamp(c, row, target);
        }
    }

    private float target(int c, boolean row, float[] basis, float remaining, float grow, float shrink) {
        if (remaining > 0) {
            float g = or(c, GROW, 0f);
            return grow > 0 && g > 0 ? basis[c] + remaining * g / grow : basis[c];
        }
        float scaled = or(c, SHRINK, 0f) * basis[c];
        return shrink > 0 && scaled > 0 ? basis[c] + remaining * scaled / shrink : basis[c];
    }

    private float itemCross(int n, int c, boolean row, float mainSize, float availCross, int crossMode,
                            boolean wrap, float innerW, float innerH) {
        float styled = resolveSize(c, row ? HEIGHT : WIDTH, row ? HEIGHT_PCT : WIDTH_PCT, row ? innerH : innerW);
        float crossSize;
        int mode;
        if (defined(styled)) {
            crossSize = styled;
            mode = EXACTLY;
        } else if (alignOf(n, c) == A_STRETCH && crossMode == EXACTLY && !wrap && defined(availCross)) {
            crossSize = availCross - margins(c, !row);
            mode = EXACTLY;
        } else if (defined(availCross)) {
            crossSize = availCross;
            mode = AT_MOST;
        } else {
            crossSize = Float.NaN;
            mode = UNDEFINED;
        }
        if (row) {
            layoutNode(c, mainSize, EXACTLY, crossSize, mode, innerW, innerH, false);
            return h[c];
        }
        layoutNode(c, crossSize, mode, mainSize, EXACTLY, innerW, innerH, false);
        return w[c];
    }

    private float sizeFor(int n, boolean axisRow, int mode, float avail, float content) {
        float pb = padBorder(n, axisRow);
        if (mode == EXACTLY) {
            return clamp(n, axisRow, avail);
        }
        if (mode == AT_MOST) {
            return Math.max(Math.min(avail, clamp(n, axisRow, content + pb)), pb);
        }
        return clamp(n, axisRow, content + pb);
    }

    private void layoutAbsolute(int n, int c, boolean parentRow) {
        float pw = w[n];
        float ph = h[n];
        float bl = edge(n, BORDER, LEFT), br = edge(n, BORDER, RIGHT);
        float bt = edge(n, BORDER, TOP), bb = edge(n, BORDER, BOTTOM);
        float l = inset(c, LEFT, pw), r = inset(c, RIGHT, pw), tp = inset(c, TOP, ph), bo = inset(c, BOTTOM, ph);
        float cw = resolveSize(c, WIDTH, WIDTH_PCT, pw);
        float ch = resolveSize(c, HEIGHT, HEIGHT_PCT, ph);
        if (!defined(cw) && defined(l) && defined(r)) {
            cw = pw - bl - br - l - r - margins(c, true);
        }
        if (!defined(ch) && defined(tp) && defined(bo)) {
            ch = ph - bt - bb - tp - bo - margins(c, false);
        }
        int cwMode = defined(cw) ? EXACTLY : UNDEFINED;
        int chMode = defined(ch) ? EXACTLY : UNDEFINED;
        if (!parentRow && !defined(cw)) {
            cw = pw;
            cwMode = AT_MOST;
        }
        layoutNode(c, cwMode == EXACTLY ? clamp(c, true, cw) : cw, cwMode,
            chMode == EXACTLY ? clamp(c, false, ch) : ch, chMode, pw, ph, true);
        x[c] = place(n, c, true, parentRow, l, r, pw, w[c], bl, br);
        y[c] = place(n, c, false, parentRow, tp, bo, ph, h[c], bt, bb);
    }

    /** position: relative nudges the laid-out box; leading inset wins over trailing. */
    private float relativeOffset(int c, int leading, int trailing, float ownerSize) {
        float l = inset(c, leading, ownerSize);
        if (defined(l)) {
            return l;
        }
        float r = inset(c, trailing, ownerSize);
        return defined(r) ? -r : 0;
    }

    private float inset(int c, int side, float ownerSize) {
        float v = t.get(c, POS + side);
        if (defined(v)) {
            return v;
        }
        float pct = t.get(c, POS_PCT + side);
        return defined(pct) ? pct * ownerSize / 100f : Float.NaN;
    }

    private float place(int n, int c, boolean axisRow, boolean parentRow, float leading, float trailing,
                        float ownerSize, float size, float borderLead, float borderTrail) {
        float mLead = marginLead(c, axisRow);
        float mTrail = margins(c, axisRow) - mLead;
        if (defined(leading)) {
            return borderLead + leading + mLead;
        }
        if (defined(trailing)) {
            return ownerSize - borderTrail - trailing - mTrail - size;
        }
        float pbLead = lead(n, axisRow);
        float inner = ownerSize - padBorder(n, axisRow);
        int mode = axisRow == parentRow ? (int) or(n, JUSTIFY, J_START) : alignOf(n, c);
        boolean center = axisRow == parentRow ? mode == J_CENTER : mode == A_CENTER;
        boolean end = axisRow == parentRow ? mode == J_END : mode == A_END;
        float outer = size + margins(c, axisRow);
        if (center) {
            return pbLead + (inner - outer) / 2 + mLead;
        }
        if (end) {
            return pbLead + inner - outer + mLead;
        }
        return pbLead + mLead;
    }

    // ───────────────────────────── output ─────────────────────────────

    private void write(int n, float absX, float absY, float pointScale, float[] out) {
        float ax = absX + (n == 0 ? 0 : x[n]);
        float ay = absY + (n == 0 ? 0 : y[n]);
        if (pointScale > 0) {
            float l = round(ax, pointScale);
            float tp = round(ay, pointScale);
            out[n * 4] = l;
            out[n * 4 + 1] = tp;
            out[n * 4 + 2] = round(ax + w[n], pointScale) - l;
            out[n * 4 + 3] = round(ay + h[n], pointScale) - tp;
        } else {
            out[n * 4] = ax;
            out[n * 4 + 1] = ay;
            out[n * 4 + 2] = w[n];
            out[n * 4 + 3] = h[n];
        }
        for (int c : children.get(n)) {
            write(c, ax, ay, pointScale, out);
        }
    }

    private static float round(float v, float scale) {
        return (float) Math.floor(v * scale + 0.5) / scale;
    }
}
