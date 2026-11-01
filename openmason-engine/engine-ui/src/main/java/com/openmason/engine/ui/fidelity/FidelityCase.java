package com.openmason.engine.ui.fidelity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One point of a screen's fidelity matrix (#296): which screen, which state of it, and the
 * framebuffer, UI scale and pixel ratio it is captured at. Everything else a capture depends on
 * (font, assets, clock, seed, backdrop) is pinned by the fixture that renders it.
 *
 * @param variant screen state such as {@code offline}, {@code online-hover-quit}; may be empty
 */
public record FidelityCase(String screen, String variant, Viewport viewport) {

    /**
     * A framebuffer size at a UI scale and pixel ratio.
     *
     * @param pixelRatio content scale; 1 everywhere today (no DPI handling exists, #283 ledger)
     */
    public record Viewport(int width, int height, float uiScale, float pixelRatio) {

        public Viewport {
            if (width <= 0 || height <= 0 || !(uiScale > 0) || !(pixelRatio > 0)) {
                throw new IllegalArgumentException("invalid viewport");
            }
        }

        public Viewport(int width, int height, float uiScale) {
            this(width, height, uiScale, 1f);
        }

        /** {@code 1920x1080_s1}, {@code 1921x1081_s1_25}, {@code ..._dpr2} when the ratio is not 1. */
        public String id() {
            String id = width + "x" + height + "_s" + num(uiScale);
            return pixelRatio == 1f ? id : id + "_dpr" + num(pixelRatio);
        }

        private static String num(float v) {
            String s = v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%s", v);
            return s.replace('.', '_');
        }
    }

    /**
     * The agreed viewports (#283 fixtures, re-used by #296): the common desktop size, a scaled-down
     * laptop, 4K at 2×, and an odd size at a fractional scale that exposes half-pixel rounding.
     */
    public static final List<Viewport> STANDARD = List.of(
        new Viewport(1920, 1080, 1f),
        new Viewport(1280, 720, 0.75f),
        new Viewport(3840, 2160, 2f),
        new Viewport(1921, 1081, 1.25f));

    public FidelityCase {
        Objects.requireNonNull(screen, "screen");
        variant = variant == null ? "" : variant;
        Objects.requireNonNull(viewport, "viewport");
        if (screen.isBlank() || !(screen + variant).matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("screen and variant must be lowercase-dashed: " + screen + "/" + variant);
        }
    }

    /** File-safe id: {@code pause-offline_1920x1080_s1}. Baselines are named after it. */
    public String id() {
        return (variant.isEmpty() ? screen : screen + "-" + variant) + "_" + viewport.id();
    }

    /** Every variant at every viewport, variants outermost. */
    public static List<FidelityCase> matrix(String screen, List<String> variants, List<Viewport> viewports) {
        List<FidelityCase> out = new ArrayList<>();
        for (String v : variants) {
            for (Viewport vp : viewports) {
                out.add(new FidelityCase(screen, v, vp));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return id();
    }
}
