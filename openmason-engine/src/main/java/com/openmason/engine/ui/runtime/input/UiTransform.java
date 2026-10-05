package com.openmason.engine.ui.runtime.input;

/**
 * A 2D affine transform {@code [a c tx; b d ty]} mapping element-local device pixels to
 * viewport device pixels (#288). Input converts viewport points into an element's space with
 * {@link #inverse()}, so when {@code scale}/{@code rotate} land (#295) local coordinates and
 * hit tests stay correct without changing any handler.
 */
public record UiTransform(float a, float b, float c, float d, float tx, float ty) {

    public static final UiTransform IDENTITY = new UiTransform(1, 0, 0, 1, 0, 0);

    public static UiTransform translate(float x, float y) {
        return new UiTransform(1, 0, 0, 1, x, y);
    }

    public static UiTransform scale(float sx, float sy) {
        return new UiTransform(sx, 0, 0, sy, 0, 0);
    }

    /** Counter-clockwise in a y-up frame, i.e. clockwise on screen (CSS {@code rotate}). */
    public static UiTransform rotate(float degrees) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        return new UiTransform(cos, sin, -sin, cos, 0, 0);
    }

    /** {@code this ∘ next}: apply {@code next} first, then this. */
    public UiTransform then(UiTransform next) {
        return new UiTransform(
            a * next.a + c * next.b, b * next.a + d * next.b,
            a * next.c + c * next.d, b * next.c + d * next.d,
            a * next.tx + c * next.ty + tx, b * next.tx + d * next.ty + ty);
    }

    public float applyX(float x, float y) {
        return a * x + c * y + tx;
    }

    public float applyY(float x, float y) {
        return b * x + d * y + ty;
    }

    /** @throws IllegalStateException for a degenerate (zero-area) transform */
    public UiTransform inverse() {
        float det = a * d - b * c;
        if (det == 0 || !Float.isFinite(det)) {
            throw new IllegalStateException("transform is not invertible");
        }
        float ia = d / det;
        float ib = -b / det;
        float ic = -c / det;
        float id = a / det;
        return new UiTransform(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty));
    }
}
