package com.openmason.engine.ui.layoutspike;

import java.util.Arrays;

/**
 * Flat flexbox node records, the shared input of both layout engines in the
 * #283 spike ({@link YogaFlex} and {@link JavaFlex}). The field layout mirrors
 * {@code cenda/flex.h}; NaN means unset, which each engine resolves to Yoga's
 * defaults (column direction, shrink 0, align-items stretch, align-content
 * flex-start).
 */
final class FlexTree {

    static final int PARENT = 0, DISPLAY = 1, POSITION_TYPE = 2, DIRECTION = 3, WRAP = 4, JUSTIFY = 5,
        ALIGN_ITEMS = 6, ALIGN_SELF = 7, ALIGN_CONTENT = 8, GROW = 9, SHRINK = 10, BASIS = 11,
        WIDTH = 12, HEIGHT = 13, WIDTH_PCT = 14, HEIGHT_PCT = 15, MIN_W = 16, MIN_H = 17, MAX_W = 18,
        MAX_H = 19, MARGIN = 20, PADDING = 24, BORDER = 28, POS = 32, GAP_ROW = 36, GAP_COLUMN = 37,
        ASPECT = 38, MEASURE_ID = 39, POS_PCT = 40, STRIDE = 44;

    static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;

    static final int COLUMN = 0, COLUMN_REVERSE = 1, ROW = 2, ROW_REVERSE = 3;
    static final int NO_WRAP = 0, WRAP_ON = 1;
    static final int J_START = 0, J_CENTER = 1, J_END = 2, J_BETWEEN = 3, J_AROUND = 4, J_EVENLY = 5;
    static final int A_AUTO = 0, A_START = 1, A_CENTER = 2, A_END = 3, A_STRETCH = 4;

    private float[] data = new float[STRIDE * 16];
    private int count;

    /** Adds a node under {@code parent} (ignored for the first node, the root). */
    int add(int parent) {
        if ((count + 1) * STRIDE > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        int base = count * STRIDE;
        Arrays.fill(data, base, base + STRIDE, Float.NaN);
        data[base + PARENT] = parent;
        data[base + DISPLAY] = 0;
        data[base + POSITION_TYPE] = 0;
        return count++;
    }

    int count() {
        return count;
    }

    float get(int node, int field) {
        return data[node * STRIDE + field];
    }

    FlexTree set(int node, int field, float value) {
        data[node * STRIDE + field] = value;
        return this;
    }

    float[] records() {
        return data;
    }

    // ── fluent helpers ──

    FlexTree size(int n, float w, float h) {
        return set(n, WIDTH, w).set(n, HEIGHT, h);
    }

    FlexTree row(int n) {
        return set(n, DIRECTION, ROW);
    }

    FlexTree column(int n) {
        return set(n, DIRECTION, COLUMN);
    }

    FlexTree justify(int n, int j) {
        return set(n, JUSTIFY, j);
    }

    FlexTree alignItems(int n, int a) {
        return set(n, ALIGN_ITEMS, a);
    }

    FlexTree gap(int n, float rowGap, float columnGap) {
        return set(n, GAP_ROW, rowGap).set(n, GAP_COLUMN, columnGap);
    }

    FlexTree edges(int n, int field, float l, float t, float r, float b) {
        return set(n, field + LEFT, l).set(n, field + TOP, t).set(n, field + RIGHT, r).set(n, field + BOTTOM, b);
    }

    FlexTree absolute(int n, float l, float t, float r, float b) {
        set(n, POSITION_TYPE, 1);
        return edges(n, POS, l, t, r, b);
    }

    FlexTree hidden(int n, boolean hidden) {
        return set(n, DISPLAY, hidden ? 1 : 0);
    }

    FlexTree measure(int n, int id) {
        return set(n, MEASURE_ID, id);
    }
}
