package com.stonebreak.ui.support;

import java.util.List;

/**
 * Reference viewport sizes for UI layout tests.
 *
 * <p>Layout code must behave sanely across the whole range players actually use, including the
 * awkward ones: a 1024x600 short panel (vertically-stacked inventory sections start to collide)
 * and a 3440x1440 ultrawide (horizontal centering math with a stray division can drift). Pinning
 * tests to 1920x1080 alone hides both.
 *
 * <p>Deliberately a plain list rather than a JUnit {@code @MethodSource}: this module depends on
 * {@code junit-jupiter-api} and {@code -engine} only, not {@code junit-jupiter-params}, and no test
 * in the repository uses {@code @ParameterizedTest}. Tests loop over {@link #ALL} and report the
 * failing size themselves — see {@link UiLayoutAssert} for the message helpers.
 */
public final class Resolutions {

    private Resolutions() {}

    /** A viewport under test. */
    public record Size(int width, int height) {
        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** The full sweep, smallest first. */
    public static final List<Size> ALL = List.of(
        new Size(1024, 600),   // short panel — vertical crowding
        new Size(1280, 720),
        new Size(1920, 1080),  // the common case
        new Size(2560, 1440),
        new Size(3440, 1440),  // ultrawide — horizontal centering
        new Size(3840, 2160)
    );

    /**
     * The sweep minus the cramped 1024x600 entry, for layouts that legitimately do not fit on a
     * short screen and report that themselves via an adequacy check.
     */
    public static final List<Size> COMFORTABLE = ALL.subList(1, ALL.size());
}
